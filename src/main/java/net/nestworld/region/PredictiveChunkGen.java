package net.nestworld.region;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Layer 3 of the chunk-gen plan: predictive frontier generation. Each tick, for
 * every moving player, project a short frontier of chunks just past view distance
 * along their heading and request generation for them, so the terrain is already
 * built by the time the player arrives — seamless exploration without pre-generating
 * the 1,000,000 x 1,000,000 world.
 *
 * <p><b>Safe + additive.</b> It only adds tickets through the public
 * {@code addRegionTicket} API, so the normal generation pipeline (worker pool,
 * neighbour dependencies) handles them — no critical-path change, no deadlock.
 * Predictive tickets are <em>not</em> {@code PLAYER} tickets, so the tiered
 * {@link NestworldTuning#CHUNK_GEN_BUDGET} treats them as bulk (Tier 2) and paces
 * them: they consume only spare generation capacity and can never freeze the main
 * thread, while the chunk a player actually steps into carries a Tier-1 PLAYER
 * ticket (unthrottled) and finds its terrain already generated.
 *
 * <p>Tickets carry a short timeout, so when a player stops or turns the stale
 * frontier expires on its own — no manual cleanup, no permanently pinned chunks.
 *
 * <p>Default off; enable with {@code -Dnestworld.predictiveGen=true}. Builds on the
 * tiered budget — without a budget it still works, but then a heavy frontier is not
 * paced, so it is intended to run alongside {@code -Dnestworld.chunkGenBudget>0}.
 */
public final class PredictiveChunkGen {

    /** Master switch. */
    public static final boolean ENABLED =
            Boolean.getBoolean("nestworld.predictiveGen");

    /** How many chunks past view distance to pre-generate along the heading. */
    private static final int LOOKAHEAD =
            Integer.getInteger("nestworld.predictiveLookahead", 5);

    /** Half-width (in chunks) of the frontier fan, so strafing/curving still hits. */
    private static final int FAN =
            Integer.getInteger("nestworld.predictiveFan", 1);

    /** Min horizontal speed (blocks/tick) before projecting ahead at all. */
    private static final double MIN_SPEED = 0.08D;

    /** Predictive tickets self-expire after this many ticks once the frontier moves. */
    private static final int TIMEOUT_TICKS = 100;

    /**
     * Distance arg for {@code addRegionTicket}: the ticket level is
     * {@code FULL - distance}, so 0 generates the chunk all the way to FULL
     * (fully generated + saved) but leaves it non-ticking (no entity/mob cost).
     */
    private static final int FULL_DISTANCE = 0;

    private static final TicketType<ChunkPos> PREDICTIVE =
            TicketType.create("nestworld_predictive",
                    Comparator.comparingLong(ChunkPos::toLong), TIMEOUT_TICKS);

    /** Periodic diagnostic log (set {@code -Dnestworld.predictiveLog=true}). */
    private static final boolean LOG = Boolean.getBoolean("nestworld.predictiveLog");

    private static final org.slf4j.Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();

    /** Last seen position per player, to derive a heading. */
    private final Map<UUID, Vec3> lastPos = new HashMap<>();

    /** Diagnostic counters since the last log line. */
    private long issuedSinceLog = 0L;
    private int maxLeadSinceLog = 0;
    private int logCooldown = 0;

    /**
     * Cumulative frontier tickets issued since server start — never reset, unlike
     * {@link #issuedSinceLog}. Exposed for {@code /nestworld chunkpromotion}'s
     * requested/admitted/promoted/backlog breakdown (2026-08-20, live ATM9 incident):
     * comparing this against {@code DistanceManager.nestworldPromotionTotalApplied()}
     * is what actually surfaces a predictive/promotion-budget mismatch (this project's
     * live measurement found predictive issuing ~225-250 tickets/s against a ~40/s
     * promotion budget) instead of it only being visible as an emergent 80k-backlog
     * symptom after the fact.
     */
    private long issuedTotal = 0L;

    public long issuedTotal() { return this.issuedTotal; }

    /**
     * Project and request the frontier for every moving player. Call once per tick
     * on the main thread (player positions are main-thread state).
     */
    public void tick(ServerLevel level) {
        if (!ENABLED) {
            return;
        }
        Set<UUID> present = null;
        int viewDistance = Math.max(2, level.getServer().getPlayerList().getViewDistance());
        for (ServerPlayer player : level.players()) {
            if (player.isRemoved()) {
                continue;
            }
            UUID id = player.getUUID();
            if (present == null) {
                present = new HashSet<>();
            }
            present.add(id);
            Vec3 cur = player.position();
            Vec3 prev = this.lastPos.put(id, cur);
            if (prev == null) {
                continue; // first observation — no heading yet
            }
            double dx = cur.x - prev.x;
            double dz = cur.z - prev.z;
            double speed = Math.sqrt(dx * dx + dz * dz);
            if (speed < MIN_SPEED) {
                continue; // standing still / mining — nothing to pre-generate
            }
            double nx = dx / speed;
            double nz = dz / speed;
            // Perpendicular (for the fan), so a turning player still gets coverage.
            double px = -nz;
            double pz = nx;
            int pcx = player.chunkPosition().x;
            int pcz = player.chunkPosition().z;
            for (int d = 1; d <= LOOKAHEAD; d++) {
                int ahead = viewDistance + d;
                for (int f = -FAN; f <= FAN; f++) {
                    int cx = pcx + (int) Math.round(nx * ahead + px * f);
                    int cz = pcz + (int) Math.round(nz * ahead + pz * f);
                    ChunkPos cp = new ChunkPos(cx, cz);
                    // Re-adding each tick refreshes the timeout on the live frontier;
                    // chunks left behind are not re-added and expire on their own.
                    level.getChunkSource().addRegionTicket(PREDICTIVE, cp, FULL_DISTANCE, cp);
                    this.issuedTotal++;
                    if (LOG) {
                        this.issuedSinceLog++;
                        int lead = Math.abs(cx - pcx) + Math.abs(cz - pcz);
                        if (lead > this.maxLeadSinceLog) {
                            this.maxLeadSinceLog = lead;
                        }
                    }
                }
            }
        }
        if (LOG && this.issuedSinceLog > 0 && --this.logCooldown <= 0) {
            LOGGER.info("[predictive] issued {} frontier tickets (max lead {} chunks ahead, "
                    + "view-distance {})", this.issuedSinceLog, this.maxLeadSinceLog, viewDistance);
            this.issuedSinceLog = 0;
            this.maxLeadSinceLog = 0;
            this.logCooldown = 40; // ~2s between lines
        }
        // Forget players who disconnected, so the heading map can't grow unbounded.
        if (present == null) {
            if (!this.lastPos.isEmpty()) {
                this.lastPos.clear();
            }
        } else if (this.lastPos.size() > present.size()) {
            this.lastPos.keySet().retainAll(present);
        }
    }
}
