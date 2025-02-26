package com.litongjava.enhance.buffer;

/**
 * ByteBuffer内存页
 */
public interface BufferPage {

  /**
   * 申请虚拟内存
   *
   * @param size 申请大小
   * @return 虚拟内存对象
   */
  VirtualBuffer allocate(final int size);

}
