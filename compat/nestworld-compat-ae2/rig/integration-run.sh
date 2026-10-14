#!/bin/bash
R="python3 /root/compat-ae2-rig/rcon.py"; cd /root/compat-ae2-rig
$R stop >/dev/null 2>&1; timeout 90 bash -c 'while for p in $(pgrep -f java.*unix_args); do readlink /proc/$p/cwd; done | grep -q compat-ae2-rig; do sleep 2; done'
for p in $(pgrep -f "java.*unix_args"); do [ "$(readlink /proc/$p/cwd)" = /root/compat-ae2-rig/server ] && kill -9 $p; done
cp /root/nestworldcore/compat/nestworld-compat-ae2/build/libs/nestworld-compat-ae2-0.1.0-all.jar server/mods/
cd server; mv console.log console-$(date +%H%M%S).log; (setsid nohup bash run.sh nogui > console.log 2>&1 < /dev/null &); sleep 1
timeout 300 bash -c 'until grep -qE "Done \(|Exception in thread" console.log; do sleep 2; done'; grep -E "gate|Exception in thread" console.log | cut -c1-120; cd ..
$R "forceload remove all" >/dev/null
$R "nestworld split 0" "nestworld split 1" "nestworld split 2" >/dev/null; /root/compat-ae2-rig/freerun-all.sh >/dev/null
$R "forceload add 192 192 223 223" "fill 196 -60 196 212 -56 206 air" "kill @e[type=minecraft:armor_stand]" "summon minecraft:armor_stand 202.5 -60 201.5 {NoGravity:1b}" >/dev/null; sleep 2
for k in during before; do
  $R "setblock 200 -60 201 air" >/dev/null; sleep 1
  $R "nwcompatae2 integration life_$k 200 -60 201" | sed -n 2p | cut -c1-80
  timeout 60 bash -c "until $R 'nwcompatae2 integration status' | grep -qE 'PASS|FAIL|INVALID'; do sleep 1; done"
  echo "$($R 'nwcompatae2 integration status' | sed -n 2p | cut -c1-400)"
done
$R "setblock 205 -59 200 ae2:creative_energy_cell" "setblock 206 -59 200 ae2:charger" "setblock 207 -60 200 minecraft:chest" \
   "setblock 206 -60 200 minecraft:hopper[facing=east]" "setblock 206 -58 200 minecraft:hopper[facing=down]" >/dev/null; sleep 2
$R "nwcompatae2 integration staging 206 -59 200 60" | sed -n 2p
$R "item replace block 206 -58 200 container.0 with ae2:certus_quartz_crystal 4" | sed -n 2p
timeout 120 bash -c "until $R 'nwcompatae2 integration status' | grep -qE 'PASS|FAIL|INVALID'; do sleep 2; done"
echo "$($R 'nwcompatae2 integration status' | sed -n 2p | cut -c1-500)"
$R "data get block 207 -60 200 Items" | sed -n 2p | cut -c1-200
echo "exceptions: $(grep -E 'Exception|ReportedException' server/console.log | grep -vc 'FailingExample\|deliberate')"
grep -E "Exception|ReportedException" server/console.log | grep -v "FailingExample\|deliberate" | head -3
