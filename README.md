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

## Known Limitations

These are tracked and being addressed:

- The client creates a new Vert.x instance and TCP connection per call; there is no connection reuse or request multiplexing
- Calls have no timeout; a failed connect can block the caller indefinitely
- `failOver` / `failBack` tolerant strategies are stubs; retries target the same node
- The registry's discovery cache is not keyed by service, so multiple services can collide
- Heartbeats re-grant a new etcd lease instead of using keep-alive

## Roadmap

- Transport: shared event loop, per-endpoint connection pooling, request-ID multiplexing, deadlines
- Serialization: Protobuf
- Benchmarks: open-loop load generator (HdrHistogram), paired comparison against gRPC-java
- Resilience: node-exclusion failover, circuit breaking, fault-injection suite on Kubernetes
- Observability: OpenTelemetry tracing, Prometheus metrics
