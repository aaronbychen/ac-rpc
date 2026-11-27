# baseline-local

- Host: 13th Gen Intel(R) Core(TM) i9-13980HX, 32 threads, 16 GB, Windows-11-10.0.26200-SP0
- Target `baseline`: 952068527fc6, openjdk version "25.0.5.1" 2026-11-17 LTS
- 3 rounds, warmup 10s, measured 30s, timeout 5s, serializer kryo, JVM -Xms1g -Xmx1g --sun-misc-unsafe-memory-access=allow
- Values are median [min, max] across rounds. Latency is measured from each request's intended send time (open loop).
- A run is unreliable if it failed, was aborted (thread cap or overrun), left >0.1% of requests incomplete or dropped, or started with low free memory.

Aborted runs:
- baseline rate=20 round=0: thread count 2009 exceeded --max-threads 2000 after 33.024s
- baseline rate=40 round=0: thread count 2018 exceeded --max-threads 2000 after 16.576s
- baseline rate=80 round=0: thread count 2009 exceeded --max-threads 2000 after 15.94s
- baseline rate=20 round=1: thread count 2003 exceeded --max-threads 2000 after 32.569s
- baseline rate=80 round=1: thread count 2093 exceeded --max-threads 2000 after 21.366s
- baseline rate=40 round=1: thread count 2009 exceeded --max-threads 2000 after 16.358s
- baseline rate=20 round=2: thread count 2011 exceeded --max-threads 2000 after 32.995s
- baseline rate=40 round=2: thread count 2001 exceeded --max-threads 2000 after 16.39s
- baseline rate=80 round=2: thread count 2002 exceeded --max-threads 2000 after 14.136s

| target | payload B | offered rps | achieved rps | p50 ms | p99 ms | p99.9 ms | error rate | peak threads | unreliable runs |
|---|---|---|---|---|---|---|---|---|---|
| baseline | 64 | 5 | 5.0 [5.0, 5.0] | 44.07 [42.99, 57.41] | 76.74 [68.75, 233.44] | 102.50 [96.73, 267.91] | 0.0% [0.0%, 0.0%] | 625 [625, 626] | 0/3 |
| baseline | 64 | 10 | 10.0 [10.0, 10.0] | 43.68 [41.16, 72.55] | 70.45 [59.28, 101.06] | 117.38 [73.27, 167.77] | 0.0% [0.0%, 0.0%] | 1223 [1222, 1223] | 0/3 |
| baseline | 64 | 20 | 20.0 [20.0, 20.0] | 43.09 [40.40, 98.37] | 112.13 [84.48, 983.56] | 121.05 [120.98, 1138.75] | 0.4% [0.2%, 0.4%] | 2009 [2003, 2011] | 3/3 |
| baseline | 64 | 40 | 39.8 [39.7, 39.8] | 65.14 [52.95, 66.00] | 286.26 [167.51, 332.92] | 297.01 [172.75, 338.69] | 1.2% [1.1%, 1.6%] | 2009 [2001, 2018] | 3/3 |
| baseline | 64 | 80 | 0.0 [0.0, 0.0] | n/a | n/a | n/a | 100.0% [100.0%, 100.0%] | 2009 [2002, 2093] | 3/3 |
