package net.nestworld.region;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/**
 * Phase 11 #31.4 step 3 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — the minimal choke-point
 * experiment for {@code ServerPlayerGameMode.useItem()} (no-target item use: eating, drinking,
 * bow draw, ender pearl throw). Deliberately narrower than item-on-block/item-on-entity (left
 * untouched, future steps): {@code useItem()} already collapses its own internal {@code
 * ItemStack.use()} -> {@code InteractionResultHolder<ItemStack>} handling down to a plain {@code
 * InteractionResult} before returning, applying every mutation (held-item swap, hunger/health/
 * effects) to the ACTING PLAYER's own state along the way -- no foreign block or entity is ever
 * touched by this specific method. Owner resolved by PLAYER position (like {@link
 * PlayerAttackDispatcher}), not a foreign target's position.
 *
 * <p>Same synchronous-result-channel shape as {@link PlayerInteractionDispatcher}: the packet
 * handler's vanilla flow (arm swing) needs the real {@code InteractionResult} back, so {@link
 * #dispatch} blocks the calling (main) thread on a bounded wait, falling back to {@code
 * Optional.empty()} (caller runs {@code gameMode.useItem()} itself) on timeout, ownership
 * mismatch, a missing player, or any exception on the region side.
 */
public final class PlayerUseItemDispatcher {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/DIAG");

    private PlayerUseItemDispatcher() {}

    private static WorldRegion resolveByPosition(ServerLevel level, double x, double z) {
        if (!NestworldRegionSystem.isInitialised() || !NestworldRegionSystem.get().isManagedLevel(level)) {
            return null;
        }
        int cx = ((int) Math.floor(x)) >> 4;
        int cz = ((int) Math.floor(z)) >> 4;
        return NestworldRegionSystem.get().getDimensionRegion(level).getGrid().getRegionForChunk(cx, cz);
    }

    /**
     * Called from {@code ServerGamePacketListenerImpl.handleUseItem()} (main thread), before the
     * vanilla direct {@code gameMode.useItem(...)} call. Returns the region-computed result if
     * dispatched and applied in time, or {@link Optional#empty()} if the caller should run {@code
     * gameMode.useItem()} itself unchanged (flag off, player not opted in, owner resolution
     * failed, or the bounded wait timed out).
     */
    public static Optional<InteractionResult> dispatch(ServerPlayer player, InteractionHand hand) {
        if (!NestworldTuning.PLAYER_USE_ITEM_REGION_EXECUTION || !PlayerUseItemExperiment.isEnabled(player.getUUID())) {
            return Optional.empty();
        }
        ServerLevel level = player.serverLevel();
        WorldRegion owner = resolveByPosition(level, player.getX(), player.getZ());
        if (owner == null) {
            return Optional.empty();
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<InteractionResult> resultRef = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean(false);
        owner.nestworldPostMessage(RegionMessage.useItem(null, owner,
                level.getServer().getTickCount(),
                new RegionMessage.UseItem(player.getUUID(), hand, latch, resultRef, claimed)));
        // NestWorld: shared per-tick wait budget -- see NestworldTuning.MAX_INTERACTION_WAIT_PER_TICK_NANOS's
        // javadoc; this is the exact call site the watchdog crash's thread dump was stuck in.
        long requestedNanos = TimeUnit.MILLISECONDS.toNanos(NestworldTuning.PLAYER_INTERACTION_TIMEOUT_MS);
        long grantedNanos = PlayerInteractionWaitBudget.reserve(requestedNanos, level.getServer().getTickCount());
        boolean completed = false;
        if (grantedNanos > 0) {
            long waitStart = System.nanoTime();
            long cpuStart = PlayerInteractionWaitBudget.currentThreadCpuTime();
            try {
                completed = latch.await(grantedNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                PlayerInteractionWaitBudget.recordWait(System.nanoTime() - waitStart, grantedNanos, cpuStart);
            }
        }
        if (!completed) {
            PlayerInteractionWaitBudget.recordFallback(grantedNanos <= 0);
            if (!claimed.compareAndSet(false, true)) {
                LOGGER.warn("PlayerUseItemDispatcher: timed out for player {} but region {} claimed it just as "
                        + "we gave up -- treating as handled, not falling back", player.getUUID(), owner.getId());
                return Optional.of(InteractionResult.PASS);
            }
            LOGGER.warn("PlayerUseItemDispatcher: {} waiting for region {} to process useItem "
                    + "for {} -- falling back to main-thread execution",
                    grantedNanos <= 0 ? "per-tick wait budget already exhausted"
                            : "timed out after " + TimeUnit.NANOSECONDS.toMillis(grantedNanos) + "ms",
                    owner.getId(), player.getUUID());
            return Optional.empty();
        }
        return Optional.ofNullable(resultRef.get());
    }

    /**
     * Called by {@code RegionThread}'s own mailbox drain when applying a queued {@code
     * PLAYER_USE_ITEM} message. Same "whoever wins the claimed CAS actually executes" discipline
     * as {@link PlayerInteractionDispatcher#applyQueued} -- prevents a timed-out-then-fallback
     * dispatch from being double-applied when the region catches up later. Re-resolves the
     * player's CURRENT owner region fresh (not the position at dispatch time) and re-reads the
     * player's CURRENT held item fresh, never trusting a stale snapshot.
     */
    public static void applyQueued(RegionMessage.UseItem useItem, WorldRegion applyingRegion, ServerLevel level) {
        if (!useItem.claimed().compareAndSet(false, true)) {
            return;
        }
        try {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(useItem.playerId());
            if (player == null) {
                return;
            }
            WorldRegion currentOwner = resolveByPosition(player.serverLevel(), player.getX(), player.getZ());
            if (currentOwner != applyingRegion) {
                return;
            }
            ItemStack itemstack = player.getItemInHand(useItem.hand());
            long t0 = System.nanoTime();
            InteractionResult result = player.gameMode.useItem(player, level, itemstack, useItem.hand());
            PlayerUseItemTiming.recordRegion(System.nanoTime() - t0);
            useItem.resultRef().set(result);
        } catch (Throwable t) {
            LOGGER.warn("[#31.4 step3 experiment] useItem for {} failed: {}", useItem.playerId(), t.getMessage());
        } finally {
            useItem.latch().countDown();
        }
    }
}
