package net.nestworld.region;

import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Entity Safety Layer invariant test (docs/LOCAL_TICK_STAGE4.md, "Entity Safety
 * Layer"): the project owner's explicit request after the Explosion P0 fix — a
 * dedicated check that fails LOUDLY the moment a region thread directly mutates
 * an entity it does not own, rather than relying on the absence of a crash as
 * proof the ownership discipline holds. Every fix in this layer (P0, Boundary-
 * EntityTransfer, ChunkMap tracker fallback) enforces the same rule: "cross-
 * region entity mutation -> only the owning thread mutates." This class is the
 * mechanical check for that rule, not another fix for it.
 *
 * <p>Hooked at the small number of vanilla chokepoints every entity mutation
 * funnels through regardless of call site: {@code Entity.setPosRaw},
 * {@code Entity.setDeltaMovement(Vec3)}, {@code Entity.setRemoved}, and
 * {@code LivingEntity.actuallyHurt} — deliberately NOT hooked at every public
 * mutator (setPos/teleport/hurt/discard/etc. all funnel through these), mirroring
 * the same "hook the chokepoint, not every call site" lesson already learned
 * from the ChunkMap tracker fix.
 *
 * <p>Only flags region-thread-vs-region-thread violations. A main-thread mutation
 * (commands, player packets, event handlers) is not this layer's concern and is
 * intentionally not flagged — {@link RegionThread} instances are the only threads
 * this class treats as having an ownership obligation.
 *
 * <p>Enabled via {@code NESTWORLD_ENTITY_GUARD=1} / {@code -Dnestworld.entityGuard=true}.
 * Zero cost when disabled beyond one {@code static final boolean} check per call.
 */
public final class EntityOwnershipGuard {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/EntityOwnershipGuard");

    public static final boolean ENABLED =
            System.getenv("NESTWORLD_ENTITY_GUARD") != null || Boolean.getBoolean("nestworld.entityGuard");

    private static final AtomicLong violationTotal = new AtomicLong();
    /** Cap how many full stack traces get logged — the counter itself stays exact. */
    private static final int MAX_LOGGED_STACKS = 20;

    private EntityOwnershipGuard() {}

    /**
     * Call at the top of a mutation chokepoint, before the mutation is applied.
     * No-op unless {@link #ENABLED} and the calling thread is a {@link RegionThread}.
     *
     * <p>A newly-spawned entity starts with no owner at all (see
     * {@link NestworldRegionSystem#markOwnershipDirty}) until the event-driven
     * ownership tracker claims it, and a region thread positioning an entity it
     * is about to naturally own (spawning) during its own tick is expected, not
     * a violation. So "unowned by anyone" is deliberately NOT flagged — only a
     * mutation on an entity a DIFFERENT region already owns is the real hazard.
     */
    public static void check(Entity entity, String mutationKind) {
        if (!ENABLED) return;
        Thread t = Thread.currentThread();
        if (!(t instanceof RegionThread rt)) return;
        WorldRegion self = rt.getRegion();
        if (self.ownsEntity(entity.getUUID())) return;
        if (!NestworldRegionSystem.isInitialised()) return;
        NestworldDimensionRegion dr = NestworldRegionSystem.get().getDimensionRegion(entity.level());
        if (dr == null) return;
        WorldRegion actualOwner = dr.getGrid().findOwningRegion(entity.getUUID());
        if (actualOwner == null || actualOwner == self) return;

        long n = violationTotal.incrementAndGet();
        String msg = "NO_FOREIGN_ENTITY_MUTATION violated: region #" + self.getId()
                + " thread called " + mutationKind + " on entity " + entity.getUUID()
                + " (" + entity.getClass().getSimpleName() + ") owned by region #" + actualOwner.getId()
                + " (violation #" + n + ")";
        if (n <= MAX_LOGGED_STACKS) {
            LOGGER.error(msg, new Throwable("NO_FOREIGN_ENTITY_MUTATION stack trace"));
        } else {
            LOGGER.error(msg);
        }
    }

    public static long violations() {
        return violationTotal.get();
    }

    public static void reset() {
        violationTotal.set(0);
    }
}
