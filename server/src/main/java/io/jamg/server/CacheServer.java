package io.jamg.server;

import com.sun.net.httpserver.*;
import io.jamg.core.Cache;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Loopback HTTP transport. No remote deployment until TLS and authorization are implemented. */
public final class CacheServer implements AutoCloseable {
  private final HttpServer server;
  private final Map<String, Cache> caches = new ConcurrentHashMap<>();
  private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
  private final ThreadPoolExecutor workers;
  private final long capacity;
  private final int maxCaches;
  private volatile boolean ready;

  public CacheServer(InetSocketAddress address, long capacity, int maxCaches) throws IOException {
    if (capacity < 1 || maxCaches < 1 || maxCaches > 1024)
      throw new IllegalArgumentException("invalid capacity or maxCaches");
    if (address.getAddress() == null || !address.getAddress().isLoopbackAddress())
      throw new IllegalArgumentException("only loopback binding is supported in this milestone");
    this.capacity = capacity;
    this.maxCaches = maxCaches;
    server = HttpServer.create(address, 64);
    workers =
        new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            new ThreadPoolExecutor.AbortPolicy());
    server.setExecutor(workers);
    server.createContext("/", this::handle);
    caches.put("default", new Cache(capacity, Clock.systemUTC()));
  }

  public void start() {
    server.start();
    ready = true;
    cleaner.scheduleWithFixedDelay(
        () -> caches.values().forEach(Cache::cleanExpired), 1, 1, TimeUnit.SECONDS);
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private static String jsonString(String s) {
    return "\"" + s + "\"";
  }

  private static String object(Map<String, Long> values) {
    return values.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> jsonString(e.getKey()) + ":" + e.getValue())
        .collect(java.util.stream.Collectors.joining(",", "{", "}"));
  }

  private static void reply(HttpExchange x, int status, String body) throws IOException {
    reply(x, status, body.getBytes(StandardCharsets.UTF_8), "application/json");
  }

  private static void reply(HttpExchange x, int status, byte[] body, String type)
      throws IOException {
    x.getResponseHeaders().set("Content-Type", type);
    x.sendResponseHeaders(status, body.length);
    x.getResponseBody().write(body);
  }

  private void handle(HttpExchange x) throws IOException {
    try {
      String path = x.getRequestURI().getRawPath(), method = x.getRequestMethod();
      if (path.length() > 8192) {
        reply(x, 414, "{\"error\":\"URI too long\"}");
        return;
      }
      if (path.equals("/health") || path.equals("/ready")) {
        if (!method.equals("GET")) {
          reply(x, 405, "{}");
          return;
        }
        reply(x, ready ? 200 : 503, "{\"ready\":" + ready + "}");
        return;
      }
      if (path.equals("/v1/caches") && method.equals("GET")) {
        reply(
            x,
            200,
            caches.keySet().stream()
                .sorted()
                .map(CacheServer::jsonString)
                .collect(java.util.stream.Collectors.joining(",", "[", "]")));
        return;
      }
      String[] parts = path.split("/", -1);
      if (parts.length < 4
          || !parts[1].equals("v1")
          || !parts[2].equals("caches")
          || !parts[3].matches("[A-Za-z0-9_-]{1,64}")) {
        reply(x, 404, "{}");
        return;
      }
      String name = parts[3];
      if (parts.length == 4 && method.equals("PUT")) {
        synchronized (caches) {
          if (!caches.containsKey(name)) {
            if (caches.size() >= maxCaches) {
              reply(x, 409, "{\"error\":\"cache limit reached\"}");
              return;
            }
            caches.put(name, new Cache(capacity, Clock.systemUTC()));
          }
        }
        reply(x, 200, "{}");
        return;
      }
      Cache cache = caches.get(name);
      if (cache == null) {
        reply(x, 404, "{\"error\":\"unknown cache\"}");
        return;
      }
      if (parts.length == 4) {
        if (method.equals("GET")) reply(x, 200, object(cache.stats()));
        else if (method.equals("DELETE")) {
          cache.clear();
          reply(x, 200, "{}");
        } else reply(x, 405, "{}");
        return;
      }
      if (parts.length != 6 || !parts[4].equals("entries")) {
        reply(x, 404, "{}");
        return;
      }
      if (parts[5].length() > 5500) throw new IllegalArgumentException("key too large");
      byte[] key = Base64.getUrlDecoder().decode(parts[5]);
      switch (method) {
        case "GET" -> {
          var v = cache.get(key);
          if (v.isEmpty()) reply(x, 404, "{}");
          else {
            x.getResponseHeaders().set("X-Version", Long.toString(v.get().version()));
            x.getResponseHeaders().set("X-Expires-At", Long.toString(v.get().expiresAt()));
            reply(x, 200, v.get().bytes(), "application/octet-stream");
          }
        }
        case "PUT" -> {
          byte[] body = x.getRequestBody().readNBytes(Cache.MAX_VALUE + 1);
          if (body.length > Cache.MAX_VALUE) {
            reply(x, 413, "{\"error\":\"value too large\"}");
            return;
          }
          String ttl = x.getRequestHeaders().getFirst("X-Ttl-Millis"),
              expected = x.getRequestHeaders().getFirst("X-Expected-Version");
          var v =
              cache.put(
                  key,
                  body,
                  ttl == null ? 0 : Long.parseLong(ttl),
                  expected == null ? -1 : Long.parseLong(expected));
          if (v.isEmpty()) reply(x, 409, "{\"error\":\"version conflict\"}");
          else {
            x.getResponseHeaders().set("X-Version", Long.toString(v.get().version()));
            reply(x, 200, "{\"version\":" + v.get().version() + "}");
          }
        }
        case "DELETE" -> reply(x, cache.delete(key) ? 200 : 404, "{}");
        default -> reply(x, 405, "{}");
      }
    } catch (IllegalArgumentException | ArithmeticException e) {
      reply(x, 400, "{\"error\":\"invalid request\"}");
    } catch (Exception e) {
      System.err.println("{\"level\":\"error\",\"event\":\"request_failed\"}");
      reply(x, 500, "{\"error\":\"internal error\"}");
    } finally {
      x.close();
    }
  }

  @Override
  public void close() {
    ready = false;
    server.stop(1);
    cleaner.shutdownNow();
    workers.shutdownNow();
  }
}
