#!/bin/bash
# 2026-08-28 steady-state packet-amplification sweep: 50/75/100/125/140/150.
# Fresh server restart per tier. JFR always on. Measures only after an extra settle period
# past connection completion (steady-state, not ramp-up).
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
  LABEL="SteadySweep-n${N}"
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
import sys, json; sys.path.insert(0, '.'); sys.path.insert(0, 'scratchpad')
from scaling_test import run_steady_state_tier
r = run_steady_state_tier($N, settle_seconds=15, hold_seconds=30, label='$LABEL')
with open('$SCRATCH/steady_result_n${N}.json', 'w') as f:
    json.dump(r, f)
print('RESULT_TPS', r.get('tps'))
print('RESULT_MSPT', r.get('mspt'))
print('RESULT_WATCHDOG', r.get('watchdog'))
print('RESULT_WINDOW', r.get('steady_state_start_wall'), r.get('steady_state_end_wall'))
"
  PID=$(pgrep -f "java.*unix_args.txt" | head -1)
  if [ -n "$PID" ]; then
    jcmd $PID JFR.dump filename=$SCRATCH/jfr_steady_n${N}.jfr 2>&1 | tail -1
  elif [ -f "$BENCH/recording.jfr" ]; then
    cp $BENCH/recording.jfr $SCRATCH/jfr_steady_n${N}.jfr
  fi
}

for N in 50 75 100 125; do
  run_one_tier $N
done

echo ""
echo "=== ALL TIERS DONE ==="
