package io.jamg.server;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class ServerMain {
  public static void main(String[] args) throws Exception {
    Properties props = new Properties();
    for (int i = 0; i < args.length; i += 2) {
      if (i + 1 >= args.length) throw new IllegalArgumentException("options require values");
      if (args[i].equals("--config"))
        try (var in = Files.newInputStream(Path.of(args[i + 1]))) {
          props.load(in);
        }
    }
    for (String k : List.of("host", "port", "capacity", "max-caches")) {
      String value = System.getenv("JAMG_" + k.toUpperCase(Locale.ROOT).replace('-', '_'));
      if (value != null) props.setProperty(k, value);
    }
    for (int i = 0; i < args.length; i += 2) {
      if (args[i].equals("--config")) continue;
      if (!List.of("--host", "--port", "--capacity", "--max-caches").contains(args[i]))
        throw new IllegalArgumentException("unknown option: " + args[i]);
      props.setProperty(args[i].substring(2), args[i + 1]);
    }
    CacheServer s =
        new CacheServer(
            new InetSocketAddress(
                props.getProperty("host", "127.0.0.1"),
                Integer.parseInt(props.getProperty("port", "7410"))),
            Long.parseLong(props.getProperty("capacity", "16777216")),
            Integer.parseInt(props.getProperty("max-caches", "8")));
    Runtime.getRuntime().addShutdownHook(new Thread(s::close));
    s.start();
    System.out.println("{\"event\":\"listening\",\"port\":" + s.port() + "}");
    new CountDownLatch(1).await();
  }
}
