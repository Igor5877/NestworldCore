package net.nestworld.region;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntitySelector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * NaturalSpawner player-lookup fix (2026-08-29 Phase B, per the confirmed NaturalSpawner
 * Attribution findings -- naturalspawner-attribution-2026-08-29.md). Replaces the unbounded
 * O(players) linear scan inside {@code EntityGetter.getNearestPlayer()} (called from deep inside
 * {@code NaturalSpawner.spawnCategoryForPosition()}'s innermost attempt loop, up to ~48x per
 * spawn-eligible chunk, every tick) with a cheap grid-bucketed spatial lookup -- SAME result,
 * cheaper way to get it, per the user's explicit "не міняти spawn semantics" requirement.
 *
 * <p><b>Correctness proof (why the bounded search is safe)</b>: this class is used ONLY from
 * {@code NaturalSpawner.spawnCategoryForPosition()}, which is only ever reached for a chunk that
 * already passed {@code ChunkMap.anyPlayerCloseEnoughForSpawning()} (a player within
 * {@code SPAWN_DISTANCE_BLOCK}=128 blocks of the CHUNK's own position) -- OR via
 * {@code DistanceManager.shouldForceTicks()} (a force-loaded chunk, which may have NO player
 * anywhere nearby at all). For the FIRST case: since at least one player is within 128 blocks of
 * the chunk center, and the random spawn position is within the same 16-block chunk (at most
 * ~11.3 blocks from the chunk's own reference corner), the GLOBAL nearest player to ANY point in
 * that chunk is mathematically guaranteed to be within 128 + 16*sqrt(2) &lt; 151 blocks -- a
 * bounded search with SAFETY_RADIUS=256 (generous margin) is therefore GUARANTEED to find the
 * exact same player the old unbounded scan would. For the SECOND case (force-loaded, possibly no
 * nearby player at all): if the bounded search finds NOTHING, {@link #findNearestOrFallback}
 * falls back to the original vanilla unbounded scan -- so correctness never depends on the
 * force-loaded case also being "close" (it usually isn't, and that's fine, the fallback is rare
 * and only pays the O(players) cost in that uncommon case, exactly matching today's behavior for
 * it).
 *
 * <p>Rebuilt lazily once per (level, tick) -- multiple spawn attempts within the same tick reuse
 * the same built index, cheap because it's a single O(players) pass building small grid buckets,
 * done ONCE instead of the OLD code's O(players) cost paid separately for EVERY attempt.
 */
public final class NearestPlayerIndex {
    private NearestPlayerIndex() {}

    private static final int CELL_SIZE = 128;
    /** Proven safe upper bound (151 blocks) with margin -- see class javadoc's proof. */
    private static final double SAFETY_RADIUS = 256.0;
    private static final double SAFETY_RADIUS_SQ = SAFETY_RADIUS * SAFETY_RADIUS;

    /** Pairs a player with its index in {@code level.players()} at cache-build time, so ties
     *  (two players at IDENTICAL squared distance from the query point -- empirically confirmed,
     *  see Phase C.1 mismatch diagnostics, 200/200 sampled mismatches had diffDistSq=0.0000) can
     *  be broken the SAME way vanilla's flat {@code for (Player p : this.players())} scan breaks
     *  them: first-in-list-order wins. Without this, our spatial grid's scan order (cell-by-cell)
     *  picks an arbitrary-but-different winner among exactly-tied candidates. */
    private static final class RankedPlayer {
        final ServerPlayer player;
        final int rank;
        RankedPlayer(ServerPlayer player, int rank) { this.player = player; this.rank = rank; }
    }

    private static final class Cache {
        long builtAtTick = Long.MIN_VALUE;
        Map<Long, List<RankedPlayer>> grid = Map.of();
    }

    private static final Map<ServerLevel, Cache> caches = new ConcurrentHashMap<>();

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static Cache getOrBuild(ServerLevel level) {
        long tick = level.getServer().getTickCount();
        Cache cache = caches.computeIfAbsent(level, k -> new Cache());
        if (cache.builtAtTick == tick) return cache;
        synchronized (cache) {
            if (cache.builtAtTick == tick) return cache; // another thread just built it
            Map<Long, List<RankedPlayer>> grid = new java.util.HashMap<>();
            int rank = 0;
            for (ServerPlayer p : level.players()) {
                if (EntitySelector.NO_SPECTATORS.test(p)) {
                    int cx = Mth.floor(p.getX() / CELL_SIZE);
                    int cz = Mth.floor(p.getZ() / CELL_SIZE);
                    grid.computeIfAbsent(cellKey(cx, cz), k -> new java.util.ArrayList<>(4))
                        .add(new RankedPlayer(p, rank));
                }
                rank++; // matches vanilla's list-order tie-break, regardless of NO_SPECTATORS filtering
            }
            cache.grid = grid;
            cache.builtAtTick = tick;
            return cache;
        }
    }

    /** Fast path: bounded grid search (see class javadoc for the safety proof). Falls back to
     *  the original vanilla unbounded scan if the bounded search finds nothing (force-loaded
     *  chunks with no nearby player, or any other case outside the proof's premise) -- so this
     *  method's result is ALWAYS identical to {@code level.getNearestPlayer(x,y,z,-1.0,false)},
     *  just cheaper in the common (player-nearby) case. */
    public static Player findNearestOrFallback(ServerLevel level, double x, double y, double z) {
        Cache cache = getOrBuild(level);
        int cx = Mth.floor(x / CELL_SIZE);
        int cz = Mth.floor(z / CELL_SIZE);
        int cellRadius = Mth.ceil(SAFETY_RADIUS / CELL_SIZE); // 2 cells at CELL_SIZE=128, radius=256
        ServerPlayer nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        int nearestRank = Integer.MAX_VALUE;
        for (int dx = -cellRadius; dx <= cellRadius; dx++) {
            for (int dz = -cellRadius; dz <= cellRadius; dz++) {
                List<RankedPlayer> bucket = cache.grid.get(cellKey(cx + dx, cz + dz));
                if (bucket == null) continue;
                for (RankedPlayer rp : bucket) {
                    double d = rp.player.distanceToSqr(x, y, z);
                    if (d > SAFETY_RADIUS_SQ) continue;
                    // Tie-break matches vanilla's flat list-order scan (see RankedPlayer javadoc).
                    if (d < nearestDistSq || (d == nearestDistSq && rp.rank < nearestRank)) {
                        nearestDistSq = d;
                        nearestRank = rp.rank;
                        nearest = rp.player;
                    }
                }
            }
        }
        if (nearest != null) return nearest;
        // Fallback: nothing within the safety radius -- rare (force-loaded/sparse chunk), pay
        // the original O(players) cost to stay correct, matches vanilla exactly.
        return level.getNearestPlayer(x, y, z, -1.0D, false);
    }

    public static void invalidate(ServerLevel level) {
        caches.remove(level);
    }

    // --- Phase C correctness verification (2026-08-29) -- calls BOTH implementations and
    // compares, so "bit-identical" is checked empirically on live data, not just argued
    // mathematically. Off by default (doubles the cost of every call while active -- a
    // temporary verification aid, not a permanent feature). Enable with
    // -Dnestworld.naturalSpawnerPlayerIndexVerify=true.
    private static final boolean VERIFY = Boolean.getBoolean("nestworld.naturalSpawnerPlayerIndexVerify");
    private static final LongAdder verifyChecks = new LongAdder();
    private static final LongAdder verifyMismatches = new LongAdder();
    private static final int MAX_MISMATCH_LOG = 200;
    private static final java.util.List<String> mismatchLog = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static Player findNearestOrFallbackVerified(ServerLevel level, double x, double y, double z) {
        Player fast = findNearestOrFallback(level, x, y, z);
        if (VERIFY) {
            Player slow = level.getNearestPlayer(x, y, z, -1.0D, false);
            verifyChecks.increment();
            if (fast != slow) {
                verifyMismatches.increment();
                if (mismatchLog.size() < MAX_MISMATCH_LOG) {
                    logMismatch(level, x, y, z, fast, slow);
                }
            }
        }
        return fast;
    }

    private static void logMismatch(ServerLevel level, double x, double y, double z, Player fast, Player slow) {
        long tick = level.getServer().getTickCount();
        double fastDistSq = fast != null ? fast.distanceToSqr(x, y, z) : -1;
        double slowDistSq = slow != null ? slow.distanceToSqr(x, y, z) : -1;
        String fastRegion = regionOf(level, fast);
        String slowRegion = regionOf(level, slow);
        mismatchLog.add(String.format(
                "tick=%d pos=(%.1f,%.1f,%.1f) | FAST=%s distSq=%.2f region=%s | SLOW=%s distSq=%.2f region=%s | diffDistSq=%.4f",
                tick, x, y, z,
                fast != null ? fast.getGameProfile().getName() : "null", fastDistSq, fastRegion,
                slow != null ? slow.getGameProfile().getName() : "null", slowDistSq, slowRegion,
                Math.abs(fastDistSq - slowDistSq)));
    }

    private static String regionOf(ServerLevel level, Player p) {
        if (p == null) return "n/a";
        try {
            if (!NestworldRegionSystem.isInitialised() || !NestworldRegionSystem.get().isManagedLevel(level)) return "unmanaged";
            int cx = p.getBlockX() >> 4;
            int cz = p.getBlockZ() >> 4;
            WorldRegion r = NestworldRegionSystem.get().getDimensionRegion(level).getGrid().getRegionForChunk(cx, cz);
            if (r == null) return "none";
            return "id=" + r.getId() + (r.isFreeRunning() ? " FREE-RUNNING" : " barrier-synced");
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    public static java.util.List<String> mismatchLog() {
        return mismatchLog;
    }

    public static String verifyReport() {
        return String.format("NW nearest-player-index verify: enabled=%s checks=%,d mismatches=%,d",
                VERIFY, verifyChecks.sum(), verifyMismatches.sum());
    }

    public static void verifyReset() {
        verifyChecks.reset();
        verifyMismatches.reset();
        mismatchLog.clear();
    }
}
