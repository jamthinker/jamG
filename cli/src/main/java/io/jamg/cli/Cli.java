package io.jamg.cli;

import io.jamg.client.CacheClient;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class Cli {
  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  public static int run(String[] args, PrintStream out, PrintStream err) {
    List<String> words = new ArrayList<>();
    Map<String, String> options = new HashMap<>();
    try {
      for (int i = 0; i < args.length; i++) {
        if (args[i].equals("--yes") || args[i].equals("--json") || args[i].equals("--help")) {
          options.put(args[i], "true");
          continue;
        }
        if (args[i].startsWith("--")) {
          if (!Set.of(
                  "--endpoint",
                  "--cache",
                  "--ttl",
                  "--expected-version",
                  "--input",
                  "--output",
                  "--timeout")
              .contains(args[i])) throw new IllegalArgumentException("unknown option");
          if (i + 1 == args.length) throw new IllegalArgumentException("missing option value");
          options.put(args[i], args[++i]);
        } else words.add(args[i]);
      }
      if (options.containsKey("--help")) {
        out.println(
            "cachectl [--endpoint URL] [--cache NAME] get KEY | put KEY [VALUE] [--input FILE|-]"
                + " [--ttl 10m] [--expected-version N] | delete KEY | exists KEY | stats | status |"
                + " cache list|create NAME|describe NAME|clear NAME --yes; --json; --output FILE;"
                + " --timeout MILLIS");
        return 0;
      }
      if (words.isEmpty()) throw new IllegalArgumentException("command required; use --help");
      String cache = options.getOrDefault("--cache", "default"), command = words.getFirst();
      try (var client =
          new CacheClient(
              URI.create(
                  options.getOrDefault(
                      "--endpoint",
                      System.getenv().getOrDefault("JAMG_ENDPOINT", "http://127.0.0.1:7410"))),
              Duration.ofMillis(Long.parseLong(options.getOrDefault("--timeout", "5000"))))) {
        switch (command) {
          case "get", "exists" -> {
            require(words, 2);
            var v = client.get(cache, words.get(1).getBytes(StandardCharsets.UTF_8));
            if (command.equals("exists")) {
              out.println(v.isPresent());
              return v.isPresent() ? 0 : 3;
            }
            if (v.isEmpty()) return 3;
            byte[] value = v.get().bytes();
            if (options.containsKey("--output"))
              Files.write(Path.of(options.get("--output")), value);
            else if (options.containsKey("--json"))
              out.println(
                  "{\"valueBase64\":\""
                      + Base64.getEncoder().encodeToString(value)
                      + "\",\"version\":"
                      + v.get().version()
                      + ",\"expiresAt\":"
                      + v.get().expiresAt()
                      + "}");
            else out.write(value);
          }
          case "put" -> {
            byte[] value;
            if (options.containsKey("--input")) {
              require(words, 2);
              String source = options.get("--input");
              if (source.equals("-")) value = System.in.readNBytes(1048577);
              else
                try (var in = Files.newInputStream(Path.of(source))) {
                  value = in.readNBytes(1048577);
                }
            } else {
              require(words, 3);
              value = words.get(2).getBytes(StandardCharsets.UTF_8);
            }
            if (value.length > 1048576) throw new IllegalArgumentException("value exceeds 1 MiB");
            long v =
                client.put(
                    cache,
                    words.get(1).getBytes(StandardCharsets.UTF_8),
                    value,
                    ttl(options.getOrDefault("--ttl", "0")),
                    Long.parseLong(options.getOrDefault("--expected-version", "-1")));
            out.println(
                options.containsKey("--json") ? "{\"version\":" + v + "}" : Long.toString(v));
          }
          case "delete" -> {
            require(words, 2);
            if (!client.delete(cache, words.get(1).getBytes(StandardCharsets.UTF_8))) return 3;
            out.println("{}");
          }
          case "stats" -> {
            require(words, 1);
            out.println(client.stats(cache));
          }
          case "status" -> {
            require(words, 1);
            out.println(client.health());
          }
          case "cache" -> {
            if (words.size() < 2) throw new IllegalArgumentException("cache subcommand required");
            switch (words.get(1)) {
              case "list" -> {
                require(words, 2);
                out.println(client.caches());
              }
              case "create" -> {
                require(words, 3);
                client.create(words.get(2));
                out.println("{}");
              }
              case "describe" -> {
                require(words, 3);
                out.println(client.stats(words.get(2)));
              }
              case "clear" -> {
                require(words, 3);
                if (!options.containsKey("--yes"))
                  throw new IllegalArgumentException("clear requires --yes");
                client.clear(words.get(2));
                out.println("{}");
              }
              default -> throw new IllegalArgumentException("unknown cache subcommand");
            }
          }
          default -> throw new IllegalArgumentException("unknown command; use --help");
        }
      }
      return 0;
    } catch (CacheClient.CacheException e) {
      err.println(e.getMessage());
      return e.status() == 409 ? 4 : 5;
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      return 2;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      err.println("interrupted");
      return 5;
    } catch (IOException e) {
      err.println("request or file operation failed: " + e.getClass().getSimpleName());
      return 5;
    }
  }

  private static void require(List<String> words, int n) {
    if (words.size() != n) throw new IllegalArgumentException("invalid arguments; use --help");
  }

  private static long ttl(String text) {
    long multiplier = 1;
    String number = text;
    if (text.endsWith("ms")) number = text.substring(0, text.length() - 2);
    else if (text.endsWith("s")) {
      multiplier = 1000;
      number = text.substring(0, text.length() - 1);
    } else if (text.endsWith("m")) {
      multiplier = 60000;
      number = text.substring(0, text.length() - 1);
    } else if (text.endsWith("h")) {
      multiplier = 3600000;
      number = text.substring(0, text.length() - 1);
    }
    try {
      long result = Math.multiplyExact(Long.parseLong(number), multiplier);
      if (result < 0) throw new IllegalArgumentException("negative TTL");
      return result;
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("TTL too large");
    }
  }
}
