#!/bin/bash
# AE2-DEMAIN-001 B4-B6 WP3-B bus transit fixtures on the rig (H401BEDOM, AE2 15.4.10, Core 401, PRODUCTION pins: 'ae2'
# mod-pinned, barrels unpinned). One JVM: reference vs transit windows (import / export separately), conservation over
# N transfers, interruptions (bus broken, grid split, grid unpowered, chunk unload+reload, negative control recovery-off),
# GRID_ISOLATION (huge vs small grid at a tiny commit budget; negative control FIFO). Second JVM: server stop + restart
# with transfers held in transit. Usage: b4-run.sh <label> <addon-jar>
set -u
B=/root/airis-2026-10-25; I=H401BEDOM; L=$1; JAR=$2; E=$B/evidence-be-ready/$L; T=$(dirname $(readlink -f $0))
mkdir -p $E; cd $B/$I
# atomic lock (AGENT-RULES): noclobber create, retry; never overwrite / delete another agent's lock
until ( set -o noclobber; echo "agent WP3-B $L" > $B/RIG_LOCK ) 2>/dev/null; do sleep 30; done
trap "$T/rig-restore.sh $L" EXIT
live0() { (cd /root/project-infinity && ./rcon.sh list 2>/dev/null | grep -q "There are 0 of"); }
# heavy-test gate (coordinator 2026-11-09): 0 live players for >= 5 consecutive minutes
# owner 2026-11-09: rig tests allowed with live players -> gate only when LIVEGATE=1 (pi-smoke style)
n=0; [ "${LIVEGATE:-0}" = 1 ] || n=6
until [ $n -ge 6 ]; do if live0; then n=$((n+1)); else n=0; echo "wait live players $(date +%T)"; fi; [ $n -ge 6 ] || sleep 60; done
# mid-run guard: a live player appears -> stop the rig, mark the run invalid
guard() { [ "${LIVEGATE:-0}" = 1 ] || return 0; live0 || { echo "ABORT: live player online at $(date +%T) -> run invalid"; $T/rig-restore.sh $L; mv $E $B/evidence-be-ready/invalid-$L-live-player; exit 2; }; }
R() { ./rcon.sh "$@" 2>/dev/null | grep -vE "^$|^>"; }
F() { R "nwcompatae2 bustransitfx $*"; }
quiesce() { for i in $(seq 1 120); do s=$(R "nwcompatae2 bustransit" | grep -o "outstanding=[0-9]*" | head -1); [ "$s" = "outstanding=0" ] && return 0; sleep 1; done; echo "NOT QUIESCENT: $s"; }
count() { F count $1 $2 >/dev/null; for i in $(seq 1 30); do s=$(F cstatus $1); case "$s" in *pending*) sleep 1;; *) break;; esac; done; echo "$s"; }
window() { F window $1 $2 >/dev/null; }
wwait() { for i in $(seq 1 200); do s=$(F wstatus $1); case "$s" in window*) echo "$s"; return;; esac; sleep 2; done; echo "window $1 timeout"; }
held() { F hold on >/dev/null; F gen 5 on >/dev/null; F gen 6 on >/dev/null; sleep 4; F gen 5 off >/dev/null; F gen 6 off >/dev/null; sleep 2; }
settle() { sleep 25; quiesce; }   # generators off: import barrels drain empty, export barrels fill up -> no further movement
$T/rig-up.sh $L $JAR -Dnestworld.compatAe2.busTransit=true > $E/up.txt 2>&1 || { cat $E/up.txt; exit 1; }
cat $E/up.txt
P1="-200 300 -200"; P2="-200 300 -180"; P3="200 300 200"; P4="200 300 230"; P5="-200 300 -160"; P6="-200 300 -140"
# TEST-ONLY: stations have up to 48 buses on a controller-less grid (8 channels) -> AE2 channel mode infinite for this
# run (same for the reference and the transit windows); common.json is restored by the EXIT trap
R "ae2 channelmode infinite"
R "forceload add -232 -232 -100 -100" "forceload add 168 168 280 280" >/dev/null; sleep 20
{
guard >> $E/out1.txt
echo "== build"
F build import $P1 8 2 0; F build export $P2 8 2 400000; F build import $P3 48 2 0; F build import $P4 1 2 0
F build import $P5 4 2 0; F build export $P6 4 2 200000
sleep 10; F list; R "ae2 channelmode"
guard >> $E/out1.txt
settle; echo "== baseline counts"; for s in 1 2 5 6; do count $s c0; done

guard >> $E/out1.txt
echo "== reference (transit OFF) vs transit ON windows, 600 ticks, generators on"
F gen 1 on; F gen 2 on; sleep 6
F off; sleep 4; window 1 600; window 2 600; wwait 1; wwait 2
F on; sleep 4; window 1 600; window 2 600; wwait 1; wwait 2
F off; sleep 4; window 1 600; window 2 600; wwait 1; wwait 2
F on; sleep 4; F reset; window 1 1200; window 2 1200; wwait 1; wwait 2
F gen 1 off; F gen 2 off; settle
guard >> $E/out1.txt
echo "== grids after transit windows (latency in ticks)"; R "nwcompatae2 bustransit grids"
guard >> $E/out1.txt
echo "== conservation stations 1 2"; for s in 1 2; do count $s c1; done

guard >> $E/out1.txt
echo "== interrupt: bus broken mid-transit"
held 5; F hold on; R "nwcompatae2 bustransit" | grep -E "^records|^ledger"
F break 5 4; F break 6 4; F hold off; settle; for s in 5 6; do count $s broken; done
R "nwcompatae2 bustransit" | grep -E "^recovery|^ledger"
F rebuild 5 2; F rebuild 6 2; sleep 6

guard >> $E/out1.txt
echo "== interrupt: grid split mid-transit (cable cut)"
held 5; F split 5 cut; F split 6 cut; F hold off; sleep 8; F split 5 restore; F split 6 restore; sleep 6; settle
for s in 5 6; do count $s split; done

guard >> $E/out1.txt
echo "== interrupt: grid gone (power removed) mid-transit"
held 5; F power 5 off; F power 6 off; F hold off; sleep 8; F power 5 on; F power 6 on; sleep 6; settle
for s in 5 6; do count $s unpowered; done

guard >> $E/out1.txt
echo "== interrupt: chunk unload + reload mid-transit"
held 5; R "nwcompatae2 bustransit" | grep -E "^records"
R "forceload remove -232 -232 -100 -100" >/dev/null; sleep 25; R "nwcompatae2 bustransit" | grep -E "^recovery"
R "forceload add -232 -232 -100 -100" >/dev/null; sleep 20; F hold off; sleep 4; settle
for s in 5 6; do count $s reloaded; done; R "nwcompatae2 bustransit" | grep -E "^recovery|^ledger"

guard >> $E/out1.txt
echo "== NEGATIVE CONTROL: recovery off, bus broken mid-transit (loss must be visible)"
F recoveryoff on; held 5; F break 5 4; F hold off; settle; count 5 negRecoveryOff; F recoveryoff off; F rebuild 5 2; sleep 6
R "nwcompatae2 bustransit" | grep -E "^recovery"

guard >> $E/out1.txt
echo "== GRID_ISOLATION: huge grid (48 buses) + small grid (1 bus), commit budget 30 us/tick"
F gen 3 on; F gen 4 on; sleep 6; F budget 30; F reset; sleep 40; R "nwcompatae2 bustransit grids"
guard >> $E/out1.txt
echo "== NEGATIVE CONTROL: global FIFO, same budget"
F fifo on; F reset; sleep 40; R "nwcompatae2 bustransit grids"; F fifo off; F budget 20000
F gen 3 off; F gen 4 off; sleep 4; quiesce
guard >> $E/out1.txt
echo "== GRID_ISOLATION at the default budget"; F gen 3 on; F gen 4 on; F reset; sleep 30; R "nwcompatae2 bustransit grids"
F gen 3 off; F gen 4 off; quiesce

guard >> $E/out1.txt
echo "== restart prep: transfers held in transit, then stop"
settle; for s in 5 6; do count $s prestop0; done
held 5; for s in 5 6; do count $s prestop; done; R "nwcompatae2 bustransit" | grep -E "^records|^ledger"
guard >> $E/out1.txt
echo "== final summary JVM1"; R "nwcompatae2 bustransit"; R "nwcompatae2 stats" | tr ' ' '\n' | grep -E "^t2|mainAffinity|readyInitFailed" | tr '\n' ' '; echo
} > $E/out1.txt 2>&1
$T/rig-down.sh $L >> $E/out1.txt 2>&1; cp logs/latest.log $E/latest-jvm1.log
guard; KEEP=1 $T/rig-up.sh $L $JAR -Dnestworld.compatAe2.busTransit=true > $E/up2.txt 2>&1 || { cat $E/up2.txt; }
{
guard >> $E/out1.txt
echo "== JVM2 after restart (world kept)"
sleep 20; F attach import $P5 4; F attach export $P6 4; sleep 10; settle
for s in 1 2; do count $s afterRestart; done
R "nwcompatae2 bustransit"
} > $E/out2.txt 2>&1
$T/rig-down.sh $L >> $E/out2.txt 2>&1; cp logs/latest.log $E/latest-jvm2.log
grep -a "bus transit\|BE-DOMAIN\] task\|Exception in server tick\|Watchdog" $E/latest-jvm1.log $E/latest-jvm2.log | cut -c1-400 > $E/log-lines.txt
cat $E/out1.txt $E/out2.txt
