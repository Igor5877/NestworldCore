#!/bin/bash
# APPLIEDENH-PERF-001 equivalence + spark fixture on the production-pack copy /root/pi-smoke.
# Usage: aeh-run.sh <label> <jar> <POS|NEG> [benchSeconds]
#   POS: elision default ON; trace (OFF/ON/OFF on the same seeded inventory) + benches ON,OFF,ON,OFF, spark on the first ON/OFF.
#   NEG: -Dnestworld.compatAe2.appliedEnhCacheOff=true (hard off): trace must show 0 elisions, status shows disabled>0.
# TEST-ONLY changes, restored + cmp-verified at the end: addon jar swapped (0.7.7 restored), 1-2 JVM flags appended.
set -u
P=/root/pi-smoke; B=/root/airis-2026-10-25; L=$1; JAR=$2; ARM=$3; BS=${4:-120}
E=/root/airis-2026-10-25/evidence-appliedenh/$L; mkdir -p $E; cd $P
LIVE=/root/project-infinity/world/data; PF="nestworld-be-pins.txt nestworld-pinned-mods.txt nestworld-pins.txt nestworld-pin-reasons.txt"
hjava() { for p in $(pgrep -x java); do [ "$(readlink /proc/$p/cwd)" = "$P" ] && echo $p; done; }
livezero() { (cd /root/project-infinity && ./rcon.sh list 2>/dev/null) | grep -q "There are 0 of"; }
# live must show "There are 0" for >= 5 consecutive minutes (11 polls x 30 s; a restart window does not count)
ok=0; while [ $ok -lt 11 ]; do if [ ! -f $B/PI_SMOKE_LOCK ] && livezero; then ok=$((ok+1)); else ok=0; fi; [ $ok -lt 11 ] && sleep 30; done
livezero || { echo "live not empty at start" | tee $E/result.txt; exit 1; }
[ -n "$(hjava)" ] && { echo "pi-smoke JVM already running" | tee $E/result.txt; exit 1; }
echo "agent APPLIEDENH $L" > $B/PI_SMOKE_LOCK
for f in $PF; do cmp -s world/data/$f $LIVE/$f || { echo "PRECONDITION FAIL: $f differs from live" | tee $E/result.txt; rm -f $B/PI_SMOKE_LOCK; exit 1; }; done
mkdir -p $E/pins-orig; for f in $PF nestworld-autopin-error-counts.txt; do cp -p world/data/$f $E/pins-orig/; done
cp user_jvm_args.txt $E/user_jvm_args.orig
ORIG=$(ls mods/nestworld-compat-ae2-*-all.jar); echo "$ORIG" > $E/orig-addon.txt; mv $ORIG $E/
cp $JAR mods/nestworld-compat-ae2-aeh-test-all.jar
restore() {
  rm -f mods/nestworld-compat-ae2-aeh-test-all.jar; mv $E/$(basename $ORIG) mods/
  for f in $PF nestworld-autopin-error-counts.txt; do cp -p $E/pins-orig/$f world/data/$f; done
  cp $E/user_jvm_args.orig user_jvm_args.txt
  ok=1; for f in $PF; do cmp -s world/data/$f $LIVE/$f || { ok=0; echo "RESTORE MISMATCH $f"; }; done
  cmp -s user_jvm_args.txt $E/user_jvm_args.orig || ok=0
  echo "restored: addon=$(ls mods/nestworld-compat-ae2-*-all.jar) pins+jvmargs byte-identical: $ok" | tee -a $E/result.txt
}
printf '\n-Dnestworld.compatAe2.appliedEnhFixtures=true\n' >> user_jvm_args.txt
[ "$ARM" = NEG ] && printf -- '-Dnestworld.compatAe2.appliedEnhCacheOff=true\n' >> user_jvm_args.txt
grep -oE "^-Dnestworld[a-zA-Z0-9.]*=[a-zA-Z0-9,:_]*" user_jvm_args.txt > $E/jvm-flags.txt
sha256sum mods/nestworld-*.jar mods/appliedenergistics2*.jar mods/appliedenhancements*.jar mods/sophisticated*.jar > $E/mods.sha256
ls crash-reports 2>/dev/null | sort > $E/crash-before.txt
(setsid nohup nice -n 10 taskset -c 0-7 ./run.sh nogui > $E/console.log 2>&1 < /dev/null &)
for i in $(seq 1 30); do [ -n "$(hjava)" ] && break; sleep 1; done; echo "pid=$(hjava)" > $E/pid.txt
until ./rcon.sh list 2>/dev/null | grep -q "players online"; do [ -z "$(hjava)" ] && { echo "JVM exited during start" | tee -a $E/result.txt; restore; rm -f $B/PI_SMOKE_LOCK; exit 1; }; sleep 5; done
L0=$(grep -ac "" logs/latest.log)
R() { ./rcon.sh "$@" 2>/dev/null | grep -vE "^$|^>"; }
X=-1000; Y=200; Z=1000
R "forceload add $X $Z" "setblock $X $Y $Z air" "setblock $X $Y $Z sophisticatedstorage:barrel" "setblock $((X+2)) $Y $Z air" "setblock $((X+2)) $Y $Z minecraft:chest" > $E/setup.txt
sleep 20
R "nwappliedenh status" > $E/status-start.txt
R "nwappliedenh trace $X $Y $Z" > $E/trace-barrel.txt
R "nwappliedenh trace $X $Y $Z" > $E/trace-barrel-2.txt
R "nwappliedenh trace $((X+2)) $Y $Z" > $E/trace-chest.txt
bench() { # $1 label $2 on|off $3 ticks $4 iters $5 spark(0/1)
  R "nwappliedenh $2" >> $E/bench.txt
  [ "$5" = 1 ] && R "spark profiler start --timeout $((BS+60))" >> $E/spark-$1.txt
  R "nwappliedenh bench $X $Y $Z $3 $4" >> $E/bench.txt
  until R "nwappliedenh benchstatus" | grep -q "^idle"; do [ -z "$(hjava)" ] && return 1; livezero || { echo "ABORT: live player joined" >> $E/result.txt; ABORT=1; return 1; }; sleep 5; done
  [ "$5" = 1 ] && { R "spark profiler stop --save-to-file" >> $E/spark-$1.txt; sleep 10; }
  R "nwappliedenh benchstatus" | tee -a $E/bench.txt | grep -o "nsPerIter=[0-9]*"
}
NS=$(bench calib on 100 100 0 | cut -d= -f2); [ -z "$NS" ] && NS=50000
IT=$(( 12000000 / (NS > 0 ? NS : 1) )); [ $IT -lt 50 ] && IT=50; [ $IT -gt 20000 ] && IT=20000
echo "calib nsPerIter=$NS -> iters/tick=$IT" >> $E/bench.txt
TK=$(( BS * 20 ))
ABORT=0
for b in "on1 on 1" "off1 off 1" "on2 on 0" "off2 off 0"; do set -- $b; [ $ABORT = 1 ] && break; bench $1 $2 $TK $IT $3; done
R "nwappliedenh on" >/dev/null; R "nwappliedenh status" > $E/status-end.txt
find config/spark -newer $E/setup.txt -type f -name '*.sparkprofile' > $E/spark-files.txt 2>/dev/null
for f in $(cat $E/spark-files.txt); do cp $f $E/; done
tail -n +$L0 logs/latest.log | grep -a "APPLIEDENH\|appliedenh\|Exception in server tick\|IllegalStateException\|ConcurrentModification\|Watchdog\|Mixin.*nestworld_compat_ae2_appliedenh\|ERROR\]" | cut -c1-600 > $E/log-lines.txt
grep -a -i "appliedenh\|nestworld_compat_ae2_appliedenh" logs/latest.log | head -40 | cut -c1-600 > $E/log-startup.txt
ls crash-reports 2>/dev/null | sort | comm -13 $E/crash-before.txt - > $E/crash-new.txt
R "setblock $X $Y $Z air" "setblock $((X+2)) $Y $Z air" "forceload remove $X $Z" >/dev/null
S0=$(date +%s); R stop >/dev/null; for i in $(seq 1 90); do [ -z "$(hjava)" ] && break; sleep 2; done
[ -n "$(hjava)" ] && { echo "STOP HANG >180s" >> $E/result.txt; kill -9 $(hjava); sleep 3; }
echo "arm=$ARM stopSeconds=$(( $(date +%s) - S0 )) crashNew=$(wc -l < $E/crash-new.txt)" >> $E/result.txt
restore; rm -f $B/PI_SMOKE_LOCK; cat $E/result.txt
