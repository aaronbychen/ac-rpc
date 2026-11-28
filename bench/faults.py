"""Inject timed faults (provider kill/restart, etcd crash/restart) under steady load.

Usage: python bench/faults.py bench/configs/faults.json

Each run starts N providers registered in etcd and drives open-loop load through the registry.
Once the load generator reports the start of its measured window, this script performs the
scenario's events at fixed offsets into that window and records when each one happened.
Rounds alternate scenario order so slow drift affects them equally.

Recovery time follows the definition in the README ("Recovery time (definition)"):
per-second success rate by intended send time, healthy at >= 99%, recovered at the first
healthy second that starts 10 consecutive healthy seconds, counted from the fault's first action.
"""
import json
import math
import statistics
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run as harness  # noqa: E402
from failover import SERVICE_PREFIX, clear_registry, fmt  # noqa: E402

ROOT = harness.ROOT
HEALTHY = 0.99
STABLE_SECONDS = 10
REJOIN_SHARE = 0.10


def now_ms():
    return time.time() * 1000


def etcdctl(cfg, *args, timeout=10):
    return subprocess.run(["docker", "exec", cfg["etcdContainer"], "etcdctl", *args],
                          capture_output=True, text=True, timeout=timeout)


def etcd_healthy(cfg):
    try:
        return etcdctl(cfg, "endpoint", "health", timeout=5).returncode == 0
    except subprocess.TimeoutExpired:
        return False


