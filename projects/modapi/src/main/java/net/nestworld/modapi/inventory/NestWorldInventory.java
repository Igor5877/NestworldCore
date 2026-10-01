package net.nestworld.modapi.inventory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public NestWorld Mod API facade for API-1B.5's `setSlot` Bounded Mutation Primitive. Full
 * normative contract: {@code docs/contracts/InventorySetSlotPrimitive.md} -- this class's
 * javadoc summarizes, it does not replace, that document.
 *
 * <p>Zero {@code net.nestworld.region.*} / Core-internal types anywhere in this package's
 * signatures or imports. This class keeps loading and linking correctly even when NestWorldCore
 * is entirely absent from the classpath (ordinary Forge) -- same portability posture as
 * {@code net.nestworld.modapi.entity}.
 *
 * <pre>
 * Responsibility:
 *   The one public entry point for the setSlot Bounded Mutation Primitive.
 *
 * Does:
 *   - accept a live ServerPlayer + slot + Vec3-equivalent-for-inventory (a live ItemStack) for
 *     mod-author ergonomics
 *   - validate the slot is a real player-inventory flat index (0-40), never an
 *     AbstractContainerMenu slot -- silently rejects (logs, no-op, no exception) otherwise
 *   - snapshot the ItemStack (stack.copy()) at THIS call, before any handoff -- the caller's
 *     live ItemStack never crosses an async boundary
 *   - stamp the request with the target object's incarnation token (object identity, see
 *     PlayerIncarnationRegistry) and an intentVersion within that incarnation's domain
 *     (submission-time linearization point)
 *   - select whichever of two peer execution semantics is currently active and delegate
 *
 * Does NOT:
 *   - accept arbitrary caller logic, a Runnable, or any generic "submit" escape hatch
 *   - return a result (fire-and-forget, matching setVelocity's own contract)
 *   - guarantee delivery, or that a returned call means the slot is already visibly changed
 *   - support INSERT/EXTRACT/AbstractContainerMenu addressing/compare-and-swap -- explicitly
 *     out of scope, see the contract doc's "Explicit exclusions"
 *
 * Threading:
 *   Callable from ANY thread. Internally marshals to the correct execution context itself.
 *
 * Invariant:
 *   No live ItemStack/ServerPlayer reference supplied by the caller crosses an asynchronous
 *   handoff unchanged -- only the detached snapshot and captured identity do.
 *
 * Failure (outcome-only, no exception thrown to the caller in any documented case):
 *   invalid slot -> silent no-op (logged)
 *   player no longer connected at apply time -> silent no-op
 *   stale incarnation (the object reacquired by UUID at apply time is not the object the
 *     request was stamped against -- respawn, relogin, or a stale caller reference) -> silent
 *     no-op (INCARNATION_MISMATCH, checked before the version check). No Forge event involved.
 *   stale intent version (a newer setSlot for the same player+slot already won) -> silent no-op
 *     (STALE_INTENT_DROPPED)
 *   Delivery semantics: best-effort, at-most-once, NOT guaranteed-delivered -- same posture as
 *   setVelocity, with MORE documented silent-loss surface (incarnation staleness in addition to
 *   target-not-found/shutdown-mid-flight).
 *
 * Lifecycle:
 *   This class itself is stateless. Which peer answers a call is entirely determined by
 *   InventorySetBackendRegistry.current() at the moment of the call.
 *
 * Evidence / tests:
 *   docs/contracts/api-1b7-findings.md (runtime verification in progress -- NOT declared PASS).
 * </pre>
 */
public final class NestWorldInventory {
    private static final Logger LOGGER = LoggerFactory.getLogger(NestWorldInventory.class);

    private NestWorldInventory() {}

    /**
     * Sets the player's inventory slot {@code slot} to (a detached snapshot of) {@code stack}.
     * Fire-and-forget, no return value, best-effort/at-most-once delivery -- NOT
     * guaranteed-delivered (see the class-level contract above and
     * {@code docs/contracts/InventorySetSlotPrimitive.md}'s "Delivery" section). {@code stack} is
     * snapshotted ({@code copy()}) at THIS call, before any thread/region handoff -- mutating the
     * caller's own {@code stack} after this call returns has NO effect on what gets applied.
     *
     * @param slot the player-inventory flat index (0-40: 0-35 main+hotbar, 36-39 armor, 40
     *             offhand) -- NEVER an {@code AbstractContainerMenu}/GUI slot index
     */
    public static void setSlot(ServerPlayer player, int slot, ItemStack stack) {
        if (!InventoryTargetKey.isValidSlot(slot)) {
            LOGGER.warn("NestWorld modapi: setSlot rejected -- invalid slot {} (valid range {}-{})",
                    slot, InventoryTargetKey.MIN_SLOT, InventoryTargetKey.MAX_SLOT);
            return;
        }
        java.util.UUID playerId = player.getUUID();
        ItemStack snapshot = stack.copy(); // detached -- see class javadoc's Invariant
        // Incarnation FIRST: the version domain is (player, slot, incarnation), so the token must
        // exist before a version can be assigned within it. Both are read here, at submission,
        // from the exact object the caller passed -- a caller holding a stale ServerPlayer
        // reference gets that stale object's token, and the request will be rejected at apply.
        long playerIncarnation = PlayerIncarnationRegistry.incarnationOf(player);
        InventoryTargetKey key = new InventoryTargetKey(playerId, slot);
        long intentVersion = InventorySlotVersionTracker.assignVersion(key, playerIncarnation);
        SetSlotRequest request = new SetSlotRequest(playerId, slot, snapshot, intentVersion, playerIncarnation);

        InventorySetBackend backend = InventorySetBackendRegistry.current();
        if (backend != null) {
            backend.setSlot(request);
        } else {
            ForgeInventorySetExecution.setSlot(player, request);
        }
    }
}
