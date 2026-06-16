package net.nestworld.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Per-explosion {@link BlockGetter} that memoises {@code getBlockState} /
 * {@code getFluidState} for the duration of one {@link net.minecraft.world.level.Explosion}.
 *
 * <p>Spark showed explosions dominated by block lookups along raycasts:
 * {@code getSeenPercent} casts dozens of rays per nearby entity that all
 * converge on the blast centre (so they traverse the same central blocks), and
 * the 1352 block-destruction rays step every 0.225 blocks — consecutive steps
 * land in the same {@code BlockPos}. At 7000 TNT in one chunk with thousands of
 * nearby entities this re-reads the same blocks hundreds of times.
 *
 * <p>{@code Explosion.explode()} only READS blocks (destruction is applied later
 * in {@code finalizeExplosion}), and a fresh {@code Explosion} — hence a fresh
 * cache — is created per detonation. So the cache is touched by exactly one
 * region thread for exactly one explosion: thread-safe with no locks, no
 * invalidation, and bit-identical to a live lookup (block states are immutable
 * and unchanged during the read phase).
 */
public final class NestworldExplosionCache implements BlockGetter {

    private final Level level;
    // getBlockState / getFluidState never return null, so null means "not cached".
    private final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<FluidState> fluids = new Long2ObjectOpenHashMap<>();

    public NestworldExplosionCache(Level level) {
        this.level = level;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        long key = pos.asLong();
        BlockState state = this.blocks.get(key);
        if (state == null) {
            state = this.level.getBlockState(pos);
            this.blocks.put(key, state);
        }
        return state;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        long key = pos.asLong();
        FluidState state = this.fluids.get(key);
        if (state == null) {
            state = this.level.getFluidState(pos);
            this.fluids.put(key, state);
        }
        return state;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return this.level.getBlockEntity(pos);
    }

    @Override
    public int getHeight() {
        return this.level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return this.level.getMinBuildHeight();
    }
}
