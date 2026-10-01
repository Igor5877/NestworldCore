package net.nestworld.modapi.entity;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p><b>PLATFORM PROVIDER SPI — not part of the public Mod API.</b> This type is a contract
 * between NestWorldCore and the Mod API provider, not a promise to mods; it may change, or move to
 * a separate artifact, without notice (docs/contracts/PKG-002-distribution-model.md, D1a). Mods
 * use the facades ({@code NestWorldEntities}, {@code NestWorldInventory}) and their value types.
 *
 * API-1A.8: `LATEST_INTENT_WINS` temporal semantics for {@code setVelocity} specifically. Status:
 * IMPLEMENTED, VERIFIED (`docs/contracts/api-1a8-findings.md`). Full design rationale:
 * {@code docs/NESTWORLD_API_ARCHITECTURE.md}'s "API-1A.7 — confirmed ordering inversion; Temporal
 * Semantics decided" section.
 *
 * <p><b>The linearization point, stated precisely</b>: "latest" means the HIGHEST {@code
 * intentVersion} ever handed out by {@link #assignVersion} for a given {@link TargetKey} --
 * never wall-clock time, and never "whichever call happens to reach {@link #tryAccept} last."
 * {@link #assignVersion}'s single atomic increment per key IS the linearization point for two
 * genuinely concurrent callers on the same target: whichever caller's {@code assignVersion} call
 * executes second (as serialized by the underlying {@code ConcurrentHashMap}/{@code AtomicLong})
 * gets the higher version and therefore wins, regardless of how the two calls' subsequent
 * routing/marshaling/reacquisition happen to interleave or complete afterward. Without this
 * single, explicit point, "who was last" is ill-defined for concurrent callers -- defining it
 * anywhere else (e.g. at {@code tryAccept} time) would make the winner depend on scheduling, not
 * on caller intent, reintroducing exactly the ambiguity this class exists to remove.
 *
 * <pre>
 * Responsibility:
 *   Per-target intent versioning for setVelocity ONLY -- deliberately NOT a generic ordering
 *   framework. A stale, older intent must not overwrite a newer one already applied. This
 *   class is the entire mechanism; nothing in EntityMutationDispatcher's generic routing
 *   (dispatch/applyQueued/reroute) knows or needs to know this class exists.
 *
 * Does:
 *   - assign a strictly increasing version number per (dimension, entity) at SUBMISSION time
 *     (before any thread/region handoff -- this is what makes the version reflect caller
 *     intent order, not scheduling order). This assignVersion() call is THE linearization
 *     point -- see the class-level note above.
 *   - accept or reject an apply attempt based on whether its version is still the latest seen
 *     for that target
 *
 * Does NOT:
 *   - provide FIFO delivery, causal ordering between concurrent callers, or any guarantee
 *     beyond "an accepted apply's version was the highest submitted for this target at the
 *     moment it won arbitration" -- "moment" here means position in the assignVersion()
 *     linearization order, not wall-clock time
 *   - apply to any operation family other than SetVelocity -- a future ORDERED_COMMAND-class
 *     primitive (see the architecture doc's Temporal Semantics Classification table) needs
 *     its OWN mechanism, not this one generalized
 *   - guarantee delivery -- LATEST_INTENT_WINS is orthogonal to best-effort/at-most-once
 *     delivery, not an upgrade to it (a winning version can still be silently lost to target
 *     removal or shutdown, same as always)
 *
 * Threading:
 *   assignVersion/tryAccept are both safe to call from any thread (ConcurrentHashMap-backed).
 *
 * Invariant:
 *   Versions are scoped per (dimension, entity UUID) -- the ordering domain -- never global.
 *   Entity A's version sequence and entity B's have no relationship and are never compared.
 *   Dimension migration is intentionally NOT unified into one cross-dimension key: an entity
 *   that changes dimension already becomes a different EntityMutationDispatcher target
 *   (TARGET_NOT_FOUND, per the existing documented gap) -- this class does not silently change
 *   that semantics as a side effect of adding versioning.
 *
 * Failure:
 *   tryAccept returning false is not a failure/exception -- it is the STALE_INTENT_DROPPED
 *   terminal outcome: a newer intent for this target already won, so this older one is
 *   silently discarded, same "silent no-op" posture as target-not-found.
 *
 * Lifecycle:
 *   {@link #resetAll()} is called from NestworldRegionSystem's server-stopping hook -- a new
 *   server generation (including an integrated-server world restart in the same JVM) starts
 *   with a clean version domain, never stale counters/last-applied state from a previous world.
 *
 * Evidence / tests:
 *   Compiles and links correctly. The actual regression test -- replaying API-1A.7's exact
 *   V1/V2 migration interleaving and confirming the final state is now V2 (not V1) -- is
 *   API-1A.8's own verification pass, NOT YET RUN.
 * </pre>
 */
public final class SetVelocityIntentTracker {

    /**
     * The ordering domain for setVelocity's versioning: (dimension, entity UUID). Operation
     * family is implicit (this whole class exists only for SET_VELOCITY) so it is not encoded
     * in the key itself -- a future ORDERED_COMMAND-class primitive needs its own key shape,
     * not a shared one, per this class's own "not a generic ordering framework" constraint.
     */
    public record TargetKey(ResourceKey<Level> dimension, UUID entityId) {}

    private static final ConcurrentHashMap<TargetKey, AtomicLong> COUNTERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<TargetKey, Long> LAST_APPLIED = new ConcurrentHashMap<>();

    private SetVelocityIntentTracker() {}

    /**
     * Call exactly once per public API call, at submission time -- before any thread/region
     * handoff -- so the assigned version reflects caller intent order, never scheduling order.
     */
    public static long assignVersion(TargetKey key) {
        return COUNTERS.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * Call immediately before the actual mutation (e.g. {@code entity.setDeltaMovement(...)}),
     * never earlier. Returns {@code true} (and records {@code version} as the new latest-applied
     * value) only if no newer version has already won for this target; {@code false} means
     * STALE_INTENT_DROPPED -- the caller must skip the mutation.
     */
    public static boolean tryAccept(TargetKey key, long version) {
        Long updated = LAST_APPLIED.compute(key, (k, current) ->
                (current == null || version > current) ? version : current);
        return updated == version;
    }

    /** Server-lifecycle hook -- see class javadoc's "Lifecycle" section. */
    public static void resetAll() {
        COUNTERS.clear();
        LAST_APPLIED.clear();
    }
}
