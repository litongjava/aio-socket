package com.litongjava.enhance.buffer;

import java.nio.ByteBuffer;
import java.util.concurrent.*;

public final class DirectBufferReleaser {
  private static final ExecutorService EXEC = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "direct-buffer-releaser");
        t.setDaemon(true);
        return t;
      });

  public static void submit(ByteBuffer buf) {
    if (buf == null || !buf.isDirect())
      return;
    EXEC.execute(() -> {
      try {
        com.litongjava.enhance.buffer.DirectBufferCleaner.clean(buf);
      } catch (Throwable ignore) {
      }
    });
  }

  private DirectBufferReleaser() {
  }
}
