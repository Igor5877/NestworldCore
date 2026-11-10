#!/bin/bash
# AE2-DEMAIN-001 B4-B6 rig bring-up (H401BEDOM, AE2 15.4.10, Core 401). PRODUCTION-LIKE pins: 'ae2' stays mod-pinned
# (no unpin needed: the transit moves the NEIGHBOUR half into the barrel's own region). Fresh pristine world unless
# KEEP=1 (restart case). Backs up the rig's current addon jar + user_jvm_args.txt into $BK (per run, first call only);
# the caller's EXIT trap restores them (rig-restore.sh). Usage: rig-up.sh <evidence-label> <addon-jar> [extra -D flags...]
set -u
B=/root/airis-2026-10-25; I=H401BEDOM; E=$B/evidence-be-ready/$1; BK=$B/b4-backup-$1; JAR=$2; shift 2; mkdir -p $E; cd $B/$I
hjava() { for p in $(pgrep -x java); do [ "$(readlink /proc/$p/cwd)" = "$B/$I" ] && echo $p; done; }
[ -n "$(hjava)" ] && { echo running; exit 1; }
if [ ! -d $BK ]; then mkdir -p $BK; cp mods/nestworld-compat-ae2-test-all.jar user_jvm_args.txt config/ae2/common.json $BK/; fi
cp "$JAR" mods/nestworld-compat-ae2-test-all.jar
[ "${KEEP:-0}" = 1 ] || rsync -a --delete $B/server/world/ world/
grep -v -E "^-Dnestworld\.compatAe2\.(busTransit|appliedEnh)" $BK/user_jvm_args.txt > user_jvm_args.txt   # never inherit another run's test flags
grep -q "compatAe2.fixtures=true" user_jvm_args.txt || printf '\n-Dnestworld.compatAe2.fixtures=true\n' >> user_jvm_args.txt
for f in "$@"; do echo "$f" >> user_jvm_args.txt; done
cp user_jvm_args.txt $E/user_jvm_args.used; cp world/data/nestworld-pinned-mods.txt world/data/nestworld-be-pins.txt $E/ 2>/dev/null
sha256sum mods/nestworld-*.jar > $E/mods.sha256; cat ACTIVE_CORE > $E/core.txt; ls crash-reports | sort > $E/crash-before.txt
[ -f console.log ] && mv console.log console-$(date +%H%M%S).log
(setsid unshare -n bash -c 'ip link set lo up; exec nice -n 10 taskset -c 0-7 bash run.sh nogui' > console.log 2>&1 < /dev/null &)
for i in $(seq 1 30); do [ -n "$(hjava)" ] && break; sleep 1; done; echo "pid=$(hjava)" >> $E/pid.txt
until ./rcon.sh list 2>/dev/null | grep -q "players online"; do [ -z "$(hjava)" ] && { cp console.log $E/console-failed.log; echo "JVM exited"; exit 1; }; sleep 5; done
grep -a "Mixin apply failed\|InvalidInjectionException\|MixinTransformerError\|startup gate FAIL" logs/latest.log | cut -c1-300 > $E/mixin-errors.txt
echo "up; mixinErrors=$(wc -l < $E/mixin-errors.txt)"
