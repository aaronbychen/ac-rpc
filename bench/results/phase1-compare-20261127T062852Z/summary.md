# phase1-compare

- Host: 13th Gen Intel(R) Core(TM) i9-13980HX, 32 threads, 16 GB, Windows-11-10.0.26200-SP0
- Target `baseline`: 952068527fc6, openjdk version "25.0.5.1" 2026-11-17 LTS
- Target `pooled`: 6f1260eb4040, openjdk version "25.0.5.1" 2026-11-17 LTS
- 3 rounds, warmup 10s, measured 30s, timeout 5s, serializer kryo, JVM -Xms1g -Xmx1g --sun-misc-unsafe-memory-access=allow
- Values are median [min, max] across rounds. Latency is measured from each request's intended send time (open loop).
- A run is unreliable if it failed, was aborted (thread cap or overrun), left >0.1% of requests incomplete or dropped, or started with low free memory.

Aborted runs:
- baseline rate=20 round=0: thread count 2011 exceeded --max-threads 2000 after 33.056s
- baseline rate=20 round=1: thread count 2009 exceeded --max-threads 2000 after 32.997s
- baseline rate=20 round=2: thread count 2008 exceeded --max-threads 2000 after 32.99s

| target | payload B | offered rps | achieved rps | p50 ms | p99 ms | p99.9 ms | error rate | peak threads | unreliable runs |
|---|---|---|---|---|---|---|---|---|---|
| baseline | 64 | 5 | 5.0 [5.0, 5.0] | 72.48 [70.91, 72.61] | 96.73 [92.86, 99.61] | 101.65 [96.27, 104.99] | 0.0% [0.0%, 0.0%] | 625 [625, 626] | 0/3 |
| baseline | 64 | 10 | 10.0 [10.0, 10.0] | 79.23 [76.94, 83.17] | 108.20 [105.51, 113.11] | 119.28 [111.48, 120.65] | 0.0% [0.0%, 0.0%] | 1223 [1223, 1223] | 0/3 |
| baseline | 64 | 20 | 20.0 [19.9, 20.0] | 91.29 [43.32, 98.96] | 190.58 [114.23, 197.00] | 225.18 [134.22, 290.98] | 0.4% [0.4%, 0.7%] | 2009 [2008, 2011] | 3/3 |
| pooled | 64 | 5 | 5.0 [5.0, 5.0] | 1.68 [1.66, 1.72] | 3.11 [2.49, 3.45] | 3.50 [2.54, 4.29] | 0.0% [0.0%, 0.0%] | 22 [22, 22] | 0/3 |
| pooled | 64 | 10 | 10.0 [10.0, 10.0] | 1.47 [1.38, 1.51] | 2.70 [2.47, 2.75] | 2.94 [2.92, 3.26] | 0.0% [0.0%, 0.0%] | 22 [22, 22] | 0/3 |
| pooled | 64 | 20 | 20.0 [20.0, 20.0] | 1.08 [1.07, 1.18] | 1.99 [1.93, 2.62] | 2.28 [2.22, 3.02] | 0.0% [0.0%, 0.0%] | 22 [22, 22] | 0/3 |

## Paired comparison vs `baseline`

Per-round ratio (target / reference), then median [min, max]. Below 1.0 means lower latency / less throughput than the reference.

| target | payload B | offered rps | p99 ratio | throughput ratio | rounds |
|---|---|---|---|---|---|
| pooled | 64 | 5 | 0.032 [0.025, 0.037] | 1.000 [1.000, 1.000] | 3 |
| pooled | 64 | 10 | 0.024 [0.023, 0.025] | 1.000 [1.000, 1.000] | 3 |
| pooled | 64 | 20 | 0.014 [0.010, 0.017] | 1.002 [1.000, 1.004] | 3 |
