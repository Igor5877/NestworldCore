#!/bin/bash
# usage: g0-run.sh <mode>   (lambda | subscribe-main | subscribe-region)
MODE=$1; R=/root/module-g0-rig; S=$R/server; TS=$(date +%H%M%S); LOG=$R/evidence/g0-$MODE-$TS.log
RC="python3 $R/rcon.py"
cd $S
grep -v '^-Dg0.mode' user_jvm_args.txt > user_jvm_args.tmp; echo "-Dg0.mode=$MODE" >> user_jvm_args.tmp; mv user_jvm_args.tmp user_jvm_args.txt
setsid bash run.sh nogui > $LOG 2>&1 < /dev/null &
for i in $(seq 1 180); do grep -q 'Done (' $LOG && break; grep -q 'Stopping server\|Crash report' $LOG && break; sleep 1; done
PID=$(pgrep -f "user_jvm_args.txt.*" | while read p; do [ "$(readlink /proc/$p/cwd)" = "$S" ] && echo $p; done | head -1)
echo "mode=$MODE pid=$PID log=$LOG"
sleep 3
$RC "forceload remove all" "forceload add -16 -16 31 31" >/dev/null 2>&1; sleep 3
for k in 1 2 3 4 5 6; do $RC "summon minecraft:cow $((k*4)) -60 8 {NoAI:0b}" >/dev/null 2>&1; done
$RC "summon minecraft:cow 20 -60 24" "summon minecraft:pig 4 -60 24" >/dev/null 2>&1
sleep 5; bash $R/freerun-all.sh; sleep 25
$RC "nestworld status minecraft:overworld full" > $R/evidence/g0-$MODE-$TS-status.txt 2>&1
$RC "stop" >/dev/null 2>&1
for i in $(seq 1 60); do kill -0 $PID 2>/dev/null || break; sleep 1; done
if kill -0 $PID 2>/dev/null && [ "$(readlink /proc/$PID/cwd)" = "$S" ]; then echo "stop hang -> kill $PID"; kill -9 $PID; fi
echo "--- G0PROBE lines: $(grep -c G0PROBE $LOG)"
