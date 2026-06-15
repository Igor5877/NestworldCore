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
     * Max entities a boat or living entity pushes against per tick — the same
     * O(n^2) cramming cost as minecarts. For living entities the effective cap
     * is raised to the maxEntityCramming game rule so cramming damage still
     * triggers at the vanilla threshold.
     */
    public static final int MAX_ENTITY_PUSH =
            Integer.getInteger("nestworld.maxEntityPush", 8);

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

    /**
     * Weight of one unit of block-tick heat relative to one owned entity when
     * the split scorer scores candidate cut lines (see {@link BlockTickHeat}).
     * Block-tick heat is a decayed per-second count, so a busy redstone column
     * accumulates far more units than it has entities; this scales it down so a
     * single growth tick does not veto a split while a genuine machine still
     * dominates a cut's band score. Tune from the per-region heat reported by
     * {@code /nestworld status}. 0 disables block-tick awareness (entity-only,
     * the pre-8e434bc70 behaviour).
     */
    public static final double CUT_HEAT_WEIGHT =
            Double.parseDouble(System.getProperty("nestworld.cutHeatWeight", "0.15"));

    private NestworldTuning() {
    }
}
