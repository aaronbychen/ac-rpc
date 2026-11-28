# faults-registry-fix

- 3 providers on one host, etcd lease TTL 30 s, 2000 rps open loop, 64 B payload, 60 s measured window, 3 rounds, scenarios alternating
- Values are median [min, max] across rounds. Recovery time as defined in the README: first second, counted from the fault's first action, that starts 10 consecutive seconds with >= 99% success.

- **etcd-outage**: `--tolerant failOver --outlier-ejection true`; kill-etcd at 10.5 s, start-etcd at 30.5 s
- **restart-failFast**: `--tolerant failFast --outlier-ejection false`; kill-provider 0 at 10.5 s, start-provider 0 at 20.5 s
- **restart-failOver+ejection**: `--tolerant failOver --outlier-ejection true`; kill-provider 0 at 10.5 s, start-provider 0 at 20.5 s

| scenario | failed after fault | % | unhealthy seconds | recovery s | peak per-second p99 ms | pre-fault p99 ms | rejoin s | etcd re-registration s |
|---|---|---|---|---|---|---|---|---|
| etcd-outage | 0 [0, 0] | 0.00 [0.00, 0.00] | 0 [0, 0] | 0 [0, 0] | 506.7 [506.2, 507.8] | 0.41 [0.41, 0.43] | - | 1.8 [1.6, 2.1] |
| restart-failFast | no successful runs |||||||| 
| restart-failOver+ejection | no successful runs |||||||| 

## Timelines

Per second relative to the fault's first action: failed requests / p99 ms (and, for restarts, the restarted provider's share of traffic).

**etcd-outage round 0** (events: kill-etcd at +0.50 s, start-etcd at +20.50 s)

`-2: 0/0.4  -1: 0/0.4  +0: 0/507.8  +1: 0/71.2  +2: 0/0.4  +3: 0/0.5  +4: 0/0.4  +5: 0/0.4  +6: 0/0.4  +7: 0/0.4  +8: 0/0.4  +9: 0/0.4  +10: 0/0.4  +11: 0/0.5  +12: 0/0.4  +13: 0/0.4  +14: 0/0.4  +15: 0/0.4  +16: 0/0.4  +17: 0/0.4  +18: 0/0.4  +19: 0/0.4  +20: 0/0.4  +21: 0/0.4  +22: 0/0.4  +23: 0/0.4  +24: 0/0.4  +25: 0/0.4  +26: 0/0.4  +27: 0/0.4  +28: 0/0.4  +29: 0/0.4  +30: 0/0.4  +31: 0/0.4  +32: 0/0.4  +33: 0/0.4  +34: 0/0.4  +35: 0/0.4  +36: 0/0.4  +37: 0/0.4  +38: 0/0.4  +39: 0/0.4  +40: 0/0.4  +41: 0/0.4  +42: 0/0.5  +43: 0/0.4  +44: 0/0.4  +45: 0/0.4  +46: 0/0.4  +47: 0/0.4  +48: 0/0.4  +49: 0/0.4`

**etcd-outage round 1** (events: kill-etcd at +0.50 s, start-etcd at +20.50 s)

`-2: 0/0.4  -1: 0/0.4  +0: 0/506.7  +1: 0/74.8  +2: 0/0.4  +3: 0/0.5  +4: 0/0.4  +5: 0/0.4  +6: 0/0.3  +7: 0/0.4  +8: 0/0.4  +9: 0/0.4  +10: 0/0.4  +11: 0/0.5  +12: 0/0.4  +13: 0/0.3  +14: 0/0.4  +15: 0/0.4  +16: 0/0.4  +17: 0/0.4  +18: 0/0.4  +19: 0/0.3  +20: 0/0.6  +21: 0/0.4  +22: 0/0.4  +23: 0/0.4  +24: 0/0.4  +25: 0/0.4  +26: 0/0.4  +27: 0/0.5  +28: 0/0.4  +29: 0/0.4  +30: 0/0.4  +31: 0/0.3  +32: 0/0.4  +33: 0/0.4  +34: 0/0.4  +35: 0/0.4  +36: 0/0.4  +37: 0/0.3  +38: 0/0.4  +39: 0/0.5  +40: 0/0.4  +41: 0/0.4  +42: 0/0.4  +43: 0/0.4  +44: 0/0.4  +45: 0/0.4  +46: 0/0.4  +47: 0/0.4  +48: 0/0.4  +49: 0/0.4`

**etcd-outage round 2** (events: kill-etcd at +0.50 s, start-etcd at +20.50 s)

`-2: 0/0.4  -1: 0/0.4  +0: 0/506.2  +1: 0/72.2  +2: 0/0.4  +3: 0/0.4  +4: 0/0.4  +5: 0/0.4  +6: 0/0.4  +7: 0/0.4  +8: 0/0.4  +9: 0/0.4  +10: 0/0.4  +11: 0/0.4  +12: 0/0.4  +13: 0/0.4  +14: 0/0.4  +15: 0/0.3  +16: 0/0.4  +17: 0/0.4  +18: 0/0.3  +19: 0/0.4  +20: 0/0.4  +21: 0/0.4  +22: 0/0.4  +23: 0/0.4  +24: 0/0.4  +25: 0/0.4  +26: 0/0.4  +27: 0/0.4  +28: 0/0.4  +29: 0/0.6  +30: 0/0.4  +31: 0/0.4  +32: 0/0.4  +33: 0/0.4  +34: 0/0.4  +35: 0/0.4  +36: 0/0.4  +37: 0/0.4  +38: 0/0.4  +39: 0/0.4  +40: 0/0.4  +41: 0/0.4  +42: 0/0.4  +43: 0/0.4  +44: 0/0.4  +45: 0/0.4  +46: 0/0.4  +47: 0/0.4  +48: 0/0.4  +49: 0/0.4`
