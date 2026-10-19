package io.jamg.core;

import java.time.Clock;
import java.util.*;

/** Bounded, linearizable in-process cache. Keys and values are defensively copied. */
public final class Cache {
  public record Value(byte[] bytes, long version, long expiresAt) {
    public Value {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  private record Key(byte[] bytes) {
    Key {
      bytes = bytes.clone();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Key k && Arrays.equals(bytes, k.bytes);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(bytes);
    }
  }

  private final LinkedHashMap<Key, Value> entries = new LinkedHashMap<>(16, .75f, true);
  private final Clock clock;
  private final long capacity;
  private long used, sequence, hits, misses, evictions, expirations;
  public static final int MAX_KEY = 4096, MAX_VALUE = 1024 * 1024;

  public Cache(long capacity, Clock clock) {
    if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
    this.capacity = capacity;
    this.clock = Objects.requireNonNull(clock);
  }

  private static void keyCheck(byte[] key) {
    if (key == null || key.length == 0 || key.length > MAX_KEY)
      throw new IllegalArgumentException("key must contain 1..4096 bytes");
  }

  private static long weight(Key key, Value value) {
    return 128L + key.bytes.length + value.bytes.length;
  }

  public synchronized Optional<Value> get(byte[] key) {
    keyCheck(key);
    Value v = liveValue(new Key(key));
    if (v == null) misses++;
    else hits++;
    return Optional.ofNullable(v);
  }

  private Value liveValue(Key k) {
    Value v = entries.get(k);
    if (v != null && v.expiresAt != 0 && v.expiresAt <= clock.millis()) {
      entries.remove(k);
      used -= weight(k, v);
      expirations++;
      return null;
    }
    return v;
  }

  /** expectedVersion: -1 unconditional, 0 absent, positive compare-and-set. */
  public synchronized Optional<Value> put(
      byte[] key, byte[] bytes, long ttlMillis, long expectedVersion) {
    keyCheck(key);
    if (bytes == null || bytes.length > MAX_VALUE)
      throw new IllegalArgumentException("value exceeds 1 MiB");
    if (ttlMillis < 0 || expectedVersion < -1)
      throw new IllegalArgumentException("invalid TTL or version");
    long expiry = ttlMillis == 0 ? 0 : Math.addExact(clock.millis(), ttlMillis);
    Key k = new Key(key);
    long size = 128L + key.length + bytes.length;
    if (size > capacity) throw new IllegalArgumentException("entry exceeds cache capacity");
    Value old = liveValue(k);
    if (expectedVersion == 0 && old != null
        || expectedVersion > 0 && (old == null || old.version != expectedVersion))
      return Optional.empty();
    Value v = new Value(bytes, Math.incrementExact(sequence), expiry);
    sequence = v.version;
    if (old != null) used -= weight(k, old);
    entries.put(k, v);
    used += size;
    cleanExpired();
    while (used > capacity) {
      var it = entries.entrySet().iterator();
      var e = it.next();
      used -= weight(e.getKey(), e.getValue());
      it.remove();
      evictions++;
    }
    return Optional.of(v);
  }

  public synchronized boolean delete(byte[] key) {
    keyCheck(key);
    Value v = entries.remove(new Key(key));
    if (v == null) return false;
    used -= 128L + key.length + v.bytes.length;
    return v.expiresAt == 0 || v.expiresAt > clock.millis();
  }

  public synchronized void clear() {
    entries.clear();
    used = 0;
  }

  public synchronized void cleanExpired() {
    long now = clock.millis();
    var it = entries.entrySet().iterator();
    while (it.hasNext()) {
      var e = it.next();
      if (e.getValue().expiresAt != 0 && e.getValue().expiresAt <= now) {
        used -= weight(e.getKey(), e.getValue());
        it.remove();
        expirations++;
      }
    }
  }

  public synchronized Map<String, Long> stats() {
    cleanExpired();
    return Map.of(
        "entries",
        (long) entries.size(),
        "estimatedBytes",
        used,
        "capacityBytes",
        capacity,
        "hits",
        hits,
        "misses",
        misses,
        "evictions",
        evictions,
        "expirations",
        expirations);
  }
}
