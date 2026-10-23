#!/bin/bash
# nestworld-compat-ae2 canary — ROLLBACK (owner-run). Server must be stopped.
# Usage: airis-canary-rollback.sh <server dir> <rollback dir> [--restore-world]
#   restores the previous Core (libraries/net/minecraftforge + run.sh + user_jvm_args.txt) and mods/
#   (which removes the addon); config/ is restored too. The world is restored ONLY with --restore-world
#   (use it if world consistency is questionable). Evidence (logs, crash reports, canary-evidence/) is kept.
set -euo pipefail
D=${1:?server dir}; B=${2:?rollback dir}; RW=0; [ "${3:-}" = "--restore-world" ] && RW=1
die() { echo "ABORT: $*" >&2; exit 1; }
cd "$D"; D=$(pwd); B=$(cd "$B" && pwd)
for p in $(pgrep -f "java" || true); do [ "$(readlink /proc/$p/cwd 2>/dev/null)" = "$D" ] && die "server is running (pid $p): stop it first"; done
( cd "$B" && sha256sum -c SHA256SUMS ) || die "rollback archive checksum mismatch"
T="$D/../canary-failed-state-$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p "$T"
cp -a logs crash-reports canary-evidence "$T/" 2>/dev/null || true
cp -a run.sh user_jvm_args.txt "$T/"
echo "evidence of the canary state saved to $T"
rm -rf libraries/net/minecraftforge && tar -xzf "$B/libraries-forge.tar.gz"
cp -a "$B/run.sh" "$B/user_jvm_args.txt" .
rm -rf mods && tar -xzf "$B/mods.tar.gz"
rm -rf config && tar -xzf "$B/config.tar.gz"
if [ $RW = 1 ]; then
  mv world "$T/world-at-rollback"; tar -xzf "$B/world.tar.gz"; echo "world restored from $B/world.tar.gz (canary world kept in $T)"
fi
echo "restored Core: $(cat "$B/previous-core.txt")"; tail -1 run.sh
ls mods | grep -c "nestworld-compat-ae2" | xargs -I{} echo "addon jars in mods/: {}"
