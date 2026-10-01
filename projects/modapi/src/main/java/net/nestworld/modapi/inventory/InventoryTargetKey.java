package net.nestworld.modapi.inventory;

import java.util.UUID;

/**
 * API-1B.5/1B.6: `TargetKey = (playerId, slot)`. `slot` is EXCLUSIVELY the stable flat index
 * (0-40) {@code Inventory}'s own three compartments expose (items[36]+armor[4]+offhand[1]) --
 * NEVER an {@code AbstractContainerMenu} slot index, which is ephemeral (assigned per-GUI-
 * instance at menu construction) and must never leak into this API at all.
 *
 * <p>Dimension is deliberately NOT part of this key -- identity for lookup and identity for
 * routing are different concerns here, same principle {@code setVelocity}'s contract already
 * established. Incarnation is ALSO deliberately not part of this key: it is a property of the
 * request (see {@link PlayerIncarnationRegistry}), and it scopes the temporal domain in
 * {@link InventorySlotVersionTracker}, not the target's identity.
 */
public record InventoryTargetKey(UUID playerId, int slot) {
    /** Player inventory's real compartment layout: items[0-35] + armor[36-39] + offhand[40]. */
    public static final int MIN_SLOT = 0;
    public static final int MAX_SLOT = 40;

    public static boolean isValidSlot(int slot) {
        return slot >= MIN_SLOT && slot <= MAX_SLOT;
    }
}
