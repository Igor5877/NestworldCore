package net.nestworld.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Diagnostic-only, no behavior change. Research question (2026-09-03, per user's own correctness
 * concern): TFAR's CraftingStationMenu implements a "lastRecipe" fast-path -- if the previously
 * selected recipe still matches() the current grid, skip the real recipe-manager lookup entirely.
 * That is a genuinely cheaper operation than even the candidate index (zero RecipeManager work
 * at all on a cache hit), but its correctness is NOT obviously guaranteed in the presence of
 * BCLib's namespace-priority conflict resolution: it is conceivable that the grid changes in a
 * way where the OLD cached recipe still matches, but a DIFFERENT, higher-BCLib-priority recipe
 * has also started matching and should now win instead. A naive lastRecipe fast-path would
 * never notice this and would keep returning the stale (lower-priority) recipe -- silently wrong,
 * not just slow.
 *
 * This class does NOT implement a lastRecipe cache. It only SIMULATES one (per-container,
 * weakly keyed so it never leaks/outlives the real menu) alongside every real, authoritative
 * lookup bcl_sort already performs, and checks whether the simulated fast-path would ever have
 * returned something other than what the real full/indexed scan + BCLib comparator just
 * determined. That is the exact, empirical answer to "is a universal lastRecipe fast-path safe
 * to add" -- not a theoretical argument either way.
 */
public final class LastRecipeCacheAudit {

    private LastRecipeCacheAudit() {}

    private static volatile boolean enabled = false;
    private static volatile long windowStartNanos = 0L;

    // Identity-keyed (default Object equals/hashCode) and weak so a closed menu's container can
    // be collected -- this never pins crafting containers alive past their real lifetime.
    private static final Map<Object, Object> lastRecipeByContainer = Collections.synchronizedMap(new WeakHashMap<>());

    private static final LongAdder lookups = new LongAdder();
    private static final LongAdder cacheWouldHit = new LongAdder();
    private static final LongAdder cacheHitWrong = new LongAdder();

    private static final int DETAIL_CAP = 200;
    private static final List<String> violations = Collections.synchronizedList(new ArrayList<>());

    public static void reset() {
        lookups.reset();
        cacheWouldHit.reset();
        cacheHitWrong.reset();
        violations.clear();
        lastRecipeByContainer.clear();
        windowStartNanos = System.nanoTime();
        enabled = true;
    }

    public static void stop() {
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static Object get(Object container) {
        return lastRecipeByContainer.get(container);
    }

    public static void put(Object container, Object recipe) {
        if (recipe == null) {
            lastRecipeByContainer.remove(container);
        } else {
            lastRecipeByContainer.put(container, recipe);
        }
    }

    public static void record(boolean wouldHit, boolean wrong, String detail) {
        if (!enabled) return;
        lookups.increment();
        if (wouldHit) cacheWouldHit.increment();
        if (wrong) {
            cacheHitWrong.increment();
            if (violations.size() < DETAIL_CAP) {
                violations.add(detail);
            }
        }
    }

    public static String report() {
        double windowSeconds = Math.max(0.001, (System.nanoTime() - windowStartNanos) / 1.0e9);
        long n = lookups.sum();
        long hits = cacheWouldHit.sum();
        long wrong = cacheHitWrong.sum();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("LastRecipeCacheAudit: window=%.1fs enabled=%s lookups=%d%n", windowSeconds, enabled, n));
        sb.append(String.format(
                "  simulated cache would-hit=%d (%.1f%% of lookups) -- lookups a lastRecipe fast-path would have skipped entirely%n",
                hits, n == 0 ? 0.0 : 100.0 * hits / n));
        sb.append(String.format(
                "  WRONG cache hits (matches()==true but real BCLib winner differs)=%d  (MUST be 0 for a universal lastRecipe fast-path to be provably safe)%n",
                wrong));
        if (!violations.isEmpty()) {
            sb.append("  --- violation details (first ").append(violations.size()).append(") ---\n");
            for (String v : violations) {
                sb.append("    ").append(v).append("\n");
            }
        }
        return sb.toString();
    }
}
