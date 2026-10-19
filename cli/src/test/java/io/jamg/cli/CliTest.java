package io.jamg.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jamg.server.CacheServer;
import java.io.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CliTest {
  @Test
  void commandsAndExitCodes() throws Exception {
    try (var server = new CacheServer(new InetSocketAddress("127.0.0.1", 0), 4096, 2)) {
      server.start();
      var bytes = new ByteArrayOutputStream();
      var output = new PrintStream(bytes);
      String url = "http://127.0.0.1:" + server.port();
      assertEquals(0, run(url, output, "put", "hello", "world"));
      bytes.reset();
      assertEquals(0, run(url, output, "get", "hello"));
      assertEquals("world", bytes.toString());
      assertEquals(4, run(url, output, "put", "hello", "other", "--expected-version", "0"));
      assertEquals(2, run(url, output, "cache", "clear", "default"));
      assertEquals(0, run(url, output, "cache", "clear", "default", "--yes"));
      assertEquals(3, run(url, output, "get", "hello"));
      assertEquals(2, run(url, output, "put", "x", "v", "--ttl", "-1"));
    }
  }

  static int run(String endpoint, PrintStream out, String... args) {
    var a = new ArrayList<>(List.of("--endpoint", endpoint));
    a.addAll(List.of(args));
    return Cli.run(a.toArray(String[]::new), out, out);
  }
}
