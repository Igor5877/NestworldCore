#!/bin/bash
# AE2 grid at z=20..: controller + cells; N creative cubes each pushing into its own energy acceptor
# (all acceptors on the same grid) from region-thread block-entity ticks, while main runs E8.
R="python3 /root/compat-ae2-rig/rcon.py"
cmds=("forceload add -16 0 47 47" "fill 0 -60 20 40 -60 22 air")
cmds+=("setblock 0 -60 21 ae2:controller")
for x in $(seq 1 40); do cmds+=("setblock $x -60 21 ae2:energy_cell"); done
for x in $(seq 1 2 39); do
  cmds+=("setblock $x -60 22 ae2:energy_acceptor")
  cmds+=("setblock $x -60 23 mekanism:creative_energy_cube{componentConfig:{config6:{side0:4,side1:1,side2:1,side3:1,side4:1,side5:1},eject6:1b}}")
done
$R "${cmds[@]}" | grep -vE "^>|Changed the block|^$" | head
