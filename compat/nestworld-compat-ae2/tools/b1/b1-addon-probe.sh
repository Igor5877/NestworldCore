#!/bin/bash
# AE2-DEMAIN-001 B1 D5 per-addon fixture (pi-smoke, called by b1-soak.sh with PROBE=1): places one multiblock member of
# each AE2-ecosystem multiblock family (natural setblock → onReady / neighbour path) and two adjacent members (neighbour
# recalculation), then reads the lane's per-calculator / per-BE-type entry counters. Removed afterwards.
E=$1; cd /root/pi-smoke; R() { ./rcon.sh "$@" 2>/dev/null | grep -vE "^$|^>"; }
X=-1000; Y=300; Z=-1000; R "forceload add $X $Z $((X+70)) $Z" >/dev/null; sleep 5
BL=(ae2:1k_crafting_storage megacells:1m_crafting_storage mae2:4x_crafting_accelerator ae2omnicells:omni_crafting_unit_block
    ae2omnicells:complex_crafting_unit_block ae2omnicells:quantum_crafting_unit_block extendedae_plus:4x_crafting_accelerator
    advanced_ae:quantum_core advanced_ae:quantum_unit expatternprovider:assembler_matrix_frame extendedae_plus:super_assembler_matrix_frame
    neoecoae:crafting_casing neoecoae:storage_casing neoecoae:computation_casing molecularmanipulator:omni_computation_casing
    ae2:quantum_ring ae2:spatial_pylon megacells:mega_energy_cell megacells:mega_interface megacells:mega_pattern_provider)
R "nwcompatae2 mblane calculators" > $E/probe-before.txt; R "nwcompatae2 mblane" > $E/probe-lane-before.txt
i=0; for b in "${BL[@]}"; do x=$((X + i*3)); echo "$b @ $x $Y $Z :: $(R "setblock $x $Y $Z $b" | head -1) :: $(R "setblock $x $((Y+1)) $Z $b" | head -1)" >> $E/probe-placed.txt; i=$((i+1)); done
sleep 15
R "nwcompatae2 mblane calculators" > $E/probe-after.txt; R "nwcompatae2 mblane" > $E/probe-lane-after.txt; R "nwae2mb" > $E/probe-mb.txt
i=0; for b in "${BL[@]}"; do x=$((X + i*3)); R "nwcompatae2 domainat $x $Y $Z" >> $E/probe-domains.txt; R "setblock $x $Y $Z air" "setblock $x $((Y+1)) $Z air" >/dev/null; i=$((i+1)); done
sleep 5; R "forceload remove $X $Z $((X+70)) $Z" >/dev/null
