package com.litongjava.enhance.buffer;

import java.util.concurrent.ScheduledThreadPoolExecutor;

public class GlobalScheduler {
  public static final ScheduledThreadPoolExecutor INSTANCE = new ScheduledThreadPoolExecutor(
      Math.max(2, Runtime.getRuntime().availableProcessors() / 2), r -> {
        Thread t = new Thread(r, "aio-scheduler");
        t.setDaemon(true);
        return t;
      });
}
