#!/usr/bin/env python3
"""PERF-1A W2 datapack: one unsplittable hotspot (docs/contracts/PERF-1A-W2-spec.md).

All load is a tower of sealed one-block compartments inside ONE chunk column (cell 0's chunk):
per layer 8 x 8 compartments at odd offsets 1..15 (walls on even offsets, the outer wall at
offset 16), each layer a floor plus 2 high of air, LAYERS layers stacked from the surface, one cow
with AI per compartment. Region cuts are whole chunks, and Core's point-hotspot rule refuses any
cut whose smaller side carries < 10% of the load, so nothing can split it.

The topology is W1's: the same 16 cell anchors, so the frozen harness builds the same 16-region
layout from one marker per cell; regions 1..15 stay empty. cells.json carries the per-cell cow
counts, the tower bounds and per-layer wall boxes with exact stone counts.

Usage: gen_w2.py <out-dir> [layers=120]
Functions: perf1:setup (gamerules, forceload), perf1:walls (tower; run only after its chunk is
loaded), perf1:markers (one marker per cell), perf1:cows (the tower cows).
"""
import json, os, sys

CELLS, PITCH, BASE, PAD = 16, 512, 1024, 12      # W1 anchors (gen_datapack.py)
GROUND = -61                                      # flat-world grass; layer 0 stands on it


def main():
    out = sys.argv[1]
    layers = int(sys.argv[2]) if len(sys.argv) > 2 else 120
    tx, tz = BASE, BASE                               # tower at cell 0's chunk (64, 64)
    top_air = GROUND + 2 + 3 * (layers - 1)
    if top_air > 318:
        sys.exit("too many layers for the build height")
    fn = os.path.join(out, "data", "perf1", "functions")
    os.makedirs(fn, exist_ok=True)
    json.dump({"pack": {"pack_format": 15, "description": f"PERF-1A W2 layers={layers}"}},
              open(os.path.join(out, "pack.mcmeta"), "w"))

    anchors = [(k, BASE + k * PITCH + PAD, BASE + k * PITCH + PAD) for k in range(CELLS)]
    setup = [
        "gamerule doMobSpawning false", "gamerule doDaylightCycle false",
        "gamerule doWeatherCycle false", "gamerule randomTickSpeed 0",
        "gamerule doFireTick false", "gamerule doPatrolSpawning false",
        "gamerule doTraderSpawning false", "gamerule doInsomnia false",
        "gamerule doEntityDrops false", "gamerule maxEntityCramming 0",
        "time set 6000", "weather clear 1000000",
        # the tower chunk with one chunk of margin
        f"forceload add {tx - 16} {tz - 16} {tx + 32} {tz + 32}",
    ]
    for k, x0, z0 in anchors[1:]:                     # empty cells: their marker chunk + margin
        setup.append(f"forceload add {x0 - 16} {z0 - 16} {x0 + 16} {z0 + 16}")

    walls, cows, verify = [], [], []
    for i in range(layers):
        ya = GROUND + 1 + 3 * i                       # the 2 air blocks: ya, ya + 1
        yb = ya if i == 0 else ya - 1                 # layer 0's floor is the ground itself
        walls.append(f"fill {tx} {yb} {tz} {tx + 16} {ya + 1} {tz + 16} minecraft:stone")
        for dz in range(1, 16, 2):
            walls.append(f"fill {tx} {ya} {tz + dz} {tx + 16} {ya + 1} {tz + dz} minecraft:air")
        for dx in range(0, 17, 2):
            walls.append(f"fill {tx + dx} {ya} {tz} {tx + dx} {ya + 1} {tz + 16} minecraft:stone")
        volume = 17 * 17 * (ya + 2 - yb)
        verify.append({"k": 0, "box": [tx, yb, tz, tx + 16, ya + 1, tz + 16],
                       "stone": volume - 64 * 2})
        for dx in range(1, 16, 2):
            for dz in range(1, 16, 2):
                cows.append(f"summon minecraft:cow {tx + dx}.5 {ya} {tz + dz}.5 "
                            f"{{PersistenceRequired:1b,Tags:[\"perf1\",\"perf1c0\"]}}")

    markers = [f"summon minecraft:marker {tx + 7}.5 {GROUND + 1} {tz + 7}.5 {{Tags:[\"perf1cell\"]}}"]
    markers += [f"summon minecraft:marker {x0} {GROUND + 1} {z0} {{Tags:[\"perf1cell\"]}}"
                for _, x0, z0 in anchors[1:]]
    cells = []
    for k, x0, z0 in anchors:
        cell = {"k": k, "x0": x0, "z0": z0, "chunk_x": (x0 - PAD) // 16, "chunk_z": (z0 - PAD) // 16}
        if k == 0:
            cell["bounds"] = [tx, GROUND, tz, 16, top_air + 2 - GROUND, 16]
        cells.append(cell)
    json.dump({"workload": "W2", "layers": layers, "side": 17, "cows_per_cell": 64 * layers,
               "cows": [64 * layers] + [0] * (CELLS - 1), "verify": verify, "cells": cells},
              open(os.path.join(out, "cells.json"), "w"), indent=1)
    for name, lines in (("setup", setup), ("walls", walls), ("markers", markers), ("cows", cows)):
        with open(os.path.join(fn, name + ".mcfunction"), "w") as f:
            f.write("\n".join(lines) + "\n")
    print(f"W2: {layers} layers x 64 = {64 * layers} cows in one chunk column "
          f"({tx}..{tx + 16}, {tz}..{tz + 16}, y {GROUND}..{top_air + 1}); "
          f"{sum(v['stone'] for v in verify)} stone in {len(verify)} verified boxes")


if __name__ == "__main__":
    main()
