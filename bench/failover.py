"""Kill one provider under steady load and record what callers see, second by second.

Usage: python bench/failover.py bench/configs/failover.json

Each run starts N providers registered in etcd, drives open-loop load through the registry, and
has the load generator kill one provider (TerminateProcess / SIGKILL, so it cannot unregister)
partway through the measured window. Scenarios differ only in client options, e.g. the
fault-tolerance strategy. Rounds alternate scenarios so slow drift affects them equally.
"""
import json
import statistics
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run as harness  # noqa: E402

ROOT = harness.ROOT
SERVICE_PREFIX = "/rpc/com.ac.acrpc.bench.EchoService"


def clear_registry(cfg):
    # Keys of providers killed in an earlier run would otherwise linger until their lease expires
    subprocess.run(["docker", "exec", cfg["etcdContainer"], "etcdctl", "del", "--prefix", SERVICE_PREFIX],
                   check=True, capture_output=True)


def run_one(cfg, scenario, jar, rnd, out_file, log_dir):
    java = harness.java_exe(cfg["java"])
    jvm = cfg.get("jvmArgs", [])
    common = ["--registry", cfg["registry"], "--serializer", cfg["serializer"],
              "--lease-ttl", str(cfg["leaseTtlSec"])]
    clear_registry(cfg)
    providers = []
    try:
        for i in range(cfg["providers"]):
            port = cfg["basePort"] + i
            log = open(log_dir / f"{scenario['label']}_r{rnd}_provider{i}.log", "w")
            providers.append(subprocess.Popen(
                [java, *jvm, "-jar", str(jar), "provider", *common, "--port", str(port)],
                stdout=log, stderr=subprocess.STDOUT))
        for i in range(cfg["providers"]):
            if not harness.wait_for_port(cfg["basePort"] + i):
                return {"status": "provider-failed"}
        time.sleep(cfg.get("settleSec", 2))

        victim = providers[0]
        loadgen = [java, *jvm, *scenario.get("jvmArgs", []), "-jar", str(jar), "loadgen", *common, *scenario["args"],
                   "--rate", str(cfg["rate"]), "--payload", str(cfg["payload"]),
                   "--warmup", str(cfg["warmupSec"]), "--duration", str(cfg["durationSec"]),
                   "--timeout", str(cfg["timeoutSec"]), "--out", str(out_file),
                   "--timeline", "true", "--kill-pid", str(victim.pid), "--kill-at", str(cfg["killAtSec"]),
                   "--label.scenario", scenario["label"], "--label.round", str(rnd)]
        budget = cfg["warmupSec"] + cfg["durationSec"] + 2 * cfg["timeoutSec"] + 60
        try:
            with open(log_dir / f"{scenario['label']}_r{rnd}_loadgen.log", "w") as log:
                res = subprocess.run(loadgen, stdout=log, stderr=subprocess.STDOUT, timeout=budget)
        except subprocess.TimeoutExpired:
            return {"status": "loadgen-timeout"}
        if res.returncode != 0 or not out_file.exists():
            return {"status": "loadgen-failed"}
        return {"status": "ok"}
    finally:
        for p in providers:
            p.kill()
            p.wait()


def analyze_run(report):
    """Summary numbers for one run, relative to the moment the provider was killed."""
    timeline = report["timeline"]
    kill = report["fault"]["killedAtSec"]
    k = int(kill)
    before = [r for r in timeline[:k] if "p99" in r]
    baseline_p99 = statistics.median(r["p99"] for r in before) if before else None
    after = timeline[k:]
    failed = sum(r["errors"] for r in after)
    issued = sum(r["issued"] for r in after)
    error_secs = [r["sec"] for r in after if r["errors"] > 0]
    # Recovered: from this second on, no errors and per-second p99 within 2x the pre-kill median
    recovered_at = None
    for i in range(len(after)):
        tail = after[i:]
        if all(r["errors"] == 0 and "p99" in r and baseline_p99 is not None and r["p99"] <= 2 * baseline_p99
               for r in tail):
            recovered_at = after[i]["sec"]
            break
    return {
        "killedAtSec": kill,
        "baselineP99Ms": baseline_p99,
        "failedAfterKill": failed,
        "failedPctAfterKill": round(100 * failed / issued, 3) if issued else None,
        "lastErrorSecAfterKill": (error_secs[-1] - k + 1) if error_secs else 0,
        "peakP99Ms": max((r.get("p99", 0) for r in after), default=None),
        "recoveredSecAfterKill": (recovered_at - k) if recovered_at is not None else None,
        "firstError": report.get("firstError"),
    }


