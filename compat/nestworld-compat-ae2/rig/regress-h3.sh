#!/bin/bash
# Focused regression after P2P-FORWARDING-FAIL-CLOSED-001 (guard ON). One log per suite.
cd /root/compat-ae2-rig; E=evidence/h3-regression-$(date +%m%d-%H%M); mkdir -p $E
R="python3 /root/compat-ae2-rig/rcon.py"
./h1-run.sh > $E/h1.log 2>&1
NODEPLOY=1 ./h2-run.sh > $E/h2.log 2>&1
$R "setblock -70 -59 1200 air" "setblock -70 -59 1205 air" "setblock -70 -59 1210 air" >/dev/null 2>&1; sleep 1
NODEPLOY=1 ./p2p-run.sh > $E/p2p9.log 2>&1
./queue-run.sh > $E/queue.log 2>&1
STRESS_SECONDS=60 MULTIGRID_SECONDS=30 ./gridphase-run.sh > $E/gridphase.log 2>&1
grep -o "gridphase fixture STAGING-[a-z]*: [A-Z]*" server/console.log > $E/gridphase-staging.txt
./integration-run.sh > $E/integration.log 2>&1
./legacy-run.sh > $E/legacy.log 2>&1
$R "nwcompatae2 stats" > $E/stats-final.txt 2>&1
echo "DONE $E"
