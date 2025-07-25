package com.litongjava.enhance.buffer;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * ByteBuffer内存页
 */
public final class ElasticBufferPage extends AbstractBufferPage {

  /**
   * 待回收的虚拟Buffer
   */
  private final ConcurrentLinkedQueue<VirtualBuffer> cleanBuffers = new ConcurrentLinkedQueue<>();
  private final boolean direct;

  /**
   * @param direct 是否使用堆外内存
   */
  ElasticBufferPage(boolean direct) {
    this.direct = direct;
  }

  /**
   * 申请虚拟内存
   *
   * @param size 申请大小
   * @return 虚拟内存对象
   */
  public VirtualBuffer allocate(final int size) {
    if (size == 0) {
      throw new UnsupportedOperationException("cannot allocate zero bytes");
    }
    idle = false;
    VirtualBuffer virtualBuffer = cleanBuffers.poll();
    if (virtualBuffer != null && virtualBuffer.getCapacity() == size) {
      virtualBuffer.buffer().clear();
      virtualBuffer.buffer(virtualBuffer.buffer());
      return virtualBuffer;
    }

    if (virtualBuffer != null) {
      clean0(virtualBuffer);
    }
    return new VirtualBuffer(this, direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size), 0, size);
  }

  /**
   * 内存回收
   *
   * @param cleanBuffer 待回收的虚拟内存
   */
  public void clean(VirtualBuffer cleanBuffer) {
    cleanBuffers.offer(cleanBuffer);
  }

  /**
   * 尝试回收缓冲区
   */
  public void tryClean() {
    //下个周期依旧处于空闲则触发回收任务
    if (!idle) {
      idle = true;
    } else {
      int count = 0;
      VirtualBuffer cleanBuffer;
      while (idle && count++ < 10 && (cleanBuffer = cleanBuffers.poll()) != null) {
        clean0(cleanBuffer);
      }
    }
  }

  /**
   * 回收虚拟缓冲区
   *
   * @param virtualBuffer 虚拟缓冲区
   */
  private void clean0(VirtualBuffer virtualBuffer) {
    if (direct) {
      try {
        DirectBufferCleaner.clean(virtualBuffer.buffer());
      } catch (Throwable e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * 释放内存
   */
  public void release() {
    if (direct) {
      VirtualBuffer virtualBuffer;
      while ((virtualBuffer = cleanBuffers.poll()) != null) {
        clean0(virtualBuffer);
      }
    }
  }

  @Override
  public String toString() {
    return "BufferPage{direct=" + direct + " ,availableBuffers=" + cleanBuffers + '}';
  }
}