def fmt(values, digits=1):
    values = [v for v in values if v is not None]
    if not values:
        return "-"
    med = statistics.median(values)
    return f"{med:.{digits}f} [{min(values):.{digits}f}, {max(values):.{digits}f}]"


def write_summary(out_dir, cfg, results):
    lines = [f"# {cfg['name']}", "",
             f"- {cfg['providers']} providers on one host, etcd lease TTL {cfg['leaseTtlSec']} s, "
             f"{cfg['rate']} rps open loop, {cfg['payload']} B payload, deadline and connect timeout at defaults (3 s)",
             f"- Provider 0 is killed (no chance to unregister) {cfg['killAtSec']} s into a {cfg['durationSec']} s window; "
             f"{cfg['rounds']} rounds, scenarios alternating",
             "- Values are median [min, max] across rounds. \"Recovered\" means no errors and per-second p99 within 2x the "
             "pre-kill median from that second to the end of the run.", "",
             "| scenario | failed after kill | % of requests | last error (s after kill) | peak per-second p99 ms | recovered (s after kill) |",
             "|---|---|---|---|---|---|"]
    for scenario in cfg["scenarios"]:
        rows = [r["summary"] for r in results if r["scenario"] == scenario["label"] and r.get("summary")]
        lines.append(f"| {scenario['label']} | {fmt([r['failedAfterKill'] for r in rows], 0)} "
                     f"| {fmt([r['failedPctAfterKill'] for r in rows], 2)} "
                     f"| {fmt([r['lastErrorSecAfterKill'] for r in rows], 0)} "
                     f"| {fmt([r['peakP99Ms'] for r in rows], 0)} "
                     f"| {fmt([r['recoveredSecAfterKill'] for r in rows], 0)} |")
    lines += ["", "## Timeline, round 0", "",
              "Per second relative to the kill: failed requests / p99 ms."]
    for scenario in cfg["scenarios"]:
        run = next((r for r in results if r["scenario"] == scenario["label"] and r["round"] == 0 and r.get("summary")), None)
        if not run:
            continue
        report = json.loads((out_dir / run["file"]).read_text())
        k = int(report["fault"]["killedAtSec"])
        cells = []
        for row in report["timeline"]:
            rel = row["sec"] - k
            if rel < -2:
                continue
            p99 = f"{row['p99']:.1f}" if "p99" in row else "-"
            cells.append(f"{rel:+d}: {row['errors']}/{p99}")
        lines += ["", f"**{scenario['label']}**", "", "`" + "  ".join(cells) + "`"]
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

    results = []
    for rnd in range(cfg["rounds"]):
        order = cfg["scenarios"] if rnd % 2 == 0 else list(reversed(cfg["scenarios"]))
        for scenario in order:
            rel = Path("runs") / f"{scenario['label']}_r{rnd}.json"
            free_before = harness.available_memory_mb()
            print(f"round {rnd} {scenario['label']} (free {free_before} MB)", flush=True)
            status = run_one(cfg, scenario, jar, rnd, out_dir / rel, log_dir)
            entry = {"scenario": scenario["label"], "round": rnd, "file": str(rel).replace("\\", "/"),
                     "freeMemoryMbBefore": free_before, "lowMemory": free_before is not None and free_before < 2048,
                     **status}
            if status["status"] == "ok":
                entry["summary"] = analyze_run(json.loads((out_dir / rel).read_text()))
                print("  " + json.dumps(entry["summary"]), flush=True)
            else:
                print(f"  {status['status']}", flush=True)
            results.append(entry)

    meta = {"config": cfg, "sha": sha, "dirty": dirty, "host": harness.host_info(),
            "javaVersion": harness.java_version(cfg["java"]), "results": results,
            "finishedAt": datetime.now(timezone.utc).isoformat()}
    (out_dir / "meta.json").write_text(json.dumps(meta, indent=2))
    write_summary(out_dir, cfg, results)
    print(f"results: {out_dir.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
