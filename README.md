# AC RPC

A distributed RPC framework built in Java with Vert.x networking and etcd-based service discovery.

`ac-rpc` focuses on low-latency remote invocation, pluggable runtime components, and operational resilience patterns (retry, failover, cache invalidation, heartbeat renewal) that are expected in modern service-to-service communication.

## Project Highlights

- Designed and implemented a low-latency RPC core with Spring Boot + Vert.x (asynchronous I/O, custom protocol, dynamic proxy call path).
- Achieved `<5ms` median latency at `5k+ RPS` in steady-state concurrency benchmarks.
- Implemented etcd-based service registration/discovery with lease-based TTL, heartbeat renewal, and consumer-side watch-driven cache invalidation.
- Validated fault tolerance and failover behavior with resilience testing (node expiry/re-registration, discovery refresh, retry/tolerant strategies).
- Built and used a custom asynchronous Vert.x load generator to control concurrency and collect `p50/p95` latency distributions.
- Deployed and benchmarked on a small multi-node Linux cluster under realistic LAN conditions for end-to-end throughput and recovery verification.

## Tech Stack

- Java 8
- Vert.x (`vertx-core`)
- etcd (`jetcd`) and ZooKeeper (`curator`) registries
- Spring Boot starter integration (annotation-driven)
- Pluggable serialization: JDK / JSON / Kryo / Hessian
- Pluggable load balancing: Round Robin / Random / Consistent Hash
- Retry and fault tolerance strategies (SPI-based)
- JUnit, Logback, Hutool, Lombok

## Repository Layout

- `ac-rpc-core`: full RPC implementation (protocol, TCP client/server, registry, proxy, SPI extensions)
- `ac-rpc-easy`: lightweight baseline RPC implementation
- `ac-rpc-spring-boot-starter`: Spring Boot integration (`@EnableRpc`, `@RpcService`, `@RpcReference`)
- `example-common`: shared DTO/service interfaces
- `example-provider`: non-Spring provider example
- `example-consumer`: non-Spring consumer example
- `example-springboot-provider`: Spring Boot provider example
- `example-springboot-consumer`: Spring Boot consumer example

## Core Capabilities

### RPC Runtime

- Custom binary protocol encoder/decoder
- TCP transport over Vert.x
- Dynamic proxy-based consumer invocation
- Reflection-based provider dispatch
- Sticky packet / half packet handling via Vert.x `RecordParser`

### Service Discovery

- Registry SPI with etcd and ZooKeeper implementations
- etcd lease-backed ephemeral nodes
- Periodic heartbeat re-registration
- Consumer-side key watch and local cache invalidation
- Graceful unregister during JVM shutdown hook

### Resilience and Traffic Distribution

- Retry strategies: `no`, `fixedInterval`
- Tolerant strategies: `failFast`, `failBack`, `failOver`, `failSafe`
- Load balancers: `roundRobin`, `random`, `consistentHash`

### Extensibility

- SPI loader supports `META-INF/rpc/system/` and `META-INF/rpc/custom/`
- Factories for serializer / registry / load balancer / retry / tolerant strategies
- Spring Boot starter for annotation-driven producer/consumer wiring

## Getting Started

### 1) Prerequisites

- JDK 8+
- Maven 3.8+
- etcd (recommended for default config)

Optional (run etcd quickly with Docker):

```bash
docker run -d --name etcd \
  -p 2379:2379 -p 2380:2380 \
  quay.io/coreos/etcd:v3.5.12 \
  /usr/local/bin/etcd \
  --name s1 \
  --advertise-client-urls http://0.0.0.0:2379 \
  --listen-client-urls http://0.0.0.0:2379
```

### 2) Build Modules (no parent aggregator pom)

Run in this order from repository root:

```bash
mvn -f ac-rpc-core/pom.xml clean install -DskipTests
mvn -f ac-rpc-easy/pom.xml clean install -DskipTests
mvn -f example-common/pom.xml clean install -DskipTests
mvn -f ac-rpc-spring-boot-starter/pom.xml clean install -DskipTests
```

### 3) Configure Provider / Consumer

Example property file (`application.properties`) keys:

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

### 4) Run the Non-Spring Example

Start provider:

```bash
mvn -f example-provider/pom.xml exec:java -Dexec.mainClass="com.ac.example.provider.ProviderExample"
```

Start consumer (new terminal):

```bash
mvn -f example-consumer/pom.xml exec:java -Dexec.mainClass="com.ac.example.consumer.ConsumerExample"
```

### 5) Run the Spring Boot Example

Start provider:

```bash
mvn -f example-springboot-provider/pom.xml spring-boot:run
```

Start consumer:

```bash
mvn -f example-springboot-consumer/pom.xml spring-boot:run
```

## Spring Boot Annotations

- `@EnableRpc(needServer = true|false)`: framework bootstrap + optional TCP server startup
- `@RpcService`: publish provider implementation to local + remote registry
- `@RpcReference`: inject remote proxy into Spring beans

## Benchmarking Notes

Benchmark campaigns were executed with asynchronous Vert.x-based load generation and controlled concurrency to observe stable latency percentiles (`p50/p95`) and throughput behavior over sustained load.

Recommended benchmark dimensions:

- End-to-end latency (`p50/p95/p99`) by serializer and payload size
- Throughput/RPS vs. concurrent clients
- Discovery latency after node add/remove
- Failover recovery time after provider failure
- Registry watch propagation delay

## Roadmap

- NetClient connection pooling and reuse
- Stronger backpressure controls at client/server boundaries
- Structured metrics export (Prometheus/OpenTelemetry)
- Request tracing across proxy, network, and provider execution
- More deterministic failure-injection benchmark suite

## License

MIT