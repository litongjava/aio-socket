package nexus.io.enhance.channel;
import static org.junit.Assert.*;
import org.junit.Test;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class SocketOperationLifecycleTest {
  static class Pair implements AutoCloseable {
    ExecutorService executor=Executors.newFixedThreadPool(1);
    EnhanceAsynchronousChannelProvider provider=new EnhanceAsynchronousChannelProvider(false);
    AsynchronousChannelGroup group;AsynchronousServerSocketChannel server;EnhanceAsynchronousSocketChannel accepted;Socket client;
    Pair() throws Exception {
      group=provider.openAsynchronousChannelGroup(executor,1);server=provider.openAsynchronousServerSocketChannel(group);server.bind(new InetSocketAddress("127.0.0.1",0));
      CompletableFuture<AsynchronousSocketChannel> result=new CompletableFuture<>();
      server.accept(null,new CompletionHandler<AsynchronousSocketChannel,Object>(){public void completed(AsynchronousSocketChannel c,Object a){result.complete(c);}public void failed(Throwable e,Object a){result.completeExceptionally(e);}});
      client=new Socket();client.connect(server.getLocalAddress());client.setSoTimeout(4000);accepted=(EnhanceAsynchronousSocketChannel)result.get(3,TimeUnit.SECONDS);
    }
    public void close()throws Exception {accepted.close();client.close();server.close();group.shutdownNow();executor.shutdownNow();executor.awaitTermination(3,TimeUnit.SECONDS);}
  }
  @Test public void callbackExceptionDoesNotFailRearmedRead() throws Exception {
    try(Pair p=new Pair()) {
      CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1);AtomicInteger failures=new AtomicInteger();
      p.accepted.read(ByteBuffer.allocate(1),"first",new CompletionHandler<Integer,String>(){
        public void completed(Integer n,String a){p.accepted.read(ByteBuffer.allocate(1),"second",new CompletionHandler<Integer,String>(){
          public void completed(Integer m,String b){second.countDown();}public void failed(Throwable e,String b){failures.incrementAndGet();}
        });first.countDown();throw new IllegalStateException("first callback failed");}
        public void failed(Throwable e,String a){failures.incrementAndGet();}
      });
      p.client.getOutputStream().write(1);assertTrue(first.await(3,TimeUnit.SECONDS));p.client.getOutputStream().write(2);
      assertTrue(second.await(3,TimeUnit.SECONDS));assertEquals(0,failures.get());
    }
  }
  @Test public void closingPendingWriteCompletesExactlyOnce() throws Exception {
    try(Pair p=new Pair()) {
      p.accepted.setOption(StandardSocketOptions.SO_SNDBUF,1024);ByteBuffer data=ByteBuffer.allocate(16*1024*1024);AtomicInteger failures=new AtomicInteger();CountDownLatch failed=new CountDownLatch(1);
      p.accepted.write(data,null,new CompletionHandler<Integer,Object>(){
        public void completed(Integer n,Object a){if(data.hasRemaining())p.accepted.write(data,a,this);}public void failed(Throwable e,Object a){failures.incrementAndGet();failed.countDown();}
      });
      assertTrue(data.hasRemaining());p.accepted.close();assertTrue(failed.await(3,TimeUnit.SECONDS));p.accepted.close();assertEquals(1,failures.get());
    }
  }
  @Test public void writeFailureResetsStateBeforeCallback() throws Exception {
    try(Pair p=new Pair()) {
      p.accepted.close();AtomicInteger failures=new AtomicInteger();
      p.accepted.write(ByteBuffer.wrap(new byte[]{1}),null,new CompletionHandler<Integer,Object>(){
        public void completed(Integer n,Object a){fail("closed socket wrote");}
        public void failed(Throwable e,Object a){assertTrue(e instanceof ClosedChannelException);if(failures.incrementAndGet()==1)p.accepted.write(ByteBuffer.wrap(new byte[]{2}),a,this);}
      });assertEquals(2,failures.get());
    }
  }
  @Test public void zeroCopyTransferResumesAndPreservesContents() throws Exception {
    Path file=Files.createTempFile("zero-copy-", ".bin");byte[] expected=new byte[256*1024];for(int i=0;i<expected.length;i++)expected[i]=(byte)(i*31);Files.write(file,expected);
    try(Pair p=new Pair();FileChannel input=FileChannel.open(file,StandardOpenOption.READ)) {
      p.accepted.setOption(StandardSocketOptions.SO_SNDBUF,1024);CompletableFuture<Long> done=new CompletableFuture<>();AtomicLong offset=new AtomicLong();
      CompletionHandler<Long,Object> handler=new CompletionHandler<Long,Object>(){
        public void completed(Long n,Object a){long next=offset.addAndGet(n);if(next<expected.length)p.accepted.transfer(input,next,expected.length-next,a,this);else done.complete(next);}
        public void failed(Throwable e,Object a){done.completeExceptionally(e);}
      };
      p.accepted.transfer(input,0,expected.length,null,handler);
      byte[] actual=new byte[expected.length];int read=0;while(read<actual.length){int n=p.client.getInputStream().read(actual,read,actual.length-read);assertTrue(n>0);read+=n;}
      assertEquals(Long.valueOf(expected.length),done.get(3,TimeUnit.SECONDS));assertArrayEquals(expected,actual);
    } finally {Files.deleteIfExists(file);}
  }
  @Test public void fullSocketWriteReturnsInsteadOfSpinning() throws Exception {
    try(Pair p=new Pair()) {
      p.accepted.setOption(StandardSocketOptions.SO_SNDBUF,1024);ByteBuffer fill=ByteBuffer.allocate(16*1024*1024);
      while(fill.hasRemaining() && p.accepted.getSocketChannel().write(fill)>0){}
      assertTrue(fill.hasRemaining());CountDownLatch failed=new CountDownLatch(1);
      ExecutorService caller=Executors.newSingleThreadExecutor();
      try {
        caller.submit(()->p.accepted.write(ByteBuffer.allocate(16*1024*1024),null,new CompletionHandler<Integer,Object>(){
          public void completed(Integer n,Object a){}public void failed(Throwable e,Object a){failed.countDown();}
        })).get(1,TimeUnit.SECONDS);
      } finally {p.accepted.close();caller.shutdownNow();}
      assertTrue(failed.await(1,TimeUnit.SECONDS));
    }
  }
}
