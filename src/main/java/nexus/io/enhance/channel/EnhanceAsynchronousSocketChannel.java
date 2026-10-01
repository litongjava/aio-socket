package nexus.io.enhance.channel;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.ReadPendingException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ShutdownChannelGroupException;
import java.nio.channels.SocketChannel;
import java.nio.channels.FileChannel;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.channels.WritePendingException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 该类模拟JDK7的AIO处理方式，通过NIO实现异步IO操作。
 * 主要功能包括：
 * 1. 提供异步读写操作接口：支持非阻塞的读写操作，提高IO效率
 * 2. 支持回调机制处理IO事件：通过CompletionHandler接口处理异步操作的结果
 * 3. 实现了低内存模式的支持：在资源受限环境下优化内存使用
 * 4. 管理Socket连接的生命周期：包括连接建立、数据传输和连接关闭
 * 5. 提供Future和CompletionHandler两种异步操作方式：灵活支持不同的编程模型
 * <p>
 * 实现原理：
 * - 底层使用NIO的SocketChannel实现网络通信
 * - 通过Worker线程池处理异步IO事件
 * - 使用SelectionKey管理IO事件的注册与触发
 * - 支持低内存模式下的内存优化策略
 * <p>
 * 该类是smart-socket框架的核心数据传输组件，通过精心设计的事件处理机制，解决了以下问题：
 * - 避免了传统NIO编程中的复杂性和易错性
 * - 提供了类似JDK7 AIO的编程体验，但性能更优
 * - 实现了读写操作的并发控制，避免资源竞争
 * - 支持连接超时和操作取消等高级特性
 * - 在低内存环境下能够智能管理缓冲区资源
 *
 * @author 三刀
 * @version V1.0 , 2018/5/24
 */
public class EnhanceAsynchronousSocketChannel extends AsynchronousSocketChannel {
  /**
   * 底层的Socket通道，用于实际的网络IO操作
   * 该通道是非阻塞模式的，支持异步读写操作
   */
  protected final SocketChannel channel;

  /**
   * 处理读事件的工作线程，负责异步读取操作的执行
   * 通过Worker线程池来处理读事件，实现真正的异步操作
   */
  private final EnhanceAsynchronousChannelGroup.Worker readWorker;

  /**
   * 读缓冲区，用于存储从通道读取的数据
   * 数据读取后经过解码处理，处理完成后缓冲区可重复使用
   * 采用ByteBuffer实现高效的数据读取和处理
   */
  private ByteBuffer readBuffer;

  /**
   * 写缓冲区，用于存储待写入通道的数据
   * 支持异步写入操作，提高IO效率
   */
  private ByteBuffer writeBuffer;

  /**
   * 读操作的回调处理器，用于处理异步读取完成后的回调逻辑
   * 支持自定义处理读取结果的方式
   */
  private CompletionHandler<Number, Object> readCompletionHandler;

  /**
   * 写操作的回调处理器，用于处理异步写入完成后的回调逻辑
   * 支持自定义处理写入结果的方式
   */
  private CompletionHandler<Number, Object> writeCompletionHandler;

  /**
   * 读操作的附加对象，可在回调时传递额外的上下文信息
   * 用于在异步操作完成时传递自定义数据
   */
  private Object readAttachment;

  /**
   * 写操作的附加对象，可在回调时传递额外的上下文信息
   * 用于在异步操作完成时传递自定义数据
   */
  private Object writeAttachment;

  /**
   * 用于读操作的选择键，管理通道的读事件注册
   * 通过SelectionKey实现事件的监听和触发
   */
  private SelectionKey readSelectionKey;

  /**
   * 是否启用低内存模式
   * 在低内存模式下，会采用特殊的内存管理策略以减少内存占用
   * 适用于资源受限的环境
   */
  private final boolean lowMemory;

  // Operation state is detached under its lock; callbacks run outside both locks.
  private final Object readLock = new Object();
  private final Object writeLock = new Object();
  private final AtomicInteger readWork = new AtomicInteger();
  private final AtomicInteger writeWork = new AtomicInteger();
  private long readGeneration;
  private long writeGeneration;
  private FileChannel writeFile;
  private long writeFilePosition;
  private long writeFileCount;

  public EnhanceAsynchronousSocketChannel(EnhanceAsynchronousChannelGroup group, SocketChannel channel, boolean lowMemory)
      throws IOException {
    super(group.provider());
    this.channel = channel;
    readWorker = group.getReadWorker();
    this.lowMemory = lowMemory;
  }

  protected EnhanceAsynchronousChannelGroup group() {
    return readWorker.group();
  }