def ensure_etcd(cfg, timeout=60):
    """Starts the etcd container if needed and waits until it answers."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if etcd_healthy(cfg):
            return True
        subprocess.run(["docker", "start", cfg["etcdContainer"]], capture_output=True)
        time.sleep(1)
    return False


def registered_count(cfg):
    try:
        res = etcdctl(cfg, "get", "--prefix", "--keys-only", SERVICE_PREFIX, timeout=5)
    except subprocess.TimeoutExpired:
        return None
    if res.returncode != 0:
        return None
    return len([line for line in res.stdout.splitlines() if line.strip()])


class Run:
    def __init__(self, cfg, scenario, jar, rnd, out_file, log_dir):
        self.cfg, self.scenario, self.jar, self.rnd = cfg, scenario, jar, rnd
        self.out_file, self.log_dir = out_file, log_dir
        self.java = harness.java_exe(cfg["java"])
        self.jvm = cfg.get("jvmArgs", [])
        self.common = ["--registry", cfg["registry"], "--serializer", cfg["serializer"],
                       "--lease-ttl", str(cfg["leaseTtlSec"])]
        self.providers = {}      # index -> Popen of the current process
        self.provider_logs = {}  # index -> list of log paths, one per start
        self.all_procs = []
        self.events = []
        self.reregistered_ms = None
        self.threads = []

    def log_path(self, name):
        return self.log_dir / f"{self.scenario['label']}_r{self.rnd}_{name}.log"

    def start_provider(self, i):
        starts = self.provider_logs.setdefault(i, [])
        path = self.log_path(f"provider{i}" + (f"_start{len(starts)}" if starts else ""))
        starts.append(path)
        log = open(path, "w")
        p = subprocess.Popen([self.java, *self.jvm, "-jar", str(self.jar), "provider", *self.common,
                              "--port", str(self.cfg["basePort"] + i)], stdout=log, stderr=subprocess.STDOUT)
        self.providers[i] = p
        self.all_procs.append(p)
        return p

    def watch_reregistration(self, started_ms):
        """Polls etcd after it restarts until every provider's key is back."""
        deadline = time.time() + self.cfg["durationSec"]
        while time.time() < deadline:
            n = registered_count(self.cfg)
            if n is not None and n >= self.cfg["providers"]:
                self.reregistered_ms = now_ms()
                return
            time.sleep(0.2)

    def do(self, event):
        action = event["action"]
        t0 = now_ms()
        if action == "kill-provider":
            self.providers[event["index"]].kill()  # TerminateProcess / SIGKILL: no unregister
        elif action == "start-provider":
            self.start_provider(event["index"])
        elif action == "kill-etcd":
            subprocess.run(["docker", "kill", self.cfg["etcdContainer"]], capture_output=True, check=True)
        elif action == "start-etcd":
            subprocess.run(["docker", "start", self.cfg["etcdContainer"]], capture_output=True, check=True)
            t = threading.Thread(target=self.watch_reregistration, args=(t0,), daemon=True)
            t.start()
            self.threads.append(t)
        else:
            raise ValueError(f"unknown action {action}")
        self.events.append({**event, "startedEpochMs": t0, "doneEpochMs": now_ms()})

    def wait_window_start(self, log_path, loadgen, timeout=60):
        deadline = time.time() + timeout
        while time.time() < deadline and loadgen.poll() is None:
            try:
                for line in log_path.read_text(errors="replace").splitlines():
                    if line.startswith("window-start-epoch-ms "):
                        return int(line.split()[1])
            except OSError:
                pass
            time.sleep(0.05)
        return None

    def execute(self):
        cfg = self.cfg
        if not ensure_etcd(cfg):
            return {"status": "etcd-unavailable"}
        clear_registry(cfg)
        try:
            for i in range(cfg["providers"]):
                self.start_provider(i)
            for i in range(cfg["providers"]):
                if not harness.wait_for_port(cfg["basePort"] + i):
                    return {"status": "provider-failed"}
            time.sleep(cfg.get("settleSec", 2))

            loadgen_log = self.log_path("loadgen")
            cmd = [self.java, *self.jvm, "-jar", str(self.jar), "loadgen", *self.common, *self.scenario["args"],
                   "--rate", str(cfg["rate"]), "--payload", str(cfg["payload"]),
                   "--warmup", str(cfg["warmupSec"]), "--duration", str(cfg["durationSec"]),
                   "--timeout", str(cfg["timeoutSec"]), "--out", str(self.out_file), "--timeline", "true",
                   "--label.scenario", self.scenario["label"], "--label.round", str(self.rnd)]
            with open(loadgen_log, "w") as log:
                loadgen = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
            window_ms = self.wait_window_start(loadgen_log, loadgen)
            if window_ms is None:
                loadgen.kill()
                return {"status": "loadgen-no-window"}
            for event in sorted(self.scenario["events"], key=lambda e: e["at"]):
                wait = (window_ms + event["at"] * 1000 - now_ms()) / 1000
                if wait > 0:
                    time.sleep(wait)
                self.do(event)

            budget = cfg["warmupSec"] + cfg["durationSec"] + 2 * cfg["timeoutSec"] + 60
            try:
                loadgen.wait(timeout=budget)
            except subprocess.TimeoutExpired:
                loadgen.kill()
                return {"status": "loadgen-timeout", "events": self.events}
            for t in self.threads:
                t.join(timeout=5)
            final_registered = registered_count(cfg) if etcd_healthy(cfg) else None
            if loadgen.returncode != 0 or not self.out_file.exists():
                return {"status": "loadgen-failed", "events": self.events}
            return {"status": "ok", "windowStartEpochMs": window_ms, "events": self.events,
                    "reregisteredEpochMs": self.reregistered_ms, "finalRegistered": final_registered,
                    "providerLogs": {str(i): [p.name for p in paths] for i, paths in self.provider_logs.items()}}
        finally:
            for p in self.all_procs:
                if p.poll() is None:
                    p.kill()
                p.wait()
            ensure_etcd(cfg)


def served_per_second(log_path, window_ms, seconds):
    """Calls a provider served in each second of the measured window, from its 'served' log lines."""
    samples = []
    for line in Path(log_path).read_text(errors="replace").splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[0] == "served":
            samples.append((int(parts[1]), int(parts[2])))
    per_sec = [0] * seconds
    for (t0, c0), (t1, c1) in zip(samples, samples[1:]):
        sec = math.floor(((t0 + t1) / 2 - window_ms) / 1000)
        if 0 <= sec < seconds:
            per_sec[sec] += c1 - c0
    return per_sec


def successes(row):
    return row["ok"] - row.get("slow", 0)


