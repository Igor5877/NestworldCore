#!/usr/bin/env python3
"""Summarize observed WP3-B windows without attributing natural workload drift to transit."""
import json
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])


def text(path):
    return path.read_text()


def counters(path):
    raw = text(path)
    result = {k: int(v) for k, v in re.findall(r"\b(scheduled|transitImport|transitExport|outstanding|missed|commitFailures|regionWrongDomain|regionFailures|dropped)=(\d+)", raw)}
    for key in ("mainOpInline", "mainOpTransit", "commit", "region"):
        match = re.search(rf"\b{key}=(\d+)us", raw)
        if match:
            result[key + "Us"] = int(match[1])
    return result


rows = []
for phase in sorted(root.glob("phase-*-*")):
    if not (phase / "perf.txt").exists():
        continue
    perf = dict(re.findall(r"(\w+)=([\w.+-]+)", text(phase / "perf.txt")))
    if "n" not in perf:
        raise SystemExit(f"Invalid perf evidence: {phase}")
    n = int(perf["n"])
    if perf.get("saturated") != "false" or n == 0:
        raise SystemExit(f"Saturated/empty measurement: {phase}")
    before, after = [counters(phase / f"transit-{p}.txt") for p in ("before", "after")]
    cpu_before, cpu_after = [json.loads(text(phase / f"cpu-{p}.json")) for p in ("before", "after")]
    wall = cpu_after["monotonic"] - cpu_before["monotonic"]
    devices = {}
    for line in text(phase / "devices.txt").splitlines():
        m = re.match(r"([\d.]+) ms/tick .* (\S+)$", line)
        if m:
            devices[m[2]] = float(m[1])
    row = {"phase": phase.name, "ticks": n, "wallSeconds": wall,
           "mainCpuMsPerTick": float(perf["MAINcpuMsPerTick"]),
           "MSPT": {key: float(perf[key]) for key in ("MSPTavg", "p50", "p95", "p99", "max")},
           "inlineImportItemsPerTick": int(perf["inlineImportItems"]) / n,
           "inlineExportItemsPerTick": int(perf["inlineExportItems"]) / n,
           "transitImportItemsPerTick": int(perf["transitImportItems"]) / n,
           "transitExportItemsPerTick": int(perf["transitExportItems"]) / n,
           "regionCpuMsPerTick": (cpu_after["region_cpu_seconds"] - cpu_before["region_cpu_seconds"]) * 1000 / n,
           "regionCpuCoreEquivalents": (cpu_after["region_cpu_seconds"] - cpu_before["region_cpu_seconds"]) / wall,
           "busTimingMsPerTick": {key: (after[key + "Us"] - before[key + "Us"]) / 1000 / n
                                   for key in ("mainOpInline", "mainOpTransit", "commit", "region")},
           "deltaCounters": {key: after[key] - before[key] for key in ("scheduled", "transitImport", "transitExport", "missed", "commitFailures", "regionWrongDomain", "regionFailures", "dropped")},
           "deviceMsPerTick": devices}
    rows.append(row)

transit = sum(r["deltaCounters"]["transitImport"] + r["deltaCounters"]["transitExport"] for r in rows)
print(json.dumps({"validLiveGate": not (root / "INVALID.txt").exists(), "completedWindows": len(rows),
                  "transitOperations": transit,
                  "causalBenefitDemonstrated": False if transit == 0 else "requires matched workload analysis",
                  "rows": rows}, indent=2))
