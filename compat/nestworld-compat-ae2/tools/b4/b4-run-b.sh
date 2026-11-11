#!/bin/bash
# WP3-B part B (owner 2026-11-10): short export path latency (p100 <= 2) + export reference equivalence, import
# return-failure fallback (network refuses AND source full -> accounted drop), grid-gone + bus-broken with recovery ON
# (no loss) and recovery OFF (NEGATIVE CONTROL: loss must be visible). Usage: b4-run-b.sh <label> <addon-jar>
set -u
B=/root/airis-2026-10-25; I=H401BEDOM; L=$1; JAR=$2; E=$B/evidence-be-ready/$L; T=$(dirname $(readlink -f $0)); mkdir -p $E; cd $B/$I
until ( set -o noclobber; echo "agent WP3-B $L" > $B/RIG_LOCK ) 2>/dev/null; do sleep 30; done
trap "$T/rig-restore.sh $L" EXIT
R() { ./rcon.sh "$@" 2>/dev/null | grep -vE "^$|^>"; }
F() { R "nwcompatae2 bustransitfx $*"; }
quiesce() { for i in $(seq 1 120); do s=$(R "nwcompatae2 bustransit" | grep -o "outstanding=[0-9]*" | head -1); [ "$s" = "outstanding=0" ] && return 0; sleep 1; done; echo "NOT QUIESCENT: $s"; }
count() { F count $1 $2 >/dev/null; for i in $(seq 1 30); do s=$(F cstatus $1); case "$s" in *pending*) sleep 1;; *) break;; esac; done; echo "$s"; }
wwait() { for i in $(seq 1 200); do s=$(F wstatus $1); case "$s" in window*) echo "$s"; return;; esac; sleep 2; done; echo "window $1 timeout"; }
settle() { sleep 25; quiesce; }
held() { F hold on >/dev/null; F gen $1 on >/dev/null; sleep 4; F gen $1 off >/dev/null; sleep 2; }
$T/rig-up.sh $L $JAR -Dnestworld.compatAe2.busTransit=true > $E/up.txt 2>&1 || { cat $E/up.txt; exit 1; }
R "ae2 channelmode infinite" >/dev/null; R "forceload add -232 -232 -100 -100" >/dev/null; sleep 20
for i in $(seq 1 12); do R "nwcompatae2 domainat -199 300 -140" | grep -q REGION && break; sleep 5; done; sleep 10
{
echo "== build"; F build export -200 300 -180 8 2 400000; F build import -200 300 -160 4 2 0; F build import -200 300 -140 4 2 0
# Catch fallback ItemEntities inside the station counter's Y=297..304 volume.
# Without this floor, a valid drop at Y=300 falls outside the snapshot before count.
R "fill -202 299 -163 -192 299 -157 minecraft:glass" >/dev/null
sleep 10; F list; settle; for s in 1 2 3; do count $s c0; done
echo "== export windows (OFF / ON / ON 1200)"; F gen 1 on; sleep 6
F off; sleep 4; F window 1 600 >/dev/null; wwait 1
F on; sleep 4; F reset; F window 1 600 >/dev/null; wwait 1; R "nwcompatae2 bustransit grids"
F reset; F window 1 1200 >/dev/null; wwait 1; R "nwcompatae2 bustransit grids"
F gen 1 off; settle; count 1 c1
echo "== export partial fill (barrels refill only partly between ops)"; F reset; F fill 1; sleep 2; F gen 1 on; sleep 1; F gen 1 off; sleep 30; settle; count 1 partial
R "nwcompatae2 bustransit grids"; R "nwcompatae2 bustransit" | grep -E "^modulation|^ledger"
echo "== import return-failure fallback: network refuses (split) AND source full -> accounted drop after 200 ticks"
held 2; F split 2 cut; F fill 2; sleep 2; F hold off; sleep 20; R "nwcompatae2 bustransit" | grep -E "^records|^recovery"
settle; F split 2 restore; sleep 4; settle; count 2 returnFailed; R "nwcompatae2 bustransit" | grep -E "^records|^recovery|^ledger"
echo "== grid gone + bus broken mid-transit, recovery ON"
held 3; F power 3 off; F break 3 4; F hold off; sleep 15; settle; F power 3 on; sleep 4; settle; count 3 gonebrokenOn
R "nwcompatae2 bustransit" | grep -E "^recovery|^ledger"; F rebuild 3 2; sleep 6; settle; count 3 rebuilt
echo "== NEGATIVE CONTROL: grid gone + bus broken mid-transit, recovery OFF (loss must be visible)"
F recoveryoff on; held 3; F power 3 off; F break 3 4; F hold off; sleep 15; settle; F recoveryoff off; F power 3 on; sleep 4; settle
count 3 negGoneBroken; R "nwcompatae2 bustransit" | grep -E "^recovery"
echo "== final"; R "nwcompatae2 bustransit"
} > $E/out.txt 2>&1
$T/rig-down.sh $L >> $E/out.txt 2>&1
cat $E/out.txt
