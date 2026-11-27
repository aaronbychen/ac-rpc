#!/usr/bin/env python3
"""Summarize a results directory produced by run.py.

For each (target, payload, rate) cell: median and [min, max] across rounds. With two or more
targets, also a paired comparison against the first target: the ratio is computed within each
round, then summarized, so round-to-round machine noise cancels out.

Usage: python bench/analyze.py bench/results/<dir>
"""
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

METRICS = [("p50", "p50 ms"), ("p99", "p99 ms"), ("p999", "p99.9 ms")]


def load(result_dir):
    meta = json.loads((result_dir / "meta.json").read_text())
    runs = []
    for entry in meta["runs"]:
        f = result_dir / "runs" / entry["file"]
        data = json.loads(f.read_text()) if entry["status"] == "ok" and f.exists() else None
        runs.append({**entry, "data": data})
    return meta, runs


def spread(values, fmt="{:.2f}"):
    if not values:
        return "n/a"
    med = statistics.median(values)
    if len(values) == 1:
        return fmt.format(med)
    return f"{fmt.format(med)} [{fmt.format(min(values))}, {fmt.format(max(values))}]"


def summarize(meta, runs):
    cells = defaultdict(list)
    for r in runs:
        cells[(r["target"], r["payload"], r["rate"])].append(r)

    lines = [f"# {meta['config']['name']}", ""]
    host = meta["host"]
    lines.append(f"- Host: {host.get('cpuModel', host['processor'])}, {host['cpuCount']} threads, "
                 f"{host.get('memoryGb', '?')} GB, {host['platform']}")
    for label, t in meta["targets"].items():
        jdk = t["javaVersion"][0] if t["javaVersion"] else "?"
        lines.append(f"- Target `{label}`: {t['sha'][:12]}{' (dirty)' if t['dirty'] else ''}, {jdk}")
    c = meta["config"]
    lines.append(f"- {c['rounds']} rounds, warmup {c['warmupSec']}s, measured {c['durationSec']}s, "
                 f"timeout {c['timeoutSec']}s, serializer {c['serializer']}, JVM {' '.join(c.get('jvmArgs', []))}")
    lines.append("- Values are median [min, max] across rounds. Latency is measured from each request's "
                 "intended send time (open loop).")
    lines.append("- A run is unreliable if it failed, was aborted (thread cap or overrun), left >0.1% of requests "
                 "incomplete or dropped, or started with low free memory.")
    aborted = [r for r in runs if r["data"] and r["data"].get("aborted")]
    if aborted:
        lines += ["", "Aborted runs:"]
        for r in aborted:
            lines.append(f"- {r['target']} rate={r['rate']} round={r['round']}: {r['data']['abortReason']} "
                         f"after {r['data'].get('abortedAfterSec')}s")
    lines.append("")

    header = "| target | payload B | offered rps | achieved rps | " + " | ".join(m[1] for m in METRICS) + \
             " | error rate | peak threads | unreliable runs |"
    lines += [header, "|" + "---|" * (header.count("|") - 1)]
    for (target, payload, rate) in sorted(cells, key=lambda k: (k[0], k[1], k[2])):
        group = cells[(target, payload, rate)]
        ok = [g["data"] for g in group if g["data"]]
        failed = len(group) - len(ok)
        row = [target, str(payload), str(rate), spread([d["throughputOkPerSec"] for d in ok], "{:.1f}")]
        for key, _ in METRICS:
            row.append(spread([d["latencyMs"][key] for d in ok if key in d["latencyMs"]]))
        row.append(spread([d["errorRate"] * 100 for d in ok], "{:.1f}%"))
        row.append(spread([d.get("peakThreads", 0) for d in ok], "{:.0f}"))
        unreliable = sum(1 for g in group
                         if not g["data"] or not g["data"]["percentilesReliable"] or g.get("lowMemory"))
        row.append(f"{unreliable}/{len(group)}")
        lines.append("| " + " | ".join(row) + " |")

    labels = list(meta["targets"])
    if len(labels) > 1:
        ref = labels[0]
        lines += ["", f"## Paired comparison vs `{ref}`", "",
                  "Per-round ratio (target / reference), then median [min, max]. "
                  "Below 1.0 means lower latency / less throughput than the reference.", "",
                  "| target | payload B | offered rps | p99 ratio | throughput ratio | rounds |", "|---|---|---|---|---|---|"]
        by_round = {(r["target"], r["payload"], r["rate"], r["round"]): r["data"] for r in runs if r["data"]}
        for other in labels[1:]:
            for (payload, rate) in sorted({(p, q) for (_, p, q) in cells}):
                p99, thr = [], []
                for rnd in range(c["rounds"]):
                    a, b = by_round.get((ref, payload, rate, rnd)), by_round.get((other, payload, rate, rnd))
                    if a and b and a["latencyMs"].get("p99") and a["throughputOkPerSec"]:
                        p99.append(b["latencyMs"]["p99"] / a["latencyMs"]["p99"])
                        thr.append(b["throughputOkPerSec"] / a["throughputOkPerSec"])
                lines.append(f"| {other} | {payload} | {rate} | {spread(p99, '{:.3f}')} | "
                             f"{spread(thr, '{:.3f}')} | {len(p99)} |")
    return "\n".join(lines) + "\n"


def main():
    result_dir = Path(sys.argv[1])
    meta, runs = load(result_dir)
    text = summarize(meta, runs)
    (result_dir / "summary.md").write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
