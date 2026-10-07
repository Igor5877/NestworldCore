#!/bin/bash
# PERF-1B Step 7: instrumentation OFF/ON sanity (docs/contracts/PERF-1B-spec.md §8).
# One stable scenario (B2, N=4, K=32768), counters OFF/ON alternated ABBA-style so host drift
# cannot correlate with the mode. Usage: step7.sh <rig-dir> <evidence-dir>
set -u
R=$1; E=$2; mkdir -p $E
H=$(dirname "$(readlink -f "$0")")
i=0
for mode in 0 1 1 0 0 1; do
  i=$((i+1)); tag="s7-$([ $mode = 1 ] && echo on || echo off)-$i"
  PERF1_HARNESS_CPUS=1 python3 $H/perf1b.py run $R/server $R/snap-t256.tar $R/dp-t256 nestworld API self 4 2,3,4,5 \
      $tag $E --k 32768 --api-stats $mode > $E/$tag.out 2>&1
  echo "$tag exit=$? $(python3 $H/perf1b.py validate $E/$tag.json 2>&1 | tail -1)"
done
echo STEP7_DONE
