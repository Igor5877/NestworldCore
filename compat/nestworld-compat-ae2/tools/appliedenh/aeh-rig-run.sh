#!/bin/bash
# APPLIEDENH-PERF-001 equivalence + spark fixture on the AE2 rig H401BEDOM (AE2 15.4.10; owner: allowed while live has players).
# TEST-ONLY additions, restored afterwards: appliedenhancements-1.1.2 jar copied in (rig has none), addon test jar swapped,
# 1-2 JVM flags; pristine world copy (rsync from ../server/world). The rig's sophisticated jars are NOT the audited
# hashes, so the barrel handler is an ineligible control; the eligible handler is a detached Forge ItemStackHandler
# (tracemem / benchmem) driven through the real AE2 ExternalStorageFacade + appliedenhancements chain.
# Usage: aeh-rig-run.sh <label> <addon-jar> <POS|NEG> [benchSeconds]
set -u
B=/root/airis-2026-10-25; I=H401BEDOM; L=$1; JAR=$2; ARM=$3; BS=${4:-120}
E=$B/evidence-appliedenh/rig-$L; mkdir -p $E; cd $B/$I
AEH=/root/project-infinity/mods/appliedenhancements-1.1.2-forge.jar
hjava() { for p in $(pgrep -x java); do [ "$(readlink /proc/$p/cwd)" = "$B/$I" ] && echo $p; done; }
TAG="agent APPLIEDENH rig-$L"   # atomic lock (noclobber); retry while another agent holds it; never touch theirs
until ( set -o noclobber; echo "$TAG" > $B/RIG_LOCK ) 2>/dev/null; do sleep 30; done
unlock() { [ "$(cat $B/RIG_LOCK 2>/dev/null)" = "$TAG" ] && rm -f $B/RIG_LOCK; }
[ -n "$(hjava)" ] && { echo "rig JVM already running" | tee $E/result.txt; unlock; exit 1; }
# clean-args precondition (rig-pos1 2026-11-10 was contaminated by another agent's leftover flags)
grep -qE "^-Dnestworld.compatAe2.(fixtures|busTransit|mbLaneOff)=" user_jvm_args.txt && { echo "PRECONDITION FAIL: foreign compatAe2 flags in user_jvm_args.txt" | tee $E/result.txt; unlock; exit 1; }
rsync -a --delete $B/server/world/ world/
cp user_jvm_args.txt $E/user_jvm_args.orig
mkdir -p $E/orig-mods; [ -f mods/nestworld-compat-ae2-test-all.jar ] && mv mods/nestworld-compat-ae2-test-all.jar $E/orig-mods/
cp $JAR mods/nestworld-compat-ae2-test-all.jar; cp $AEH mods/
restore() {
  rm -f mods/appliedenhancements-1.1.2-forge.jar mods/nestworld-compat-ae2-test-all.jar
  # the rig's reference test jar is the 0.8.0 backup (*.b4orig) when present (coordinator 2026-11-10); else what we found
  if [ -f mods/nestworld-compat-ae2-test-all.jar.b4orig ]; then cp -p mods/nestworld-compat-ae2-test-all.jar.b4orig mods/nestworld-compat-ae2-test-all.jar
  else mv $E/orig-mods/nestworld-compat-ae2-test-all.jar mods/; fi
  cp $E/user_jvm_args.orig user_jvm_args.txt
  ok=1; cmp -s user_jvm_args.txt $E/user_jvm_args.orig || ok=0; [ -f mods/appliedenhancements-1.1.2-forge.jar ] && ok=0
  echo "restored: addon sha=$(sha256sum mods/nestworld-compat-ae2-test-all.jar | cut -c1-16) appliedenh removed + jvm args identical: $ok" | tee -a $E/result.txt
}
printf '\n-Dnestworld.compatAe2.appliedEnhFixtures=true\n' >> user_jvm_args.txt
[ "$ARM" = NEG ] && printf -- '-Dnestworld.compatAe2.appliedEnhCacheOff=true\n' >> user_jvm_args.txt
grep -oE "^-Dnestworld[a-zA-Z0-9.]*=[a-zA-Z0-9,:_]*" user_jvm_args.txt > $E/jvm-flags.txt
sha256sum mods/nestworld-*.jar mods/appliedenergistics2*.jar mods/appliedenhancements*.jar mods/sophisticated*.jar > $E/mods.sha256; cat ACTIVE_CORE > $E/core.txt
ls crash-reports 2>/dev/null | sort > $E/crash-before.txt
[ -f console.log ] && mv console.log console-$(date +%H%M%S).log
(setsid unshare -n bash -c 'ip link set lo up; exec nice -n 10 taskset -c 0-7 bash run.sh nogui' > console.log 2>&1 < /dev/null &)
for i in $(seq 1 30); do [ -n "$(hjava)" ] && break; sleep 1; done; echo "pid=$(hjava)" > $E/pid.txt
until ./rcon.sh list 2>/dev/null | grep -q "players online"; do [ -z "$(hjava)" ] && { echo "JVM exited during start" | tee -a $E/result.txt; cp console.log $E/; restore; unlock; exit 1; }; sleep 5; done
L0=$(grep -ac "" logs/latest.log)
R() { ./rcon.sh "$@" 2>/dev/null | grep -vE "^$|^>"; }
X=-200; Y=200; Z=200
R "forceload add $X $Z" "setblock $X $Y $Z sophisticatedstorage:barrel" "setblock $((X+2)) $Y $Z minecraft:chest" > $E/setup.txt
sleep 15
R "nwappliedenh status" > $E/status-start.txt
R "nwappliedenh tracemem" > $E/trace-mem.txt
R "nwappliedenh tracemem" > $E/trace-mem-2.txt
R "nwappliedenh trace $X $Y $Z" > $E/trace-barrel.txt
R "nwappliedenh trace $((X+2)) $Y $Z" > $E/trace-chest.txt
R "nwappliedenh status" > $E/status-after-trace.txt
bench() { # $1 label $2 on|off $3 ticks $4 iters $5 spark(0/1)
  R "nwappliedenh $2" >> $E/bench.txt
  [ "$5" = 1 ] && R "spark profiler start --timeout $((BS+60))" >> $E/spark-$1.txt
  R "nwappliedenh benchmem $3 $4" >> $E/bench.txt
  until R "nwappliedenh benchstatus" | grep -q "^idle"; do [ -z "$(hjava)" ] && return 1; sleep 5; done
  [ "$5" = 1 ] && { R "spark profiler stop --save-to-file" >> $E/spark-$1.txt; sleep 10; }
  R "nwappliedenh benchstatus" | tee -a $E/bench.txt | grep -o "nsPerIter=[0-9]*"
}
NS=$(bench calib on 100 100 0 | cut -d= -f2); [ -z "$NS" ] && NS=50000
IT=$(( 12000000 / (NS > 0 ? NS : 1) )); [ $IT -lt 50 ] && IT=50; [ $IT -gt 20000 ] && IT=20000
echo "calib nsPerIter=$NS -> iters/tick=$IT" >> $E/bench.txt
TK=$(( BS * 20 ))
if [ "$ARM" = POS ]; then for b in "on1 on 1" "off1 off 1" "on2 on 0" "off2 off 0"; do set -- $b; bench $1 $2 $TK $IT $3; done
else bench neg on 600 $IT 0; fi
R "nwappliedenh status" > $E/status-end.txt
find config/spark spark -newer $E/setup.txt -type f -name '*.sparkprofile' 2>/dev/null > $E/spark-files.txt
for f in $(cat $E/spark-files.txt); do cp $f $E/; done
tail -n +$L0 logs/latest.log | grep -a "APPLIEDENH\|Exception in server tick\|IllegalStateException\|ConcurrentModification\|Watchdog\|ERROR\]" | cut -c1-900 > $E/log-lines.txt
grep -a -i "appliedenh\|Mixin apply failed\|InvalidInjection" logs/latest.log | head -40 | cut -c1-600 > $E/log-startup.txt
ls crash-reports 2>/dev/null | sort | comm -13 $E/crash-before.txt - > $E/crash-new.txt
./rcon.sh stop >/dev/null 2>&1; for i in $(seq 1 60); do [ -z "$(hjava)" ] && break; sleep 3; done; P=$(hjava); [ -n "$P" ] && { echo "STOP HANG" >> $E/result.txt; kill -9 $P; sleep 3; }
echo "arm=$ARM crashNew=$(wc -l < $E/crash-new.txt)" >> $E/result.txt
restore; unlock; cat $E/result.txt
