package com.litongjava.enhance.buffer;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class GlobalScheduler {
  public static final ScheduledThreadPoolExecutor INSTANCE = new ScheduledThreadPoolExecutor(
      Math.max(2, Runtime.getRuntime().availableProcessors() / 2), r -> {
        Thread t = new Thread(r, "aio-scheduler");
        t.setDaemon(true);
        return t;
      });

  public static ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
      TimeUnit unit) {
    return INSTANCE.scheduleWithFixedDelay(command, initialDelay, delay, unit);
  }
}
