#!/bin/bash
# Live-player gate (coordinator rule 2026-11-09 23:2x): heavy tests only after "There are 0" for >= 5 consecutive
# minutes; stop the test immediately if a player joins (rcon failure / restart window counts as NOT 0).
# Usage: b1-live-gate.sh wait | guard <server dir> <evidence dir> <run pid>
zero() { (cd /root/project-infinity && ./rcon.sh list 2>/dev/null | grep -q "There are 0 of"); }
case $1 in
  wait) n=0; while [ $n -lt 11 ]; do if zero; then n=$((n+1)); else n=0; fi; [ $n -lt 11 ] && sleep 30; done; echo "gate open $(date +%T) (0 players >= 5 min)";;
  guard) S=$2; E=$3; RP=$4
    while kill -0 $RP 2>/dev/null; do
      if ! zero; then
        mkdir -p $E; echo "INVALID: live not at 0 players at $(date +%T): $(cd /root/project-infinity && ./rcon.sh list 2>&1 | tail -1)" >> $E/INVALID.txt
        (cd $S && ./rcon.sh stop >/dev/null 2>&1); echo "guard stopped $S $(date +%T)"; exit 0
      fi; sleep 20; done;;
esac
