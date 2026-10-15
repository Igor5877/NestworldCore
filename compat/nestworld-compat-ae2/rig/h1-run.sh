#!/bin/bash
# P2P-MAP-STATE-001 (H1) deterministic fixtures. Deploys the current jar unless NODEPLOY=1.
R="python3 /root/compat-ae2-rig/rcon.py"
[ -z "$NODEPLOY" ] && /root/compat-ae2-rig/redeploy.sh
$R "forceload add -96 1184 80 1280" >/dev/null; sleep 3; /root/compat-ae2-rig/freerun-all.sh >/dev/null
$R "nwcompatae2 p2p h1setup -40 -60 1190 -40 -60 1186" | sed -n 2p; sleep 4
for k in ${CASES:-liveview allocrace updaterace independent}; do
  $R "nwcompatae2 p2p h1 $k -40 -60 1190 -40 -60 1186" >/dev/null
  timeout 60 bash -c "until $R 'nwcompatae2 p2p status' | grep -qE '(PASS|FAIL|INVALID)'; do sleep 1; done"
  $R 'nwcompatae2 p2p status' | sed -n 2p
done
$R "nwcompatae2 stats" | sed -n 2p | grep -o "mode=[A-Z]*\|unauditedPaths=[0-9]*\|p2p[A-Za-z]*=[0-9]*" | tr '\n' ' '; echo
