package net.nestworld.module;

import net.minecraft.resources.ResourceLocation;
import java.util.Objects;
import java.util.UUID;

/** A bounded non-ticking FULL-chunk lease. Coordinates are CHUNK coordinates.
 * Lifetime includes both preparation and the READY hold window; retry after expiry uses a new id. */
public record ChunkPreparationRequest(UUID id, ResourceLocation dimension, int x, int z,
                                      int radius, int lifetimeTicks) {
    public ChunkPreparationRequest {
        Objects.requireNonNull(id);
        Objects.requireNonNull(dimension);
        if (radius < 0 || radius > 4) throw new IllegalArgumentException("radius must be 0..4");
        if (lifetimeTicks < 1 || lifetimeTicks > 1200)
            throw new IllegalArgumentException("lifetimeTicks must be 1..1200");
        if (Math.abs((long) x) + radius > 1_874_999 || Math.abs((long) z) + radius > 1_874_999)
            throw new IllegalArgumentException("chunk coordinates outside world bounds");
    }
}
