package io.jamg.client;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Reuses the JDK client's connection pool; never retries ambiguous writes. */
public final class CacheClient implements AutoCloseable {
  public record Value(byte[] bytes, long version, long expiresAt) {
    public Value {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  public static final class CacheException extends IOException {
    private final int status;

    public CacheException(int status) {
      super("server returned HTTP " + status);
      this.status = status;
    }

    public int status() {
      return status;
    }
  }

  private final URI endpoint;
  private final HttpClient http;
  private final Duration timeout;

  public CacheClient(URI endpoint, Duration timeout) {
    if (!Set.of("http", "https").contains(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null
        || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/")))
      throw new IllegalArgumentException("endpoint must be an HTTP(S) origin without credentials");
    this.endpoint = endpoint;
    this.timeout = timeout;
    http =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  private static String cachePath(String cache) {
    if (!cache.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException("invalid cache name");
    return "/v1/caches/" + cache;
  }

  private static String entryPath(String cache, byte[] key) {
    if (key.length == 0 || key.length > 4096)
      throw new IllegalArgumentException("invalid key size");
    return cachePath(cache)
        + "/entries/"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(key);
  }

  private HttpResponse<byte[]> send(
      String method, String path, byte[] body, Map<String, String> headers)
      throws IOException, InterruptedException {
    var b =
        HttpRequest.newBuilder(endpoint.resolve(path))
            .timeout(timeout)
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
    headers.forEach(b::header);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static void check(HttpResponse<?> r) throws CacheException {
    if (r.statusCode() != 200) throw new CacheException(r.statusCode());
  }

  public Optional<Value> get(String cache, byte[] key) throws IOException, InterruptedException {
    var r = send("GET", entryPath(cache, key), null, Map.of());
    if (r.statusCode() == 404) return Optional.empty();
    check(r);
    return Optional.of(
        new Value(
            r.body(),
            Long.parseLong(r.headers().firstValue("X-Version").orElseThrow()),
            Long.parseLong(r.headers().firstValue("X-Expires-At").orElseThrow())));
  }

  public long put(String cache, byte[] key, byte[] value, long ttlMillis, long expectedVersion)
      throws IOException, InterruptedException {
    var r =
        send(
            "PUT",
            entryPath(cache, key),
            value,
            Map.of(
                "X-Ttl-Millis",
                Long.toString(ttlMillis),
                "X-Expected-Version",
                Long.toString(expectedVersion)));
    check(r);
    return Long.parseLong(r.headers().firstValue("X-Version").orElseThrow());
  }

  public boolean delete(String cache, byte[] key) throws IOException, InterruptedException {
    var r = send("DELETE", entryPath(cache, key), null, Map.of());
    if (r.statusCode() == 404) return false;
    check(r);
    return true;
  }

  public String caches() throws IOException, InterruptedException {
    return text("GET", "/v1/caches");
  }

  public String stats(String cache) throws IOException, InterruptedException {
    return text("GET", cachePath(cache));
  }

  public String health() throws IOException, InterruptedException {
    return text("GET", "/ready");
  }

  public void create(String cache) throws IOException, InterruptedException {
    text("PUT", cachePath(cache));
  }

  public void clear(String cache) throws IOException, InterruptedException {
    text("DELETE", cachePath(cache));
  }

  private String text(String method, String path) throws IOException, InterruptedException {
    var r = send(method, path, null, Map.of());
    check(r);
    return new String(r.body(), java.nio.charset.StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    http.close();
  }
}
