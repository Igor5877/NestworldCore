package net.nestworld.region;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Phase 11 #31.4 step 2 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — the block-interaction
 * choke-point experiment. Narrower than attack: covers only {@code BlockState.use()} (lever/
 * button/door-class "flip state" right-clicks), invoked from {@code ServerPlayerGameMode
 * .useItemOn()} right before its own direct call to the same method. Item-use-on-block,
 * container opening, and inventory mutation are explicitly NOT covered here -- separate future
 * steps, each needing its own op-type design (this one only works because {@code
 * InteractionResult} is a plain enum -- genuinely immutable/value-like crossing the thread
 * boundary; item-use-on-block would need to carry a mutated {@code ItemStack} back, which is a
 * different, harder problem deliberately deferred).
 *
 * <p>Unlike every other #31 dispatcher (fire-and-forget), this one needs a SYNCHRONOUS result:
 * the packet handler's vanilla flow (arm swing, "build too high" messaging, {@code
 * CriteriaTriggers}) branches on the real {@code InteractionResult}. {@link #dispatch} therefore
 * blocks the calling (main) thread on a bounded {@link CountDownLatch} wait, falling back to
 * {@code Optional.empty()} (caller runs the vanilla direct call itself) on timeout, ownership
 * mismatch, a missing player, or any exception on the region side -- same "safe direct fallback"
 * precedent as every other #31 dispatcher, just surfaced through {@code Optional} instead of a
 * boolean return.
 */
public final class PlayerInteractionDispatcher {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/DIAG");

    private PlayerInteractionDispatcher() {}

    private static WorldRegion resolveByBlockPos(ServerLevel level, BlockPos pos) {
        if (!NestworldRegionSystem.isInitialised() || !NestworldRegionSystem.get().isManagedLevel(level)) {
            return null;
        }
        return NestworldRegionSystem.get().getDimensionRegion(level).getGrid().getRegionForChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Called from {@code ServerPlayerGameMode.useItemOn()} (main thread), before its own direct
     * {@code blockstate.use(...)} call. Returns the region-computed result if the interaction was
     * successfully dispatched and applied in time, or {@link Optional#empty()} if the caller
     * should run {@code blockstate.use(...)} itself unchanged (flag off, player not opted in,
     * owner resolution failed, or the bounded wait timed out).
     */
    public static Optional<InteractionResult> dispatch(ServerPlayer player, Level level, BlockPos pos,
            InteractionHand hand, BlockHitResult hit) {
        if (!NestworldTuning.PLAYER_INTERACTION_REGION_EXECUTION || !PlayerInteractionExperiment.isEnabled(player.getUUID())) {
            return Optional.empty();
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }
        WorldRegion owner = resolveByBlockPos(serverLevel, pos);
        if (owner == null) {
            return Optional.empty();
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<InteractionResult> resultRef = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean(false);
        RegionMessage<RegionMessage.BlockInteraction> message = RegionMessage.blockInteraction(null, owner,
                serverLevel.getServer().getTickCount(),
                new RegionMessage.BlockInteraction(pos, player.getUUID(), hand, hit, latch, resultRef, claimed));
        owner.nestworldPostMessage(message);
        // NestWorld: shared per-tick wait budget -- see NestworldTuning.MAX_INTERACTION_WAIT_PER_TICK_NANOS's
        // javadoc for the real watchdog crash this closes. Never call latch.await() for longer than what's
        // left of the tick's shared budget; if it's already exhausted, skip the wait entirely.
        long requestedNanos = TimeUnit.MILLISECONDS.toNanos(NestworldTuning.PLAYER_INTERACTION_TIMEOUT_MS);
        long grantedNanos = PlayerInteractionWaitBudget.reserve(requestedNanos, serverLevel.getServer().getTickCount());
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
                // The region thread claimed it in the narrow window right as our wait timed
                // out -- it's actually going to run (or just ran) the interaction. Don't also
                // run it on main (that's exactly the double-apply bug this field exists to
                // prevent) and don't wait further; report "handled" with no visible swing/
                // messaging on THIS click rather than risk a duplicate toggle.
                LOGGER.warn("PlayerInteractionDispatcher: timed out for block interaction at {} but region {} "
                        + "claimed it just as we gave up -- treating as handled, not falling back",
                        pos, owner.getId());
                return Optional.of(InteractionResult.PASS);
            }
            LOGGER.warn("PlayerInteractionDispatcher: {} waiting for region {} to process "
                    + "block interaction at {} -- falling back to main-thread execution",
                    grantedNanos <= 0 ? "per-tick wait budget already exhausted"
                            : "timed out after " + TimeUnit.NANOSECONDS.toMillis(grantedNanos) + "ms",
                    owner.getId(), pos);
            return Optional.empty();
        }
        return Optional.ofNullable(resultRef.get());
    }

    /**
     * Called by {@code RegionThread}'s own mailbox drain when applying a queued {@code
     * PLAYER_BLOCK_INTERACTION} message. Leaves {@code interaction.resultRef()} {@code null}
     * (signalling "fall back to main") on any of: the block is no longer owned by this region
     * (rare -- a split/merge landed between dispatch and apply; no reroute chain for this first
     * narrow experiment, matching the user's explicit "don't over-build this yet" scoping), the
     * player disconnected, or {@code BlockState.use()} itself throws.
     */
    public static void applyQueued(RegionMessage.BlockInteraction interaction, WorldRegion applyingRegion, ServerLevel level) {
        // See RegionMessage.BlockInteraction's javadoc: whoever wins this CAS actually executes
        // the interaction. Losing it means the dispatcher already timed out and fell back to
        // running it on main itself -- executing it again here would double-apply (e.g. flip a
        // lever a second time, silently undoing the fallback's flip).
        if (!interaction.claimed().compareAndSet(false, true)) {
            return;
        }
        try {
            WorldRegion currentOwner = resolveByBlockPos(level, interaction.pos());
            if (currentOwner != applyingRegion) {
                return;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(interaction.playerId());
            if (player == null) {
                return;
            }
            BlockState state = level.getBlockState(interaction.pos());
            long t0 = System.nanoTime();
            InteractionResult result = state.use(level, player, interaction.hand(), interaction.hit());
            PlayerInteractionTiming.recordRegion(System.nanoTime() - t0);
            interaction.resultRef().set(result);
        } catch (Throwable t) {
            LOGGER.warn("[#31.4 step2 experiment] Block interaction at {} failed: {}", interaction.pos(), t.getMessage());
        } finally {
            interaction.latch().countDown();
        }
    }
}
