#!/bin/bash
# PERF-1B pilot calibration: short diag runs (window 60 s) to choose K per mode so that the run is
# saturated (TPS < 19) without starving producers. Usage: calibrate.sh <rig> <evidence> "<mode N cpus K>"...
# mode: B0 | B1 | B2 | B3
set -u
R=$1; E=$2; shift 2; mkdir -p $E; H=$(dirname "$(readlink -f "$0")")
for spec in "$@"; do
  set -- $spec; m=$1; n=$2; cpus=$3; k=$4
  case $m in
    B0) a="$R/stock-server forge RAW self";; B1) a="$R/stock-server forge API self";;
    B2) a="$R/server nestworld API self";;   B3) a="$R/server nestworld API partner";;
  esac
  set -- $a; srv=$1; shift
  tag="cal-$m-n$n-k$k"
  PERF1_HARNESS_CPUS=1 python3 $H/perf1b.py run $srv $R/snap-t256.tar $R/dp-t256 "$@" $n $cpus $tag $E \
      --k $k --warmup-ops 5000000 --warmup 60 --window 60 > $E/$tag.out 2>&1
  echo "$tag exit=$? $(tail -1 $E/$tag.out | python3 -c 'import json,sys
try:
  d=json.loads(sys.stdin.read()); print("tps=%.2f submitted/s=%.0f terminal/s=%.0f" % (d["tps"], d["submitted_per_s"], d["terminal_per_s"]))
except Exception as e: print("no-json")') | $(python3 $H/perf1b.py validate $E/$tag.json 2>&1 | tail -1)"
done
echo CAL_DONE
