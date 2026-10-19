# jamG

A small Java 21 cache under development. The current implementation is the **single-node, volatile first milestone**, not yet a distributed or persistent cache. See [architecture and remaining milestones](docs/architecture.md) for consistency, durability and failure-handling decisions.

## Build

Requires a full JDK 21 (a JRE alone is insufficient), Maven 3.9.9+, and access to Maven Central. Runtime code has no third-party dependencies. JUnit is test-only.

```sh
mvn verify
```

In this cloud workspace use:

```sh
JAVA_HOME=/workspace/tools/jdk-21.0.9+10 \
MAVEN_OPTS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
/workspace/tools/apache-maven-3.9.9/bin/mvn \
  -Dmaven.repo.local=/workspace/.m2/repository \
  -s /workspace/tools/maven-settings.xml verify
```

The workspace-specific Maven settings supply proxy routing without credentials. The command selects a writable Maven cache and the system Java truststore, which contains the cloud proxy CA. These overrides are specific to this workspace; a standard JDK/Maven installation can use `mvn verify`.

## Run

```sh
java -Xmx256m -jar server/target/jamg-server-0.1.0-SNAPSHOT.jar --config config/local.properties
java -jar cli/target/jamg-cli-0.1.0-SNAPSHOT.jar status
java -jar cli/target/jamg-cli-0.1.0-SNAPSHOT.jar put hello world --ttl 10m
java -jar cli/target/jamg-cli-0.1.0-SNAPSHOT.jar get hello
java -jar cli/target/jamg-cli-0.1.0-SNAPSHOT.jar cache create sessions
java -jar cli/target/jamg-cli-0.1.0-SNAPSHOT.jar put user payload --cache sessions --expected-version 0
java -jar cli/target/jamg-cli-0.1.0-SNAPSHOT.jar cache clear sessions --yes
```

`--expected-version 0` means put-if-absent; a positive version means compare-and-set; omitted means unconditional write. Get with `--json` returns Base64 data, version and expiration deadline. TTL accepts integer milliseconds or `ms`, `s`, `m`, `h`; zero means no expiration. Get emits raw bytes without a newline by default. Use `--input FILE` or `--input -` for file/stdin values and `--output FILE` for binary downloads. CLI keys are UTF-8; the client API supports binary keys.

Exit codes: 0 success; 2 invalid arguments or missing destructive confirmation; 3 entry absent; 4 conflict; 5 HTTP, network or file failure. Control commands emit JSON; `--json` selects a structured binary-safe envelope for `get` and `put`. No interactive shell or credential profiles yet.

Configuration precedence: command-line options > `JAMG_HOST`, `JAMG_PORT`, `JAMG_CAPACITY`, `JAMG_MAX_CACHES` > properties file > defaults. Capacity is estimated bytes **per cache**, not a process heap cap. Defaults are loopback:7410, 16 MiB per cache and at most eight caches. Named caches share these limits in this milestone. Limit keys to 4096 bytes and values to 1 MiB. Remote bindings are refused. There is no authentication or TLS server yet.

Stop with SIGTERM or Ctrl-C. Data disappears on shutdown. The current processes are not a reusable cluster; starting three servers creates three independent caches.

## Embedded use

```java
try (var client = new io.jamg.client.CacheClient(
        java.net.URI.create("http://127.0.0.1:7410"), java.time.Duration.ofSeconds(5))) {
    long version = client.put("default", new byte[]{1}, new byte[]{2}, 60_000, 0);
    var value = client.get("default", new byte[]{1});
}
```

Use `new io.jamg.core.Cache(capacity, Clock.systemUTC())` for embedded storage without networking. One cache serializes its operations; different named caches can proceed independently. Client instances reuse a connection pool and must be closed. Requests have timeouts and writes are never automatically retried. A timeout does not establish whether a write was applied. Errors currently use HTTP status-bearing exceptions rather than a complete typed error hierarchy.

## Protocol

- `GET /health`, `GET /ready`: readiness JSON.
- `GET /v1/caches`: cache names.
- `PUT /v1/caches/NAME`: create cache idempotently.
- `GET /v1/caches/NAME`: stats.
- `DELETE /v1/caches/NAME`: clear entries, retaining the cache and version sequence.
- `GET|PUT|DELETE /v1/caches/NAME/entries/BASE64URL_KEY`.
- PUT headers: `X-Ttl-Millis`, `X-Expected-Version`; raw value body.
- GET response headers: `X-Version`, `X-Expires-At` (epoch milliseconds; 0 for no TTL).

There are four HTTP workers and a bounded work queue. This prototype has no slow-client defense and is restricted to trusted local clients. Stats expose approximate capacity, entries, hits, misses, evictions and expirations. Native Java deserialization is never used.

## Not implemented yet

Persistence/backup, replication/failover, dynamic membership/rebalancing, batch APIs, per-cache configuration, typed protocol errors, TLS/auth/roles, endpoint profiles, distributed metrics and performance benchmarking. These remain acceptance gates in the architecture document. Do not use this milestone where data survival or cluster behavior is required.
