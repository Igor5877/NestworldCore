#!/bin/bash
# GRID-PHASE-001 fixtures (deploys current jar unless NODEPLOY=1); STRESS_SECONDS default 60
R="python3 /root/compat-ae2-rig/rcon.py"; cd /root/compat-ae2-rig
if [ -z "$NODEPLOY" ]; then
  $R stop >/dev/null 2>&1; timeout 90 bash -c 'while for p in $(pgrep -f java.*unix_args); do readlink /proc/$p/cwd; done | grep -q compat-ae2-rig; do sleep 2; done'
  for p in $(pgrep -f "java.*unix_args"); do [ "$(readlink /proc/$p/cwd)" = /root/compat-ae2-rig/server ] && kill -9 $p; done
  cp /root/nestworldcore/compat/nestworld-compat-ae2/build/libs/nestworld-compat-ae2-0.1.0-all.jar server/mods/
  cd server; mv console.log console-$(date +%H%M%S).log; (setsid nohup bash run.sh nogui > console.log 2>&1 < /dev/null &); sleep 1
  timeout 300 bash -c 'until grep -qE "Done \(|Exception in thread" console.log; do sleep 2; done'; cd ..
$R "forceload remove all" >/dev/null
fi
/root/compat-ae2-rig/freerun-all.sh >/dev/null
$R "forceload add 1096 1096 1119 1119" "fill 1100 -60 1100 1110 -55 1104 air" "setblock 1100 -60 1100 ae2:controller" "setblock 1101 -60 1100 ae2:drive" "setblock 1100 -59 1100 ae2:creative_energy_cell" >/dev/null; sleep 3
for k in boundary coalesce activephase watcherfifo watcherdestroyed; do
  $R "nwcompatae2 gridphase staging $k 1101 -60 1100" >/dev/null
  timeout 60 bash -c "until $R 'nwcompatae2 gridphase status' | grep -qE 'PASS|FAIL|INVALID'; do sleep 1; done"
  echo "$($R 'nwcompatae2 gridphase status' | sed -n 2p | cut -c1-300)"
done
$R "kill @e[type=minecraft:armor_stand]" "summon minecraft:armor_stand 1103.5 -60 1102.5 {NoGravity:1b}" "setblock 1102 -60 1100 ae2:inscriber" >/dev/null; sleep 3
for i in 1 2 3 4 5 6; do
  $R "setblock 1102 -60 1100 ae2:inscriber" >/dev/null; sleep 2
  /root/compat-ae2-rig/freerun-all.sh >/dev/null
  $R "nwcompatae2 gridphase overlap 1101 -60 1100 1102 -60 1100" >/dev/null
  timeout 60 bash -c "until $R 'nwcompatae2 gridphase status' | grep -qE 'PASS|FAIL|INVALID'; do sleep 1; done"
  echo "$($R 'nwcompatae2 gridphase status' | sed -n 2p | cut -c1-300)"
done
$R "nwcompatae2 gridphase replayfresh 1101 -60 1100" >/dev/null
timeout 60 bash -c "until $R 'nwcompatae2 gridphase status' | grep -qE 'PASS|FAIL|INVALID'; do sleep 1; done"; echo "$($R 'nwcompatae2 gridphase status' | sed -n 2p | cut -c1-300)"
/root/compat-ae2-rig/freerun-all.sh >/dev/null
MG=${MULTIGRID_SECONDS:-60}
$R "nwcompatae2 gridphase multigrid 1102 -60 1100 $MG" >/dev/null
timeout $((MG+120)) bash -c "until $R 'nwcompatae2 gridphase status' | grep -qE 'PASS|FAIL|INVALID'; do sleep 2; done"; echo "$($R 'nwcompatae2 gridphase status' | sed -n 2p | cut -c1-300)"
S=${STRESS_SECONDS:-60}
$R "kill @e[type=minecraft:armor_stand]" "summon minecraft:armor_stand 1105.5 -60 1103.5 {NoGravity:1b}" "setblock 1105 -60 1101 air" >/dev/null; sleep 2
$R "nwcompatae2 gridphase stress 1105 -60 1101 $S" >/dev/null
timeout $((S+120)) bash -c "until $R 'nwcompatae2 gridphase status' | grep -qE 'PASS|FAIL|INVALID'; do sleep 2; done"
echo "$($R 'nwcompatae2 gridphase status' | sed -n 2p | cut -c1-300)"
if [ -z "$SKIP_NESTED" ]; then
  echo "$($R 'nwcompatae2 gridphase nested 1101 -60 1100' | sed -n 2p | cut -c1-300)"      # LAST: switches the kernel toward DEGRADED
fi
echo "exceptions: $(grep -E 'Exception|ReportedException' server/console.log | grep -vc 'FailingExample\|deliberate')"
grep -E "Exception|ReportedException" server/console.log | grep -v "FailingExample\|deliberate" | head -3 | cut -c1-200
