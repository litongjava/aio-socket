package nexus.io.enhance.buffer;

import java.nio.ByteBuffer;

import org.junit.Test;

public class DirectBufferCleanerTest {

  @Test
  public void test() {
    System.out.println(DirectBufferCleaner.JAVA_MAJOR_VERSION);
  }

  @Test
  public void cleansDirectBufferOnTheRunningJdk() {
    DirectBufferCleaner.clean(ByteBuffer.allocateDirect(64));
  }

}
