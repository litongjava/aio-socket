package com.litongjava.enhance.buffer;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ByteBuffer内存池管理类，负责创建和管理多个BufferPage实例。
 * 该类提供了内存分配、回收和释放的功能，通过内存池化技术减少频繁创建和销毁ByteBuffer带来的性能开销。
 * 支持堆内存和堆外内存（直接缓冲区）两种模式，并提供不同的分配策略。
 *
 * @author 三刀
 * @version V1.0 , 2018/10/31
 */
public final class BufferPagePool {
  /**
   * 默认的BufferPagePool实例，使用1个堆内存页。 提供给不需要自定义内存池配置的场景使用，简化API使用。
   */
  public static final BufferPagePool DEFAULT_BUFFER_PAGE_POOL = new BufferPagePool(1, false);
  /**
   * 内存页分配游标，用于顺序分配策略。 使用原子整数确保在多线程环境下的线程安全，避免多个线程获取到同一个内存页索引。
   */
  private final AtomicInteger cursor = new AtomicInteger(0);
  /**
   * 内存页数组，存储所有由该内存池管理的BufferPage实例。 数组长度由构造函数中的pageNum参数决定。
   */
  private BufferPage[] requestBufferPages;

  private BufferPage[] responseBufferPages;
  /**
   * 内存池启用状态标志。 当值为true时，表示内存池处于启用状态，可以分配内存和定期回收；
   * 当值为false时，表示内存池已被禁用，不再分配新内存，并释放已分配的内存。
   */
  private boolean enabled = true;
  /**
   * 内存回收定时任务的Future对象。 用于控制定时任务的执行和取消，在内存池释放时可以通过该对象取消定时任务。
   */
  private final ScheduledFuture<?> future;

  /**
   * 构造一个内存池对象。
   *
   * @param pageNum  内存页个数，决定了内存池中BufferPage实例的数量
   * @param isDirect 是否使用直接缓冲区。当值为true时，使用堆外内存；当值为false时，使用堆内存
   * @throws IllegalStateException 当在不支持直接缓冲区的JDK版本中尝试使用直接缓冲区时抛出异常
   */
  public BufferPagePool(final int pageNum, boolean isDirect) {
    // 创建指定数量的内存页
    requestBufferPages = new BufferPage[pageNum];
    responseBufferPages = new BufferPage[pageNum];
    for (int i = 0; i < pageNum; i++) {
      requestBufferPages[i] = new BufferPage(isDirect);
      responseBufferPages[i] = new BufferPage(isDirect);
    }
    // 如果内存页数量大于0，则启动定时回收任务
    if (pageNum > 0) {
      future = GlobalScheduler.INSTANCE.scheduleWithFixedDelay(new Runnable() {
        @Override
        public void run() {
          if (enabled) {
            // 内存池启用状态下，尝试回收每个内存页中的缓冲区
            for (BufferPage bufferPage : requestBufferPages) {
              bufferPage.tryClean();
            }
            for (BufferPage bufferPage : responseBufferPages) {
              bufferPage.tryClean();
            }
          } else {
            // 内存池禁用状态下，释放所有内存页资源并取消定时任务
            if (requestBufferPages != null) {
              for (BufferPage page : requestBufferPages) {
                page.release();
              }
              requestBufferPages = null;
            }

            if (responseBufferPages != null) {
              for (BufferPage page : responseBufferPages) {
                page.release();
              }
              requestBufferPages = null;
            }
            future.cancel(false);
          }
        }
      }, 500, 1000, TimeUnit.MILLISECONDS); // 初始延迟500ms，之后每1000ms执行一次
    } else {
      future = null;
    }
  }

  /**
   * 按顺序从内存页组中分配指定大小的虚拟缓冲区。 该方法会按顺序依次从内存页中获取缓冲区，使用原子计数器确保顺序分配。
   *
   * @param size 要分配的缓冲区大小
   * @return 分配的虚拟缓冲区
   */
  public VirtualBuffer allocateSequentially(final int size) {
    return allocateRequestSequentially(size);
  }

  public VirtualBuffer allocateRequestSequentially(final int size) {
    return requestBufferPages[(cursor.getAndIncrement() & Integer.MAX_VALUE) % requestBufferPages.length]
        .allocate(size);
  }

  /**
   * 根据当前线程 ID 从内存页组中分配指定大小的虚拟缓冲区。 该方法会根据当前线程的 ID 对内存页数量取模，将缓冲区分配到对应的内存页。
   *
   * @param size 要分配的缓冲区大小
   * @return 分配的虚拟缓冲区
   */
  public VirtualBuffer allocateByThreadId(final int size) {
    return allocateRequestByThreadId(size);
  }

  public VirtualBuffer allocateRequestByThreadId(final int size) {
    return requestBufferPages[(int) ((Thread.currentThread().getId()) % requestBufferPages.length)].allocate(size);
  }

  public VirtualBuffer allocateResponseSequentially(final int size) {
    return responseBufferPages[(cursor.getAndIncrement() & Integer.MAX_VALUE) % requestBufferPages.length]
        .allocate(size);
  }

  public VirtualBuffer allocateResponseByThreadId(final int size) {
    return responseBufferPages[(int) ((Thread.currentThread().getId()) % requestBufferPages.length)].allocate(size);
  }

  /**
   * 释放内存池中的所有资源。 该方法不会立即释放资源，而是通过将enabled标志设置为false，
   * 让定时任务在下一次执行时检测到状态变化并执行实际的资源释放操作。 这种设计可以避免在调用线程中执行耗时的资源释放操作。
   */
  public void release() {
    enabled = false;
  }

  /**
   * 返回内存池的字符串表示形式，用于调试和日志记录。 该方法会遍历所有内存页，并将它们的字符串表示形式拼接起来。
   *
   * @return 包含所有内存页信息的字符串
   */
  @Override
  public String toString() {
    String logger = "";
    for (BufferPage page : requestBufferPages) {
      logger += "\r\n" + page.toString();
    }
    return logger;
  }

  public BufferPage[] getRequestBufferPages() {
    return requestBufferPages;
  }

  public BufferPage[] getResponseBufferPages() {
    return responseBufferPages;
  }

  public BufferMemoryStat[] getRequestBufferMemoryStat() {
    BufferMemoryStat[] bufferMemoryStats = new BufferMemoryStat[requestBufferPages.length];
    for (int i = 0; i < requestBufferPages.length; i++) {
      BufferMemoryStat stat = requestBufferPages[i].getStat();
      bufferMemoryStats[i] = stat;
    }
    return bufferMemoryStats;
  }

  public BufferMemoryStat[] getResponseBufferMemoryStat() {
    BufferMemoryStat[] bufferMemoryStats = new BufferMemoryStat[responseBufferPages.length];
    for (int i = 0; i < responseBufferPages.length; i++) {
      BufferMemoryStat stat = responseBufferPages[i].getStat();
      bufferMemoryStats[i] = stat;
    }
    return bufferMemoryStats;
  }
}