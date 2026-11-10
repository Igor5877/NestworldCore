#!/bin/bash
# AE2-DEMAIN-001 B1 — B10-shape soak (derived from b1-soak.sh) on the production-pack copy /root/pi-smoke.
# Usage: b10-soak.sh <label> <seconds> <P|U> <on|off>
# Differences to b1-soak.sh: TWO extra test networks (W at -800/808, E at 800/-808, interior of one chunk each, glass
# floor under them so drops do not fall into the void), item-holding CPUs: before each crafting-storage toggle the
# network's cluster is seeded with diamonds (fixtures-flag helper `mblanefx seed`, in the cluster's BE domain), drops are
# collected in the chunk-owning domain every 60 s and at the end (`mblanefx collect`), ledger `mblanefx items`
# (seeded = collected + inCluster at quiescence); region splits via `nestworld splitat <id> X|Z <driver chunk>`
# (alternating axes, both drivers); unload / reload alternates between the two network chunks.
# Test-only flag added for the run: -Dnestworld.compatAe2.fixtures=true (removed afterwards with the jvm args).
#   P = production pins (byte-identical to live); U = TEST-ONLY narrow unpin of the crafting multiblock types:
#       mods ae2 / megacells / mae2 / ae2omnicells removed from pinned mods, every NON-crafting BE id of those mods
#       pinned per type (list: $B/b1-u-be-pins.txt); restored byte-identically afterwards (cmp, logged).
#   on/off = MB lane (off = -Dnestworld.compatAe2.mbLaneOff=true, negative control).
# Test-only additions (removed afterwards): addon 0.8.0 (0.7.7 restored), race detector 0.2.0, mek-churn driver.
set -u
P=/root/pi-smoke; B=/root/airis-2026-10-25; L=$1; T=$2; PINS=$3; LANE=$4; E=$B/evidence-be-ready/$L; mkdir -p $E; cd $P
LIVE=/root/project-infinity/world/data; PF="nestworld-be-pins.txt nestworld-pinned-mods.txt nestworld-pins.txt nestworld-pin-reasons.txt"
hjava() { for p in $(pgrep -x java); do [ "$(readlink /proc/$p/cwd)" = "$P" ] && echo $p; done; }
[ -n "$(hjava)" ] && { echo running; exit 1; }
until ( set -o noclobber; echo "agent-b1 $L" > $B/PI_SMOKE_LOCK ) 2>/dev/null; do grep -qx "agent-b1 $L" $B/PI_SMOKE_LOCK 2>/dev/null && break; sleep 30; done   # atomic (AGENT-RULES)
for f in $PF; do cmp -s world/data/$f $LIVE/$f || { echo "PRECONDITION FAIL: $f differs from live" | tee $E/result.txt; exit 1; }; done
mkdir -p $E/pins-orig; for f in $PF nestworld-autopin-error-counts.txt; do cp -p world/data/$f $E/pins-orig/; done
cp user_jvm_args.txt $E/user_jvm_args.orig
# jars
mv mods/nestworld-compat-ae2-0.7.7-all.jar $E/ 2>/dev/null
cp $B/evidence-be-ready/b1-jars/0.8.0-all-6b1aa219.jar mods/nestworld-compat-ae2-0.8.0-all.jar   # pinned copy (build/libs moves on: 0.9.x)
cp /root/nestworldcore/compat/nestworld-ae2-race-detector/build/libs/nestworld-ae2-race-detector-0.2.0-all.jar mods/
cp mods-disabled/nestworld-mek-churn-0.1.0.jar mods/
restore() {
  rm -f mods/nestworld-compat-ae2-0.8.0-all.jar mods/nestworld-ae2-race-detector-0.2.0-all.jar mods/nestworld-mek-churn-0.1.0.jar
  [ -f $E/nestworld-compat-ae2-0.7.7-all.jar ] && mv $E/nestworld-compat-ae2-0.7.7-all.jar mods/
  for f in $PF nestworld-autopin-error-counts.txt; do cp -p $E/pins-orig/$f world/data/$f; done
  cp $E/user_jvm_args.orig user_jvm_args.txt
  ok=1; for f in $PF; do cmp -s world/data/$f $LIVE/$f || { ok=0; echo "RESTORE MISMATCH $f"; }; done
  echo "pins restored byte-identical to live: $ok" | tee -a $E/result.txt
}
if [ "$PINS" = U ]; then
  sed -i -E '/^(ae2|megacells|mae2|ae2omnicells)$/d' world/data/nestworld-pinned-mods.txt
  cat $B/b1-u-be-pins.txt >> world/data/nestworld-be-pins.txt
  # autoPin would re-pin ae2 at startup (carried-over mod:ae2 error count) or mid-run (the negative control's races):
  # TEST-ONLY off for U runs, so the pin configuration stays the one under test
  sed -i 's/^-Dnestworld.autoPin=true$/-Dnestworld.autoPin=false/' user_jvm_args.txt
