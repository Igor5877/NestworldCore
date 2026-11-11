#!/bin/bash
# Targeted real MM machine cases, AFTER the production-pack A/B. Never touches live except read-only list gate.
# Usage: b4-pismoke-mm.sh <new-evidence-label> <reobfuscated-addon-jar>
set -u
B=/root/airis-2026-10-25; P=/root/pi-smoke; L=$1; JAR=$2; E=$B/evidence-be-ready/$L
T=$(dirname "$(readlink -f "$0")")
[ ! -e "$E" ] && [ -r "$JAR" ] || exit 1
mkdir "$E"; cd "$P" || exit 1
LK=$B/PI_SMOKE_LOCK; OWNER="agent WP3-B $L"
until (set -o noclobber; echo "$OWNER" > "$LK") 2>/dev/null; do sleep 30; done
release() { grep -qxF "$OWNER" "$LK" 2>/dev/null && rm -f "$LK"; }
trap release EXIT
hjava() { for p in $(pgrep -x java); do [ "$(readlink /proc/$p/cwd)" = "$P" ] && echo "$p"; done; }
live0() { (cd /root/project-infinity && ./rcon.sh list 2>/dev/null | python3 "$T/live-player-gate.py" "${WP3B_IGNORE_PLAYER:-}"); }
guard() { live0 || { echo "INVALID: live gate closed" > "$E/INVALID.txt"; exit 2; }; }
pause() { local left=$1; while [ "$left" -gt 0 ]; do guard; sleep 5; left=$((left-5)); done; guard; }
R() { ./rcon.sh "$@" 2>/dev/null | grep -vE '^$|^>'; }
F() { R "nwcompatae2 bustransitfx $*"; }
n=0
while [ "$n" -lt 11 ]; do
  if live0; then n=$((n+1)); else n=0; fi
  [ "$n" -ge 11 ] || sleep 30
done
echo "MM gate open; explicitly ignored player=${WP3B_IGNORE_PLAYER:-none}"
[ -z "$(hjava)" ] || { echo 'test JVM already running'; exit 1; }
PF='nestworld-be-pins.txt nestworld-pinned-mods.txt nestworld-pins.txt nestworld-pin-reasons.txt'
for f in $PF; do cmp -s "world/data/$f" "/root/project-infinity/world/data/$f" || { echo "pin precondition failed $f"; exit 1; }; done
grep -qx ae2 world/data/nestworld-pinned-mods.txt || exit 1
[ -f mods/nestworld-compat-ae2-0.7.7-all.jar ] || exit 1
mkdir "$E/orig"
cp -p user_jvm_args.txt mods/nestworld-compat-ae2-0.7.7-all.jar "$E/orig/"
for f in $PF nestworld-autopin-error-counts.txt; do cp -p "world/data/$f" "$E/orig/" || exit 1; done
restore() {
  JP=$(hjava)
  if [ -n "$JP" ]; then
    R stop >/dev/null
    for i in $(seq 1 60); do [ -z "$(hjava)" ] && break; sleep 3; done
    JP=$(hjava); [ -z "$JP" ] || kill -9 $JP
  fi
  cp -p "$E/orig/user_jvm_args.txt" user_jvm_args.txt
  rm -f mods/nestworld-compat-ae2-wp3b-mm.jar
  cp -p "$E/orig/nestworld-compat-ae2-0.7.7-all.jar" mods/
  ok=1
  for f in $PF nestworld-autopin-error-counts.txt; do
    cp -p "$E/orig/$f" "world/data/$f" || ok=0
    cmp -s "$E/orig/$f" "world/data/$f" || ok=0
  done
  echo "restored=$ok" >> "$E/result.txt"
  release
}
trap restore EXIT
mv mods/nestworld-compat-ae2-0.7.7-all.jar "$E/orig/baseline.jar"
cp "$JAR" mods/nestworld-compat-ae2-wp3b-mm.jar || exit 1
printf '\n-Dnestworld.compatAe2.busTransit=true\n-Dnestworld.compatAe2.fixtures=true\n' >> user_jvm_args.txt
sha256sum mods/nestworld-compat-ae2-wp3b-mm.jar mods/appliedenergistics2*.jar mods/MasterfulMachinery*.jar > "$E/mods.sha256"
(setsid nohup nice -n 10 taskset -c 0-7 ./run.sh nogui > "$E/console.log" 2>&1 < /dev/null &)
pause 10
deadline=$((SECONDS+600))
until R list | grep -q 'players online'; do
  guard; [ "$SECONDS" -lt "$deadline" ] && [ -n "$(hjava)" ] || exit 1; sleep 5
done
snapshot() {
  for i in $(seq 1 12); do
    result=$(F mmsnapshot)
    case "$result" in *requested*) break;; esac
    pause 5
  done
  pause 5; F mmstatus
}
{
R 'nwcompatae2 status'; R 'nestworld pins'; pause 30
for mode in off on; do
  F "$mode"; F reset
  if [ "$mode" = off ]; then X=200; else X=296; fi
  echo "== MM $mode real recipe: 128 lava crystals -> 128 infused crystals"
  R 'forceload add -32 -16 -1 -1' "forceload add $((X-16)) 1984 $((X+16)) 2032"
  pause 10
  R "clone -19 64 -7 -18 65 -6 $X 300 2000 replace"
  F mmsetup "$X" 300 2000
  for i in $(seq 1 6); do
    pause 5; snapshot
  done
  F mmsnapshot; pause 5; F mmstatus
  R 'nwcompatae2 bustransit'; R 'nwcompatae2 bustransit strategies'; R 'nwcompatae2 bustransit grids'
done
echo '== MM ON unload/reload while export holds resources'
X=392; F on; F hold on
R 'forceload add 376 1984 408 2032'; pause 10
R 'clone -19 64 -7 -18 65 -6 392 300 2000 replace'; F mmsetup 392 300 2000
pause 10; F mmpause on; R 'nwcompatae2 bustransit records'
R 'forceload remove 376 1984 408 2032'; pause 30
R 'nwcompatae2 bustransit'
R 'forceload add 376 1984 408 2032'; pause 15
F hold off; F mmpause off
for i in $(seq 1 6); do pause 5; snapshot; done
R 'nwcompatae2 bustransit'; R 'nwcompatae2 bustransit records'
R 'nwcompatae2 bustransit strategies'
echo '== final MM log scan'
grep -aE 'Tick skipped|ItemPortHandler|MM targeted fixture failure|bus transit .*failed|Exception in server tick' logs/latest.log | cut -c1-500 || true
} > "$E/out.txt" 2>&1
cp logs/latest.log "$E/latest.log"
echo 'completed=1 (see physical accounting and controller formed/active state; not an automatic PASS)' > "$E/result.txt"
cat "$E/out.txt"
