#!/bin/bash
# B10-shape soak on /root/pi-smoke behind the live gate: PI_SMOKE_LOCK taken atomically FIRST (so the guard can only ever
# stop our own JVM), then start only after live shows 0 players for >= 5 minutes; the guard stops pi-smoke gracefully
# (run marked invalid, pins restored by b10-soak.sh) if a player joins / live rcon fails.
# Usage: b10-gated.sh <label> <seconds> <P|U> <on|off>
B=/root/airis-2026-10-25; EV=$B/evidence-be-ready; L=$1; LK=$B/PI_SMOKE_LOCK
until ( set -o noclobber; echo "agent-b1 $L" > $LK ) 2>/dev/null; do grep -qx "agent-b1 $L" $LK 2>/dev/null && break; sleep 30; done
echo "lock taken $(date +%T)"
while true; do
  $B/b1-live-gate.sh wait
  grep -qx "agent-b1 $L" $LK || ( set -o noclobber; echo "agent-b1 $L" > $LK ) 2>/dev/null || { echo "lock lost"; exit 1; }
  $B/b10-soak.sh "$@" & RP=$!; $B/b1-live-gate.sh guard /root/pi-smoke $EV/$L $RP $LK "agent-b1 $L" & GP=$!
  wait $RP; kill $GP 2>/dev/null
  if [ -f $EV/$L/INVALID.txt ]; then N=$EV/invalid-$L-player-joined-$(date +%H%M); mv $EV/$L $N; echo "INVALID -> $N; retrying"
    until ( set -o noclobber; echo "agent-b1 $L" > $LK ) 2>/dev/null; do grep -qx "agent-b1 $L" $LK 2>/dev/null && break; sleep 30; done
  else echo "SOAK DONE $(date +%T)"; break; fi
done
grep -qx "agent-b1 $L" $LK 2>/dev/null && rm -f $LK
