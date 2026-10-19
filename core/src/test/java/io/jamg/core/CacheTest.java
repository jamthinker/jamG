package io.jamg.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheTest {
  static byte[] b(String s) {
    return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  static final class TestClock extends Clock {
    long now = 1000;

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(now);
    }
  }

  @Test
  void ttlAndDefensiveCopies() {
    var clock = new TestClock();
    var c = new Cache(4096, clock);
    byte[] k = b("key"), v = b("value");
    c.put(k, v, 100, -1);
    k[0] = 0;
    v[0] = 0;
    var result = c.get(b("key")).orElseThrow();
    assertArrayEquals(b("value"), result.bytes());
    result.bytes()[0] = 0;
    assertArrayEquals(b("value"), c.get(b("key")).orElseThrow().bytes());
    clock.now = 1100;
    assertTrue(c.get(b("key")).isEmpty());
    assertEquals(1L, c.stats().get("expirations"));
  }

  @Test
  void lruAndCapacity() {
    var c = new Cache(260, new TestClock());
    c.put(b("a"), b("1"), 0, -1);
    c.put(b("b"), b("2"), 0, -1);
    c.get(b("a"));
    c.put(b("c"), b("3"), 0, -1);
    assertTrue(c.get(b("b")).isEmpty());
    assertTrue(c.get(b("a")).isPresent());
    assertEquals(260L, c.stats().get("estimatedBytes"));
    assertThrows(IllegalArgumentException.class, () -> c.put(b("big"), new byte[300], 0, -1));
    assertTrue(c.get(b("a")).isPresent());
  }

  @Test
  void concurrentPutIfAbsentAndCas() throws Exception {
    var c = new Cache(4096, new TestClock());
    var successes = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(8)) {
      var gate = new CountDownLatch(1);
      var tasks = new java.util.ArrayList<Future<?>>();
      for (int i = 0; i < 32; i++)
        tasks.add(
            executor.submit(
                () -> {
                  try {
                    gate.await();
                    if (c.put(b("k"), b("v"), 0, 0).isPresent()) successes.incrementAndGet();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }));
      gate.countDown();
      for (var task : tasks) task.get();
    }
    assertEquals(1, successes.get());
    long version = c.get(b("k")).orElseThrow().version();
    assertTrue(c.put(b("k"), b("new"), 0, version).isPresent());
    assertTrue(c.put(b("k"), b("bad"), 0, version).isEmpty());
    c.clear();
    assertTrue(c.put(b("k"), b("later"), 0, 0).orElseThrow().version() > version);
  }

  @Test
  void invalidInputAndCleanup() {
    var clock = new TestClock();
    var c = new Cache(4096, clock);
    assertThrows(IllegalArgumentException.class, () -> c.put(b(""), b("v"), 0, -1));
    assertThrows(IllegalArgumentException.class, () -> c.put(b("k"), b("v"), -1, -1));
    c.put(b("k"), b("v"), 1, -1);
    clock.now++;
    c.cleanExpired();
    assertEquals(0L, c.stats().get("entries"));
  }

  @Test
  void writesDoNotInflateReadMetrics() {
    var c = new Cache(4096, new TestClock());
    c.put(b("k"), b("v"), 0, -1);
    c.put(b("k"), b("other"), 0, 0);
    assertEquals(0L, c.stats().get("hits"));
    assertEquals(0L, c.stats().get("misses"));
    c.get(b("k"));
    c.get(b("missing"));
    assertEquals(1L, c.stats().get("hits"));
    assertEquals(1L, c.stats().get("misses"));
  }
}
