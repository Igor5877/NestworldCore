package net.nestworld.modapi.inventory;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * API-1B.6 payload -- value data only. {@code snapshot} is a detached {@code ItemStack.copy()}
 * taken at the public API boundary (independence proven at runtime: API-1B.7 fixture A).
 * {@code playerIncarnation} is an opaque token from {@link PlayerIncarnationRegistry} identifying
 * the exact ServerPlayer object the caller targeted -- deliberately a {@code long}, never the
 * object itself, so a queued request cannot keep a dead player alive. {@code intentVersion} is
 * scoped to the (player, slot, incarnation) domain ({@link InventorySlotVersionTracker}); the two
 * fields are independent axes and are never merged.
 */
public record SetSlotRequest(UUID playerId, int slot, ItemStack snapshot, long intentVersion, long playerIncarnation) {
}
