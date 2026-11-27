#!/usr/bin/env python3
"""Benchmark harness.

Runs every (target, payload, rate) cell for several rounds. Each run uses a fresh provider JVM
and a fresh load-generator JVM. Targets alternate in ABBA order across rounds, and the rate order
is shuffled per round (seeded), so drift in machine state (thermal, background load, cloud noise)
hits all targets alike and results can be compared round by round.

Usage: python bench/run.py bench/configs/<name>.json [--dry-run]
"""
import argparse
import json
import os
import platform
import random
import shutil
import socket
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / ".bench"
JAR_REL = Path("ac-rpc-bench/target/ac-rpc-bench.jar")


def sh(cmd, cwd=ROOT, env=None, check=True):
    return subprocess.run(cmd, cwd=cwd, env=env, check=check, capture_output=True, text=True)


def git_sha(ref="HEAD"):
    return sh(["git", "rev-parse", ref]).stdout.strip()


def worktree_dirty():
    return bool(sh(["git", "status", "--porcelain"]).stdout.strip())


def java_exe(java_home):
    exe = "java.exe" if os.name == "nt" else "java"
    return str(Path(java_home) / "bin" / exe)


def java_version(java_home):
    out = subprocess.run([java_exe(java_home), "-version"], capture_output=True, text=True)
    return out.stderr.strip().splitlines()


def build_target(target):
    """Build the bench jar for a target and return (jar path, resolved sha, dirty flag)."""
    ref = target["ref"]
    env = dict(os.environ, JAVA_HOME=target.get("buildJava", target["java"]))
    mvn = shutil.which("mvn") or shutil.which("mvn.cmd")
    if ref == "WORKTREE":
        src, sha, dirty = ROOT, git_sha(), worktree_dirty()
    else:
        sha, dirty = git_sha(ref), False
        src = WORK / "worktrees" / sha[:12]
        if not src.exists():
            sh(["git", "worktree", "add", "--detach", str(src), sha])
    print(f"[build] {target['label']} @ {sha[:12]}{' (dirty)' if dirty else ''}", flush=True)
    sh([mvn, "-q", "-pl", "ac-rpc-bench", "-am", "package", "-DskipTests"], cwd=src, env=env)
    jars = WORK / "jars"
    jars.mkdir(parents=True, exist_ok=True)
    jar = jars / f"{target['label']}-{sha[:12]}.jar"
    shutil.copy2(src / JAR_REL, jar)
    return jar, sha, dirty


def wait_for_port(port, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        with socket.socket() as s:
            s.settimeout(0.5)
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(0.2)
    return False


def schedule(cfg):
    """List of runs in execution order."""
    rng = random.Random(cfg.get("seed", 1))
    labels = [t["label"] for t in cfg["targets"]]
    runs = []
    for r in range(cfg["rounds"]):
        order = labels if r % 4 in (0, 3) else list(reversed(labels))  # A B B A
        cells = [(p, rate) for p in cfg["payloadBytes"] for rate in cfg["rates"]]
        rng.shuffle(cells)
        for payload, rate in cells:
            for label in order:
                runs.append({"round": r, "target": label, "payload": payload, "rate": rate})
    return runs


def available_memory_mb():
    """Free physical memory on the host, or None if unknown."""
    if os.name == "nt":
        import ctypes

        class MemoryStatus(ctypes.Structure):
            _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                        ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                        ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                        ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                        ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]

        status = MemoryStatus(dwLength=ctypes.sizeof(MemoryStatus))
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status))
        return status.ullAvailPhys // (1024 * 1024)
    try:
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) // 1024
    except OSError:
        pass
    return None


def host_info():
    info = {"platform": platform.platform(), "machine": platform.machine(),
            "processor": platform.processor(), "cpuCount": os.cpu_count()}
    if os.name == "nt":
        ps = ("(Get-CimInstance Win32_Processor).Name;"
              "[math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB)")
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True)
        lines = out.stdout.strip().splitlines()
        if len(lines) == 2:
            info["cpuModel"], info["memoryGb"] = lines[0].strip(), int(lines[1])
    return info


