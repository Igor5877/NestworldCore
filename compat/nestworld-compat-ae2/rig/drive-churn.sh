#!/bin/bash
R="python3 /root/compat-ae2-rig/rcon.py"; end=$((SECONDS+$1)); i=0
while [ $SECONDS -lt $end ]; do
  x=$((2 + (i*2) % 38)); i=$((i+1))
  $R "setblock $x -60 21 air" "setblock $x -60 21 ae2:energy_cell" >/dev/null 2>&1 || { echo "rcon failed (server down?)"; break; }
done
echo "churn rounds=$i"
