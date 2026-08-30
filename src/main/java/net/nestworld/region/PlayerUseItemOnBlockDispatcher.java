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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Phase 11 #31.4 step 4 (docs/PHASE11_PLAYER_REGION_EXECUTION_SPEC.md) — the minimal choke-point
 * experiment for {@code ItemStack.useOn(UseOnContext)} (item-on-block: axe strip/scrape/wax-off
 * first target, then {@code BlockItem} placement -- step 4b, same choke point, same protocol).
 * Unlike {@link PlayerUseItemDispatcher} (owner-by-player-position, since {@code useItem()} only
 * ever touches the acting player's own state), this one resolves ownership by POSITION -- same
 * reasoning as {@link PlayerInteractionDispatcher} -- since the mutated block may not belong to
 * the player's own region.
 *
 * <p>Step 4b audit finding (see the audit conclusion in the #31.4 step4b memory): for a {@link
 * BlockItem}, the position that actually gets mutated is NOT necessarily {@code
 * hit.getBlockPos()} (the raw clicked block). {@link BlockPlaceContext#getClickedPos()} returns
 * either the clicked position (if that block {@code canBeReplaced()}) or the ADJACENT position in
 * the click-face direction otherwise -- and since region boundaries follow chunk grid lines, that
 * adjacent position can legitimately fall in a DIFFERENT region than the clicked block itself.
 * {@link #resolveOwnerPos} computes the correct EFFECTIVE position for ownership resolution: the
 * {@code BlockPlaceContext}-resolved position for {@code BlockItem}s, the raw clicked position for
 * everything else (unchanged from the original axe-only behavior, since non-placement items always
 * mutate the block they were clicked on, never an adjacent one).
 *
 * <p>Deliberately does NOT modify {@code AxeItem.useOn()}, {@code BlockItem.useOn()}/{@code
 * place()}, or any other {@code Item} subclass -- the vanilla implementation runs completely
 * unchanged, just on a different thread. Replicates only the small creative-mode count-
 * preservation wrapper {@code ServerPlayerGameMode.useItemOn()} already applies around every
 * {@code useOn()} call, so region-executed and main-executed calls behave identically. The
 * {@code BlockPlaceContext} built here is used ONLY for owner-resolution routing (a read, not a
 * mutation) -- the actual {@code itemstack.useOn(useoncontext)} call lets {@code BlockItem}
 * construct and consult its own fresh {@code BlockPlaceContext} internally, so the authoritative
 * placement decision is always made from current block state read on the executing thread, never
 * from this routing snapshot (this is the "main-side context is a routing hint, not a source of
 * truth for execution" guard the user asked for).
 */
public final class PlayerUseItemOnBlockDispatcher {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/DIAG");

    private PlayerUseItemOnBlockDispatcher() {}

    private static WorldRegion resolveByBlockPos(ServerLevel level, BlockPos pos) {
        if (!NestworldRegionSystem.isInitialised() || !NestworldRegionSystem.get().isManagedLevel(level)) {
            return null;
        }
        return NestworldRegionSystem.get().getDimensionRegion(level).getGrid().getRegionForChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * The EFFECTIVE position to resolve ownership by: for a {@link BlockItem}, the position
     * {@code BlockPlaceContext} will actually place at (clicked or adjacent -- see class javadoc);
     * for every other item, the raw clicked position (what {@code useOn()} actually mutates for
     * axe-strip/scrape/wax-off-class items). Building a {@code BlockPlaceContext} here is a pure
     * read -- it does not touch the world.
     */
    private static BlockPos resolveOwnerPos(ItemStack itemstack, UseOnContext useoncontext) {
        if (itemstack.getItem() instanceof BlockItem) {
            return new BlockPlaceContext(useoncontext).getClickedPos();
        }
        return useoncontext.getClickedPos();
    }

    /**
     * Called from {@code ServerPlayerGameMode.useItemOn()} (main thread), before its own direct
     * {@code itemstack.useOn(useoncontext)} call. Returns the region-computed result if
     * dispatched and applied in time, or {@link Optional#empty()} if the caller should run {@code
     * useOn()} itself unchanged (flag off, player not opted in, owner resolution failed, or the
     * bounded wait timed out).
     */
    public static Optional<InteractionResult> dispatch(ServerPlayer player, InteractionHand hand, BlockHitResult hit) {
        if (!NestworldTuning.PLAYER_USE_ITEM_ON_BLOCK_REGION_EXECUTION || !PlayerUseItemOnBlockExperiment.isEnabled(player.getUUID())) {
            return Optional.empty();
        }
        ServerLevel level = player.serverLevel();
        // Routing hint only -- a pure read, never mutated, never treated as authoritative for
        // execution. The region re-derives this fresh (from current block state) at apply time.
        UseOnContext routingContext = new UseOnContext(player, hand, hit);
        BlockPos ownerPos = resolveOwnerPos(routingContext.getItemInHand(), routingContext);
        WorldRegion owner = resolveByBlockPos(level, ownerPos);
        if (owner == null) {
            return Optional.empty();
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<InteractionResult> resultRef = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean(false);
        owner.nestworldPostMessage(RegionMessage.useItemOnBlock(null, owner,
                level.getServer().getTickCount(),
                new RegionMessage.UseItemOnBlock(player.getUUID(), hand, hit, latch, resultRef, claimed)));
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
                LOGGER.warn("PlayerUseItemOnBlockDispatcher: timed out for player {} at {} but region {} claimed "
                        + "it just as we gave up -- treating as handled, not falling back",
                        player.getUUID(), hit.getBlockPos(), owner.getId());
                return Optional.of(InteractionResult.PASS);
            }
            LOGGER.warn("PlayerUseItemOnBlockDispatcher: {} waiting for region {} to process "
                    + "useOn at {} -- falling back to main-thread execution",
                    grantedNanos <= 0 ? "per-tick wait budget already exhausted"
                            : "timed out after " + TimeUnit.NANOSECONDS.toMillis(grantedNanos) + "ms",
                    owner.getId(), hit.getBlockPos());
            return Optional.empty();
        }
        return Optional.ofNullable(resultRef.get());
    }

    /**
     * Called by {@code RegionThread}'s own mailbox drain when applying a queued {@code
     * PLAYER_USE_ITEM_ON_BLOCK} message. Same "whoever wins the claimed CAS actually executes"
     * discipline as {@link PlayerInteractionDispatcher#applyQueued} -- prevents a timed-out-then-
     * fallback dispatch from being double-applied (e.g. an axe stripping a log twice, or losing
     * two durability points instead of one) when the region catches up later. Re-resolves the
     * block's CURRENT owner region fresh and re-reads the player's CURRENT held item fresh.
     */
    public static void applyQueued(RegionMessage.UseItemOnBlock useItemOnBlock, WorldRegion applyingRegion, ServerLevel level) {
        if (!useItemOnBlock.claimed().compareAndSet(false, true)) {
            return;
        }
        try {
            BlockHitResult hit = useItemOnBlock.hit();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(useItemOnBlock.playerId());
            if (player == null) {
                return;
            }
            // Fresh reconstruction, not a reuse of the main-side routing snapshot -- re-reads the
            // player's CURRENT held item and (via BlockPlaceContext, for a BlockItem) the CURRENT
            // block state at the candidate position, so a block that changed between dispatch and
            // apply is re-evaluated correctly rather than trusting a stale routing decision.
            UseOnContext useoncontext = new UseOnContext(player, useItemOnBlock.hand(), hit);
            ItemStack itemstack = useoncontext.getItemInHand();
            BlockPos ownerPos = resolveOwnerPos(itemstack, useoncontext);
            WorldRegion currentOwner = resolveByBlockPos(level, ownerPos);
            if (itemstack.getItem() instanceof BlockItem) {
                LOGGER.info("[#31.4 step4b DIAG] BlockItem placement: clickedPos={} effectivePos={} "
                        + "applyingRegion={} currentOwner={} -- {}",
                        hit.getBlockPos(), ownerPos, applyingRegion.getId(),
                        currentOwner == null ? "null" : currentOwner.getId(),
                        currentOwner == applyingRegion ? "ACCEPTED" : "REJECTED-mismatch");
            }
            if (currentOwner != applyingRegion) {
                return;
            }
            long t0 = System.nanoTime();
            InteractionResult result;
            // NestWorld: replicates ServerPlayerGameMode.useItemOn()'s own creative-mode count-
            // preservation wrapper verbatim, so this behaves identically to the main-thread path.
            if (player.gameMode.isCreative()) {
                int i = itemstack.getCount();
                result = itemstack.useOn(useoncontext);
                itemstack.setCount(i);
            } else {
                result = itemstack.useOn(useoncontext);
            }
            PlayerUseItemOnBlockTiming.recordRegion(System.nanoTime() - t0);
            useItemOnBlock.resultRef().set(result);
        } catch (Throwable t) {
            LOGGER.warn("[#31.4 step4 experiment] useOn for {} failed: {}", useItemOnBlock.playerId(), t.getMessage());
        } finally {
            useItemOnBlock.latch().countDown();
        }
    }
}
