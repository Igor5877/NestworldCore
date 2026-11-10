#!/bin/bash
# EXIT-trap restore for one run: stop a rig JVM if still up, put back the jar + user_jvm_args.txt saved by rig-up.sh,
# release ONLY this run's RIG_LOCK. Idempotent.
set -u
B=/root/airis-2026-10-25; I=H401BEDOM; L=$1; BK=$B/b4-backup-$L; T=$(dirname $(readlink -f $0))
$T/rig-down.sh $L >/dev/null 2>&1
if [ -d $BK ]; then
  cp $BK/nestworld-compat-ae2-test-all.jar $B/$I/mods/nestworld-compat-ae2-test-all.jar && cp $BK/user_jvm_args.txt $B/$I/user_jvm_args.txt \
    && { [ ! -f $BK/common.json ] || cp $BK/common.json $B/$I/config/ae2/common.json; } \
    && rm -rf $BK && echo "rig restored (jar + user_jvm_args + ae2 common.json) for $L"
fi
grep -q "^agent WP3-B $L$" $B/RIG_LOCK 2>/dev/null && rm -f $B/RIG_LOCK && echo "lock released"
true
