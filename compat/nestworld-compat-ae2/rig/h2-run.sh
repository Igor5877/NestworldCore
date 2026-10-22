#!/bin/bash
# P2P-ACCESS-DEPTH-001 (H2) deterministic fixtures. Deploys the current jar unless NODEPLOY=1.
R="python3 /root/compat-ae2-rig/rcon.py"
[ -z "$NODEPLOY" ] && /root/compat-ae2-rig/redeploy.sh
$R "forceload add -96 1184 80 1280" >/dev/null; sleep 3; /root/compat-ae2-rig/freerun-all.sh >/dev/null
# networks: fe z=1200, item z=1205 (from p2p-run.sh), fluid z=1210 (new); loop column at (-50,-58,1215)
$R "nwcompatae2 p2p setup fe -80 -60 1200 -70 -60 1200" "nwcompatae2 p2p setup item -80 -60 1205 -70 -60 1205" "nwcompatae2 p2p setup fluid -80 -60 1210 -70 -60 1210" "nwcompatae2 p2p h2loop -50 -59 1215" | grep -i fail
sleep 4
for z in 1200 1205 1210; do $R "nwcompatae2 p2p bind -80 -60 $z -70 -60 $z" | sed -n 2p; done
$R "nwcompatae2 p2p h2loopbind -50 -59 1215" | sed -n 2p
$R "kill @e[type=minecraft:armor_stand]" >/dev/null
for s in "-82 -60 1203" "-82 -60 1213" "-47 -60 1215"; do $R "summon minecraft:armor_stand $s {NoGravity:1b,Invulnerable:1b}" >/dev/null; done
sleep 3; /root/compat-ae2-rig/freerun-all.sh >/dev/null
$R "nwcompatae2 p2p owner -80 -60 1200" "nwcompatae2 p2p owner -50 -59 1215" | grep pos=
$R "nwcompatae2 p2p h2recursion -50 -59 1215" | sed -n 2p
for k in fe:1200:1000 item:1205:16 fluid:1210:1000; do IFS=: read kind z amt <<< "$k"
  $R "nwcompatae2 p2p h2concurrent $kind -80 -60 $z -70 -60 $z $amt" | sed -n 2p; done
for k in fe:1200:1000 item:1205:16 fluid:1210:1000; do IFS=: read kind z amt <<< "$k"
  $R "nwcompatae2 p2p h2throw $kind -80 -60 $z -70 -60 $z $amt" | sed -n 2p; done
$R "nwcompatae2 stats" | sed -n 2p | grep -o "mode=[A-Z]*\|unauditedPaths=[0-9]*\|p2pDepth[A-Za-z]*=[0-9]*" | tr '\n' ' '; echo
