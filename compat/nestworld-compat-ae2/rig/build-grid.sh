#!/bin/bash
# build-grid.sh <x0> <z0> <len>: controller at x0, cells x0+1..x0+len (row z0), every 2nd cell gets an
# acceptor (z0+1) fed by a creative cube (z0+2) ejecting from its region-thread block-entity tick.
X0=$1; Z0=$2; LEN=$3; R="python3 /root/compat-ae2-rig/rcon.py"
cmds=("forceload add $((X0-16)) $((Z0-16)) $((X0+LEN+16)) $((Z0+16))" "setblock $X0 -60 $Z0 ae2:controller")
for i in $(seq 1 $LEN); do cmds+=("setblock $((X0+i)) -60 $Z0 ae2:energy_cell"); done
for i in $(seq 1 2 $LEN); do
  x=$((X0+i)); z1=$((Z0+1)); z2=$((Z0+2))
  cmds+=("setblock $x -60 $z1 ae2:energy_acceptor")
  cmds+=("setblock $x -60 $z2 mekanism:creative_energy_cube{componentConfig:{config6:{side0:4,side1:1,side2:1,side3:1,side4:1,side5:1},eject6:1b}}")
  cmds+=("data merge block $x -60 $z2 {EnergyContainers:[{Container:0b,stored:\"1000000000000\"}]}")
done
$R "${cmds[@]}" | grep -E "Could not|Invalid|Unknown|rror" | sort | uniq -c | head
