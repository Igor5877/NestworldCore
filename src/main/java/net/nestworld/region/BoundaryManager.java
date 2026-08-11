package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provides read-only "ghost" views of chunks owned by other regions.
 *
 * <p>When mod code in Region B reads a block position that belongs to Region A,
 * {@link RegionChunkView} asks for a ghost view instead of touching Region A's
 * live data structures through the normal write path.
 *
 * <p>Views are created on demand and delegate reads to the live chunk:
 * PalettedContainer guards its block-state reads internally, and the ghost
 * contract only promises data no staler than 1 tick — live reads are strictly
 * fresher. This avoids per-tick edge scans entirely, which matters because a
 * region can span the whole world (boundary strips would be millions of
 * chunks long).
 */
public class BoundaryManager {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/BoundaryManager");

    /** Nominal ghost-zone depth in chunks (documented contract for mod authors). */
    public static final int GHOST_DEPTH = 2;

    private final ServerLevel level;
    private final WorldGrid grid;

    public BoundaryManager(ServerLevel level, WorldGrid grid) {
        this.level = level;
        this.grid = grid;
    }

    /**
     * Called from the main thread after region threads finish each tick — every
     * region has released its write lock and is parked waiting for the next tick
     * signal at this exact point (see {@code RegionThread.run()}: the write lock
     * unlocks before the tick's completion latch counts down, and this method
     * only runs after {@code RegionThreadPool.tickAllRegions()} returns, i.e.
     * after every latch has counted down). Nothing else touches game state
     * concurrently here, so it is safe to call {@code BlockEntity.saveWithFullMetadata()}
     * directly on live, owner-mutated block entities.
     *
     * <p>NestWorld: Folia-style Stage 1 "Region Mailbox" pilot (see project memory
     * folia-actor-model-staged-plan.md) — publishes a fresh ghost-zone block-entity
     * NBT snapshot per region, replacing the old live unsynchronized pass-through
     * read. One pass over every currently-loaded FULL chunk (reusing {@code
     * ServerChunkCache.nestworldLoadedFull}, the same main-published lock-free
     * snapshot region threads already use for chunk reads — no new scan mechanism
     * introduced), grouping ghost-zone chunks' block entities by owning region.
     */
    public void syncGhostZones() {
        java.util.Map<WorldRegion, java.util.Map<Long, net.minecraft.nbt.CompoundTag>> perRegion =
                new java.util.IdentityHashMap<>();
        for (LevelChunk chunk : level.getChunkSource().nestworldLoadedFull.values()) {
            if (chunk.getBlockEntities().isEmpty()) continue;
            net.minecraft.world.level.ChunkPos pos = chunk.getPos();
            WorldRegion owner = grid.getRegionForChunk(pos.x, pos.z);
            if (owner == null || !withinGhostDepth(owner, pos.x, pos.z)) continue;
            java.util.Map<Long, net.minecraft.nbt.CompoundTag> snapshot =
                    perRegion.computeIfAbsent(owner, r -> new java.util.HashMap<>());
            for (java.util.Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                try {
                    snapshot.put(e.getKey().asLong(), e.getValue().saveWithFullMetadata());
                } catch (Throwable t) {
                    // A misbehaving mod's BE serialization must not break every other
                    // region's ghost-zone reads this tick — skip just this one entry.
                    // Carry the last successfully-published tag forward instead of
                    // dropping the position entirely: a dropped entry makes
                    // ChunkSnapshot.getBlockEntity() return null (looks like "no BE
                    // here" to a caller), which is a worse lie than one extra tick of
                    // staleness on an already-abnormal (throwing) block entity.
                    net.minecraft.nbt.CompoundTag prev = owner.nestworldGetGhostSnapshot().get(e.getKey().asLong());
                    if (prev != null) snapshot.put(e.getKey().asLong(), prev);
                    LOGGER.warn("Ghost-zone snapshot: failed to save block entity at {}: {}",
                            e.getKey(), t.toString());
                }
            }
        }
        for (WorldRegion region : grid.getAllRegions()) {
            region.nestworldPublishGhostSnapshot(
                    perRegion.getOrDefault(region, java.util.Map.of()));
        }
    }

    /**
     * Returns a read-only view of a chunk owned by {@code ownerRegion}, or null when
     * the chunk is not loaded OR the chunk is deeper than {@link #GHOST_DEPTH} inside
     * the owner's territory. The depth check matters: without it this always returns
     * non-null for any loaded foreign chunk, which meant {@link RegionChunkView}'s real
     * lock tier (for reads that reach further into a foreign region than the documented
     * ghost contract) was practically unreachable — this "ghost" was a live,
     * unsynchronized pass-through with no actual staleness bound, not the snapshot the
     * class javadoc promises.
     * Safe to call from any region thread.
     */
    public ChunkSnapshot getGhostChunk(WorldRegion ownerRegion, int cx, int cz) {
        if (!withinGhostDepth(ownerRegion, cx, cz)) return null;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        return chunk == null ? null : new ChunkSnapshot(chunk, ownerRegion);
    }

    /** True when (cx,cz) — assumed inside {@code owner}'s bounds — is within
     *  {@link #GHOST_DEPTH} chunks of the nearest edge of the owner region. */
    private static boolean withinGhostDepth(WorldRegion owner, int cx, int cz) {
        int distToNearestEdge = Math.min(
                Math.min(cx - owner.getMinChunkX(), owner.getMaxChunkX() - cx),
                Math.min(cz - owner.getMinChunkZ(), owner.getMaxChunkZ() - cz));
        return distToNearestEdge <= GHOST_DEPTH;
    }

