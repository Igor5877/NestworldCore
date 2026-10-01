#!/usr/bin/env bash
# Mod API distribution compatibility matrix (PKG-002). See README.md.
#   FORGE_INSTALLER      upstream forge-1.20.1-47.4.20-installer.jar           (required)
#   NESTWORLD_INSTALLER  NestWorld installer built from this repo              (required)
#   WORK                 scratch directory for the two servers (default: /tmp/modapi-compat-work)
#   SKIP_BUILD=1         reuse already-built consumers/variants/published artifacts
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); REPO=$(cd "$HERE/../.." && pwd)
: "${FORGE_INSTALLER:?set FORGE_INSTALLER}"; : "${NESTWORLD_INSTALLER:?set NESTWORLD_INSTALLER}"
WORK=${WORK:-/tmp/modapi-compat-work}
[ -n "$WORK" ] && [ "$WORK" != "/" ] || { echo "refusing WORK=$WORK"; exit 2; }
PW=modapi-compat

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  "$REPO/gradlew" -p "$REPO" :modapi:publishToMavenLocal -q || exit 1
  "$REPO/gradlew" -p "$HERE/compileonly-consumer" build -q || exit 1
  "$REPO/gradlew" -p "$HERE/jarjar-consumer" jarJar -q || exit 1
  "$REPO/gradlew" -p "$HERE/broken-provider" variants -q || exit 1
fi
A=$(ls "$HERE"/compileonly-consumer/build/libs/nwinvprobe-compileonly-consumer.jar)
B=$(ls "$HERE"/jarjar-consumer/build/libs/nwinvprobe-jarjar-consumer-all.jar)
V="$HERE/broken-provider/build/variants"
P=$(ls ~/.m2/repository/net/nestworld/nestworld-modapi-provider/1.0.0-SNAPSHOT/nestworld-modapi-provider-1.0.0-SNAPSHOT.jar)

install() { # dir installer gameport rconport
  rm -rf "$1"; mkdir -p "$1/mods"
  (cd "$1" && java -jar "$2" --installServer . > install.log 2>&1) || { echo "install failed: $1"; exit 1; }
  echo "eula=true" > "$1/eula.txt"
  printf 'server-port=%s\nenable-rcon=true\nrcon.port=%s\nrcon.password=%s\nonline-mode=false\nlevel-type=minecraft\\:flat\nspawn-protection=0\n' "$3" "$4" "$PW" > "$1/server.properties"
  echo "-Xmx3G" > "$1/user_jvm_args.txt"
}
install "$WORK/stock" "$FORGE_INSTALLER" 25610 25611
install "$WORK/nestworld" "$NESTWORLD_INSTALLER" 25612 25613

FAILURES=0
row() { # label runtime(stock|nestworld) expect(PASS|FML_FAIL|ABI_FAIL) jars...
  local label=$1 rt=$2 expect=$3; shift 3
  local d="$WORK/$rt" port; port=$([ "$rt" = stock ] && echo 25611 || echo 25613)
  find "$d/mods" -maxdepth 1 -name '*.jar' -delete; for j in "$@"; do cp "$j" "$d/mods/"; done
  rm -rf "$d/logs" "$d/world"
  local args; args=$(grep -oE '@libraries/[^ ]+unix_args.txt' "$d/run.sh")
  (cd "$d" && exec java @user_jvm_args.txt $args nogui > server.out 2>&1 < /dev/null) & local pid=$!
  for i in $(seq 1 180); do grep -qE 'Done \(|Missing or unsupported|ABI-incompatible|Crash report|Exception in thread "main"' "$d/server.out" 2>/dev/null && break; kill -0 $pid 2>/dev/null || break; sleep 1; done
  sleep 3
  local got linkage; linkage=$(grep -cE 'NoSuchMethodError|AbstractMethodError|NoClassDefFoundError' "$d/server.out")
  if grep -q 'Done (' "$d/server.out"; then
    python3 "$HERE/rcon.py" $port $PW nwprobe smoke - > /dev/null
    for i in $(seq 1 30); do grep -q 'RESULT SMOKE' "$d/logs/latest.log" 2>/dev/null && break; sleep 1; done
    got=$(grep -q 'RESULT SMOKE: PASS' "$d/logs/latest.log" && echo PASS || echo SMOKE_FAIL)
    python3 "$HERE/rcon.py" $port $PW stop > /dev/null
  elif grep -q 'is ABI-incompatible' "$d/server.out"; then got=ABI_FAIL
  elif grep -q 'Missing or unsupported mandatory dependencies' "$d/server.out"; then got=FML_FAIL
  else got=OTHER; fi
  for i in $(seq 1 60); do kill -0 $pid 2>/dev/null || break; sleep 1; done; kill $pid 2>/dev/null; wait $pid 2>/dev/null
  local ok=OK; { [ "$got" = "$expect" ] && [ "$linkage" = 0 ]; } || { ok=MISMATCH; FAILURES=$((FAILURES+1)); }
  printf '%-9s | %-62s | expected %-8s | got %-10s | linkage errors %s | %s\n' "$rt" "$label" "$expect" "$got" "$linkage" "$ok"
  if [ "$got" = PASS ]; then grep -hE 'NWPROBE\] (provider:|setVelocity:)' "$d/logs/latest.log" | sed -E 's/^.*\[NWPROBE\] /            /'
  elif [ "$got" = FML_FAIL ]; then grep -hE "Mod ID: 'nestworld_modapi'" "$d/server.out" | head -1 | sed -E 's/^\s*/            /'
  elif [ "$got" = ABI_FAIL ]; then grep -hE '^\s+- ' "$d/server.out" | head -1 | sed -E 's/^\s*/            /' | cut -c1-200; fi
  [ "$ok" = OK ] || grep -E 'RESULT SMOKE|Mod ID|ABI-incompatible|^\s+- ' "$d/server.out" "$d/logs/latest.log" 2>/dev/null | head -5
}

row "compileOnly consumer + external provider"                stock     PASS     "$A" "$P"
row "compileOnly consumer, provider missing"                  stock     FML_FAIL "$A"
row "jarJar consumer (nested provider)"                       stock     PASS     "$B"
row "compileOnly consumer, built-in provider"                 nestworld PASS     "$A"
row "jarJar consumer, built-in + nested provider"             nestworld PASS     "$B"
row "compileOnly consumer + unsupported newer provider 1.0.1" nestworld FML_FAIL "$A" "$V/provider-unverified-1.0.1.jar"
row "compileOnly + supported version, setSlot removed (S5)"   nestworld ABI_FAIL "$A" "$V/provider-abi-remove-setSlot.jar"
row "compileOnly + supported version, abstract method added"  nestworld ABI_FAIL "$A" "$V/provider-abi-add-abstract.jar"
row "compileOnly + supported version, registry class deleted" nestworld ABI_FAIL "$A" "$V/provider-abi-delete-registry.jar"
row "compileOnly + 9.0.0 provider, setSlot removed (E7)"      nestworld FML_FAIL "$A" "$V/provider-9.0.0-remove-setSlot.jar"
echo "matrix mismatches: $FAILURES"
exit $FAILURES
