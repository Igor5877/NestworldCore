#!/bin/bash
# stop the rig server (kill after 90 s if stop hangs; cwd-checked), copy the current addon jar, start, wait for Done
R="python3 /root/compat-ae2-rig/rcon.py"; cd /root/compat-ae2-rig
$R stop >/dev/null 2>&1; timeout 90 bash -c 'while for p in $(pgrep -f "java @user_jvm"); do readlink /proc/$p/cwd; done | grep -q compat-ae2-rig; do sleep 2; done'
for p in $(pgrep -f "java @user_jvm"); do [ "$(readlink /proc/$p/cwd)" = /root/compat-ae2-rig/server ] && kill -9 $p; done
cp /root/nestworldcore/compat/nestworld-compat-ae2/build/libs/nestworld-compat-ae2-0.1.0-all.jar server/mods/
cd server; mv console.log console-$(date +%H%M%S).log; (setsid nohup bash run.sh nogui > console.log 2>&1 < /dev/null &); sleep 1
timeout 300 bash -c 'until grep -qE "Done \(|Exception in thread" console.log; do sleep 2; done'; grep -E "Done \(" console.log | tail -1
$R "forceload remove all" >/dev/null
