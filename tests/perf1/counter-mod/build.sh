#!/bin/bash
# Builds perf1-counter.jar with plain javac against an installed server's libraries.
# Usage: build.sh <server-root> <out-jar>
set -euo pipefail
ROOT=$(cd "$1" && pwd); OUT=$(realpath -m "$2")
HERE=$(cd "$(dirname "$0")" && pwd)
L=$ROOT/libraries
CP=$(find "$L" -name '*.jar' \( -path '*eventbus*' -o -path '*fmlcore*' -o -path '*javafmllanguage*' \
      -o -name 'forge-*-universal.jar' -o -name 'server-*-srg.jar' \) | tr '\n' ':')
W=$(mktemp -d)
javac --release 17 -nowarn -cp "$CP" -d "$W" "$HERE"/src/net/nestworld/perf1/*.java
cp -r "$HERE"/resources/META-INF "$W"/
jar --create --file "$OUT" -C "$W" .
rm -rf "$W"
echo "built $OUT"