def analyze_run(report, status, log_dir):
    timeline = report["timeline"]
    window_ms = status["windowStartEpochMs"]
    events = [{**e, "sec": (e["startedEpochMs"] - window_ms) / 1000} for e in status["events"]]
    fault_sec = min(e["sec"] for e in events)
    k = math.floor(fault_sec)

    healthy = [r["issued"] > 0 and successes(r) / r["issued"] >= HEALTHY for r in timeline]
    recovered = None
    for b in range(k, len(timeline) - STABLE_SECONDS + 1):
        if all(healthy[b:b + STABLE_SECONDS]):
            recovered = b - k
            break

    before = [r for r in timeline[:k] if "p99" in r]
    after = timeline[k:]
    failed_after = sum(r["issued"] - successes(r) for r in after)
    issued_after = sum(r["issued"] for r in after)
    failed_before = sum(r["issued"] - successes(r) for r in timeline[:k])
    unhealthy_secs = [i - k for i in range(k, len(timeline)) if not healthy[i]]
    summary = {
        "faultSec": round(fault_sec, 3),
        "events": [{"action": e["action"], "sec": round(e["sec"], 3),
                    "commandMs": round(e["doneEpochMs"] - e["startedEpochMs"])} for e in events],
        "failedBeforeFault": failed_before,
        "failedAfterFault": failed_after,
        "failedPctAfterFault": round(100 * failed_after / issued_after, 3) if issued_after else None,
        "unhealthySecondsAfterFault": unhealthy_secs,
        "recoverySec": recovered,
        "baselineP99Ms": statistics.median(r["p99"] for r in before) if before else None,
        "peakP99Ms": max((r.get("p99", 0) for r in after), default=None),
        "peakP99Sec": max(((r.get("p99", 0), r["sec"] - k) for r in after), default=(None, None))[1],
        "postFaultMedianP99Ms": statistics.median(r["p99"] for r in after if "p99" in r) if after else None,
        "firstError": report.get("firstError"),
    }

    restart = next((e for e in events if e["action"] == "start-provider"), None)
    if restart:
        logs = status["providerLogs"][str(restart["index"])]
        served = served_per_second(log_dir / logs[-1], window_ms, len(timeline))
        r0 = math.floor(restart["sec"])
        rejoin = next((s - r0 for s in range(r0, len(timeline))
                       if timeline[s]["issued"] and served[s] / timeline[s]["issued"] >= REJOIN_SHARE), None)
        summary["rejoinSec"] = rejoin
        summary["restartedShareBySec"] = {s - r0: round(served[s] / timeline[s]["issued"], 3)
                                          for s in range(r0, len(timeline)) if timeline[s]["issued"]}
    etcd_start = next((e for e in events if e["action"] == "start-etcd"), None)
    if etcd_start:
        rereg = status.get("reregisteredEpochMs")
        summary["reregisteredSecAfterEtcdStart"] = (round((rereg - etcd_start["startedEpochMs"]) / 1000, 2)
                                                    if rereg else None)
        summary["finalRegistered"] = status.get("finalRegistered")
        loadgen_log = (log_dir / f"{report['labels']['scenario']}_r{report['labels']['round']}_loadgen.log")
        text = loadgen_log.read_text(errors="replace") if loadgen_log.exists() else ""
        summary["staleFallbackLogLines"] = text.count("using last known providers")
        summary["watchFailedLogLines"] = text.count("Watch on")
    return summary


