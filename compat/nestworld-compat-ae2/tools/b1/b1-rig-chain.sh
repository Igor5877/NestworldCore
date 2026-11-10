#!/bin/bash
B=/root/airis-2026-10-25; RIG=$B/H401BEDOM; EV=$B/evidence-be-ready
# Owner decision 2026-11-09: rig tests are allowed while live has players (RIG_LOCK, nice/taskset, one JVM);
# the live gate/guard applies only with LIVEGATE=1 (pi-smoke soak uses it always)
run() { L=$1; shift; [ "${LIVEGATE:-0}" = 1 ] && $B/b1-live-gate.sh wait; echo "=== $(date +%T) $L $*"
  "$@" > $B/chain-$L.log 2>&1 & RP=$!; GP=; [ "${LIVEGATE:-0}" = 1 ] && { $B/b1-live-gate.sh guard $RIG $EV/$L $RP & GP=$!; }
  wait $RP; [ -n "$GP" ] && kill $GP 2>/dev/null; tail -40 $B/chain-$L.log
  # early-exit cleanup (mb-lane-run.sh exits before its restore when the JVM dies during start)
  for p in $(pgrep -x java); do [ "$(readlink /proc/$p/cwd)" = "$RIG" ] && { echo "rig JVM still up after $L"; exit 1; }; done
  [ -f $EV/$L/user_jvm_args.before ] && cp $EV/$L/user_jvm_args.before $RIG/user_jvm_args.txt
  grep -qx "agent-b1 $L" $B/RIG_LOCK 2>/dev/null && rm -f $B/RIG_LOCK
  [ -f $EV/$L/INVALID.txt ] && { mv $EV/$L $EV/invalid-$L-player-joined-$(date +%H%M); echo "INVALID $L (player joined)"; }; }
run b1-d4a-teardown-prod-lane-on $B/b1-d4a-run.sh b1-d4a-teardown-prod-lane-on teardown prod on
run b1-d4a-tdc-prod-lane-on $B/b1-d4a-run.sh b1-d4a-tdc-prod-lane-on teardownclosure prod on
run b1-d4a-tdc-unpinned-lane-on $B/b1-d4a-run.sh b1-d4a-tdc-unpinned-lane-on teardownclosure unpinned on
run b1-d4a-teardown-prod-lane-off $B/b1-d4a-run.sh b1-d4a-teardown-prod-lane-off teardown prod off
run b1-d4a-tdc-unpinned-lane-off $B/b1-d4a-run.sh b1-d4a-tdc-unpinned-lane-off teardownclosure unpinned off
run b1-drops-U-lane-on env MODES=drops LANES=on $B/mb-lane-run.sh b1-drops-U-lane-on
run b1-drops-U-lane-off env MODES=drops LANES=off $B/mb-lane-run.sh b1-drops-U-lane-off
echo "CHAIN DONE $(date +%T)"
