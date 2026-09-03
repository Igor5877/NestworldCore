package net.nestworld.region;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Diagnostic-only, no behavior change. Per-tick watcher: for a configured list of block
 * positions in one dimension, logs (every tick, not sampled/averaged) the exact tick number,
 * wall-clock timestamp (ms), main-thread tick cost, region-pool dispatch cost for that tick, and
 * each watched block's full state string -- to a plain file, not RCON (RCON responses aren't
 * meant for continuous per-tick streaming).
 *
 * 2026-09-03, live redstone-desync investigation -- see NestworldDimensionRegion.tick()'s call
 * site for exactly which two timings ("main" / "region") are threaded through.
 */
public final class BlockTickWatcher {

    private BlockTickWatcher() {}

    private static volatile boolean enabled = false;
    private static volatile PrintWriter writer = null;
    private static volatile ResourceKey<Level> watchDimension = Level.OVERWORLD;
    private static final List<BlockPos> watchPositions = new CopyOnWriteArrayList<>();
    private static volatile long lineCount = 0;

    public static synchronized void start(String filePath) {
        stop();
        try {
            Path p = Path.of(filePath);
            writer = new PrintWriter(Files.newBufferedWriter(p,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            writer.println("tick,wallClockMs,mainMs,regionMs,blocks...");
            lineCount = 0;
            enabled = true;
        } catch (IOException e) {
            enabled = false;
            writer = null;
        }
    }

    public static synchronized void stop() {
        enabled = false;
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setDimension(ResourceKey<Level> dim) {
        watchDimension = dim;
    }

    public static void addPos(int x, int y, int z) {
        watchPositions.add(new BlockPos(x, y, z));
    }

    public static void clearPositions() {
        watchPositions.clear();
    }

    public static List<BlockPos> positions() {
        return new ArrayList<>(watchPositions);
    }

    /** Called once per tick from NestworldDimensionRegion.tick(), after everything for that
     *  tick (including region-thread work, which is barrier-joined before this point) has
     *  settled -- so block states read here are the tick's final, externally-consistent
     *  values, not a mid-tick snapshot. */
    public static void sample(ServerLevel level, double mainMs, double regionMs) {
        if (!enabled || writer == null) return;
        if (!level.dimension().equals(watchDimension)) return;
        if (watchPositions.isEmpty()) return;

        long tick = level.getServer().getTickCount();
        long wallClockMs = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        sb.append(tick).append(',').append(wallClockMs).append(',')
                .append(String.format("%.3f", mainMs)).append(',')
                .append(String.format("%.3f", regionMs));
        for (BlockPos pos : watchPositions) {
            BlockState state = level.getBlockState(pos);
            sb.append(',').append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ())
                    .append('=').append(state);
        }
        writer.println(sb);
        lineCount++;
        if ((lineCount & 63) == 0) {
            writer.flush();
        }
    }

    public static String status() {
        return String.format("BlockTickWatcher: enabled=%s dimension=%s positions=%d linesWritten=%d",
                enabled, watchDimension.location(), watchPositions.size(), lineCount);
    }
}