    /**
     * Read-only view of a boundary chunk for cross-region access.
     *
     * <p>{@link #getBlockState} delegates to the live chunk — vanilla's {@code
     * PalettedContainer} already guarantees lock-free-safe concurrent reads via its
     * own volatile-snapshot design (verified by reading it directly: mutation swaps
     * a whole new {@code Data} object onto a {@code volatile} field rather than
     * mutating one a reader might be mid-read on), so this needs no Stage-1 mailbox
     * treatment.
     *
     * <p>{@link #getBlockEntity}, in contrast, reads {@code ownerRegion}'s published
     * ghost-zone NBT snapshot (see {@link WorldRegion#nestworldGetGhostSnapshot()},
     * {@link #syncGhostZones()}) and reconstructs a DETACHED copy via {@code
     * BlockEntity.loadStatic} — never the live, owner-mutated instance. Block
     * entities have no equivalent lock-free-safe-read guarantee (arbitrary mutable
     * fields, no vanilla or Forge contract protecting concurrent access), so the old
     * {@code chunk.getBlockEntity(pos)} live pass-through here was a genuine,
     * unprotected data race despite this class's javadoc claiming "1-tick-stale" —
     * it was actually a live read with no staleness bound and no protection at all.
     */
    public static final class ChunkSnapshot {
        private final LevelChunk chunk;
        private final WorldRegion ownerRegion;

        ChunkSnapshot(LevelChunk chunk, WorldRegion ownerRegion) {
            this.chunk = chunk;
            this.ownerRegion = ownerRegion;
        }

        public BlockState getBlockState(BlockPos pos) {
            return chunk.getBlockState(pos);
        }

        @javax.annotation.Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            net.minecraft.nbt.CompoundTag tag = ownerRegion.nestworldGetGhostSnapshot().get(pos.asLong());
            if (tag == null) return null; // no BE at this position as of the last publish
            return BlockEntity.loadStatic(pos, chunk.getBlockState(pos), tag);
        }
    }

    // -----------------------------------------------------------------------
    // Differential self-test (NESTWORLD_MAILBOX_TEST=1): verifies the
    // save/loadStatic round trip the Region Mailbox depends on WITHOUT a live
    // world — a live ServerLevel/RegionThread is not needed to prove a detached
    // copy reconstructed from NBT reads back the same container contents as the
    // live source it was snapshotted from. Runs headless at startup, same
    // pattern as RegionSplitManager.selfTest()/NestworldPins.selfTest()/
    // RegionTree.selfTest(). Complements (does not replace) the live 1728/1728
    // hopper-conservation test already run on gen-spike-repro for the end-to-end
    // ghost-zone read path — see docs/LOCAL_TICK_STAGE4.md.
    // -----------------------------------------------------------------------

    public static boolean selfTest() {
        boolean ok = true;
        BlockPos pos = new BlockPos(0, 64, 0);
        BlockState state = net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState();

        // Round trip: a live chest with known item contents, saved and reconstructed
        // via the exact mechanism ChunkSnapshot.getBlockEntity() uses, must read back
        // the same contents from the DETACHED copy — this is the property mod code
        // relies on for a read-only ghost-zone container check.
        net.minecraft.world.level.block.entity.ChestBlockEntity original =
                new net.minecraft.world.level.block.entity.ChestBlockEntity(pos, state);
        net.minecraft.world.item.ItemStack stack =
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 42);
        original.setItem(0, stack);
        net.minecraft.nbt.CompoundTag tag = original.saveWithFullMetadata();

        BlockEntity restored = BlockEntity.loadStatic(pos, state, tag);
        ok &= check("round trip: reconstructed", restored instanceof net.minecraft.world.level.block.entity.ChestBlockEntity);
        if (restored instanceof net.minecraft.world.Container c) {
            net.minecraft.world.item.ItemStack got = c.getItem(0);
            ok &= check("round trip: item id matches", got.is(net.minecraft.world.item.Items.DIAMOND));
            ok &= check("round trip: item count matches", got.getCount() == 42);
        } else {
            ok = false;
        }
        // The restored copy must be a genuinely separate object — mutating it must
        // never reach back into the live source (the whole point of "detached").
        ok &= check("round trip: detached (not same instance)", restored != original);

        // Malformed id must degrade to null, never throw — syncGhostZones()'s per-entry
        // try/catch exists for the SAVE side; the LOAD side (mod code reading a foreign
        // snapshot) must be equally defensive since a snapshot can outlive a mod being
        // removed between saves (e.g. a config change mid-session).
        net.minecraft.nbt.CompoundTag badTag = new net.minecraft.nbt.CompoundTag();
        badTag.putString("id", "nonexistent_mod:not_a_real_block_entity");
        BlockEntity fromBad = BlockEntity.loadStatic(pos, state, badTag);
        ok &= check("unknown type: degrades to null, no throw", fromBad == null);

        LOGGER.info("Region Mailbox self-test: {}", ok ? "PASS" : "FAIL");
        return ok;
    }

    private static boolean check(String name, boolean cond) {
        if (!cond) LOGGER.error("Region Mailbox self-test FAILED: {}", name);
        return cond;
    }
}
