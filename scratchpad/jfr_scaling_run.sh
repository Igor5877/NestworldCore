#!/bin/bash
# 2026-08-28 packet-amplification scaling measurement across n=50/100/150.
# Fresh server restart per tier (clean JFR recording.jfr from boot each time).
set -e
cd /root/nestworldcore
BENCH=/root/bench-vanilla-217
SCRATCH=/tmp/claude-0/-root-nestworldcore/81b13e0b-9ff1-4e69-8c1f-48a58da07d0f/scratchpad

restart_server() {
  PID=$(pgrep -f "java.*unix_args.txt" | head -1)
  if [ -n "$PID" ]; then
    python3 -c "
import sys; sys.path.insert(0, '.')
from rcon_batch import RconConn
try:
    c = RconConn('127.0.0.1', 25577, 'ftbrepro')
    c.cmd('stop')
    c.close()
except Exception:
    pass
" || true
    for i in $(seq 1 30); do
      if ! ps aux | grep -q "[u]nix_args.txt"; then break; fi
      sleep 3
    done
  fi
  rm -f $BENCH/recording.jfr
  cd $BENCH && nohup ./run.sh nogui > console.log 2>&1 & disown
  cd /root/nestworldcore
  for i in $(seq 1 40); do
    if grep -q "Done (" $BENCH/console.log 2>/dev/null; then break; fi
    sleep 5
  done
}

run_one_tier() {
  N=$1
  LABEL="JfrScale-n${N}"
  echo ""
  echo "=== TIER: n=$N ==="
  restart_server
  python3 -c "
import sys; sys.path.insert(0, '.')
from rcon_batch import RconConn
c = RconConn('127.0.0.1', 25577, 'ftbrepro')
print(c.cmd('kill @e[type=minecraft:armor_stand]'))
c.close()
"
  python3 -c "
import sys; sys.path.insert(0, '.'); sys.path.insert(0, 'scratchpad')
from scaling_test import run_tier
r = run_tier($N, 'cluster', hold_seconds=45, label='$LABEL')
print('RESULT_TPS', r.get('tps'))
print('RESULT_MSPT', r.get('mspt'))
print('RESULT_WATCHDOG', r.get('watchdog'))
"
  # Dump the final JFR state (works whether or not the process crashed later -- captured now)
  PID=$(pgrep -f "java.*unix_args.txt" | head -1)
  if [ -n "$PID" ]; then
    jcmd $PID JFR.dump filename=$SCRATCH/jfr_scale_n${N}.jfr 2>&1 | tail -1
  else
    echo "server not running after tier -- checking if recording.jfr survived a crash dump"
    if [ -f "$BENCH/recording.jfr" ]; then
      cp $BENCH/recording.jfr $SCRATCH/jfr_scale_n${N}.jfr
    fi
  fi
}

for N in 50 100 150; do
  run_one_tier $N
done

echo ""
echo "=== ALL TIERS DONE ==="
