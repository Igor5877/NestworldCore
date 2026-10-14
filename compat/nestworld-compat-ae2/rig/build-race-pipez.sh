#!/bin/bash
# Pipez variant: creative cube (z=24, no self-eject) <- extracting energy pipe (z=23) -> AE2 acceptor (z=22)
R="python3 /root/compat-ae2-rig/rcon.py"
cmds=("forceload add -16 0 47 47" "fill 0 -60 20 40 -60 24 air")
cmds+=("setblock 0 -60 21 ae2:controller")
for x in $(seq 1 40); do cmds+=("setblock $x -60 21 ae2:energy_cell"); done
for x in $(seq 1 2 39); do
  cmds+=("setblock $x -60 22 ae2:energy_acceptor")
  cmds+=("setblock $x -60 24 mekanism:creative_energy_cube{componentConfig:{config6:{side0:4,side1:1,side2:1,side3:1,side4:1,side5:1},eject6:1b},EnergyContainers:[{Container:0b,stored:\"1000000000000\"}]}")
  cmds+=("setblock $x -60 23 pipez:energy_pipe[north=true,south=true,has_data=true]{ExtractingSides:[0b,0b,0b,1b,0b,0b],DisconnectedSides:[0b,0b,0b,0b,0b,0b]}")
done
$R "${cmds[@]}" | grep -E "Could not|Invalid|Unknown|error" | sort | uniq -c | head
