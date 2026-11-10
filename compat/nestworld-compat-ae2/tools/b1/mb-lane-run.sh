#!/bin/bash
# AE2-DEMAIN-001 B1 deterministic MB lane fixture (rig, AE2 15.4.10, Core 401 = no position pins → D2 fence counter-only).
# Fresh pristine world; TEST-ONLY narrow unpin U on the rig copy: 'ae2' removed from pinned mods, every other ae2:* BE id
# pinned per type (crafting_unit / crafting_storage / crafting_monitor unpinned). Lane OFF (negative control) and ON
# in one JVM via the fixture-only runtime switch. Usage: mb-lane-run.sh <label>
set -u
MODES=${MODES:-skip ise}; LANES=${LANES:-off on}   # e.g. MODES=drops LANES=on
B=/root/airis-2026-10-25; I=H401BEDOM; E=$B/evidence-be-ready/${1:-mblane}; mkdir -p $E; cd $B/$I
hjava() { for p in $(pgrep -x java); do [ "$(readlink /proc/$p/cwd)" = "$B/$I" ] && echo $p; done; }
until ( set -o noclobber; echo "agent-b1 ${1:-mblane}" > $B/RIG_LOCK ) 2>/dev/null; do grep -qx "agent-b1 ${1:-mblane}" $B/RIG_LOCK 2>/dev/null && break; sleep 30; done   # atomic (AGENT-RULES)
[ -n "$(hjava)" ] && { echo running; grep -qx "agent-b1 ${1:-mblane}" $B/RIG_LOCK 2>/dev/null && rm -f $B/RIG_LOCK; exit 1; }
rsync -a --delete $B/server/world/ world/; cp user_jvm_args.txt $E/user_jvm_args.before
grep -q "compatAe2.fixtures" user_jvm_args.txt || printf '\n-Dnestworld.compatAe2.fixtures=true\n' >> user_jvm_args.txt
# narrow unpin U (test-only, pristine copy untouched)
sed -i '/^ae2$/d' world/data/nestworld-pinned-mods.txt
sed -i '/^mod:ae2=/d' world/data/nestworld-autopin-error-counts.txt   # else Core autoPin re-pins ae2 at startup (run1 invalid)
for id in cable_bus cell_workbench charger chest condenser controller crank creative_energy_cell crystal_resonance_generator \
  debug_chunk_loader debug_cube_gen debug_energy_gen debug_item_gen debug_phantom_node dense_energy_cell drive energy_acceptor \
  energy_cell growth_accelerator inscriber interface io_port light_detector molecular_assembler paint pattern_provider \
  quantum_ring sky_chest sky_tank spatial_anchor spatial_io_port spatial_pylon vibration_chamber wireless_access_point; do
  echo "ae2:$id" >> world/data/nestworld-be-pins.txt; done
cp world/data/nestworld-pinned-mods.txt world/data/nestworld-be-pins.txt $E/
sha256sum mods/nestworld-*.jar > $E/mods.sha256; cat ACTIVE_CORE > $E/core.txt; ls crash-reports | sort > $E/crash-before.txt
[ -f console.log ] && mv console.log console-$(date +%H%M%S).log
(setsid unshare -n bash -c 'ip link set lo up; exec nice -n 10 taskset -c 0-7 bash run.sh nogui' > console.log 2>&1 < /dev/null &)
for i in $(seq 1 30); do [ -n "$(hjava)" ] && break; sleep 1; done; echo "pid=$(hjava)" > $E/pid.txt
until ./rcon.sh list 2>/dev/null | grep -q "players online"; do [ -z "$(hjava)" ] && { echo "JVM exited"; cp $E/user_jvm_args.before user_jvm_args.txt; grep -qx "agent-b1 ${1:-mblane}" $B/RIG_LOCK 2>/dev/null && rm -f $B/RIG_LOCK; exit 1; }; sleep 5; done
grep -a "Mixin apply failed\|InvalidInjectionException\|MixinTransformerError\|mixin.*error" logs/latest.log | cut -c1-300 > $E/mixin-errors.txt
R() { ./rcon.sh "$@" 2>/dev/null | grep -vE "^$|^>"; }
A="-200 300 -200"; Bp="200 300 200"
R "nestworld pins" > $E/pins-runtime.txt
R "forceload add -200 -200" "forceload add 200 200" >/dev/null; sleep 15
{ echo "config: U narrow unpin (crafting types unpinned), mixinErrors=$(wc -l < $E/mixin-errors.txt)"
  R "nwbeready domain $A ae2:crafting_storage" "nwbeready domain $Bp ae2:crafting_storage"
  for lane in $LANES; do
    R "nwcompatae2 mblanefx $lane"
    for mode in $MODES; do
      R "nwcompatae2 mblanefx $mode $A $Bp"
      for i in $(seq 1 60); do s=$(R "nwcompatae2 mblanefx status"); case "$s" in *REPRODUCED*|*PASS*|*FAIL*|*invalid*) break;; esac; sleep 2; done
      echo "== lane=$lane mode=$mode"; echo "$s"; sleep 4
    done
  done
  echo "== final"; R "nwcompatae2 mblanefx items" "nwae2mb" "nwcompatae2 mblane" "nwcompatae2 mblane calculators" "nwae2static" "nwbeready" "nwbeready failures"
  R "nwcompatae2 stats" | grep -o "teardown{[^}]*}"; } > $E/out.txt 2>&1
R "nwae2mb stacks" > $E/mb-stacks.txt
tail -n +1 logs/latest.log | grep -a "B1 lane fixture\|MB lane\|IllegalStateException\|Exception in server tick\|Watchdog\|BE-DOMAIN\] task" | cut -c1-400 > $E/log-lines.txt
ls crash-reports | sort | comm -13 $E/crash-before.txt - > $E/crash-new.txt
./rcon.sh stop >/dev/null 2>&1; for i in $(seq 1 40); do [ -z "$(hjava)" ] && break; sleep 3; done; P=$(hjava); [ -n "$P" ] && { echo "STOP HANG" >> $E/out.txt; kill -9 $P; }
cp $E/user_jvm_args.before user_jvm_args.txt; echo "crashNew=$(wc -l < $E/crash-new.txt)" >> $E/out.txt; cat $E/out.txt
grep -qx "agent-b1 ${1:-mblane}" $B/RIG_LOCK 2>/dev/null && rm -f $B/RIG_LOCK
