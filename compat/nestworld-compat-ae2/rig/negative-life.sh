#!/bin/bash
# negative-life.sh <name> <during|before> <file-under-mixin> <patch.py>
NAME=$1; FX=$2; FILE=$3; PATCH=$4; REPO=/root/nestworldcore; R="python3 /root/compat-ae2-rig/rcon.py"
F=$REPO/compat/nestworld-compat-ae2/src/main/java/net/nestworld/compat/ae2/mixin/$FILE
cp $F /root/compat-ae2-rig/$FILE.orig; python3 $PATCH $F || exit 1
(cd $REPO && timeout 1500 ./gradlew --no-daemon -q -p compat/nestworld-compat-ae2 build jarJar -x test 2>&1 | grep -E "error" -A3 | head -5)
cp /root/compat-ae2-rig/$FILE.orig $F
cd /root/compat-ae2-rig; $R stop >/dev/null 2>&1; timeout 90 bash -c 'while for p in $(pgrep -f java.*unix_args); do readlink /proc/$p/cwd; done | grep -q compat-ae2-rig; do sleep 2; done'
for p in $(pgrep -f "java.*unix_args"); do [ "$(readlink /proc/$p/cwd)" = /root/compat-ae2-rig/server ] && kill -9 $p; done
cp $REPO/compat/nestworld-compat-ae2/build/libs/nestworld-compat-ae2-0.1.0-all.jar server/mods/
cd server; mv console.log console-negl-$NAME.log; (setsid nohup bash run.sh nogui > console.log 2>&1 < /dev/null &); sleep 1
timeout 300 bash -c 'until grep -qE "Done \(|Exception in thread" console.log; do sleep 2; done'; cd ..
/root/compat-ae2-rig/freerun-all.sh >/dev/null
$R "forceload add 192 192 223 223" "fill 196 -60 196 212 -56 206 air" "kill @e[type=minecraft:armor_stand]" "summon minecraft:armor_stand 202.5 -60 201.5 {NoGravity:1b}" >/dev/null; sleep 2
$R "nwcompatae2 integration life_$FX 200 -60 201" >/dev/null
timeout 60 bash -c "until $R 'nwcompatae2 integration status' | grep -qE 'PASS|FAIL'; do sleep 1; done"
echo "NEGATIVE $NAME ($FX): $($R 'nwcompatae2 integration status' | sed -n 2p | cut -c1-400)"
