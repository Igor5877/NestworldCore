#!/bin/bash
# usage: negative.sh <name> <fixture> <python-patch-file>
set -u
NAME=$1; FX=$2; PATCH=$3
REPO=/root/nestworldcore; F=$REPO/compat/nestworld-compat-ae2/src/main/java/net/nestworld/compat/ae2/mixin/TickManagerServiceMixin.java
cp $F /root/compat-ae2-rig/TickManagerServiceMixin.java.orig
python3 $PATCH $F || { echo "patch failed"; exit 1; }
(cd $REPO && timeout 1500 ./gradlew --no-daemon -q -p compat/nestworld-compat-ae2 build jarJar -x test 2>&1 | grep -v "^Note:" | head)
cp /root/compat-ae2-rig/TickManagerServiceMixin.java.orig $F
python3 /root/compat-ae2-rig/rcon.py stop >/dev/null 2>&1; sleep 8
cp $REPO/compat/nestworld-compat-ae2/build/libs/nestworld-compat-ae2-0.1.0-all.jar /root/compat-ae2-rig/server/mods/
cd /root/compat-ae2-rig/server; mv console.log console-neg-$NAME-prev.log
(setsid nohup bash run.sh nogui > console.log 2>&1 < /dev/null &); sleep 1
timeout 300 bash -c 'until grep -qE "Done \(|Exception in thread" console.log; do sleep 2; done'
cd /root/compat-ae2-rig; python3 rcon.py "nwcompatae2 fixture $FX" >/dev/null
timeout 40 bash -c "until python3 rcon.py 'nwcompatae2 fixture status' | grep -qE 'PASS|FAIL'; do sleep 1; done"
echo "NEGATIVE $NAME ($FX): $(python3 rcon.py 'nwcompatae2 fixture status' | sed -n 2p | cut -c1-400)"
