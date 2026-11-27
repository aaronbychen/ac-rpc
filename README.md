# AC RPC

A distributed RPC framework in Java, built on Vert.x TCP networking with etcd-based service discovery.

## Highlights

All numbers are measured with the open-loop harness in `ac-rpc-bench` on a single laptop (client and server share the CPU); every table links to its raw data.

- **Transport rewrite.** The original client leaked three threads per call and completed no requests at 80 rps. The rewrite sustains 250k rps with p99 under 5 ms, and 130k rps on a single connection where gRPC-java saturates at 65–80k ([details](#transport-rewrite), [gRPC comparison](#comparison-with-grpc-java)).
- **Virtual-thread client.** A blocking-socket client with a batching writer reaches 120k rps on one connection at 1.0 ms p99, against 3.2 ms for the Vert.x client and about 37k rps for a lock-per-write design ([details](#virtual-thread-blocking-client)).
- **A JDK bug.** Lost wakeups between virtual threads reading and writing the same socket on Windows, reduced to a [standalone reproducer](bench/repro/PollerStall.java) and matched to JDK-8334574; the upstream fix, adapted to a local JDK 25u build, removed the stall (6/6 runs to 0/6).
- **Failure handling.** With a provider killed at 2,000 rps, callers saw 14,645 errors over 22 s (until its etcd lease expired) without outlier ejection, and none with ejection plus failover, at 0.6 ms p99 ([details](#killing-a-provider-under-load)).
- **Bugs found by tests.** Among them: Kryo cannot serialize exceptions under the JDK module system, so a throwing service left callers waiting for their deadline; the discovery cache was shared by all services; the default registry address pointed at etcd's peer port.

## Features

- Custom binary protocol with TCP framing (Vert.x `RecordParser`) for sticky/half packets
- Two interchangeable clients behind one contract: Vert.x event loops, or blocking sockets on virtual threads (`rpc.transport`)
- JDK dynamic-proxy invocation on the consumer side, reflective dispatch on the provider side
- Service registry SPI with etcd and ZooKeeper backends: per-service discovery cache invalidated by prefix watches (no gap between lookup and watch), last-known providers served when the registry is unreachable, and on etcd one kept-alive lease per provider that is re-granted, with every key rewritten, if it is lost
- Pluggable serializers: JDK / JSON / Kryo / Hessian
- Pluggable load balancers: round-robin / random / consistent hashing (MurmurHash3 ring rebuilt when providers change)
- Retry strategies (`no`, `fixedInterval`) and fault-tolerance strategies: `failFast`, `failSafe`, `failOver` (each other provider once, in random order), `failBack` (default value). Exceptions thrown by the service reach the caller with their original type and never trigger failover
- Outlier ejection: three consecutive transport failures take a provider out of load balancing, with exponential backoff (1 s to 30 s) before it is tried again
- SPI loader for user-supplied extensions (`META-INF/rpc/system/`, `META-INF/rpc/custom/`)
- Spring Boot starter with annotation-driven wiring (`@EnableRpc`, `@RpcService`, `@RpcReference`)

## Repository Layout

| Module | Purpose |
|---|---|
| `ac-rpc-core` | Protocol, TCP client/server, registry, proxy, SPI extensions |
| `ac-rpc-spring-boot-starter` | Spring Boot integration |
| `example-common` | Shared service interfaces and DTOs |
| `example-provider` / `example-consumer` | Plain Java examples |
| `example-springboot-provider` / `example-springboot-consumer` | Spring Boot examples |

## Getting Started

### Prerequisites

- JDK 25
- Maven 3.9+
- etcd (default registry)

Run etcd with Docker:

```bash
docker run -d --name etcd \
  -p 2379:2379 -p 2380:2380 \
  quay.io/coreos/etcd:v3.5.12 \
  /usr/local/bin/etcd \
  --name s1 \
  --advertise-client-urls http://0.0.0.0:2379 \
  --listen-client-urls http://0.0.0.0:2379
```

### Build

```bash
mvn install -DskipTests
```

### Configure

`application.properties`:

```properties
rpc.name=acrpc
rpc.version=2.0
rpc.serverHost=localhost
rpc.serverPort=8080
rpc.serializer=kryo
rpc.loadBalancer=roundRobin
rpc.retryStrategy=no
rpc.tolerantStrategy=failFast
rpc.mock=false
rpc.registryConfig.registry=etcd
rpc.registryConfig.address=http://localhost:2379
rpc.registryConfig.timeout=10000
```

### Run the plain Java example

```bash
mvn -f example-provider/pom.xml exec:java -Dexec.mainClass="com.ac.example.provider.ProviderExample"
mvn -f example-consumer/pom.xml exec:java -Dexec.mainClass="com.ac.example.consumer.ConsumerExample"
```

### Run the Spring Boot example

```bash
mvn -f example-springboot-provider/pom.xml spring-boot:run
mvn -f example-springboot-consumer/pom.xml spring-boot:run
```

## Spring Boot Annotations

- `@EnableRpc(needServer = true|false)`: bootstraps the framework and optionally starts the TCP server
- `@RpcService`: registers a provider implementation locally and in the registry
- `@RpcReference`: injects a remote proxy into a Spring bean

## Benchmarks

`ac-rpc-bench` is an open-loop load generator: requests go out on a fixed schedule, and latency is measured from each request's intended send time, so a slow server cannot hide its queueing delay (coordinated omission). `bench/run.py` runs each configuration for several rounds with fresh JVMs, alternating targets in ABBA order; `bench/analyze.py` reports median [min, max] across rounds and paired per-round ratios between targets.

```bash
python bench/run.py bench/configs/baseline-local.json
```

### Baseline (tag `baseline`)

i9-13980HX laptop, Windows 11, JDK 25, provider and load generator on the same host, 64 B payload, Kryo, 3 rounds × (10 s warmup + 30 s measured). Full data: [`bench/results/baseline-local-20261127T061156Z`](bench/results/baseline-local-20261127T061156Z/summary.md).

| offered rps | p50 ms | p99 ms | peak client threads | outcome |
|---|---|---|---|---|
| 5 | 44 [43, 57] | 77 [69, 233] | 625 | completed |
| 10 | 44 [41, 73] | 70 [59, 101] | 1,223 | completed |
| 20 | 43 [40, 98] | 112 [84, 984] | 2,000+ | aborted at ~33 s (thread cap) |
| 40 | 65 [53, 66] | 286 [168, 333] | 2,000+ | aborted at ~16 s |
| 80 | — | — | 2,000+ | aborted at ~15 s, 0 successful calls |

Every call leaks three threads, because the client builds a new Vert.x instance per request and never closes it. Thread count therefore grows linearly with total requests, so no request rate is sustainable: at 5 rps the client reaches 2,000 threads in about two minutes. Even when idle, a single call costs about 43 ms in Vert.x startup and a fresh TCP connection.

### Transport rewrite

Changes: one shared Vert.x instance, a small connection pool per endpoint with request-ID multiplexing, per-call deadlines, fail-fast connects with automatic reconnect, an in-flight cap per connection, multiple server event loops, cached reflective dispatch, service methods on virtual threads, and pooled Kryo instances (a `ThreadLocal` would create one per virtual thread).

**Paired comparison** against `baseline`, same setup, rounds interleaved ABBA ([data](bench/results/phase1-compare-20261127T062852Z/summary.md)):

| offered rps | baseline p99 ms | rewrite p99 ms | per-round p99 ratio | client threads (baseline → rewrite) |
|---|---|---|---|---|
| 5 | 96.7 [92.9, 99.6] | 3.11 [2.49, 3.45] | 0.032 [0.025, 0.037] | 625 → 22 |
| 10 | 108 [106, 113] | 2.70 [2.47, 2.75] | 0.024 [0.023, 0.025] | 1,223 → 22 |
| 20 | 191 [114, 197]* | 1.99 [1.93, 2.62] | 0.014 [0.010, 0.017] | 2,000+ → 22 |

\* baseline runs aborted at ~33 s on the thread cap. The baseline's p50 also moved from 44 ms to 72 ms between this session and the earlier one, while the rewrite stayed within a few percent, which is why comparisons use per-round pairing rather than numbers from separate sessions.

**Throughput sweep** of the rewrite ([data](bench/results/phase1-sweep-20261127T064208Z/summary.md); 3 rounds × 20 s measured):

| offered rps | achieved rps | p50 ms | p99 ms | p99.9 ms | errors |
|---|---|---|---|---|---|
| 1,000 | 1,000 | 0.25 | 0.48 | 0.65 | 0 |
| 10,000 | 10,000 | 0.13 | 0.29 | 0.67 | 0 |
| 50,000 | 50,000 | 0.17 | 0.55 | 3.12 | 0 |
| 100,000 | 100,000 | 0.32 | 1.18 | 4.29 | 0 |
| 200,000 | 200,000 | 0.62 | 2.21 | 4.26 | 0 |
| 250,000 | 250,000 | 1.42 | 4.68 | 6.31 | 0 |
| 300,000 | 255,000 | 36.4 | 72.0 | 78.7 | 15% shed |

The knee is around 250k rps. Beyond it, latency jumps by an order of magnitude and the load generator sheds requests at its in-flight cap. Thread count stays at 53 throughout. Caveats: the client and server share one laptop, so they compete for CPU, and loopback hides network cost; multi-host numbers will come later. Latency at 5–20 rps is higher than at 10k rps. The likely causes are JIT warmup (only 50–200 calls fall in the warmup window) and CPU idle states, but this has not been investigated.

### Comparison with gRPC-java

gRPC-java 1.84 runs as the reference: generated Protobuf stubs, plaintext, and blocking stubs on virtual threads, driven exactly like ac-rpc. Both systems use **one connection** and a 3 s deadline. gRPC runs in two server configurations: `grpc-vt` (handlers on virtual threads, the same dispatch as ac-rpc) and `grpc-direct` (`directExecutor`, gRPC's recommended setting for non-blocking handlers). Targets alternate ABBA in each round; ratios are computed per round ([data](bench/results/phase2-grpc-20261127T075133Z/summary.md)). One cell (64 B / 130k, round 3) was re-run with all three targets together after the original run stopped early.

| payload | offered rps | ac-rpc p99 ms | grpc-direct p99 ms | grpc-direct / ac-rpc throughput |
|---|---|---|---|---|
| 64 B | 10,000 | 0.32 | 0.39 | 1.00 |
| 64 B | 40,000 | 0.75 | 4.04 | 1.00 |
| 64 B | 100,000 | 1.57 | 220 (saturated) | 0.65 [0.64, 0.80] |
| 64 B | 130,000 | 1.07 | 161 (saturated) | 0.60 [0.57, 0.62] |
| 4 KB | 10,000 | 0.44 | 0.45 | 1.00 |
| 4 KB | 40,000 | 18.5 [1.3, 33.5] | 412 (saturated) | 0.98 |
| 4 KB | 100,000 | 272 (saturated) | 391 (saturated) | 0.77 [0.76, 0.81] |

At 10k rps the two are close; gRPC's p99 is 1.0–1.2× ac-rpc's (1.2–1.4× for `grpc-vt`). On a single connection, ac-rpc sustains 130k rps at 64 B, where gRPC saturates around 65–80k. At 4 KB, ac-rpc's knee is around 50–60k rps and gRPC's is below 40k. The difference is mostly protocol weight. gRPC does HTTP/2 framing, header encoding, flow control, and per-call stream state, and it provides streaming, deadline propagation, interceptors, and cross-language clients. ac-rpc's binary framing supports only unary calls. Several cells are bimodal across rounds, with one round sustaining the offered rate and the others not, which is consistent with laptop thermal throttling over a long run; per-round pairing keeps the ratios stable despite this.

### Virtual-thread blocking client

`rpc.transport=blocking` swaps the Vert.x client for one built on plain blocking sockets and virtual threads, with the same pooling, request-ID multiplexing, deadlines and in-flight cap. Both clients pass the same contract tests (`RpcClientContractTest`), including one where the peer stops reading and every call must still fail at its deadline. All runs below use one connection, a 64 B payload, and a provider on JDK 25; only the client's JDK changes.

**First version (tag `blocking-v1`): each caller writes its own frame under a lock.** It saturates at about 37–41k rps on one connection, where the Vert.x client sustains 80k with p99 near 1 ms ([data](bench/results/phase2-transport-20261127T091739Z/summary.md)):

| client JDK | write lock | saturation rps | p99 at 50k offered |
|---|---|---|---|
| 21 | `ReentrantLock` | ~39–41k | 353 ms |
| 25 | `ReentrantLock` | ~36–37k | 364 ms |
| 21 | `synchronized` | ~30–31k | 347 ms |
| 25 | `synchronized` | ~27–29k | 797 ms |

At saturation, nearly every in-flight call is queued on the write lock, one socket write per call. The deadline also started only after the write, so a stalled socket could hold callers indefinitely. Since JEP 491 (JDK 24), `synchronized` no longer pins the carrier thread, but here it was still the slowest option, and slower on 25 than on 21. This has not been profiled.

**Second version: a writer per connection drains a queue and flushes in batches.** Callers enqueue a frame and wait on a future, so the deadline covers the whole call. The writer writes up to 256 queued frames per flush, so syscalls scale with batches rather than calls. Rounds interleave ABBA; ratios are per round ([data](bench/results/phase2-blocking-v2-20261127T120921Z/summary.md)):

| client JDK | offered rps | v1 p99 ms | v2 p99 ms | Vert.x p99 ms | v2 / Vert.x p99 (paired) |
|---|---|---|---|---|---|
| 25 | 40,000 | 354 (saturated) | 0.33 | 0.38 | 0.85 [0.73, 0.91] |
| 25 | 80,000 | 370 (saturated) | 0.58 | 1.26 | 0.46 [0.44, 0.59] |
| 25 | 120,000 | 302 (saturated) | 1.02 | 3.21 | 0.27 [0.19, 0.32] |
| 21 | 80,000 | — | 1.46 [0.99, 30.5] | 1.33 [1.28, 4.04] | 1.14 [0.25, 22.9] |
| 21 | 120,000 | — | 37.4 [16.2, 107] | 71.6 [9.1, 137] | 0.78 [0.23, 4.13] |

On JDK 25, v2 sustains 120k rps on one connection with a p99 of 1.0 ms, about a quarter of the Vert.x client's at that load. On JDK 21, both clients reach 80k but have unstable tails. At 120k, both shed a few percent of requests and p99 swings by an order of magnitude between rounds. The JDK 21 result is therefore a tie within noise, and what JDK 25 changed for both clients has not been isolated.

**A JDK bug on the way.** With the writer as a virtual thread, v2 occasionally stalled for seconds on Windows and JDK 25. A virtual thread blocked reading and another blocked writing on the same socket could both miss their wakeup. [`bench/repro/PollerStall.java`](bench/repro/PollerStall.java) reproduces it with no RPC code. This is JDK-8334574: Windows' wepoll can lose events when one socket has both a read and a write poll outstanding. It is fixed upstream (JDK-8394217, which removes write pollers on Windows) but not in JDK 25 updates. The writer is now a platform thread, one per connection, so only reads park in the virtual-thread poller. With the upstream fix adapted to a local jdk25u build, the reproducer went from 6/6 stalls to 0/6.

### Killing a provider under load

Three providers on one host, 2,000 rps open loop through etcd discovery (lease TTL 30 s). Ten seconds into a 50 s window, the load generator kills provider 0 with `TerminateProcess`, so it never unregisters. The two strategies are crossed with outlier ejection, 3 rounds each ([data](bench/results/failover-20261127T205432Z/summary.md); `python bench/failover.py bench/configs/failover.json`):

| strategy | ejection | failed calls after kill | errors until (s after kill) | worst per-second p99 |
|---|---|---|---|---|
| `failFast` | off | 14,645 [14,642, 14,827] | 22 [22, 23] | 11 ms |
| `failOver` | off | 0 | — | 15 ms |
| `failFast` | on | 17 [16, 18] | 16 | 0.5 ms |
| `failOver` | on | 0 | — | 0.6 ms |

Pre-kill p99 is 0.41–0.49 ms in every run. Without ejection, round robin keeps sending a third of the traffic to the dead provider until etcd expires its lease, 22–23 s after the kill (the TTL minus the time since the last keep-alive). `failFast` fails all of those calls. `failOver` saves them, but each first waits for a refused connect, which raises p99 to 11–15 ms. With ejection, three failures take the provider out within the first second. It is tried again after 1, 2, 4 and 8 s, three failed calls each time, which accounts for the 16–18 failures, and then the lease expires. Combined with `failOver`, callers saw no errors and p99 stayed under 0.6 ms.

The p99 of calls to the *healthy* providers also rose to about 11 ms without ejection under `failFast`. This happens with the Vert.x client but not with the blocking client (p99 stayed at 0.45–0.51 ms in a smoke test), and it disappears once ejection stops the reconnect attempts. The client's CPU was idle according to a JFR profile, and a non-blocking connect to the closed port returns in under 1 ms, so the event loop is not blocked in the call itself. The exact mechanism inside Vert.x/Netty has not been identified.

## Known Limitations

- `failOver` resends a request that may already have run on the failed provider, so it is only safe for idempotent methods; there is no per-method opt-in yet
- Outlier ejection counts only transport failures and deadlines. A provider that answers quickly with errors, or is slow but within the deadline, stays in rotation
- Registry integration tests run against etcd only; the ZooKeeper backend shares the cache logic but has no tests
- Benchmarks so far run client and server on a single host

## Roadmap

- Benchmarks: multi-host runs (separate client, provider and etcd machines, e.g. cloud VMs) to remove the shared-CPU caveat and add real network latency; repeat the transport, gRPC and failover experiments there
- Resilience: fault-injection suite on Kubernetes (kind or a small cluster): network partitions between consumer, provider and etcd, slow and flapping providers, etcd member loss; measure errors and recovery time per fault as in the provider-kill experiment
- Resilience: latency-based ejection, per-method idempotency opt-in for `failOver`
- Observability: OpenTelemetry tracing, Prometheus metrics
