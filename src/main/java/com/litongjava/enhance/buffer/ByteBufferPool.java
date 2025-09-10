package com.litongjava.enhance.buffer;

import java.nio.ByteBuffer;

public interface ByteBufferPool {
  ByteBuffer borrow(int minCapacity); // 返回 capacity >= min 的 direct buffer（position=0, limit=cap）

  void giveBack(ByteBuffer buf); // 归还（会 clear()）
}
