#!/bin/bash
# SOURCE-PATCH-INTEGRITY-001 bytecode canary (docs/contracts/SOURCE-PATCH-INTEGRITY-001-audit.md §4).
# usage: canary.sh <jar with patched net.minecraft classes>
#   e.g. the installed libraries/net/minecraftforge/forge/<ver>/forge-<ver>-server.jar,
#        or projects/forge/build/reobfJar/output.jar
# Checks one committed patch symbol per independently patched class, in BYTECODE.
# Exit status: 0 if all present, 1 otherwise.
set -u
J=$(readlink -f "$1"); T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
( cd "$T" && unzip -q -o "$J" 'net/minecraft/world/entity/Entity.class' 'net/minecraft/server/level/ServerChunkCache.class' \
    'net/minecraft/world/level/block/entity/TheEndGatewayBlockEntity.class' 'net/minecraft/server/level/ChunkMap.class' 2>/dev/null )
missing=0
c() {
  local cls=$1 pat=$2 label=$3 n
  n=$(javap -c -p "$T/$cls" 2>/dev/null | grep -c "$pat")
  if [ "$n" -gt 0 ]; then printf "  %-38s %-36s PRESENT\n" "$label" "$pat"; else printf "  %-38s %-36s MISSING\n" "$label" "$pat"; missing=$((missing+1)); fi
}
c net/minecraft/world/entity/Entity.class "nestworldChangeDimensionInner" "Entity (a7550a80f)"
c net/minecraft/server/level/ServerChunkCache.class "DimensionChangeScope.isActive" "ServerChunkCache (a7550a80f)"
c net/minecraft/world/level/block/entity/TheEndGatewayBlockEntity.class "DimensionChangeScope.enter" "TheEndGatewayBlockEntity (a7550a80f)"
c net/minecraft/server/level/ChunkMap.class "nestworldSaveThrottle" "ChunkMap (8733411c8)"
[ "$missing" -eq 0 ]
