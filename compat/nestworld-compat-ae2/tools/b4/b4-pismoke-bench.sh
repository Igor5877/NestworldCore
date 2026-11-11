#!/bin/bash
# WP3-B MAIN benchmark on the production-pack copy /root/pi-smoke (owner 2026-11-10): production pins (byte-identical
# to live), one JVM, transit OFF vs ON by the fixture-only runtime switch, per phase: grid census device ms (MAIN),
# bustransit counters (incl. MM linked-port classification: linkedSameDomain transit vs linkedCrossDomain fail-closed),
# spark Server-thread profile saved locally (no upload). Test-only additions removed afterwards: addon 0.9.0 dev jar in
# place of 0.7.7, -Dnestworld.compatAe2.busTransit / fixtures. Gate: PI_SMOKE_LOCK atomic + 0 live players >= 5 min.
# Usage: b4-pismoke-bench.sh <label> <addon-jar> <seconds-per-phase>
set -u
P=/root/pi-smoke; B=/root/airis-2026-10-25; L=$1; JAR=$2; S=${3:-300}; E=$B/evidence-be-ready/$L
[ ! -e "$E" ] || { echo "Refusing to overwrite evidence: $E"; exit 1; }
mkdir -p "$E"; cd "$P"
LIVE=/root/project-infinity/world/data; PF="nestworld-be-pins.txt nestworld-pinned-mods.txt nestworld-pins.txt nestworld-pin-reasons.txt"
hjava() { for p in $(pgrep -x java); do [ "$(readlink /proc/$p/cwd)" = "$P" ] && echo $p; done; }
live0() { (cd /root/project-infinity && ./rcon.sh list 2>/dev/null | grep -q "There are 0 of"); }
until ( set -o noclobber; echo "agent WP3-B $L" > $B/PI_SMOKE_LOCK ) 2>/dev/null; do sleep 30; done
# Release our reservation on failures before any test files are changed.
trap 'grep -qxF "agent WP3-B $L" "$B/PI_SMOKE_LOCK" 2>/dev/null && rm -f "$B/PI_SMOKE_LOCK"' EXIT
n=0; until [ $n -ge 6 ]; do if live0; then n=$((n+1)); else n=0; echo "wait live players $(date +%T)"; fi; [ $n -ge 6 ] || sleep 60; done
[ -n "$(hjava)" ] && { echo "pi-smoke JVM running"; rm -f $B/PI_SMOKE_LOCK; exit 1; }
for f in $PF; do cmp -s world/data/$f $LIVE/$f || { echo "PRECONDITION FAIL: $f differs from live" | tee $E/result.txt; rm -f $B/PI_SMOKE_LOCK; exit 1; }; done
grep -qx 'ae2' world/data/nestworld-pinned-mods.txt || { echo "AE2 production MAIN pin missing"; exit 1; }
[ -f mods/nestworld-compat-ae2-0.7.7-all.jar ] && [ -r "$JAR" ] || { echo "Expected baseline addon/candidate missing"; exit 1; }
mkdir -p $E/orig; for f in $PF nestworld-autopin-error-counts.txt; do cp -p world/data/$f $E/orig/; done
cp -p user_jvm_args.txt $E/orig/; ls mods | sort > $E/orig/mods.list
restore() {
  set +e
  JP=$(hjava); [ -n "$JP" ] && { ./rcon.sh stop >/dev/null 2>&1; for i in $(seq 1 60); do [ -z "$(hjava)" ] && break; sleep 3; done; JP=$(hjava); [ -n "$JP" ] && kill -9 $JP; }
  rm -f mods/nestworld-compat-ae2-0.9.0-dev.jar
  [ -f $E/orig/nestworld-compat-ae2-0.7.7-all.jar ] && cp -p $E/orig/nestworld-compat-ae2-0.7.7-all.jar mods/
  for f in $PF nestworld-autopin-error-counts.txt; do cp -p $E/orig/$f world/data/$f; done
  cp -p $E/orig/user_jvm_args.txt user_jvm_args.txt
  ok=1; for f in $PF; do cmp -s world/data/$f $LIVE/$f || { ok=0; echo "RESTORE MISMATCH $f"; }; done
  ls mods | sort | cmp -s - $E/orig/mods.list || { ok=0; echo "RESTORE MISMATCH mods"; }
  echo "restored: pins byte-identical to live + addon 0.7.7 + args: $ok" | tee -a $E/result.txt
  grep -qxF "agent WP3-B $L" $B/PI_SMOKE_LOCK 2>/dev/null && rm -f $B/PI_SMOKE_LOCK
}
trap restore EXIT
mv mods/nestworld-compat-ae2-0.7.7-all.jar $E/orig/; cp "$JAR" mods/nestworld-compat-ae2-0.9.0-dev.jar
printf '\n-Dnestworld.compatAe2.busTransit=true\n-Dnestworld.compatAe2.fixtures=true\n' >> user_jvm_args.txt
sha256sum mods/nestworld-*.jar mods/appliedenergistics2*.jar > $E/mods.sha256; ls crash-reports 2>/dev/null | sort > $E/crash-before.txt
(setsid nohup nice -n 10 taskset -c 0-7 ./run.sh nogui > $E/console.log 2>&1 < /dev/null &)
for i in $(seq 1 30); do [ -n "$(hjava)" ] && break; sleep 1; done
deadline=$((SECONDS+600))
until ./rcon.sh list 2>/dev/null | grep -q "players online"; do
  live0 || { echo "INVALID: live gate closed during startup" > "$E/INVALID.txt"; exit 2; }
  [ "$SECONDS" -lt "$deadline" ] || { echo "Startup timeout" >> "$E/result.txt"; exit 1; }
  [ -z "$(hjava)" ] && { echo "JVM exited during start" | tee -a $E/result.txt; exit 1; }; sleep 5
