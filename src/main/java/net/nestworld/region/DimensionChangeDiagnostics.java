package net.nestworld.region;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Diagnostics for a player's cross-dimension teleport (portal, End platform, gateway): logs, on
 * arrival and again 0.5s / 3s / 10s later, exactly what the SERVER believes is at the arrival
 * point and whether the arrival chunk has been sent to the player yet.
 *
 * <p>Purpose: distinguish "the portal/platform blocks were never written / were overwritten" from
 * "the blocks are on the server but the client got the chunk late" (players falling through an End
 * platform that appears a moment later). Only logs; changes no behaviour. Disable with
 * {@code -Dnestworld.diagDimensionChange=false}.
 */
public final class DimensionChangeDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/DimChange");
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("nestworld.diagDimensionChange", "true"));
    private static final long[] FOLLOW_UP_MS = {500L, 3_000L, 10_000L};

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NestWorld-DimChangeDiag");
        t.setDaemon(true);
        return t;
    });

    private DimensionChangeDiagnostics() {}

    public static final class Token {
        final ResourceKey<Level> from;
        final long startNanos = System.nanoTime();
        final String thread = Thread.currentThread().getName();

        Token(ResourceKey<Level> from) {
            this.from = from;
        }
    }

    public static Token begin(ServerPlayer player) {
        return ENABLED ? new Token(player.level().dimension()) : null;
    }

    public static void end(Token token, ServerPlayer player, ServerLevel destination, Entity result) {
        if (token == null) return;
        try {
            long tookMs = (System.nanoTime() - token.startNanos) / 1_000_000L;
            String head = player.getGameProfile().getName() + " " + token.from.location() + " -> "
                    + destination.dimension().location() + " thread=" + token.thread
                    + " took=" + tookMs + "ms result=" + (result == null ? "null" : "ok");
            snapshot("arrive", head, player);
            MinecraftServer server = player.getServer();
            if (server == null) return;
            for (long delay : FOLLOW_UP_MS) {
                TIMER.schedule(() -> server.execute(() -> {
                    if (!player.hasDisconnected()) snapshot("+" + delay + "ms", head, player);
                }), delay, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable t) {
            LOGGER.warn("dimension-change diagnostics failed: {}", t.toString());
        }
    }

    private static void snapshot(String phase, String head, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        ChunkPos cp = new ChunkPos(pos);
        LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
        String chunkInfo = chunk == null ? "not-loaded"
                : chunk.getClass().getSimpleName() + "/" + chunk.getFullStatus();
        boolean sentToPlayer = level.getChunkSource().chunkMap.getPlayers(cp, false).contains(player);
        int obsidianBelow = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (blockAt(level, pos.offset(dx, -1, dz)) == Blocks.OBSIDIAN) obsidianBelow++;
            }
        }
        int portalBlocks = 0;
        int obsidianAround = 0;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    Block b = blockAt(level, pos.offset(dx, dy, dz));
                    if (b == Blocks.NETHER_PORTAL) portalBlocks++;
                    else if (b == Blocks.OBSIDIAN) obsidianAround++;
                }
            }
        }
        var cache = level.getChunkSource();
        LOGGER.info("[{}] {} pos=({}, {}, {}) chunk={} chunkClass={} sentToPlayer={} feet={} head={} below={} "
                        + "obsidianBelow5x5={} obsidianR5={} netherPortalR5={} proxyCreations={} blockingCalls={} waitMs={}",
                phase, head, String.format("%.2f", player.getX()), String.format("%.2f", player.getY()),
                String.format("%.2f", player.getZ()), cp, chunkInfo, sentToPlayer,
                name(level, pos), name(level, pos.above()), name(level, pos.below()),
                obsidianBelow, obsidianAround, portalBlocks,
                cache.nestworldProxyCreations.get(), cache.nestworldBlockingCalls.get(),
                cache.nestworldWaitNanos.get() / 1_000_000L);
    }

    /** Block at {@code pos} read straight from an already-loaded chunk; null if not loaded. Never
     *  loads or generates anything, so the diagnostics cannot perturb what they observe. */
    private static Block blockAt(ServerLevel level, BlockPos pos) {
        LevelChunk c = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return c == null ? null : c.getBlockState(pos).getBlock();
    }

    private static String name(ServerLevel level, BlockPos pos) {
        Block b = blockAt(level, pos);
        return b == null ? "unloaded" : BuiltInRegistries.BLOCK.getKey(b).toString();
    }
}
