#!/usr/bin/env python3
"""PERF-1 W1 datapack: K identical, independent entity cells.

Each cell is a square of c x c one-block compartments (2-high stone walls, one cow each).
AI and pathfinding run, but no mob can leave its compartment, so the cost per tick is stable.

Layout: the 16 cells sit on a DIAGONAL, cell k at (BASE + k*PITCH) on both axes, so the cells
are in the same order along X and Z whichever axis a split picks. The harness builds the region
layout with Core's manual split, which cuts at the chunk of the median owned entity and gives
that chunk to the lower child; a temporary marker entity in the chunk just before the target
cell moves the median into the gap (see perf1.py). Each cell starts PAD blocks into its chunk,
so >= PAD+1 blocks separate any mob from a region edge (random-stroll reach is 10).

The region layout is built while the world holds NO load: the snapshot contains only the walls
and forceloads, the harness places one inert minecraft:marker per cell (perf1:markers) to steer
the splits, removes them, and only then summons the cows (perf1:cows) into the finished layout.
Even NoAI cows cost enough to trigger Core's automatic splitter mid-build.

Usage: gen_datapack.py <out-dir> <compartments-per-side c (2..63)>
Functions: perf1:setup (gamerules, forceload), perf1:walls (all cell walls; run only after the
forceloaded chunks are loaded, a fill on an unloaded chunk fails silently), perf1:markers (one marker per cell),
perf1:cows (one cow per compartment, all cells). Per-cell counts are read by the harness over RCON with
"execute if entity @e[tag=perf1c<k>]".
"""
import json, os, sys

CELLS = 16
PITCH = 512              # blocks between cell origins on each axis (32 chunks)
BASE = 1024              # chunk-aligned origin of cell 0
PAD = 12                 # cell wall at chunk offset PAD, first cows at PAD+1
Y = -60                  # flat-world surface (air above grass at -61)


def cells():
    for k in range(CELLS):
        yield k, BASE + k * PITCH + PAD, BASE + k * PITCH + PAD


def main():
    out, c = sys.argv[1], int(sys.argv[2])
    if not 2 <= c <= 63:
        sys.exit("c must be in 2..63 (one /fill of at most 32768 blocks per cell)")
    side = 2 * c + 1        # odd: walls on both outer edges, compartments at odd offsets
    fn = os.path.join(out, "data", "perf1", "functions")
    os.makedirs(fn, exist_ok=True)
    with open(os.path.join(out, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"pack_format": 15, "description": f"PERF-1 W1 c={c}"}}, f)

    setup = [
        "gamerule doMobSpawning false", "gamerule doDaylightCycle false",
        "gamerule doWeatherCycle false", "gamerule randomTickSpeed 0",
        "gamerule doFireTick false", "gamerule doPatrolSpawning false",
        "gamerule doTraderSpawning false", "gamerule doInsomnia false",
        "gamerule doEntityDrops false", "gamerule maxEntityCramming 0",
        "time set 6000", "weather clear 1000000",
    ]
    for k, x0, z0 in cells():
        # one chunk of margin around the cell: everything a mob can read stays loaded
        setup.append(f"forceload add {x0 - 16} {z0 - 16} {x0 + side + 15} {z0 + side + 15}")
    cows, markers, walls = [], [], []
    for k, x0, z0 in cells():
        x1, z1 = x0 + side - 1, z0 + side - 1
        lines = [f"fill {x0} {Y} {z0} {x1} {Y + 1} {z1} minecraft:stone"]
        # air rows at odd z, then stone columns at even x: air remains only at (odd x, odd z)
        for dz in range(1, side, 2):
            lines.append(f"fill {x0} {Y} {z0 + dz} {x1} {Y + 1} {z0 + dz} minecraft:air")
        for dx in range(0, side, 2):
            lines.append(f"fill {x0 + dx} {Y} {z0} {x0 + dx} {Y + 1} {z1} minecraft:stone")
        walls += lines
        for dx in range(1, side, 2):
            for dz in range(1, side, 2):
                cows.append(f"summon minecraft:cow {x0 + dx}.5 {Y} {z0 + dz}.5 "
                            f"{{PersistenceRequired:1b,Tags:[\"perf1\",\"perf1c{k}\"]}}")
        markers.append(f"summon minecraft:marker {x0 + c} {Y} {z0 + c} {{Tags:[\"perf1cell\"]}}")
    with open(os.path.join(out, "cells.json"), "w") as f:
        json.dump({"c": c, "cows_per_cell": c * c, "pad": PAD, "side": side,
                   "stone_per_cell": 2 * side * side - 2 * c * c,
                   "cells": [{"k": k, "x0": x0, "z0": z0, "chunk_x": (x0 - PAD) // 16,
                              "chunk_z": (z0 - PAD) // 16} for k, x0, z0 in cells()]}, f, indent=1)
    write(fn, "setup", setup)
    write(fn, "walls", walls)
    write(fn, "markers", markers)
    write(fn, "cows", cows)
    print(f"datapack: 16 cells x {c * c} cows = {16 * c * c} entities, cell side {side} blocks")


def write(fn, name, lines):
    with open(os.path.join(fn, name + ".mcfunction"), "w") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