done
R() { ./rcon.sh "$@" 2>/dev/null | grep -vE "^$|^>"; }
check_live() {
  if ! live0; then
    echo "INVALID: live player/restart/RCON failure $(date -Is)" | tee "$E/INVALID.txt"
    exit 2
  fi
}
# A copied production world is the workload: no synthetic barrels, unpinning or topology changes.
# These counters describe natural traffic; no per-item throughput claim is inferred from op counts.
JP=$(hjava)
main_cpu() {
  python3 - "$JP" <<'PY'
import json, os, sys, time
from pathlib import Path
p = Path('/proc') / sys.argv[1]
out = {'monotonic': time.monotonic()}
regions = {}
for t in (p / 'task').iterdir():
    try:
        name = t.joinpath('comm').read_text().strip()
        fields = t.joinpath('stat').read_text().rsplit(')', 1)[1].split()
        cpu = (int(fields[11]) + int(fields[12])) / os.sysconf('SC_CLK_TCK')
        if name == 'Server thread':
            out['main_cpu_seconds'] = cpu
        elif name.startswith('NestWorld-Reg'):
            regions[t.name] = cpu
    except FileNotFoundError:
        pass
out['region_cpu_seconds_by_tid'] = regions
out['region_cpu_seconds'] = sum(regions.values())
out['memory'] = {line.split(':', 1)[0]: line.split(':', 1)[1].strip()
                 for line in p.joinpath('status').read_text().splitlines() if line.startswith(('VmRSS:', 'VmSize:'))}
print(json.dumps(out))
PY
}
pause_checked() {
  local remaining=$1
  while [ "$remaining" -gt 0 ]; do
    check_live
    local step=5; [ "$remaining" -ge 5 ] || step=$remaining
    sleep "$step"; remaining=$((remaining-step))
  done
  check_live
}
{
echo "== status"; R "nwcompatae2 status"; R "nestworld pins"; pause_checked 120
phase=0
for mode in off on off on; do
  phase=$((phase+1)); D=$E/phase-$phase-$mode; mkdir "$D"
  check_live
  echo "== phase transit=$mode $(date +%T)"; R "nwcompatae2 bustransitfx $mode"; pause_checked 20
  R "nwcompatae2 bustransitfx reset" > "$D/reset.txt"
  R "nwcompatae2 gridcensus start" > "$D/start.txt"
  R "nwcompatae2 bustransit" > "$D/transit-before.txt"
  /usr/lib/jvm/java-17-openjdk-amd64/bin/jstat -gc "$JP" > "$D/gc-before.txt" 2>&1 || true
  R "nwcompatae2 bustransitfx perf start" > "$D/perf-start.txt"
  main_cpu > "$D/cpu-before.json"
  R "spark profiler start --interval 4" > "$D/profile-start.txt"
  elapsed=0
  while [ "$elapsed" -lt "$S" ]; do
    check_live
    { date -Is; R "nestworld mspt"; R "nestworld status"; R "nwcompatae2 bustransit"; } >> "$D/samples.txt"
    step=5; [ $((S-elapsed)) -ge 5 ] || step=$((S-elapsed))
    pause_checked "$step"; elapsed=$((elapsed+step))
  done
  R "spark profiler stop --save-to-file" > "$D/profile-stop.txt"
  R "nwcompatae2 bustransitfx perf report" > "$D/perf.txt"
  /usr/lib/jvm/java-17-openjdk-amd64/bin/jstat -gc "$JP" > "$D/gc-after.txt" 2>&1 || true
  main_cpu > "$D/cpu-after.json"
  R "nwcompatae2 gridcensus report" > "$D/census.txt"
  R "nwcompatae2 gridcensus devices" > "$D/devices.txt"
  R "spark tps" > "$D/tps.txt"
  R "nwcompatae2 bustransit" > "$D/transit-after.txt"
  R "nwcompatae2 bustransit grids" > "$D/grids.txt"
  cat "$D/census.txt" "$D/devices.txt" "$D/tps.txt" "$D/transit-after.txt"
done
} > $E/out.txt 2>&1
ls crash-reports 2>/dev/null | sort | comm -13 $E/crash-before.txt - > $E/crash-new.txt; echo "crashNew=$(wc -l < $E/crash-new.txt)" >> $E/out.txt
cp logs/latest.log $E/latest.log; ls -t config/spark/ 2>/dev/null | head -3 >> $E/out.txt
cat $E/out.txt
