package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Fix for "#0" (2026-09-03 redstone investigation, see project memory around this date):
 * {@code alternate.current.wire.WireHandler} -- both the level-wide instance created in {@code
 * ServerLevel}'s constructor and every region's own bounded instance (see {@link
 * RegionThread#acWireHandler()}) -- starts with an EMPTY internal network graph and only ever
 * learns about a wire network reactively, via {@code onWireAdded}/{@code onWireUpdated}/
 * {@code onWireRemoved} (see {@link NestworldRedstone}). It never scans already-loaded chunk
 * data on its own. A wire network loaded from disk therefore keeps whatever {@code power} value
 * it had at save time, frozen, until something calls one of those three methods for a position
 * in that network -- which normally only happens via a genuine gameplay event (breaking/placing
 * a wire, or a neighboring power source changing state). That's the root cause of "redstone
 * looks frozen right after a server restart until you touch the wire or a neighbor."
 *
 * <p>Fix: seed the handler once per chunk, the first time that chunk's block data becomes
 * genuinely available ({@code FullChunkStatus.FULL} -- see the {@code ChunkMap
 * #onFullChunkStatusChange} call site, confirmed via {@code ChunkHolder
 * #scheduleFullChunkPromotion} to fire exactly once per threshold crossing on the way up, never
 * repeated for the same load, never fired again on the way down). Scans ONLY that one chunk's
 * sections -- a cheap {@link LevelChunkSection#maybeHas} palette check first, the full 4096-cell
 * scan only for a section whose palette actually contains a wire block -- and calls {@link
 * NestworldRedstone#wireUpdated} for each wire position found: the exact same entry point a
 * manual "touch the wire" already uses, so this reuses AC's own network-discovery logic (a BFS
 * inside {@code WireHandler#onWireUpdated}) rather than reimplementing any of it here.
 *
 * <p>Deliberately NOT a full-world scan at startup: cost is spread across each chunk's own load
 * event, naturally staggered as players explore/load chunks (the same amortization every other
 * chunk-scoped cost in this codebase already relies on). A chunk with no wire in it costs one
 * cheap {@code maybeHas} check per non-empty section and nothing more. Runs on the main thread
 * (the same thread {@code onFullChunkStatusChange} itself runs on), so this always goes through
 * the level's own unbounded {@code nestworldWireHandler} -- correct regardless of which region
 * (if any) will eventually own this chunk, since network discovery here is independent of
 * region ownership.
 */
public final class WireNetworkColdStart {

    private WireNetworkColdStart() {}

    public static void seedChunk(ServerLevel level, ChunkPos chunkPos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (chunk == null) return; // not actually available yet -- nothing to scan

        LevelChunkSection[] sections = chunk.getSections();
        int minBuildHeight = chunk.getMinBuildHeight();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            if (!section.maybeHas(state -> state.getBlock() instanceof RedStoneWireBlock)) continue;

            int sectionMinY = minBuildHeight + (i << 4);
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.getBlock() instanceof RedStoneWireBlock) {
                            BlockPos pos = new BlockPos(
                                    (chunkPos.x << 4) + x, sectionMinY + y, (chunkPos.z << 4) + z);
                            NestworldRedstone.wireUpdated(level, pos);
                        }
                    }
                }
            }
        }
    }
}
