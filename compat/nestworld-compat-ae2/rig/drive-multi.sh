#!/bin/bash
# drive-multi.sh <seconds> <x0,z0> ... : rotate cell break/place over the given grids (cells at x0+2.. even)
R="python3 /root/compat-ae2-rig/rcon.py"; T=$1; shift; G=("$@"); end=$((SECONDS+T)); i=0
while [ $SECONDS -lt $end ]; do
  g=${G[$((i % ${#G[@]}))]}; X0=${g%,*}; Z0=${g#*,}; x=$((X0 + 2 + ((i / ${#G[@]}) * 2) % 18)); i=$((i+1))
  $R "setblock $x -60 $Z0 air" "setblock $x -60 $Z0 ae2:energy_cell" >/dev/null 2>&1 || { echo "rcon failed"; break; }
done
echo "churn rounds=$i"
