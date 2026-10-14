#!/bin/bash
# fill all cubes, then churn cells on main for $1 seconds while region-thread cubes inject
R="python3 /root/compat-ae2-rig/rcon.py"
c=(); for x in $(seq 1 2 39); do c+=("data merge block $x -60 23 {EnergyContainers:[{Container:0b,stored:\"1000000000000\"}]}"); done
$R "${c[@]}" >/dev/null
end=$((SECONDS+$1)); i=0
while [ $SECONDS -lt $end ]; do
  x=$((2 + (i*2) % 38)); i=$((i+1))
  $R "setblock $x -60 21 air" "setblock $x -60 21 ae2:energy_cell" >/dev/null 2>&1 || { echo "rcon failed (server down?)"; break; }
done
echo "churn rounds=$i"
