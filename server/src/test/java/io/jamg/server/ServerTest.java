package io.jamg.server;

import static org.junit.jupiter.api.Assertions.*;

import io.jamg.client.CacheClient;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ServerTest {
  @Test
  void functionalProtocol() throws Exception {
    try (var server = new CacheServer(new InetSocketAddress("127.0.0.1", 0), 4096, 2)) {
      server.start();
      try (var c =
          new CacheClient(URI.create("http://127.0.0.1:" + server.port()), Duration.ofSeconds(2))) {
        assertEquals("{\"ready\":true}", c.health());
        c.create("sessions");
        byte[] key = {0, 1, -1}, value = {0, -1, 2};
        long version = c.put("sessions", key, value, 0, 0);
        assertArrayEquals(value, c.get("sessions", key).orElseThrow().bytes());
        var conflict =
            assertThrows(
                CacheClient.CacheException.class, () -> c.put("sessions", key, value, 0, 0));
        assertEquals(409, conflict.status());
        c.put("sessions", key, new byte[] {3}, 0, version);
        assertArrayEquals(new byte[] {3}, c.get("sessions", key).orElseThrow().bytes());
        assertTrue(c.delete("sessions", key));
        assertFalse(c.delete("sessions", key));
        assertThrows(CacheClient.CacheException.class, () -> c.create("too-many"));
        c.put("sessions", key, value, 0, -1);
        c.clear("sessions");
        assertTrue(c.get("sessions", key).isEmpty());
        assertEquals("[\"default\",\"sessions\"]", c.caches());
        assertTrue(c.stats("sessions").contains("\"entries\":0"));
      }
    }
  }

  @Test
  void rejectsRemoteBinding() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CacheServer(new InetSocketAddress("0.0.0.0", 0), 4096, 1));
  }

  @Test
  void rejectsOversizeAndMalformedRequests() throws Exception {
    try (var server = new CacheServer(new InetSocketAddress("127.0.0.1", 0), 2_000_000, 1);
        var http = HttpClient.newHttpClient()) {
      server.start();
      String base = "http://127.0.0.1:" + server.port() + "/v1/caches/default/entries/";
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "aw"))
                  .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[1048577]))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(413, response.statusCode());
      response =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "!")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(400, response.statusCode());
    }
  }
}
