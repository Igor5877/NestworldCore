package net.nestworld.modapi.domain;

import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Ordinary-Forge peer: nothing is ever queued, callers run their work inline exactly as without NestWorld. */
final class InlineDomainHandle implements DomainHandle {
    static final InlineDomainHandle INSTANCE = new InlineDomainHandle();

    @Override public boolean submit(Set<ResourceKey<Level>> touchedLevels, Runnable job) { return false; }
    @Override public void awaitAll() {}
    @Override public boolean isDomainThread() { return false; }
    @Override public void claim(ResourceKey<Level> level, long[] positions, String[] beTypeIds) {}
    @Override public void release(ResourceKey<Level> level, long[] positions) {}
    @Override public long ownershipEpoch() { return 0; }
    @Override public boolean owns(ResourceKey<Level> level, long pos) { return false; }
    @Override public boolean submitOwned(Set<ResourceKey<Level>> touchedLevels, Runnable job) { return false; }
    @Override public String stats() { return "domain=INLINE (no NestWorld Core backend)"; }
}
