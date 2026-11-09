#!/bin/bash
# AE2-DEMAIN-001 B1: D4a teardown fixtures (AE2-COMPAT-CLOSURE-001) rerun on addon 0.8.0 with the MB lane.
# Rig H401BEDOM (AE2 15.4.10), fresh pristine world per run, fixtures flag.
# Usage: b1-d4a-run.sh <label> <teardown|teardownclosure> <prod|unpinned> <on|off>
#   prod = production pins (pristine world: ae2 pinned); unpinned = ae2 removed from pinned mods, autoPin off (as
#   teardown-closure-run.sh). off = -Dnestworld.compatAe2.mbLaneOff=true (lane-OFF control).
set -u
B=/root/airis-2026-10-25; I=H401BEDOM; L=$1; FX=$2; CFG=$3; LANE=$4; E=$B/evidence-be-ready/$L; mkdir -p $E; cd $B/$I
hjava() { for p in $(pgrep -x java); do [ "$(readlink /proc/$p/cwd)" = "$B/$I" ] && echo $p; done; }
while [ -f $B/RIG_LOCK ] && ! grep -q "^agent-b1 $L$" $B/RIG_LOCK; do sleep 30; done
[ -n "$(hjava)" ] && { echo running; exit 1; }
echo "agent-b1 $L" > $B/RIG_LOCK
S=$B/server/world/data
rsync -a --delete $B/server/world/ world/; cp user_jvm_args.txt $E/user_jvm_args.before
if [ "$CFG" = unpinned ]; then
  grep -v "^ae2$" $S/nestworld-pinned-mods.txt > world/data/nestworld-pinned-mods.txt
  grep -v "^mod:ae2=" $S/nestworld-autopin-error-counts.txt > world/data/nestworld-autopin-error-counts.txt
  sed -i 's/^-Dnestworld.autoPin=true/-Dnestworld.autoPin=false/' user_jvm_args.txt
fi
grep -q "compatAe2.fixtures" user_jvm_args.txt || printf '\n-Dnestworld.compatAe2.fixtures=true\n' >> user_jvm_args.txt
[ "$LANE" = off ] && printf '\n-Dnestworld.compatAe2.mbLaneOff=true\n' >> user_jvm_args.txt
cp world/data/nestworld-pinned-mods.txt $E/
grep -oE "^-Dnestworld[a-zA-Z.]*=[a-z0-9,:_]*" user_jvm_args.txt > $E/jvm-flags.txt
sha256sum mods/nestworld-*.jar mods/appliedenergistics2*.jar > $E/mods.sha256; cat ACTIVE_CORE > $E/core.txt; ls crash-reports | sort > $E/crash-before.txt
[ -f console.log ] && mv console.log console-$(date +%H%M%S).log
(setsid unshare -n bash -c 'ip link set lo up; exec nice -n 10 taskset -c 0-7 bash run.sh nogui' > console.log 2>&1 < /dev/null &)
for i in $(seq 1 30); do [ -n "$(hjava)" ] && break; sleep 1; done; echo "pid=$(hjava)" > $E/pid.txt
until ./rcon.sh list 2>/dev/null | grep -q "players online"; do [ -z "$(hjava)" ] && { echo "JVM exited" >> $E/out.txt; break; }; sleep 5; done
grep -a "Mixin apply failed\|InvalidInjectionException\|MixinTransformerError" logs/latest.log | cut -c1-200 > $E/mixin-errors.txt
R() { ./rcon.sh "$@" 2>/dev/null | grep -vE "^$|^>"; }
{ echo "config: fixture=$FX pins=$CFG lane=$LANE mixinErrors=$(wc -l < $E/mixin-errors.txt)"
  R "nwcompatae2 mblane" | cut -c1-200
  if [ "$FX" = teardown ]; then
    R "nwcompatae2 topology teardown -20 120 130"
    for i in $(seq 1 80); do s=$(R "nwcompatae2 topology teardown status"); case "$s" in *PASS*|*FAIL*) break;; esac; sleep 3; done
  else
    X=63; Y=120; Z=2000
    R "nwbeready domain $X $Y $Z ae2:crafting_storage" "nwbeready domain $((X+2)) $Y $Z ae2:crafting_storage" "nwbeready domain $((X+5)) $Y $Z ae2:crafting_storage"
    R "nwcompatae2 topology teardownclosure $X $Y $Z"
    for i in $(seq 1 200); do s=$(R "nwcompatae2 topology teardownclosure status"); case "$s" in *PASS*|*FAIL*) break;; esac; sleep 3; done
  fi
  echo "$s"; R "nwbeready" | grep -E "^S[123]_"; R "nwcompatae2 stats" | grep -o "teardown{.*itemsRetained=[0-9]*"
  R "nwcompatae2 stats" | grep -o "lifeAdmission{.*skippedNested=[0-9]*"; R "nwbeready failures"
  echo "== lane"; R "nwcompatae2 mblane"; echo "== detector"; R "nwae2mb" "nwae2static"; } >> $E/out.txt 2>&1
grep -a "D4a closure fixture\|D4a fixture\|D1_ADMISSION_SKIPPED\|fell back\|RETAINED\|Split Region\|merge\|MB lane\|IllegalStateException\|Exception in server tick\|Watchdog" logs/latest.log | cut -c1-400 > $E/log-lines.txt
ls crash-reports | sort | comm -13 $E/crash-before.txt - > $E/crash-new.txt
./rcon.sh stop >/dev/null 2>&1; for i in $(seq 1 40); do [ -z "$(hjava)" ] && break; sleep 3; done; P=$(hjava); [ -n "$P" ] && { echo "STOP HANG" >> $E/out.txt; kill -9 $P; }
sleep 2; for f in nestworld-be-pins.txt nestworld-pinned-mods.txt nestworld-pins.txt nestworld-autopin-error-counts.txt nestworld-pin-reasons.txt; do cp $S/$f world/data/$f; done
cp $E/user_jvm_args.before user_jvm_args.txt; echo "crashNew=$(wc -l < $E/crash-new.txt)" >> $E/out.txt
rm -f $B/RIG_LOCK; cat $E/out.txt
