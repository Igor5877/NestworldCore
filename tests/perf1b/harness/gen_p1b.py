#!/usr/bin/env python3
"""PERF-1B workload datapack (docs/contracts/PERF-1B-spec.md, D3).

Same 16-cell diagonal topology as PERF-1A W1 (tests/perf1/gen_datapack.py), so the frozen layout
builder makes one region per cell from one marker per cell. Each cell then gets:
  - T target entities: minecraft:marker, tagged perf1 / perf1c<k> (the perf1 harness's
    ownership and containment checks) and perf1b_c<k> (the consumer binds by this tag);
  - one producer block perf1b:producer{cell:k} (benchmark-mod), whose block entity ticks in
    that cell's region and issues K setVelocity calls per tick on its own cell's targets (B2)
    or on cell k^1's targets (B3).
Markers have no physics, so the final-state LATEST_INTENT_WINS check reads exact values.

Usage: gen_p1b.py <out-dir> <targets-per-cell>
Functions: perf1:setup (forceload), perf1:walls (no-op), perf1:markers (layout markers),
perf1:cows (targets + producers; called by the perf1 layout builder after the layout exists).
"""
import json, os, sys

CELLS, PITCH, BASE, PAD = 16, 512, 1024, 12
Y = -60
SIDE = 33                                   # target area per cell (blocks); no walls needed


def main():
    out, t = sys.argv[1], int(sys.argv[2])
    side = int(t ** 0.5) + 1
    if side * side < t or side > SIDE:
        sys.exit("targets per cell must fit a 33 x 33 grid")
    fn = os.path.join(out, "data", "perf1", "functions")
    os.makedirs(fn, exist_ok=True)
    json.dump({"pack": {"pack_format": 15, "description": f"PERF-1B targets={t}"}},
              open(os.path.join(out, "pack.mcmeta"), "w"))
    anchors = [(k, BASE + k * PITCH + PAD, BASE + k * PITCH + PAD) for k in range(CELLS)]
    setup = ["gamerule doMobSpawning false", "gamerule doDaylightCycle false",
             "gamerule doWeatherCycle false", "gamerule randomTickSpeed 0",
             "time set 6000", "weather clear 1000000"]
    markers, cows = [], []
    for k, x0, z0 in anchors:
        setup.append(f"forceload add {x0 - 16} {z0 - 16} {x0 + SIDE + 15} {z0 + SIDE + 15}")
        markers.append(f"summon minecraft:marker {x0 + 16} {Y} {z0 + 16} {{Tags:[\"perf1cell\"]}}")
        n = 0
        for i in range(side):
            for j in range(side):
                if n == t:
                    break
                cows.append(f"summon minecraft:marker {x0 + 1 + i}.5 {Y} {z0 + 1 + j}.5 "
                            f"{{Tags:[\"perf1\",\"perf1c{k}\",\"perf1b_c{k}\"]}}")
                n += 1
        cows.append(f"setblock {x0 + SIDE - 2} {Y} {z0 + SIDE - 2} perf1b:producer{{cell:{k}}}")
    cells = [{"k": k, "x0": x0, "z0": z0, "chunk_x": (x0 - PAD) // 16, "chunk_z": (z0 - PAD) // 16,
              "bounds": [x0, Y - 1, z0, SIDE - 1, 3, SIDE - 1]} for k, x0, z0 in anchors]
    json.dump({"workload": "PERF-1B", "side": SIDE, "cows_per_cell": t, "cows": [t] * CELLS,
               "verify": [], "cells": cells}, open(os.path.join(out, "cells.json"), "w"), indent=1)
    for name, lines in (("setup", setup), ("walls", ["say perf1b: no walls"]),
                        ("markers", markers), ("cows", cows)):
        with open(os.path.join(fn, name + ".mcfunction"), "w") as f:
            f.write("\n".join(lines) + "\n")
    print(f"PERF-1B: 16 cells x {t} marker targets + 1 producer each")


if __name__ == "__main__":
    main()
