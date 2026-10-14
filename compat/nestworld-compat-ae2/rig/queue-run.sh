#!/bin/bash
# deploy current addon jar, restart scratch server, run QUEUE + T2 fixtures (QUEUE-001.1 §1.5/§4.2)
R="python3 /root/compat-ae2-rig/rcon.py"; cd /root/compat-ae2-rig
$R stop >/dev/null 2>&1; timeout 90 bash -c 'while for p in $(pgrep -f java.*unix_args); do readlink /proc/$p/cwd; done | grep -q compat-ae2-rig; do sleep 2; done'
for p in $(pgrep -f "java.*unix_args"); do [ "$(readlink /proc/$p/cwd)" = /root/compat-ae2-rig/server ] && kill -9 $p; done
cp /root/nestworldcore/compat/nestworld-compat-ae2/build/libs/nestworld-compat-ae2-0.1.0-all.jar server/mods/
cd server; mv console.log console-$(date +%H%M%S).log; (setsid nohup bash run.sh nogui > console.log 2>&1 < /dev/null &); sleep 1
timeout 300 bash -c 'until grep -qE "Done \(|Exception in thread" console.log; do sleep 2; done'; grep -E "gate|Exception in thread" console.log | cut -c1-140; cd ..
$R "forceload remove all" >/dev/null
/root/compat-ae2-rig/freerun-all.sh >/dev/null; $R "forceload add 192 192 207 207" "setblock 200 -60 200 ae2:inscriber" >/dev/null; sleep 3
for k in idle before_dequeue during many_during self at_finish after_finish; do
  $R "nwcompatae2 queuefixture $k 200 -60 200" >/dev/null
  timeout 60 bash -c "until $R 'nwcompatae2 queuefixture status' | grep -qE 'PASS|FAIL|INVALID'; do sleep 1; done"
  echo "$($R 'nwcompatae2 queuefixture status' | sed -n 2p | cut -c1-160)"
done
$R "nwcompatae2 queuefixture staging 200 -60 200" >/dev/null
timeout 60 bash -c "until $R 'nwcompatae2 queuefixture status' | grep -qE 'PASS|FAIL|INVALID'; do sleep 1; done"; echo "$($R 'nwcompatae2 queuefixture status' | sed -n 2p | cut -c1-160)"
for k in stale wake alert self; do $R "nwcompatae2 fixture $k" >/dev/null; timeout 40 bash -c "until $R 'nwcompatae2 fixture status' | grep -qE 'PASS|FAIL|INVALID'; do sleep 1; done"; echo "$($R 'nwcompatae2 fixture status' | sed -n 2p | cut -c1-60)"; done
echo "exceptions: $(grep -E 'Exception|ReportedException' server/console.log | grep -vc 'FailingExample\|deliberate')"
$R "nwcompatae2 stats" | sed -n 2p | grep -oE "mode=[A-Z]+|queue[A-Za-z]+=[0-9]+|setChanged[A-Za-z]+=[0-9]+|initCancelled=[0-9]+|lifeOrderViolation=[0-9]+|unauditedPaths=[0-9]+" | tr '\n' ' '; echo
