#!/bin/bash
# LEGACY-ORIENTATION-001.1 disk-path fixture (deploy current jar first unless NODEPLOY=1)
R="python3 /root/compat-ae2-rig/rcon.py"; cd /root/compat-ae2-rig
if [ -z "$NODEPLOY" ]; then
  $R stop >/dev/null 2>&1; timeout 90 bash -c 'while for p in $(pgrep -f java.*unix_args); do readlink /proc/$p/cwd; done | grep -q compat-ae2-rig; do sleep 2; done'
  for p in $(pgrep -f "java.*unix_args"); do [ "$(readlink /proc/$p/cwd)" = /root/compat-ae2-rig/server ] && kill -9 $p; done
  cp /root/nestworldcore/compat/nestworld-compat-ae2/build/libs/nestworld-compat-ae2-0.1.0-all.jar server/mods/
  cd server; mv console.log console-$(date +%H%M%S).log; (setsid nohup bash run.sh nogui > console.log 2>&1 < /dev/null &); sleep 1
  timeout 300 bash -c 'until grep -qE "Done \(|Exception in thread" console.log; do sleep 2; done'; cd ..
$R "forceload remove all" >/dev/null
fi
Y=-60; Z=1000; declare -A B=([1001]=ae2:drive [1002]=ae2:inscriber [1003]=ae2:molecular_assembler [1004]=ae2:pattern_provider)
declare -A F=([1001]=east [1002]=south [1003]=west [1004]=down); declare -A U=([1001]=up [1002]=up [1003]=up [1004]=north)
$R "forceload add 1000 1000" "fill 1000 $Y 1000 1010 -55 1003 air" "setblock 1000 $Y $Z ae2:controller" >/dev/null
for x in 1001 1002 1003 1004; do $R "setblock $x $Y $Z ${B[$x]}" >/dev/null; done; sleep 3
echo "== placed"; for x in 1001 1002 1003 1004; do $R "nwcompatae2 legacy check $x $Y $Z" | sed -n 2p | cut -c1-200; done
unload() { $R "save-all flush" "forceload remove 1000 1000" >/dev/null; for i in $(seq 1 120); do $R "nwcompatae2 legacy loaded 1000 $Y $Z" | grep -q "loaded=false" && return 0; sleep 1; done; echo "chunk did not unload"; return 1; }
unload || exit 1
for x in 1001 1002 1003 1004; do $R "nwcompatae2 legacy inject $x $Y $Z ${F[$x]} ${U[$x]}" | sed -n 2p; done
$R "forceload add 1000 1000" >/dev/null; sleep 4
echo "== reloaded with legacy NBT (expect migrated, pending=null, node present)"; for x in 1001 1002 1003 1004; do echo "${F[$x]}/${U[$x]}: $($R "nwcompatae2 legacy check $x $Y $Z" | sed -n 2p | cut -c1-260)"; done
unload || exit 1
echo "== saved NBT after migration (expect legacyKeys=false)"; for x in 1001 1002 1003 1004; do $R "nwcompatae2 legacy savednbt $x $Y $Z" | sed -n 2p; done
$R "forceload add 1000 1000" >/dev/null; sleep 4
echo "== second reload (expect migrated counter unchanged)"; $R "nwcompatae2 legacy check 1001 $Y $Z" | sed -n 2p | cut -c1-260
echo "== live-world NBT placement (expect liveSkipped+1, no world write)"; $R "setblock 1005 $Y $Z air" >/dev/null; sleep 1; $R "setblock 1005 $Y $Z ae2:drive{forward:\"east\",up:\"up\"}" >/dev/null; sleep 3; $R "nwcompatae2 legacy check 1005 $Y $Z" | sed -n 2p | cut -c1-260
echo "exceptions: $(grep -E 'Exception|ReportedException' server/console.log | grep -vc 'FailingExample\|deliberate')"
