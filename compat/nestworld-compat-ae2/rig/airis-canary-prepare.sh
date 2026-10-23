#!/bin/bash
# nestworld-compat-ae2 live-world canary — PREPARE (owner-run inside the Airis container).
# Usage: airis-canary-prepare.sh <server dir> [--no-omit-flag]
#   1. refuses to run while the server is up
#   2. backs up world/, config/, mods/, Core libraries + run.sh + user_jvm_args.txt (rollback point)
#   3. filesystem baseline (canary-baseline.sh baseline-pre)
#   4. verifies artifact SHA-256 (Core d0dbffaf…, addon 71a5d781…)
#   5. installs Core 47.4.307-canary-core-306-ownership, verifies active Core + Git-Dirty=false
#   6. installs the addon, verifies its SHA in mods/
#   7. JVM: refuses a fixtures flag; adds -XX:-OmitStackTraceInFastThrow unless --no-omit-flag
# Changes ONLY Core + addon (+ the optional JVM flag). Mods, configs, pins, world: untouched.
set -euo pipefail
D=${1:?server dir}; OMIT=1; [ "${2:-}" = "--no-omit-flag" ] && OMIT=0
HERE=$(cd "$(dirname "$0")" && pwd)
CORE_JAR=forge-1.20.1-47.4.307-canary-core-306-ownership-installer.jar
CORE_SHA=d0dbffafed320798e5b45e70298c35c1f6c8b09e6a63e2acc6044c429b2f27fc
ADDON_JAR=nestworld-compat-ae2-0.1.0-all.jar
ADDON_SHA=71a5d781e3308cf926123db43ba940fb51c5131ddf1e90184b6c6777beb233be
VER=1.20.1-47.4.307-canary-core-306-ownership
die() { echo "ABORT: $*" >&2; exit 1; }

cd "$D"; D=$(pwd)
[ -f run.sh ] && [ -d mods ] && [ -d world ] || die "$D does not look like a server dir (run.sh, mods/, world/)"
for p in $(pgrep -f "java" || true); do [ "$(readlink /proc/$p/cwd 2>/dev/null)" = "$D" ] && die "server is running (pid $p): stop it first"; done

echo "== 1. artifact verification"
[ "$(sha256sum "$HERE/$CORE_JAR" | cut -c1-64)" = "$CORE_SHA" ] || die "Core installer SHA mismatch"
[ "$(sha256sum "$HERE/$ADDON_JAR" | cut -c1-64)" = "$ADDON_SHA" ] || die "addon SHA mismatch"
echo "Core installer OK ($CORE_SHA)"; echo "addon OK ($ADDON_SHA)"

STAMP=$(date -u +%Y%m%dT%H%M%SZ); B="$D/../canary-rollback-$STAMP"; mkdir -p "$B"; B=$(cd "$B" && pwd)
echo "== 2. rollback point: $B"
OLD_CORE=$(grep -o "forge/[^/]*/unix_args" run.sh | cut -d/ -f2 || true)
echo "$OLD_CORE" > "$B/previous-core.txt"
cp -a run.sh user_jvm_args.txt "$B/"; [ -f run.bat ] && cp -a run.bat "$B/"
tar -czf "$B/world.tar.gz" world
tar -czf "$B/config.tar.gz" config
tar -czf "$B/mods.tar.gz" mods
tar -czf "$B/libraries-forge.tar.gz" libraries/net/minecraftforge
( cd "$B" && sha256sum *.tar.gz run.sh user_jvm_args.txt > SHA256SUMS )
echo "previous Core: $OLD_CORE"; ls -la "$B"
echo "   (if the container storage supports it, take an LXD snapshot NOW as well)"

echo "== 3. filesystem baseline (pre)"
"$HERE/canary-baseline.sh" "$D" baseline-pre

echo "== 4. install Core $VER"
java -jar "$HERE/$CORE_JAR" --installServer "$D" > "$B/install-core.log" 2>&1 || die "installer failed (see $B/install-core.log)"
grep -q "forge/$VER/unix_args" run.sh || die "run.sh does not launch $VER"
U="libraries/net/minecraftforge/forge/$VER/forge-$VER-universal.jar"
MF=$(unzip -p "$U" META-INF/MANIFEST.MF | tr -d '\r')
grep -q "^Git-Dirty: false" <<< "$MF" || die "installed Core is not Git-Dirty: false"
grep -q "^Git-Commit: 1b179775" <<< "$MF" || die "installed Core is not commit 1b179775"
LISTING=$(unzip -l "$U")
grep -q "net/nestworld/region/NestworldOwnership.class" <<< "$LISTING" || die "NestworldOwnership contract missing"
echo "active Core: $VER (Git-Commit 1b179775, Git-Dirty false, NestworldOwnership present)"

echo "== 5. install addon"
ls mods/ | grep -i "nestworld-compat-ae2" | grep -v "^$ADDON_JAR$" && die "another nestworld-compat-ae2 jar is present in mods/"
cp "$HERE/$ADDON_JAR" mods/
[ "$(sha256sum "mods/$ADDON_JAR" | cut -c1-64)" = "$ADDON_SHA" ] || die "addon SHA mismatch in mods/"
echo "addon installed: mods/$ADDON_JAR"

echo "== 6. JVM args"
grep -q "compatAe2.fixtures" user_jvm_args.txt && die "fixtures flag present in user_jvm_args.txt — not a production canary"
if [ $OMIT = 1 ] && ! grep -q "OmitStackTraceInFastThrow" user_jvm_args.txt; then
  printf '\n# AE2 canary (owner-recommended): full stacks for repeated exceptions\n-XX:-OmitStackTraceInFastThrow\n' >> user_jvm_args.txt
  echo "added -XX:-OmitStackTraceInFastThrow"
fi
diff "$B/user_jvm_args.txt" user_jvm_args.txt || true

echo "== READY TO START. Rollback point: $B"
echo "   start the server as usual; then: RCON_PORT=<p> RCON_PASS=<pw> $HERE/canary-baseline.sh $D t0-after-start"
