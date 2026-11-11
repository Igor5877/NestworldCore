#!/usr/bin/env python3
"""Fail closed on missing/failed WP3-B part-B evidence; never infer delivery from a drop ledger."""
import json
import re
import sys
from pathlib import Path

evidence = Path(sys.argv[1])
raw = (evidence / "out.txt").read_text()
failures = []
counts = {}
for label in ("c1", "partial", "returnFailed", "gonebrokenOn", "rebuilt", "negGoneBroken"):
    match = re.search(rf"^count {label} .*invariant\(total-created\+consumed\)=(-?\d+)", raw, re.M)
    if match is None:
        failures.append(f"missing count: {label}")
        continue
    counts[label] = int(match[1])
    if label == "negGoneBroken":
        if counts[label] >= 0:
            failures.append("recovery-off negative control did not expose loss")
    elif counts[label] != 0:
        failures.append(f"physical conservation {label}: {counts[label]}")

windows = re.findall(r"^window station=1 export ticks=(\d+) transit=(true|false) scheduledOps=(\d+) networkDelta=(-?\d+) perOp=([\d.]+)", raw, re.M)
rates = [float(w[4]) for w in windows]
if len(windows) != 3 or {w[1] for w in windows} != {"true", "false"} or any(int(w[2]) == 0 for w in windows):
    failures.append("missing/nonworking OFF/ON export windows")
elif len(set(rates)) != 1 or len({int(w[2]) / int(w[0]) for w in windows}) != 1:
    failures.append("export throughput/scheduling differs OFF vs ON")

grid = re.search(r"^station 1 export .*grid=Grid #(\d+)", raw, re.M)
latencies = []
if grid:
    for row in re.findall(rf"^Grid #{grid[1]} .*", raw, re.M):
        columns = row.split()
        # gridId scheduled completed missed cancelled backlog mainCommit mainOp import export ...
        if len(columns) > 10 and columns[10] != "-/-/-/-":
            latencies.append(int(columns[10].split("/")[-1]))
if not latencies or any(n > 2 for n in latencies):
    failures.append(f"export p100 <=2 not demonstrated: {latencies}")
if re.search(r"\btimeout\b", raw, re.I) or "NOT QUIESCENT" in raw:
    failures.append("fixture timed out or did not quiesce")
for field in ("commitFailures", "missed", "enqueueFailed", "regionWrongDomain", "regionFailures", "regionEnergyCalls", "regionStorageMisuse", "savedInflight", "sealDirtyInconsistent", "energyUncharged"):
    values = re.findall(rf"\b{field}=(\d+)", raw)
    if not values or any(int(n) for n in values):
        failures.append(f"missing/nonzero {field}")
if not re.search(r"^crashNew=0$", raw, re.M):
    failures.append("missing clean crash delta")
report = {"scope": "WP3-B single-key export and import fallback, not production acceptance",
          "counts": counts, "export_p100_ticks": latencies, "windows": windows,
          "status": "FAIL" if failures else "PASS", "failures": failures}
print(json.dumps(report, indent=2))
sys.exit(bool(failures))