  @Override
  public final void close() throws IOException {
    IOException exception = null;
    try {
      if (channel.isOpen()) {
        channel.close();
      }
    } catch (IOException e) {
      exception = e;
    }
    failRead(new ClosedChannelException(), -1);
    failWrite(new ClosedChannelException(), -1);
    if (readSelectionKey != null) {
      readSelectionKey.cancel();
      readSelectionKey = null;
    }
    SelectionKey key = channel.keyFor(group().writeWorker.selector);
    if (key != null) {
      key.cancel();
    }
    key = channel.keyFor(group().commonWorker.selector);
    if (key != null) {
      key.cancel();
    }
    if (exception != null) {
      throw exception;
    }
  }

  @Override
  public final AsynchronousSocketChannel bind(SocketAddress local) throws IOException {
    channel.bind(local);
    return this;
  }

  @Override
  public final <T> AsynchronousSocketChannel setOption(SocketOption<T> name, T value) throws IOException {
    channel.setOption(name, value);
    return this;
  }

  @Override
  public final <T> T getOption(SocketOption<T> name) throws IOException {
    return channel.getOption(name);
  }

  @Override
  public final Set<SocketOption<?>> supportedOptions() {
    return channel.supportedOptions();
  }

  @Override
  public final AsynchronousSocketChannel shutdownInput() throws IOException {
    channel.shutdownInput();
    return this;
  }

  @Override
  public final AsynchronousSocketChannel shutdownOutput() throws IOException {
    channel.shutdownOutput();
    return this;
  }

  @Override
  public final SocketAddress getRemoteAddress() throws IOException {
    return channel.getRemoteAddress();
  }

