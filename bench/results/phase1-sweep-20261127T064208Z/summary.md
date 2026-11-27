# phase1-sweep

- Host: 13th Gen Intel(R) Core(TM) i9-13980HX, 32 threads, 16 GB, Windows-11-10.0.26200-SP0
- Target `pooled`: 6f1260eb4040 (dirty), openjdk version "25.0.5.1" 2026-11-17 LTS
- 3 rounds, warmup 10s, measured 20s, timeout 5s, serializer kryo, JVM -Xms1g -Xmx1g --sun-misc-unsafe-memory-access=allow
- Values are median [min, max] across rounds. Latency is measured from each request's intended send time (open loop).
- A run is unreliable if it failed, was aborted (thread cap or overrun), left >0.1% of requests incomplete or dropped, or started with low free memory.

| target | payload B | offered rps | achieved rps | p50 ms | p99 ms | p99.9 ms | error rate | peak threads | unreliable runs |
|---|---|---|---|---|---|---|---|---|---|
| pooled | 64 | 1000 | 1000.0 [1000.0, 1000.0] | 0.25 [0.18, 0.26] | 0.48 [0.42, 0.49] | 0.65 [0.58, 0.67] | 0.0% [0.0%, 0.0%] | 26 [25, 26] | 0/3 |
| pooled | 64 | 10000 | 10000.0 [10000.0, 10000.0] | 0.13 [0.09, 0.14] | 0.29 [0.29, 0.29] | 0.67 [0.54, 0.68] | 0.0% [0.0%, 0.0%] | 53 [53, 53] | 0/3 |
| pooled | 64 | 50000 | 50000.0 [50000.0, 50000.0] | 0.17 [0.15, 0.18] | 0.55 [0.50, 0.57] | 3.12 [2.98, 3.31] | 0.0% [0.0%, 0.0%] | 53 [53, 53] | 0/3 |
| pooled | 64 | 100000 | 100000.0 [100000.0, 100000.0] | 0.32 [0.31, 0.34] | 1.18 [1.15, 1.21] | 4.29 [4.01, 4.42] | 0.0% [0.0%, 0.0%] | 53 [53, 53] | 0/3 |
| pooled | 64 | 150000 | 150000.0 [150000.0, 150000.0] | 0.51 [0.33, 0.56] | 1.50 [1.45, 1.61] | 5.09 [4.61, 5.13] | 0.0% [0.0%, 0.0%] | 53 [53, 53] | 0/3 |
| pooled | 64 | 200000 | 200000.0 [200000.0, 200000.0] | 0.62 [0.60, 0.64] | 2.21 [2.08, 2.24] | 4.26 [4.13, 4.66] | 0.0% [0.0%, 0.0%] | 53 [53, 53] | 0/3 |
| pooled | 64 | 250000 | 250000.0 [250000.0, 250000.0] | 1.42 [1.07, 1.49] | 4.68 [3.75, 4.86] | 6.31 [6.01, 7.16] | 0.0% [0.0%, 0.0%] | 53 [53, 53] | 0/3 |
| pooled | 64 | 300000 | 254950.2 [254329.8, 260918.6] | 36.44 [35.36, 36.67] | 72.02 [64.29, 73.01] | 78.71 [78.25, 79.50] | 15.0% [13.0%, 15.2%] | 53 [53, 53] | 3/3 |
