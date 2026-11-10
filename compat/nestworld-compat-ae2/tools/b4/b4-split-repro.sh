#!/bin/bash
# focused repro: export station, transfers held, grid split, release -> dump outstanding records
set -u
B=/root/airis-2026-10-25; I=H401BEDOM; L=$1; JAR=$2; E=$B/evidence-be-ready/$L; T=$(dirname $(readlink -f $0)); mkdir -p $E; cd $B/$I
until ( set -o noclobber; echo "agent WP3-B $L" > $B/RIG_LOCK ) 2>/dev/null; do sleep 30; done
trap "$T/rig-restore.sh $L" EXIT
R() { ./rcon.sh "$@" 2>/dev/null | grep -vE "^$|^>"; }
F() { R "nwcompatae2 bustransitfx $*"; }
$T/rig-up.sh $L $JAR -Dnestworld.compatAe2.busTransit=true > $E/up.txt 2>&1 || { cat $E/up.txt; exit 1; }
R "ae2 channelmode infinite"; R "forceload add -232 -232 -100 -100" >/dev/null; sleep 20; for i in $(seq 1 12); do R "nwcompatae2 domainat -199 300 -140" | grep -q "REGION" && break; sleep 5; done; sleep 10
{ F build export -200 300 -140 4 2 200000; F build import -200 300 -160 4 2 0; sleep 30
  echo "== held"; F hold on; F gen 1 on; F gen 2 on; sleep 4; F gen 1 off; F gen 2 off; sleep 2; R "nwcompatae2 bustransit records"
  echo "== split + release"; F split 1 cut; F split 2 cut; F hold off; sleep 8; R "nwcompatae2 bustransit records"
  F split 1 restore; F split 2 restore; sleep 15; echo "== after restore"; R "nwcompatae2 bustransit records"; R "nwcompatae2 bustransit"
} > $E/out.txt 2>&1
cat $E/out.txt