  /**
   * 异步连接远程地址
   * 实现了异步连接操作，支持通过CompletionHandler处理连接结果
   *
   * @param remote     远程服务器地址
   * @param attachment 附加对象，可在连接完成时传递给CompletionHandler
   * @param handler    连接完成的回调处理器
   * @throws ShutdownChannelGroupException 如果通道组已关闭
   * @throws AlreadyConnectedException     如果通道已经连接
   * @throws ConnectionPendingException    如果连接操作正在进行中
   */
  @Override
  public <A> void connect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) {
    if (group().isTerminated()) {
      throw new ShutdownChannelGroupException();
    }
    if (channel.isConnected()) {
      throw new AlreadyConnectedException();
    }
    if (channel.isConnectionPending()) {
      throw new ConnectionPendingException();
    }
    doConnect(remote, attachment, handler);
  }

  private <A> void doConnect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> completionHandler) {
    try {
      // 此前通过Future调用,且触发了cancel
//            if (completionHandler instanceof FutureCompletionHandler && ((FutureCompletionHandler) completionHandler).isDone()) {
//                return;
//            }
      boolean connected = channel.isConnectionPending();
      if (connected || channel.connect(remote)) {
        connected = channel.finishConnect();
      }
      // 这行代码不要乱动
      channel.configureBlocking(false);
      if (connected) {
        completionHandler.completed(null, attachment);
      } else {
        group().commonWorker.addRegister(selector -> {
          try {
            channel.register(selector, SelectionKey.OP_CONNECT, (Runnable) () -> doConnect(remote, attachment, completionHandler));
          } catch (ClosedChannelException e) {
            completionHandler.failed(e, attachment);
          }
        });
      }
    } catch (IOException e) {
      completionHandler.failed(e, attachment);
    }
  }

  @Override
  public Future<Void> connect(SocketAddress remote) {
    throw new UnsupportedOperationException();
  }

  @Override
  public final <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
    if (timeout > 0) {
      throw new UnsupportedOperationException();
    }
    read0(dst, attachment, handler);
  }

  private <V extends Number, A> void read0(ByteBuffer readBuffer, A attachment, CompletionHandler<V, ? super A> handler) {
    Objects.requireNonNull(handler, "handler");
    if (!lowMemory) Objects.requireNonNull(readBuffer, "buffer");
    synchronized (readLock) {
      if (this.readCompletionHandler != null) throw new ReadPendingException();
      this.readBuffer = readBuffer;
      this.readAttachment = attachment;
      this.readCompletionHandler = (CompletionHandler<Number, Object>) handler;
      readGeneration++;
    }
    if (!channel.isOpen()) {
      failRead(new ClosedChannelException(), -1);
    } else if (readWork.get() == 0) {
      // Initial reads run on the read worker, never on the accept or caller thread.
      readWorker.addRegister(selector -> doRead(true, false));
    } else {
      doRead(false, false);
    }
  }

  @Override
  public final Future<Integer> read(ByteBuffer readBuffer) {
    CompletableFuture<Integer> readFuture = new CompletableFuture<>();
    EnhanceAsynchronousChannelProvider.SYNC_READ_FLAG.set(true);
    try {
      read(readBuffer, 0, TimeUnit.MILLISECONDS, readFuture, EnhanceAsynchronousChannelProvider.SYNC_READ_HANDLER);
    } finally {
      EnhanceAsynchronousChannelProvider.SYNC_READ_FLAG.set(false);
    }
    return readFuture;
  }

  @Override
  public final <A> void read(ByteBuffer[] dsts, int offset, int length, long timeout, TimeUnit unit, A attachment,
      CompletionHandler<Long, ? super A> handler) {
    throw new UnsupportedOperationException();
  }

  @Override
  public final <A> void write(ByteBuffer src, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
    if (timeout > 0) {
      throw new UnsupportedOperationException();
    }
    write0(src, attachment, handler);
  }

  private <V extends Number, A> void write0(ByteBuffer writeBuffer, A attachment, CompletionHandler<V, ? super A> handler) {
    Objects.requireNonNull(handler, "handler");
    Objects.requireNonNull(writeBuffer, "buffer");
    synchronized (writeLock) {
      if (this.writeCompletionHandler != null) throw new WritePendingException();
      this.writeBuffer = writeBuffer;
      this.writeAttachment = attachment;
      this.writeCompletionHandler = (CompletionHandler<Number, Object>) handler;
      this.writeFile = null;
      writeGeneration++;
    }
    doWrite();
  }

  /** Transfers a file region without copying its plaintext into a user-space buffer. */
  @SuppressWarnings("unchecked")
  public <A> void transfer(FileChannel file, long position, long count, A attachment,
      CompletionHandler<Long, ? super A> handler) {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(handler, "handler");
    if (position < 0 || count < 0) throw new IllegalArgumentException("Invalid file region");
    synchronized (writeLock) {
      if (writeCompletionHandler != null) throw new WritePendingException();
      writeFile = file;
      writeFilePosition = position;
      writeFileCount = count;
      writeBuffer = null;
      writeAttachment = attachment;
      writeCompletionHandler = (CompletionHandler<Number, Object>) (CompletionHandler<?, ?>) handler;
      writeGeneration++;
    }
    doWrite();
  }

  @Override
  public final Future<Integer> write(ByteBuffer src) {
    throw new UnsupportedOperationException();
  }

  @Override
  public final <A> void write(ByteBuffer[] srcs, int offset, int length, long timeout, TimeUnit unit, A attachment,
      CompletionHandler<Long, ? super A> handler) {
    throw new UnsupportedOperationException();
  }

  @Override
  public final SocketAddress getLocalAddress() throws IOException {
    return channel.getLocalAddress();
  }

  /**
   * 执行异步读取操作
   * 该方法实现了复杂的异步读取逻辑，包括以下功能：
   * 1. 处理Future取消的情况
   * 2. 支持低内存模式下的读取优化
   * 3. 实现读取限流，避免单个连接占用过多资源
   * 4. 处理读取完成后的回调通知
   *
   * @param direct 是否直接读取，true表示立即读取，false表示通过事件触发读取
   */
  public final void doRead(boolean direct, boolean switchThread) {
    if (readWork.getAndIncrement() != 0) return;
    int missed = 1;
    do {
      Runnable completion;
      synchronized (readLock) { completion = readOnce(direct); }
      if (completion != null) completion.run();
      missed = readWork.addAndGet(-missed);
    } while (missed != 0);
  }

  private Runnable readOnce(boolean direct) {
    if (readCompletionHandler == null) return null;
    final long generation = readGeneration;
    try {
      if (!channel.isOpen()) throw new ClosedChannelException();
      if (lowMemory && readBuffer == null && direct) {
        return takeReadCompletion(EnhanceAsynchronousChannelProvider.READABLE_SIGNAL, null);
      }
      int size = readBuffer == null ? 0 : channel.read(readBuffer);
      if (size != 0 || (readBuffer != null && !readBuffer.hasRemaining())) {
        return takeReadCompletion(size, null);
      }
      Runnable monitor = null;
      if (lowMemory && readBuffer != null && readBuffer.position() == 0) {
        readBuffer = null;
        final CompletionHandler<Number, Object> handler = readCompletionHandler;
        final Object attach = readAttachment;
        monitor = () -> invokeCompleted(handler, EnhanceAsynchronousChannelProvider.READ_MONITOR_SIGNAL, attach, "read-callback");
      }
      readWorker.addRegister(selector -> {
        Throwable failure = null;
        synchronized (readLock) {
          if (readCompletionHandler == null || readGeneration != generation) return;
          try {
            if (!channel.isOpen()) throw new ClosedChannelException();
            readSelectionKey = channel.register(selector, SelectionKey.OP_READ, this);
          } catch (Throwable error) { failure = error; }
        }
        if (failure != null) failRead(failure, generation);
      });
      return monitor;
    } catch (Throwable error) {
      return takeReadCompletion(null, error);
    }
  }

  private Runnable takeReadCompletion(Number count, Throwable error) {
    final CompletionHandler<Number, Object> handler = readCompletionHandler;
    final Object attach = readAttachment;
    resetRead();
    try {
      if (readSelectionKey != null && readSelectionKey.isValid())
        EnhanceAsynchronousChannelGroup.removeOps(readSelectionKey, SelectionKey.OP_READ);
    } catch (Throwable interestError) {
      EnhanceAsynchronousChannelProvider.reportWorkerError("read-interest", interestError);
    }
    return () -> {
      if (error == null) invokeCompleted(handler, count, attach, "read-callback");
      else invokeFailed(handler, error, attach, "read-failed");
    };
  }

  private void failRead(Throwable error, long generation) {
    Runnable completion;
    synchronized (readLock) {
      if (readCompletionHandler == null || (generation >= 0 && readGeneration != generation)) return;
      completion = takeReadCompletion(null, error);
    }
    completion.run();
  }

  private void resetRead() {
    readCompletionHandler = null;
    readAttachment = null;
    readBuffer = null;
  }

  /** A zero-byte write waits for selector readiness instead of spinning. */
  public final boolean doWrite() {
    if (writeWork.getAndIncrement() != 0) return false;
    int missed = 1;
    do {
      Runnable completion;
      synchronized (writeLock) { completion = writeOnce(); }
      if (completion != null) completion.run();
      missed = writeWork.addAndGet(-missed);
    } while (missed != 0);
    return false;
  }

  private Runnable writeOnce() {
    if (writeCompletionHandler == null) return null;
    final long generation = writeGeneration;
    try {
      if (!channel.isOpen()) throw new ClosedChannelException();
      Number count;
      boolean done;
      if (writeFile != null) {
        if (writeFileCount > 0 && writeFilePosition >= writeFile.size())
          throw new IOException("File ended before the requested region");
        long size = writeFile.transferTo(writeFilePosition, Math.min(writeFileCount, 1024 * 1024), channel);
        count = Long.valueOf(size);
        done = size > 0 || writeFileCount == 0;
      } else {
        int size = channel.write(writeBuffer);
        count = Integer.valueOf(size);
        done = size > 0 || !writeBuffer.hasRemaining();
      }
      if (done) return takeWriteCompletion(count, null);
      group().writeWorker.addRegister(selector -> {
        Throwable failure = null;
        synchronized (writeLock) {
          if (writeCompletionHandler == null || writeGeneration != generation) return;
          try {
            if (!channel.isOpen()) throw new ClosedChannelException();
            channel.register(selector, SelectionKey.OP_WRITE, this);
          } catch (Throwable error) { failure = error; }
        }
        if (failure != null) failWrite(failure, generation);
      });
      return null;
    } catch (Throwable error) {
      return takeWriteCompletion(null, error);
    }
  }

  private Runnable takeWriteCompletion(Number count, Throwable error) {
    final CompletionHandler<Number, Object> handler = writeCompletionHandler;
    final Object attach = writeAttachment;
    resetWrite();
    return () -> {
      if (error == null) invokeCompleted(handler, count, attach, "write-callback");
      else invokeFailed(handler, error, attach, "write-failed");
    };
  }

  private void failWrite(Throwable error, long generation) {
    Runnable completion;
    synchronized (writeLock) {
      if (writeCompletionHandler == null || (generation >= 0 && writeGeneration != generation)) return;
      completion = takeWriteCompletion(null, error);
    }
    completion.run();
  }

  private static void invokeCompleted(CompletionHandler<Number, Object> handler, Number count, Object attach, String where) {
    try { handler.completed(count, attach); }
    catch (Throwable error) { EnhanceAsynchronousChannelProvider.reportWorkerError(where, error); }
  }

  private static void invokeFailed(CompletionHandler<Number, Object> handler, Throwable error, Object attach, String where) {
    try { handler.failed(error, attach); }
    catch (Throwable callbackError) { EnhanceAsynchronousChannelProvider.reportWorkerError(where, callbackError); }
  }

  private void resetWrite() {
    writeAttachment = null;
    writeCompletionHandler = null;
    writeBuffer = null;
    writeFile = null;
  }

  @Override
  public final boolean isOpen() {
    return channel.isOpen();
  }

  public SocketChannel getSocketChannel() {
    return channel;
  }
}