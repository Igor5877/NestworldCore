#!/bin/bash
# AE2 addon canary — READ-ONLY baseline / interval snapshot (owner-run on the canary instance).
# Usage: canary-baseline.sh <server dir> [label]
#   optional live data over RCON: RCON_PORT=<port> RCON_PASS=<password> (requires python3)
# Writes <server dir>/canary-evidence/<label>-<UTC timestamp>.txt and prints its path. Changes nothing else.
set -u
D=${1:?server dir}; LABEL=${2:-baseline}
OUT_DIR="$D/canary-evidence"; mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/$LABEL-$(date -u +%Y%m%dT%H%M%SZ).txt"
{
echo "== canary snapshot: $LABEL  $(date -u +%FT%TZ)  host=$(hostname)"
echo "== Core (Forge/NestWorld) libraries"
for j in "$D"/libraries/net/minecraftforge/forge/*/forge-*-universal.jar; do
  [ -f "$j" ] || continue
  echo "$(sha256sum "$j" | cut -c1-64)  $(basename "$j")"
  unzip -p "$j" META-INF/MANIFEST.MF 2>/dev/null | grep -E "^(Git-Commit|Git-Dirty|Git-Branch|Implementation-Version):" | tr -d '\r' | sed 's/^/    /'
  unzip -l "$j" 2>/dev/null | grep -q "net/nestworld/region/NestworldOwnership.class" && echo "    NestworldOwnership contract: present" || echo "    NestworldOwnership contract: ABSENT (addon would refuse ALL P2P forwards)"
done
echo "== launcher"; tail -1 "$D/run.sh" 2>/dev/null
echo "ACTIVE Core: $(grep -o "forge/[^/]*/unix_args" "$D/run.sh" 2>/dev/null | cut -d/ -f2)"
echo "== JVM args (user_jvm_args.txt)"; cat "$D/user_jvm_args.txt" 2>/dev/null
grep -q "compatAe2.fixtures=true" "$D/user_jvm_args.txt" 2>/dev/null && echo "!! WARNING: fixtures flag present — NOT a production canary"
grep -q "freeRunningRegions=true" "$D/user_jvm_args.txt" 2>/dev/null && echo "freeRunningRegions: true (JVM flag)" || echo "freeRunningRegions: not set by JVM flag"
echo "== mods (sha256 + file)"
for j in "$D"/mods/*.jar; do echo "$(sha256sum "$j" | cut -c1-64)  $(basename "$j")"; done
echo "== key mods"
ls "$D"/mods/ 2>/dev/null | grep -iE "appliedenergistics|ae2|megacells|extendedae|mekanism|solarflux|pipez|nestworld-compat" | sed 's/^/  /'
echo "== pin policy (world data)"
PF=$(ls "$D"/*/data/nestworld-*pin*.txt "$D"/*/data/nestworld-be-cascade-safe.txt 2>/dev/null)
if [ -z "$PF" ]; then echo "(no nestworld pin files found in <world>/data)"; else for f in $PF; do echo "-- $f"; cat "$f"; done; fi
if [ -n "${RCON_PORT:-}" ] && [ -n "${RCON_PASS:-}" ]; then
  echo "== live (RCON)"
  python3 - "$RCON_PORT" "$RCON_PASS" <<'PY'
import socket, struct, sys
port, pw = int(sys.argv[1]), sys.argv[2]
def pkt(i, t, b): b = b.encode() + b'\x00\x00'; return struct.pack('<iii', len(b) + 8, i, t) + b
def rd(s):
    h = b''
    while len(h) < 4:
        c = s.recv(4 - len(h))
        if not c: raise ConnectionError('closed')
        h += c
    n = struct.unpack('<i', h)[0]; d = b''
    while len(d) < n:
        c = s.recv(n - len(d))
        if not c: raise ConnectionError('closed')
        d += c
    return struct.unpack('<i', d[:4])[0], d[8:-2].decode('utf-8', 'replace')
s = socket.create_connection(('127.0.0.1', port), timeout=60); s.send(pkt(1, 3, pw)); rd(s)
for c in ['nwcompatae2 stats', 'nestworld status', 'nestworld pins', 'forceload query']:
    try:
        s.send(pkt(2, 2, c)); s.send(pkt(3, 0, '')); out = []
        while True:
            rid, body = rd(s)
            if rid == 3: break
            out.append(body)
        print('> ' + c); print(''.join(out))
    except Exception as e:
        print('> ' + c + ' FAILED: ' + str(e)); break
PY
fi
echo "== latest log signals"
L="$D/logs/latest.log"; [ -f "$L" ] || L="$D/console.log"
grep -E "startup gate|P2P forwarding ownership|compatibility manifest" "$L" 2>/dev/null | tail -5
for p in "Cannot modify energy requesters" "ConcurrentModificationException" "NullPointerException" "TickHandler.processQueue" "nestworld-compat-ae2.*(ERROR|WARN)" "Watchdog|ServerHangWatchdog" "tick-skipped|skipping this tick"; do
  printf '%-48s %s\n' "$p" "$(grep -cE "$p" "$L" 2>/dev/null)"
done
echo "== crash reports"; ls -t "$D"/crash-reports 2>/dev/null | head -5
} > "$OUT" 2>&1
echo "$OUT"
