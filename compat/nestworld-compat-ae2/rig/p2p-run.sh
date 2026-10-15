#!/bin/bash
# AE2-COMPAT-P2P-001 P2P-9 (H3 conservation, 9A/9B/9C, fe + item) and P2P-10 (Redstone P2P callback
# boundary). Reproduction only: no P2P fix exists. Deploys the current jar unless NODEPLOY=1.
# Geometry: region boundary at chunk x=0 (region west of it = A, region east = B); verify with `p2p owner`.
R="python3 /root/compat-ae2-rig/rcon.py"
[ -z "$NODEPLOY" ] && /root/compat-ae2-rig/redeploy.sh
$R "forceload add -96 1184 80 1280" >/dev/null; sleep 4; /root/compat-ae2-rig/freerun-all.sh >/dev/null
for p in "-80 -60 1200" "-70 -59 1200" "5 -59 1220" "60 -59 1240" "60 -60 1270"; do $R "nwcompatae2 p2p owner $p" | sed -n 2p; done
# kind in out  (target = out.above)
NETS="fe:-80:1200:-70:1200 fe:-80:1220:5:1220 fe:-80:1240:60:1240 item:-80:1205:-70:1205 item:-80:1225:5:1225 item:-80:1245:60:1245 redstone:-80:1270:60:1270"
for n in $NETS; do IFS=: read k ix iz ox oz <<< "$n"; $R "nwcompatae2 p2p setup $k $ix -60 $iz $ox -60 $oz" >/dev/null; done; sleep 4
for n in $NETS; do IFS=: read k ix iz ox oz <<< "$n"; $R "nwcompatae2 p2p bind $ix -60 $iz $ox -60 $oz" | sed -n 2p; done
$R "kill @e[type=minecraft:armor_stand]" >/dev/null
for s in "-82 -60 1203" "-70 -60 1203" "-82 -60 1223" "7 -60 1223" "-82 -60 1243" "62 -60 1243" "-79 -60 1273"; do $R "summon minecraft:armor_stand $s {NoGravity:1b,Invulnerable:1b}" >/dev/null; done
sleep 3; /root/compat-ae2-rig/freerun-all.sh >/dev/null
run() { $R "nwcompatae2 p2p conserve $1" >/dev/null; timeout 30 bash -c "until $R 'nwcompatae2 p2p status' | grep -qE 'CONSERVED|VIOLATION|INVALID'; do sleep 1; done"; $R 'nwcompatae2 p2p status' | sed -n 2p; }
for i in 1 2 3; do run "9A fe -80 -60 1200 -70 -60 1200 1000"; run "9B fe -80 -60 1220 5 -60 1220 1000"; run "9C fe -80 -60 1240 60 -60 1240 1000"; done
for i in 1 2; do run "9A item -80 -60 1205 -70 -60 1205 16"; run "9B item -80 -60 1225 5 -60 1225 16"; run "9C item -80 -60 1245 60 -60 1245 16"; done
L='execute if block 60 -59 1270 minecraft:redstone_lamp[lit=true]'
rs() {   # $1 = block under the input while cut (redstone_block → ON transition, grass_block → OFF transition)
  $R "nwcompatae2 p2p rscut -79 -60 1270" >/dev/null; sleep 2; $R "setblock -80 -61 1270 minecraft:$1" >/dev/null; sleep 2
  echo "P2P-10 [$1] lamp lit while cut: $($R "$L" | sed -n 2p)"
  $R "nwcompatae2 p2p rsreconnect -79 -60 1270" >/dev/null; sleep 5
  $R "nwcompatae2 p2p status" | sed -n 2p | sed 's/ || /\n    /g; s/ :: /\n    /'
  sleep 2; echo "P2P-10 [$1] lamp lit later: $($R "$L" | sed -n 2p)"
}
$R "setblock -80 -61 1270 minecraft:grass_block" >/dev/null; sleep 2
rs redstone_block; rs grass_block
$R "nwcompatae2 stats" | sed -n 2p | grep -o "mode=[A-Z]*\|manifestEligible=[a-z]*\|unauditedPaths=[0-9]*\|callbackExceptions=[0-9]*" | tr '\n' ' '; echo