fi
[ "$LANE" = off ] && printf '\n-Dnestworld.compatAe2.mbLaneOff=true\n' >> user_jvm_args.txt
printf '\n-Dnestworld.compatAe2.fixtures=true\n' >> user_jvm_args.txt
sed -i '/^-Dnestworld.autoPinTest=true$/d' user_jvm_args.txt
cp world/data/nestworld-pinned-mods.txt world/data/nestworld-be-pins.txt $E/
grep -oE "^-Dnestworld[a-zA-Z.]*=[a-z0-9,:_]*" user_jvm_args.txt > $E/jvm-flags.txt
sha256sum mods/nestworld-*.jar mods/appliedenergistics2*.jar > $E/mods.sha256
ls crash-reports 2>/dev/null | sort > $E/crash-before.txt
(setsid nohup nice -n 10 taskset -c 0-7 ./run.sh nogui > $E/console.log 2>&1 < /dev/null &)
for i in $(seq 1 30); do [ -n "$(hjava)" ] && break; sleep 1; done; JP=$(hjava); echo "pid=$JP" > $E/pid.txt
until ./rcon.sh list 2>/dev/null | grep -q "players online"; do [ -z "$(hjava)" ] && { echo "JVM exited during start" | tee -a $E/result.txt; restore; grep -qx "agent-b1 $L" $B/PI_SMOKE_LOCK 2>/dev/null && rm -f $B/PI_SMOKE_LOCK; exit 1; }; sleep 5; done
L0=$(grep -ac "" logs/latest.log)
R() { ./rcon.sh "$@" 2>/dev/null | grep -vE "^$|^>"; }
R "nwcompatae2 status" > $E/status.txt; R "nestworld pins" > $E/pins-runtime-start.txt
[ -f $B/b1-registry.txt ] || { R "nestworld dumpregistries b1-registry.txt" > /dev/null; sleep 2; mv b1-registry.txt $B/b1-registry.txt; }
PICK=()
for xz in "-200 -200" "200 200" "-200 200" "200 -200" "-400 0" "400 0" "0 400" "0 -400" "-600 -600" "600 600"; do
  set -- $xz; R "forceload add $1 $2" >/dev/null
  D=$(R "nwbeready domain $(( $1 + 1 )) 300 $2 nestworld_mekchurn:driver" "nwbeready domain $1 301 $2 ae2:crafting_storage")
  echo "$xz :: $D" >> $E/positions.txt
  echo "$D" | grep -q "REGION#" && PICK+=("$1 $2") || R "forceload remove $1 $2" >/dev/null
  [ ${#PICK[@]} -ge 2 ] && break
done
echo "picked: ${PICK[*]}" >> $E/positions.txt
CM=("nwmekchurn pause" "nwmekchurn block ae2:1k_crafting_storage" "nwmekchurn period 1")
for xz in "${PICK[@]}"; do set -- $xz; CM+=("setblock $1 301 $2 ae2:1k_crafting_storage" "setblock $(( $1 + 1 )) 300 $2 nestworld_mekchurn:driver"); done
NY=300; NETS=("-800 808" "800 -808")
for nz in "${NETS[@]}"; do set -- $nz; NX=$1; NZ=$2; R "forceload add $NX $NZ" >/dev/null
  CM+=("fill $((NX-2)) $((NY-1)) $((NZ-3)) $((NX+9)) $((NY-1)) $((NZ+3)) minecraft:glass"
     "setblock $NX $NY $NZ ae2:creative_energy_cell" "setblock $((NX+1)) $NY $NZ ae2:controller" "setblock $((NX+2)) $NY $NZ ae2:drive"
     "setblock $((NX+3)) $NY $NZ ae2:interface" "setblock $((NX+4)) $NY $NZ ae2:crafting_unit" "setblock $((NX+5)) $NY $NZ ae2:1k_crafting_storage"
     "setblock $((NX+6)) $NY $NZ ae2:1k_crafting_storage"); done
R "${CM[@]}" > $E/setup.txt
# drives stay WITHOUT a cell: an idle CPU's CraftingCpuLogic.storeItems() would move the seeded diamonds into network
# storage (outside the world + cluster ledger)
sleep 60   # warm-up
for nz in "${NETS[@]}"; do set -- $nz; R "nwbeready domain $(( $1 + 5 )) $NY $2 ae2:crafting_storage" >> $E/positions.txt; done
{ R "nwae2mb"; R "nwcompatae2 mblane"; R "nwcompatae2 mblanefx items"; R "nestworld mspt"; } > $E/before.txt
R "nwae2b0 start" "nwcompatae2 gridcensus start" > $E/census-start.txt
R "nwmekchurn resume" >/dev/null
START=$(date +%s); n=0; alive=1; lastPoll=0
while [ $(( $(date +%s) - START )) -lt $T ]; do
  n=$((n+1)); t=$(( $(date +%s) - START ))
  set -- ${NETS[$(( (n / 4) % 2 ))]}; NX=$1; NZ=$2
  case $(( n % 4 )) in
    0) R "setblock $NX $NY $NZ air" >/dev/null; sleep 1; R "setblock $NX $NY $NZ ae2:creative_energy_cell" >/dev/null;;
    1) R "setblock $((NX+1)) $NY $NZ air" >/dev/null; sleep 1; R "setblock $((NX+1)) $NY $NZ ae2:controller" >/dev/null;;
    2) R "setblock $((NX+3)) $NY $NZ air" >/dev/null; sleep 1; R "setblock $((NX+3)) $NY $NZ ae2:interface" >/dev/null;;
    3) R "nwcompatae2 mblanefx seed $((NX+5)) $NY $NZ 7" >> $E/seed.txt; sleep 0.3
       R "setblock $((NX+5 + (n / 8) % 2)) $NY $NZ air" >/dev/null; sleep 1; R "setblock $((NX+5 + (n / 8) % 2)) $NY $NZ ae2:1k_crafting_storage" >/dev/null;;
  esac
  if [ $(( n % 60 )) = 30 ] && [ ${#PICK[@]} -ge 1 ]; then
    k=$(( (n / 60) % ${#PICK[@]} )); set -- ${PICK[$k]}; dx=$(( $1 + 1 )); dz=$2
    id=$(R "nwbeready domain $dx 300 $dz nestworld_mekchurn:driver" | grep -o "REGION#[0-9]*" | cut -d# -f2)
    if [ $(( (n / 120) % 2 )) = 0 ]; then ax=X; cut=$(( dx >> 4 )); else ax=Z; cut=$(( dz >> 4 )); fi
    [ -n "$id" ] && { out=$(R "nestworld splitat $id $ax $cut"); echo "t+$t splitat REGION#$id $ax $cut :: $out" >> $E/topology.txt; }
  fi
  if [ $(( n % 90 )) = 45 ]; then set -- ${NETS[$(( (n / 90) % 2 ))]}
    R "forceload remove $1 $2" >/dev/null; echo "t+$t unload network chunk $1 $2" >> $E/topology.txt; sleep 15; R "forceload add $1 $2" >/dev/null; echo "t+$(( $(date +%s) - START )) reload" >> $E/topology.txt; fi
  if [ $(( t - lastPoll )) -ge 60 ]; then lastPoll=$t
    for nz in "${NETS[@]}"; do set -- $nz; R "nwcompatae2 mblanefx collect $(( $1 + 5 )) $NY $2 8" >/dev/null; done
    { echo "== t+${t}s"; R "nwae2mb" | head -1; R "nwcompatae2 mblane" | cut -c1-600; R "nwcompatae2 mblanefx items"; R "nwmekchurn status" | head -1; R "nestworld status" | head -3; } >> $E/poll.txt; fi
  [ -z "$(hjava)" ] && { alive=0; break; }
  sleep 1
done
R "nwmekchurn pause" >/dev/null
R "nwae2b0 report" > $E/b0.txt; R "nwae2b0 callers" >> $E/b0.txt; R "nwae2b0 stop" >/dev/null
R "nwcompatae2 gridcensus report" > $E/census-end.txt; R "nwcompatae2 gridcensus devices" > $E/census-devices.txt
sleep 20   # quiescence
: > $E/items-final.txt
for r in 1 2 3; do for nz in "${NETS[@]}"; do set -- $nz; R "nwcompatae2 mblanefx collect $(( $1 + 5 )) $NY $2 8" >/dev/null; sleep 2; R "nwcompatae2 mblanefx items" >> $E/items-final.txt; done; sleep 5; done
{ R "nwae2mb" "nwcompatae2 mblane" "nwcompatae2 mblane calculators" "nwbeready" "nwbeready d2" "nwbeready failures" "nwae2static" "nwmekchurn status"; R "nwcompatae2 stats"; R "nestworld mspt"; R "nestworld bedomain"; } > $E/final.txt
R "nwae2mb stacks" > $E/mb-stacks.txt; R "nestworld pins" > $E/pins-runtime-end.txt
[ -x $B/b1-addon-probe.sh ] && [ "${PROBE:-0}" = 1 ] && $B/b1-addon-probe.sh $E
tail -n +$L0 logs/latest.log | grep -a "AE2-FAIL-ATTR\|AE2-BE-READY-DET\|MB lane\|Tick skipped\|Exception in server tick\|IllegalStateException\|ConcurrentModification\|Watchdog\|thread dump\|fell back\|RETAINED\|ERROR\]\|BE-DOMAIN\] task" | cut -c1-400 > $E/log-lines.txt
ls crash-reports 2>/dev/null | sort | comm -13 $E/crash-before.txt - > $E/crash-new.txt
S0=$(date +%s); R stop >/dev/null; for i in $(seq 1 90); do [ -z "$(hjava)" ] && break; sleep 2; done
[ -n "$(hjava)" ] && { echo "STOP HANG >180s" >> $E/topology.txt; kill -9 $(hjava); sleep 3; }
echo "alive=$alive seconds=$(( S0 - START )) stopSeconds=$(( $(date +%s) - S0 )) crashNew=$(wc -l < $E/crash-new.txt) pins=$PINS lane=$LANE" > $E/result.txt
restore; grep -qx "agent-b1 $L" $B/PI_SMOKE_LOCK 2>/dev/null && rm -f $B/PI_SMOKE_LOCK; cat $E/result.txt
