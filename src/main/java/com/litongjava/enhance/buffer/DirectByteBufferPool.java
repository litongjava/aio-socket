package com.litongjava.enhance.buffer;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 生产可用的 Direct ByteBuffer 池： - 分桶：1KB 起，按 2 的幂增长，直到 maxBucketSize -
 * 每线程本地缓存：每桶最多 threadLocalMaxPerBucket 个 - 全局池：每桶最多 globalMaxPerBucket 个
 */
public class DirectByteBufferPool implements ByteBufferPool {

  private final int minBucketSize; // e.g. 1024
  private final int maxBucketSize; // e.g. 1<<20 (1MB)
  private final int bucketCount; // 计算得出
  private final int threadLocalMaxPerBucket;
  private final int globalMaxPerBucket;

  // 全局池：每个桶一个无锁队列
  private final ConcurrentLinkedQueue<ByteBuffer>[] globalPools;

  // 线程本地缓存（每线程一个结构）
  private final ThreadLocal<LocalCache> local = ThreadLocal.withInitial(LocalCache::new);

  public DirectByteBufferPool(int minBucketSize, int maxBucketSize, int threadLocalMaxPerBucket,
      int globalMaxPerBucket) {
    this.minBucketSize = Math.max(1, Pow2.ceilPow2(minBucketSize));
    this.maxBucketSize = Math.max(this.minBucketSize, Pow2.ceilPow2(maxBucketSize));
    int bc = 0;
    for (int sz = this.minBucketSize; sz <= this.maxBucketSize; sz <<= 1)
      bc++;
    this.bucketCount = bc;
    this.threadLocalMaxPerBucket = Math.max(1, threadLocalMaxPerBucket);
    this.globalMaxPerBucket = Math.max(1, globalMaxPerBucket);

    // noinspection unchecked
    this.globalPools = new ConcurrentLinkedQueue[bucketCount];
    for (int i = 0; i < bucketCount; i++) {
      this.globalPools[i] = new ConcurrentLinkedQueue<>();
    }
  }

  @Override
  public ByteBuffer borrow(int minCapacity) {
    int cap = Math.min(Math.max(minCapacity, minBucketSize), maxBucketSize);
    int bucketIdx = bucketIndexFor(cap);
    cap = bucketSize(bucketIdx);

    // 1) 线程本地
    LocalCache lc = local.get();
    ByteBuffer buf = lc.pop(bucketIdx);
    if (buf != null) {
      buf.clear();
      return buf;
    }

    // 2) 全局池
    ConcurrentLinkedQueue<ByteBuffer> q = globalPools[bucketIdx];
    buf = q.poll();
    if (buf != null) {
      buf.clear();
      return buf;
    }

    // 3) 新建
    return ByteBuffer.allocateDirect(cap);
  }

  @Override
  public void giveBack(ByteBuffer buf) {
    if (buf == null || !buf.isDirect())
      return; // 仅池化 direct
    int cap = buf.capacity();
    if (cap < minBucketSize || cap > maxBucketSize || !isPowerOfTwo(cap)) {
      // 非标准桶尺寸：直接丢弃（交给 Cleaner）
      return;
    }
    buf.clear();

    int idx = bucketIndexFor(cap);
    // 优先：线程本地（尝试 O(1) 放入）
    LocalCache lc = local.get();
    if (lc.push(idx, buf, threadLocalMaxPerBucket)) {
      return;
    }
    // 次选：全局池（有上限）
    ConcurrentLinkedQueue<ByteBuffer> q = globalPools[idx];
    if (q.size() < globalMaxPerBucket) {
      q.offer(buf);
      return;
    }
    // 超量：随机丢弃一半概率，避免全局池抖动
    if ((ThreadLocalRandom.current().nextInt() & 1) == 0) {
      q.poll(); // 淘汰一个旧的
      q.offer(buf);
    }
    // 否则直接丢弃：由 Cleaner 回收（可接受，极端峰值保护）
  }

  private boolean isPowerOfTwo(int x) {
    return (x & (x - 1)) == 0;
  }

  private int bucketIndexFor(int cap) {
    int idx = 0;
    int sz = minBucketSize;
    while (sz < cap && idx < bucketCount - 1) {
      sz <<= 1;
      idx++;
    }
    return idx;
  }

  private int bucketSize(int idx) {
    return minBucketSize << idx;
  }

  // 每线程本地缓存
  private static final class LocalCache {
    // 每桶一个栈
    private final ArrayDeque<ByteBuffer>[] stacks;

    @SuppressWarnings("unchecked")
    LocalCache() {
      stacks = new ArrayDeque[32]; // 延迟初始化到实际大小（见 ensure）
    }

    ByteBuffer pop(int idx) {
      ArrayDeque<ByteBuffer> s = ensure(idx);
      return s.pollLast();
    }

    boolean push(int idx, ByteBuffer buf, int limit) {
      ArrayDeque<ByteBuffer> s = ensure(idx);
      if (s.size() >= limit)
        return false;
      s.addLast(buf);
      return true;
    }

    private ArrayDeque<ByteBuffer> ensure(int idx) {
      ArrayDeque<ByteBuffer> s = stacks[idx];
      if (s == null) {
        s = new ArrayDeque<>();
        stacks[idx] = s;
      }
      return s;
    }
  }
}
