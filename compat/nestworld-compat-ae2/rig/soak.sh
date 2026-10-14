#!/bin/bash
# soak.sh <minutes>: 5-minute chunks of churn over all grids + one T2 fixture per chunk; stats snapshot per chunk
R="python3 /root/compat-ae2-rig/rcon.py"; cd /root/compat-ae2-rig; OUT=evidence/soak.tsv; L0=$(wc -l < server/console.log)
FX=(stale wake alert self); echo -e "chunk\ttime\texceptions\tfixture\tstats" > $OUT
for c in $(seq 1 $(( $1 / 5 ))); do
  f=${FX[$(( (c-1) % 4 ))]}; $R "nwcompatae2 fixture $f" >/dev/null 2>&1
  ./drive-multi.sh 295 -60,-40 -60,60 40,-40 40,60 -10,-80 20,-80 > /dev/null || true
  st=$($R "nwcompatae2 fixture status" 2>/dev/null | sed -n 2p | cut -c1-60); s=$($R "nwcompatae2 stats" 2>/dev/null | sed -n 2p)
  ex=$(tail -n +$L0 server/console.log | grep -E 'Exception|ReportedException' | grep -vc 'FailingExample\|deliberate')
  echo -e "$c\t$(date +%H:%M:%S)\t$ex\t$st\t$s" >> $OUT
  [ -z "$s" ] && { echo "server not answering at chunk $c" >> $OUT; break; }
done
echo SOAK_DONE >> $OUT