def write_summary(out_dir, cfg, results):
    lines = [f"# {cfg['name']}", "",
             f"- {cfg['providers']} providers on one host, etcd lease TTL {cfg['leaseTtlSec']} s, "
             f"{cfg['rate']} rps open loop, {cfg['payload']} B payload, {cfg['durationSec']} s measured window, "
             f"{cfg['rounds']} rounds, scenarios alternating",
             "- Values are median [min, max] across rounds. Recovery time as defined in the README: "
             "first second, counted from the fault's first action, that starts 10 consecutive seconds with >= 99% success.",
             ""]
    for scenario in cfg["scenarios"]:
        ev = ", ".join(f"{e['action']}" + (f" {e['index']}" if "index" in e else "") + f" at {e['at']} s"
                       for e in scenario["events"])
        lines.append(f"- **{scenario['label']}**: `{' '.join(scenario['args'])}`; {ev}")
    lines += ["", "| scenario | failed after fault | % | unhealthy seconds | recovery s | peak per-second p99 ms "
                  "| pre-fault p99 ms | rejoin s | etcd re-registration s |",
              "|---|---|---|---|---|---|---|---|---|"]
    for scenario in cfg["scenarios"]:
        rows = [r["summary"] for r in results if r["scenario"] == scenario["label"] and r.get("summary")]
        if not rows:
            lines.append(f"| {scenario['label']} | no successful runs |||||||| ")
            continue
        not_recovered = sum(1 for r in rows if r["recoverySec"] is None)
        rec = fmt([r["recoverySec"] for r in rows], 0) + (f" ({not_recovered} not recovered)" if not_recovered else "")
        lines.append(f"| {scenario['label']} | {fmt([r['failedAfterFault'] for r in rows], 0)} "
                     f"| {fmt([r['failedPctAfterFault'] for r in rows], 2)} "
                     f"| {fmt([len(r['unhealthySecondsAfterFault']) for r in rows], 0)} "
                     f"| {rec} "
                     f"| {fmt([r['peakP99Ms'] for r in rows], 1)} "
                     f"| {fmt([r['baselineP99Ms'] for r in rows], 2)} "
                     f"| {fmt([r.get('rejoinSec') for r in rows], 0)} "
                     f"| {fmt([r.get('reregisteredSecAfterEtcdStart') for r in rows], 1)} |")
    lines += ["", "## Timelines", "",
              "Per second relative to the fault's first action: failed requests / p99 ms "
              "(and, for restarts, the restarted provider's share of traffic)."]
    for r in results:
        if not r.get("summary"):
            continue
        report = json.loads((out_dir / r["file"]).read_text())
        s = r["summary"]
        k = math.floor(s["faultSec"])
        cells = []
        for row in report["timeline"]:
            rel = row["sec"] - k
            if rel < -2:
                continue
            p99 = f"{row['p99']:.1f}" if "p99" in row else "-"
            cells.append(f"{rel:+d}: {row['issued'] - successes(row)}/{p99}")
        lines += ["", f"**{r['scenario']} round {r['round']}** (events: "
                  + ", ".join(f"{e['action']} at {e['sec'] - k:+.2f} s" for e in s["events"]) + ")",
                  "", "`" + "  ".join(cells) + "`"]
    (out_dir / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    cfg_path = Path(sys.argv[1]).resolve()
    cfg = json.loads(cfg_path.read_text(encoding="utf-8-sig"))
    if harness.worktree_dirty():
        print("warning: uncommitted changes are included in this build")
    jar, sha, dirty = harness.build_target({"label": cfg["name"], "ref": "WORKTREE", "java": cfg["java"]})
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = ROOT / "bench" / "results" / f"{cfg['name']}-{stamp}"
    (out_dir / "runs").mkdir(parents=True)
    log_dir = out_dir / "logs"
    log_dir.mkdir()

    only = set(sys.argv[2:])
    scenarios = [s for s in cfg["scenarios"] if not only or s["label"] in only]
    results = []
    for rnd in range(cfg["rounds"]):
        order = scenarios if rnd % 2 == 0 else list(reversed(scenarios))
        for scenario in order:
            rel = Path("runs") / f"{scenario['label']}_r{rnd}.json"
            free_before = harness.available_memory_mb()
            print(f"round {rnd} {scenario['label']} (free {free_before} MB)", flush=True)
            status = Run(cfg, scenario, jar, rnd, out_dir / rel, log_dir).execute()
            entry = {"scenario": scenario["label"], "round": rnd, "file": str(rel).replace("\\", "/"),
                     "freeMemoryMbBefore": free_before, "lowMemory": free_before is not None and free_before < 2048,
                     **status}
            if status["status"] == "ok":
                entry["summary"] = analyze_run(json.loads((out_dir / rel).read_text()), status, log_dir)
                print("  " + json.dumps({k: v for k, v in entry["summary"].items() if k != "restartedShareBySec"}),
                      flush=True)
            else:
                print(f"  {status['status']}", flush=True)
            results.append(entry)
            (out_dir / "meta.json").write_text(json.dumps({"config": cfg, "results": results}, indent=2))

    meta = {"config": cfg, "sha": sha, "dirty": dirty, "host": harness.host_info(),
            "javaVersion": harness.java_version(cfg["java"]), "results": results,
            "finishedAt": datetime.now(timezone.utc).isoformat()}
    (out_dir / "meta.json").write_text(json.dumps(meta, indent=2))
    write_summary(out_dir, cfg, [r for r in results if r["scenario"] in {s["label"] for s in scenarios}])
    print(f"results: {out_dir.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
