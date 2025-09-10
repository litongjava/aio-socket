package com.litongjava.enhance.buffer;

final class Pow2 {
  static int ceilPow2(int x) {
    if (x <= 1) {
      return 1;
    }

    x--;
    x |= x >> 1;
    x |= x >> 2;
    x |= x >> 4;
    x |= x >> 8;
    x |= x >> 16;
    return (x + 1);
  }
}
