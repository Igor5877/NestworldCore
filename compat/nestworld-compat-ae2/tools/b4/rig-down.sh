#!/bin/bash
# stop the rig JVM (cwd-identified), record new crash reports
set -u
B=/root/airis-2026-10-25; I=H401BEDOM; E=$B/evidence-be-ready/$1; cd $B/$I
hjava() { for p in $(pgrep -x java); do [ "$(readlink /proc/$p/cwd)" = "$B/$I" ] && echo $p; done; }
[ -n "$(hjava)" ] && ./rcon.sh stop >/dev/null 2>&1; for i in $(seq 1 40); do [ -z "$(hjava)" ] && break; sleep 3; done
P=$(hjava); [ -n "$P" ] && { echo "STOP HANG" >> $E/out.txt; kill -9 $P; }
[ -f $E/crash-before.txt ] && { ls crash-reports | sort | comm -13 $E/crash-before.txt - > $E/crash-new.txt; echo "crashNew=$(wc -l < $E/crash-new.txt)"; }
cp logs/latest.log $E/latest.log 2>/dev/null; true
