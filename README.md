# AC RPC

A distributed RPC framework in Java, built on Vert.x TCP networking with etcd-based service discovery.

## Features

- Custom binary protocol with TCP framing (Vert.x `RecordParser`) for sticky/half packets
- JDK dynamic-proxy invocation on the consumer side, reflective dispatch on the provider side
- Service registry SPI with etcd (lease TTL, heartbeat renewal, watch-driven cache invalidation) and ZooKeeper backends
- Pluggable serializers: JDK / JSON / Kryo / Hessian
- Pluggable load balancers: round-robin / random / consistent hashing
- Retry strategies (`no`, `fixedInterval`) and fault-tolerance strategies (`failFast`, `failSafe`)
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

## Known Limitations

These are tracked and being addressed:

- `failOver` / `failBack` tolerant strategies are stubs; retries target the same node
- The registry's discovery cache is not keyed by service, so multiple services can collide
- Heartbeats re-grant a new etcd lease instead of using keep-alive
- `RoundRobinLoadBalancer` goes negative after its counter overflows; `ConsistentHashLoadBalancer` never removes departed nodes from its ring and is not thread-safe
- Benchmarks so far run client and server on a single host

## Roadmap

- Serialization: Protobuf
- Benchmarks: open-loop load generator (HdrHistogram), paired comparison against gRPC-java
- Resilience: node-exclusion failover, circuit breaking, fault-injection suite on Kubernetes
- Observability: OpenTelemetry tracing, Prometheus metrics
