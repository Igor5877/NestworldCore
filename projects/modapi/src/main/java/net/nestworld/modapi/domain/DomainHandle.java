package net.nestworld.modapi.domain;

import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A dedicated execution domain (docs/contracts/ExecutionDomainPrimitive.md). Obtain via {@link NestWorldDomains#open}.
 * Status: DESIGN/IMPLEMENTED for internal NestWorld addons; NOT a frozen public Mod API yet.
 */
public interface DomainHandle {
    /**
     * MAIN thread only. Queues {@code job} to run once, FIFO, on a domain thread. {@code touchedLevels} must name every
     * level whose block entities / chunks / block states the job reads or writes. Returns false when the job was NOT
     * queued (no NestWorld Core, domains disabled, not on MAIN, worker unavailable) — the caller must then run it inline.
     */
    boolean submit(Set<ResourceKey<Level>> touchedLevels, Runnable job);

    /** MAIN thread only. Returns when every job submitted so far has finished. This is also the ownership barrier:
     *  pending claim/release requests become effective here (domain idle, epoch bumped, snapshot published). */
    void awaitAll();

    // ------------------------------------------------------------- ownership (model A, TZ AE2-GRID-DOMAIN-001 §3″)

    /**
     * MAIN only. Request that the block entities at {@code positions} (BlockPos longs) in {@code level} — each expected
     * to be of {@code beTypeIds[i]} — be executed by THIS domain instead of MAIN/region. Effective at the next
     * {@link #awaitAll} barrier. A position already owned by another domain is refused. A BE whose type no longer
     * matches is ticked by MAIN/region (never by the domain).
     */
    void claim(ResourceKey<Level> level, long[] positions, String[] beTypeIds);

    /** MAIN only. Give positions back to MAIN/region execution at the next barrier. */
    void release(ResourceKey<Level> level, long[] positions);

    /** Published ownership epoch (bumped at every barrier that changed ownership). */
    long ownershipEpoch();

    /** Is {@code pos} in {@code level} owned by this domain in the currently published snapshot? */
    boolean owns(ResourceKey<Level> level, long pos);

    /**
     * MAIN only. Like {@link #submit} but WITHOUT level exclusion: the job may touch only block entities this domain
     * owns (plus read world state); MAIN keeps running the touched levels' BE phases and chunk tasks concurrently.
     * The acting-main lease still covers {@code touchedLevels}.
     */
    boolean submitOwned(Set<ResourceKey<Level>> touchedLevels, Runnable job);

    /** True on this handle's domain thread(s). */
    boolean isDomainThread();

    /** One-line telemetry. */
    String stats();
}