def run_one(cfg, target, jar, run, out_file, log_file):
    java = java_exe(target["java"])
    jvm = cfg.get("jvmArgs", []) + target.get("jvmArgs", [])
    common = ["--registry", cfg["registry"], "--serializer", cfg["serializer"]]
    port = cfg["port"]
    provider = subprocess.Popen([java, *jvm, "-jar", str(jar), "provider", "--port", str(port), *common],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        if not wait_for_port(port):
            return {"status": "provider-failed"}
        time.sleep(cfg.get("settleSec", 2))
        loadgen = [java, *jvm, "-jar", str(jar), "loadgen", *common,
                   "--rate", str(run["rate"]), "--payload", str(run["payload"]),
                   "--warmup", str(cfg["warmupSec"]), "--duration", str(cfg["durationSec"]),
                   "--timeout", str(cfg["timeoutSec"]), "--out", str(out_file),
                   "--max-threads", str(cfg.get("maxThreads", 2000)),
                   "--label.target", run["target"], "--label.round", str(run["round"])]
        budget = cfg["warmupSec"] + cfg["durationSec"] + 2 * cfg["timeoutSec"] + 60
        try:
            with open(log_file, "w") as log:
                res = subprocess.run(loadgen, stdout=log, stderr=subprocess.STDOUT, timeout=budget)
        except subprocess.TimeoutExpired:
            return {"status": "loadgen-timeout"}
        if res.returncode != 0 or not out_file.exists():
            return {"status": "loadgen-failed"}
        return {"status": "ok"}
    finally:
        provider.kill()
        provider.wait()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("config")
    ap.add_argument("--dry-run", action="store_true", help="print the schedule and exit")
    args = ap.parse_args()
    cfg = json.loads(Path(args.config).read_text())
    runs = schedule(cfg)
    per_run = cfg["warmupSec"] + cfg["durationSec"] + cfg.get("settleSec", 2) + 5
    print(f"{len(runs)} runs, roughly {len(runs) * per_run / 60:.0f} min", flush=True)
    if args.dry_run:
        for r in runs:
            print(r)
        return

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = ROOT / "bench" / "results" / f"{cfg['name']}-{stamp}"
    (out_dir / "runs").mkdir(parents=True)
    (out_dir / "logs").mkdir()

    targets = {t["label"]: t for t in cfg["targets"]}
    built, target_meta = {}, {}
    for label, t in targets.items():
        jar, sha, dirty = build_target(t)
        built[label] = jar
        target_meta[label] = {**t, "sha": sha, "dirty": dirty, "javaVersion": java_version(t["java"])}

    meta = {"config": cfg, "targets": target_meta, "host": host_info(),
            "startedAt": datetime.now(timezone.utc).isoformat(), "runs": []}
    for i, run in enumerate(runs, 1):
        name = f"{run['target']}_r{run['round']}_p{run['payload']}_rate{run['rate']}.json"
        mem_before = available_memory_mb()
        status = run_one(cfg, targets[run["target"]], built[run["target"]], run,
                         out_dir / "runs" / name, out_dir / "logs" / name.replace(".json", ".log"))
        mem_after = available_memory_mb()
        low = mem_before is not None and mem_before < cfg.get("minFreeMemoryMb", 2048)
        meta["runs"].append({**run, "file": name, **status, "freeMemoryMbBefore": mem_before,
                             "freeMemoryMbAfter": mem_after, "lowMemory": low})
        print(f"[{i}/{len(runs)}] {run['target']} round={run['round']} payload={run['payload']} "
              f"rate={run['rate']} -> {status['status']} (free {mem_before} -> {mem_after} MB)", flush=True)
        (out_dir / "meta.json").write_text(json.dumps(meta, indent=2))
    meta["finishedAt"] = datetime.now(timezone.utc).isoformat()
    (out_dir / "meta.json").write_text(json.dumps(meta, indent=2))
    print(f"results: {out_dir.relative_to(ROOT)}")
    subprocess.run([sys.executable, str(Path(__file__).parent / "analyze.py"), str(out_dir)])


if __name__ == "__main__":
    main()
