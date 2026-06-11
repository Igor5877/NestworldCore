package net.nestworld.region;

/**
 * Runtime tuning knobs, overridable via system properties.
 */
public final class NestworldTuning {

    /**
     * Max entities a single minecart collides with per tick. Bounds the
     * O(n^2) cramming cost of entity piles (500 carts in one block =
     * ~250k pair checks per tick in vanilla). Vanilla behavior: unlimited.
     */
    public static final int MAX_MINECART_PUSH =
            Integer.getInteger("nestworld.maxMinecartPush", 8);

    /**
     * Per-tick budget in nanoseconds for one region's entity round. When the
     * round exceeds it, the remaining entities are deferred to the next tick
     * (round-robin), so an unsplittable point hotspot slows down locally
     * instead of dragging the whole server's lockstep tick. Must stay above
     * the split manager's 25 ms threshold or overloaded regions would never
     * register split pressure.
     */
    public static final long REGION_ENTITY_BUDGET_NANOS =
            Integer.getInteger("nestworld.regionEntityBudgetMs", 40) * 1_000_000L;

    private NestworldTuning() {
    }
}
