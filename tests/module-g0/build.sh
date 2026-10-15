#!/bin/bash
# MODULE-2 G0 probe (scratch only). Builds the harness mod and the external probe jar against the
# rig server's own libraries. Rig layout: /root/module-g0-rig/server (copy of a Core server install).
set -e; R=${G0_RIG:-/root/module-g0-rig}; D=$(cd "$(dirname "$0")" && pwd)
CP=$(find $R/server/libraries -name "*.jar" | tr '\n' ':')
rm -rf $R/build; mkdir -p $R/build/h $R/build/m $R/server/mods $R/server/nestworld-modules-g0
javac -nowarn --release 17 -cp "$CP" -d $R/build/m $(find $D/module -name "*.java")
javac -nowarn --release 17 -cp "$CP" -d $R/build/h $(find $D/harness -name "*.java")
cp -r $D/harness/META-INF $R/build/h/
(cd $R/build/h && jar cf $R/server/mods/nwg0harness-0.0.1.jar .)
(cd $R/build/m && jar cf $R/server/nestworld-modules-g0/g0-probe.jar .)
