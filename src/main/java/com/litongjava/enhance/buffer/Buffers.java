package com.litongjava.enhance.buffer;

import java.util.Objects;

/** 全局唯一 Direct ByteBuffer 池 */
public final class Buffers {

  // 可通过 -Dbuffer.pool.min=1024 等覆盖
  private static final int MIN_BUCKET = Integer.getInteger("buffer.pool.min", 1024); // 1KB
  private static final int MAX_BUCKET = Integer.getInteger("buffer.pool.max", 1 << 20); // 1MB
  private static final int TL_MAX_PER_BUCKET = Integer.getInteger("buffer.pool.tlmax", 32); // 每线程每桶缓存上限
  private static final int GL_MAX_PER_BUCKET = Integer.getInteger("buffer.pool.glmax", 1024); // 全局每桶缓存上限

  public static final ByteBufferPool DIRECT_POOL = new DirectByteBufferPool(MIN_BUCKET, MAX_BUCKET, TL_MAX_PER_BUCKET,
      GL_MAX_PER_BUCKET);

  private Buffers() {
  }

  /** 便于单元测试/灰度时替换实现；生产环境不要随便调 */
  public static void swapDirectPool(ByteBufferPool newPool) {
    Objects.requireNonNull(newPool, "newPool");
    // 如果你想允许可热替换，可把上面的 DIRECT_POOL 改为 volatile 字段并提供 get()。
    throw new UnsupportedOperationException("Keep singleton; expose getter if you need hot-swap.");
  }
}
