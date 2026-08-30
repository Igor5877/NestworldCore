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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 11 #31.4 item-on-entity — the minimal choke-point experiment for {@code
 * Entity.interact(Player, InteractionHand)} (plain right-click, e.g. animal breeding/taming,
 * milking) and {@code Entity.interactAt(Player, Vec3, InteractionHand)} (precise-hit-location
 * right-click, e.g. armor stand equipment swap, item frame). See {@link
 * RegionMessage.Type#PLAYER_ENTITY_INTERACT}'s javadoc for the full design rationale.
 *
 * <p>Unlike every prior #31.4 dispatcher, ownership is resolved by the TARGET ENTITY's CURRENT
 * owning region ({@code WorldGrid.findOwningRegion(target.getUUID())} — membership-based, not a
 * position lookup, since an entity mid-transfer briefly has a stale position relative to its real
 * owner), not the acting player's position — {@code interact()}/{@code interactAt()} mutate the
 * target's own fields directly, with no {@link EntityMutationHelper}-style safety net inside
 * vanilla or modded overrides.
 *
 * <p>Deliberately does NOT modify {@code Animal.mobInteract()}, {@code AbstractHorse
 * .mobInteract()}, {@code ArmorStand.interactAt()}, {@code ItemFrame.interact()}, or any other
 * vanilla/modded override — the whole call runs completely unmodified, just on the target's
 * owner thread. Menu-opening branches (villager trading, horse inventory on sneak-interact) are
 * NOT specially handled here — they still run wherever this dispatcher routes them (main or
 * region); container/menu lifecycle across region execution is an explicitly separate, harder,
 * not-yet-started future step.
 */
public final class EntityInteractionDispatcher {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/DIAG");

    private EntityInteractionDispatcher() {}

    private static WorldRegion resolveByTarget(ServerLevel level, Entity target) {
        if (!NestworldRegionSystem.isInitialised() || !NestworldRegionSystem.get().isManagedLevel(level)) {
            return null;
        }
        return NestworldRegionSystem.get().getDimensionRegion(level).getGrid().findOwningRegion(target.getUUID());
    }

    /** Replicates the vanilla/Forge call verbatim for whichever branch this is — {@code atPos ==
     *  null} -> {@code player.interactOn(target, hand)} (matches {@code Player::interactOn}, the
     *  method reference {@code handleInteract}'s plain-interact branch uses); {@code atPos !=
     *  null} -> the Forge hook then {@code target.interactAt(player, atPos, hand)} (matches
     *  {@code handleInteract}'s interactAt-branch lambda exactly). Called identically from both
     *  the main-thread fallback and the region-side apply, so behavior never depends on which
     *  thread actually ran it. */
    private static InteractionResult runInteraction(ServerPlayer player, Entity target, InteractionHand hand, Vec3 atPos) {
        if (atPos == null) {
            return player.interactOn(target, hand);
        }
        InteractionResult forgeResult = net.minecraftforge.common.ForgeHooks.onInteractEntityAt(player, target, atPos, hand);
        if (forgeResult != null) {
            return forgeResult;
        }
        return target.interactAt(player, atPos, hand);
    }

    /**
     * Called from {@code ServerGamePacketListenerImpl.handleInteract()}'s {@code
     * performInteraction} (main thread), before its own direct {@link #runInteraction} call.
     * Returns the region-computed result if dispatched and applied in time, or {@link
     * Optional#empty()} if the caller should run the interaction itself unchanged (flag off,
     * player not opted in, target ownership unresolved, or the bounded wait timed out).
     */
    public static Optional<InteractionResult> dispatch(ServerPlayer player, Entity target, InteractionHand hand, Vec3 atPos) {
        if (!NestworldTuning.PLAYER_ENTITY_INTERACT_REGION_EXECUTION || !PlayerEntityInteractExperiment.isEnabled(player.getUUID())) {
            return Optional.empty();
        }
        ServerLevel level = player.serverLevel();
        WorldRegion owner = resolveByTarget(level, target);
        if (owner == null) {
            return Optional.empty();
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<InteractionResult> resultRef = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean(false);
        owner.nestworldPostMessage(RegionMessage.entityInteract(null, owner,
                level.getServer().getTickCount(),
                new RegionMessage.EntityInteract(player.getUUID(), target.getUUID(), hand, atPos, latch, resultRef, claimed)));
        // NestWorld: shared per-tick wait budget -- see NestworldTuning.MAX_INTERACTION_WAIT_PER_TICK_NANOS's
        // javadoc for the real watchdog crash this closes.
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
                LOGGER.warn("EntityInteractionDispatcher: timed out for player {} on target {} but region {} claimed "
                        + "it just as we gave up -- treating as handled, not falling back",
                        player.getUUID(), target.getUUID(), owner.getId());
                return Optional.of(InteractionResult.PASS);
            }
            LOGGER.warn("EntityInteractionDispatcher: {} waiting for region {} to process "
                    + "interact on target {} -- falling back to main-thread execution",
                    grantedNanos <= 0 ? "per-tick wait budget already exhausted"
                            : "timed out after " + TimeUnit.NANOSECONDS.toMillis(grantedNanos) + "ms",
                    owner.getId(), target.getUUID());
            return Optional.empty();
        }
        return Optional.ofNullable(resultRef.get());
    }

    /**
     * Called by {@code RegionThread}'s own mailbox drain when applying a queued {@code
     * PLAYER_ENTITY_INTERACT} message. Re-resolves the TARGET's CURRENT owner region fresh (not
     * the ownership at dispatch time) -- the target may have transferred to a different region
     * between dispatch and apply (a live split/merge, or the target simply crossing a boundary
     * under its own AI movement). Unlike {@link PlayerAttackDispatcher}/{@link
     * PlayerUseControlDispatcher}, does NOT reroute on a mismatch -- falls back to main instead
     * (via {@code resultRef} staying unset), matching every other synchronous-result dispatcher's
     * "safe direct fallback" precedent.
     */
    public static void applyQueued(RegionMessage.EntityInteract interact, WorldRegion applyingRegion, ServerLevel level) {
        if (!interact.claimed().compareAndSet(false, true)) {
            return;
        }
        try {
            Entity target = level.getEntity(interact.targetId());
            if (target == null) {
                return;
            }
            WorldRegion currentOwner = resolveByTarget(level, target);
            if (currentOwner != applyingRegion) {
                return;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(interact.playerId());
            if (player == null) {
                return;
            }
            long t0 = System.nanoTime();
            InteractionResult result = runInteraction(player, target, interact.hand(), interact.atPos());
            EntityInteractionTiming.recordRegion(System.nanoTime() - t0);
            interact.resultRef().set(result);
        } catch (Throwable t) {
            LOGGER.warn("[#31.4 item-on-entity experiment] interact for {} on {} failed: {}",
                    interact.playerId(), interact.targetId(), t.getMessage());
        } finally {
            interact.latch().countDown();
        }
    }
}
