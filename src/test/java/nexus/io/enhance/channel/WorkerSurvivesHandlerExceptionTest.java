package nexus.io.enhance.channel;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkerSurvivesHandlerExceptionTest {
  private EnhanceAsynchronousChannelGroup group;
  private AsynchronousServerSocketChannel server;
  private ExecutorService executor;
  private final List<String> errors = new CopyOnWriteArrayList<>();
  private final List<Socket> clients = new CopyOnWriteArrayList<>();
  private final List<AsynchronousSocketChannel> accepted = new CopyOnWriteArrayList<>();

  @Before public void start() throws Exception {
    EnhanceAsynchronousChannelProvider.setWorkerErrorHandler((where, error) -> errors.add(where));
    EnhanceAsynchronousChannelProvider provider = new EnhanceAsynchronousChannelProvider(false);
    executor = Executors.newFixedThreadPool(1);
    group = (EnhanceAsynchronousChannelGroup) provider.openAsynchronousChannelGroup(executor, 1);
    server = provider.openAsynchronousServerSocketChannel(group);
    server.bind(new InetSocketAddress("127.0.0.1", 0));
  }

  @After public void stop() throws Exception {
    try {
      for (Socket socket : clients) socket.close();
      for (AsynchronousSocketChannel socket : accepted) socket.close();
      if (server != null) server.close();
    } finally {
      if (group != null) group.shutdownNow();
      if (executor != null) { executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS); }
      EnhanceAsynchronousChannelProvider.setWorkerErrorHandler(null);
    }
  }

  private void connect() throws Exception {
    Socket socket = new Socket();
    clients.add(socket);
    socket.connect(server.getLocalAddress(), 2000);
  }

  private void barrier() throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    group.commonWorker.addRegister(selector -> done.countDown());
    assertTrue(done.await(3, TimeUnit.SECONDS));
  }

  @Test public void callbackFailureDoesNotFailOrClearRearmedAccept() throws Exception {
    AtomicInteger failures = new AtomicInteger();
    AtomicReference<AsynchronousSocketChannel> first = new AtomicReference<>();
    CountDownLatch firstCalled = new CountDownLatch(1), secondCalled = new CountDownLatch(1);
    CompletionHandler<AsynchronousSocketChannel, String> second = new CompletionHandler<AsynchronousSocketChannel, String>() {
      public void completed(AsynchronousSocketChannel channel, String attachment) {
        accepted.add(channel);
        if (!"SECOND".equals(attachment)) failures.incrementAndGet();
        secondCalled.countDown();
      }
      public void failed(Throwable error, String attachment) { failures.incrementAndGet(); }
    };
    server.accept("FIRST", new CompletionHandler<AsynchronousSocketChannel, String>() {
      public void completed(AsynchronousSocketChannel channel, String attachment) {
        accepted.add(channel); first.set(channel);
        server.accept("SECOND", second);
        firstCalled.countDown();
        throw new IllegalStateException("FIRST callback failed");
      }
      public void failed(Throwable error, String attachment) { failures.incrementAndGet(); }
    });
    connect();
    assertTrue(firstCalled.await(3, TimeUnit.SECONDS));
    barrier();
    assertFalse("failed callback must release its socket", first.get().isOpen());
    connect();
    assertTrue(secondCalled.await(3, TimeUnit.SECONDS));
    barrier();
    assertEquals("FIRST exception must not fail SECOND", 0, failures.get());
    assertTrue(errors.contains("accept-callback"));
  }

  @Test public void callbackWithoutRearmDoesNotPoisonNextAccept() throws Exception {
    CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1);
    server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Object>() {
      public void completed(AsynchronousSocketChannel channel, Object attachment) {
        accepted.add(channel); first.countDown(); throw new IllegalStateException("callback");
      }
      public void failed(Throwable error, Object attachment) { fail(error.toString()); }
    });
    connect(); assertTrue(first.await(3, TimeUnit.SECONDS)); barrier();
    server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Object>() {
      public void completed(AsynchronousSocketChannel channel, Object attachment) { accepted.add(channel); second.countDown(); }
      public void failed(Throwable error, Object attachment) { fail(error.toString()); }
    });
    connect(); assertTrue(second.await(3, TimeUnit.SECONDS));
  }

  @Test public void ioFailureResetsPendingBeforeFailedCallback() throws Exception {
    server.close();
    AtomicInteger failures = new AtomicInteger();
    CompletionHandler<AsynchronousSocketChannel, String> handler = new CompletionHandler<AsynchronousSocketChannel, String>() {
      public void completed(AsynchronousSocketChannel channel, String attachment) { fail("closed server accepted"); }
      public void failed(Throwable error, String attachment) {
        assertTrue(error instanceof ClosedChannelException);
        if (failures.incrementAndGet() == 1) server.accept("retry", this);
      }
    };
    server.accept("first", handler);
    assertEquals(2, failures.get());
    assertFalse(errors.contains("accept-failed"));
  }

  @Test public void registrationAndLoggingFailuresDoNotStopWorker() throws Exception {
    EnhanceAsynchronousChannelProvider.setWorkerErrorHandler((where, error) -> { throw new IllegalStateException("logger"); });
    group.commonWorker.addRegister(selector -> { throw new IllegalStateException("registration"); });
    barrier();
  }

  @Test public void singleEventFailureDoesNotStopSelectorLoop() throws Exception {
    Pipe pipe = Pipe.open();
    pipe.source().configureBlocking(false);
    Selector selector = Selector.open();
    AtomicInteger events = new AtomicInteger();
    CountDownLatch done = new CountDownLatch(1);
    EnhanceAsynchronousChannelGroup.Worker worker = group.new Worker(selector, key -> {
      if (events.incrementAndGet() == 1) throw new IllegalStateException("event");
      key.cancel(); done.countDown();
    });
    Thread thread = new Thread(worker, "test-worker");
    try {
      pipe.source().register(selector, SelectionKey.OP_READ);
      thread.start();
      pipe.sink().write(ByteBuffer.wrap(new byte[] {1}));
      assertTrue(done.await(3, TimeUnit.SECONDS));
      assertTrue(errors.contains("event"));
    } finally {
      group.shutdownNow(); selector.wakeup(); thread.join(3000);
      pipe.source().close(); pipe.sink().close(); selector.close();
    }
  }
}
