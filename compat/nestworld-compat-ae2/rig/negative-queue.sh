#!/bin/bash
# negative-queue.sh <name> <fixture> <patch.py>: temporary patch of SetChangedProtocol, run one queue fixture, revert
NAME=$1; FX=$2; PATCH=$3; REPO=/root/nestworldcore; R="python3 /root/compat-ae2-rig/rcon.py"
F=$REPO/compat/nestworld-compat-ae2/src/main/java/net/nestworld/compat/ae2/glue/SetChangedProtocol.java
cp $F /root/compat-ae2-rig/SetChangedProtocol.java.orig; python3 $PATCH $F || exit 1
(cd $REPO && timeout 1500 ./gradlew --no-daemon -q -p compat/nestworld-compat-ae2 build jarJar -x test 2>&1 | grep -v "^Note:" | head -5)
cp /root/compat-ae2-rig/SetChangedProtocol.java.orig $F
cd /root/compat-ae2-rig; $R stop >/dev/null 2>&1; timeout 90 bash -c 'while for p in $(pgrep -f java.*unix_args); do readlink /proc/$p/cwd; done | grep -q compat-ae2-rig; do sleep 2; done'
for p in $(pgrep -f "java.*unix_args"); do [ "$(readlink /proc/$p/cwd)" = /root/compat-ae2-rig/server ] && kill -9 $p; done
cp $REPO/compat/nestworld-compat-ae2/build/libs/nestworld-compat-ae2-0.1.0-all.jar server/mods/
cd server; mv console.log console-negq-$NAME.log; (setsid nohup bash run.sh nogui > console.log 2>&1 < /dev/null &); sleep 1
timeout 300 bash -c 'until grep -qE "Done \(|Exception in thread" console.log; do sleep 2; done'; cd ..
$R "forceload add 192 192 207 207" >/dev/null; sleep 2
$R "nwcompatae2 queuefixture $FX 200 -60 200" >/dev/null
timeout 60 bash -c "until $R 'nwcompatae2 queuefixture status' | grep -qE 'PASS|FAIL'; do sleep 1; done"
echo "NEGATIVE $NAME ($FX): $($R 'nwcompatae2 queuefixture status' | sed -n 2p | cut -c1-200)"
