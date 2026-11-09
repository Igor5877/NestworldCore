#!/bin/bash
# AE2 production prerequisite (owner 2026-11-09): every mod whose classes use AE2's JVM-wide MBCalculator state
# (MBCalculator subclasses, CraftingBlockEntity types / subclasses) MUST be pinned until AE2-DEMAIN-001 B1
# selective-unpin verification. Usage: mbcalc-pin-audit.sh <server dir>   (exit 1 if any user is NOT pinned)
S=${1:?server dir}; T=$(mktemp -d); bad=0
while IFS= read -r j; do
  rm -rf "$T/x"; mkdir -p "$T/x"; unzip -q -o "$j" '*.class' -d "$T/x" 2>/dev/null
  mb=$(grep -rlaE "appeng/me/cluster/MBCalculator" "$T/x" | wc -l); cb=$(grep -rlaE "appeng/blockentity/crafting/CraftingBlockEntity" "$T/x" | wc -l)
  [ $((mb + cb)) -gt 0 ] || continue
  mid=$(unzip -p "$j" META-INF/mods.toml 2>/dev/null | grep -m1 -E '^\s*modId\s*=' | sed -E 's/.*"([^"]+)".*/\1/')
  case "$mid" in ae2|nestworld_compat_ae2) continue;; esac
  if grep -xq "$mid" "$S/world/data/nestworld-pinned-mods.txt"; then st=PINNED; else st=NOT-PINNED; bad=1; fi
  echo "$mid ($(basename "$j")): MBCalculator=$mb CraftingBlockEntity=$cb -> $st"
done < <(find "$S/mods" -maxdepth 1 -name '*.jar')
grep -xq ae2 "$S/world/data/nestworld-pinned-mods.txt" || { echo "ae2 -> NOT-PINNED"; bad=1; }
rm -rf "$T"; exit $bad
