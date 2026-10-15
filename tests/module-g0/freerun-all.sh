#!/bin/bash
# idempotent: turn free-running ON for every active overworld region that is not yet free-running
# (`/nestworld freerun <id>` is a TOGGLE, so blind loops flip regions back off)
R="python3 /root/module-g0-rig/rcon.py"
for pass in 1 2 3; do
  $R "nestworld status minecraft:overworld full" > /root/module-g0-rig/tmp/fr-status.txt
  python3 -I - /root/module-g0-rig/tmp/fr-status.txt > /root/module-g0-rig/tmp/fr-off.txt <<'PY'
import sys,re
lines=open(sys.argv[1]).read().split('\n'); off=[]
for i,l in enumerate(lines):
    m=re.match(r'\s+#(\d+) \[',l)
    if m:
        nxt=lines[i+1] if i+1<len(lines) else ''
        if 'FreeRun' not in nxt: off.append(m.group(1))
print(' '.join(off))
PY
  off=$(cat /root/module-g0-rig/tmp/fr-off.txt)
  [ -z "$off" ] && { echo "all regions free-running"; exit 0; }
  for id in $off; do $R "nestworld freerun $id" >/dev/null 2>&1; done
done
echo "still not free-running: $off"
