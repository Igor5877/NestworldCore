package net.nestworld.modapi.inventory;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * <p><b>PLATFORM PROVIDER SPI — not part of the public Mod API.</b> This type is a contract
 * between NestWorldCore and the Mod API provider, not a promise to mods; it may change, or move to
 * a separate artifact, without notice (docs/contracts/PKG-002-distribution-model.md, D1a). Mods
 * use the facades ({@code NestWorldEntities}, {@code NestWorldInventory}) and their value types.
 *
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
