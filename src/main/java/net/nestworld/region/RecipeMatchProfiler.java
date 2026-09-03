package net.nestworld.region;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Diagnostic-only, no behavior change. Times EVERY individual Recipe.matches() call made
 * during BCLib's single-pass scan (see the patched bcl_sort in the deployed BCLib jar), and
 * classifies each as cheap/expensive by a duration threshold. This answers the question the
 * candidate-index design needs before it can be shaped correctly: of the ~8500 CRAFTING-type
 * candidates scanned per lookup, how many are actually expensive, and which specific recipes
 * (by id/class) contribute most of that cost -- e.g. ones that fall through to
 * StackedContents$RecipePicker's bipartite-matching-style algorithm for ambiguous/tag-heavy
 * shapeless ingredient lists, which is far more expensive than a simple Ingredient.test() walk.
 *
 * 2026-09-03, Candidate Recipe Index ТЗ -- Phase 0 (profiling before design).
 *
 * Temporary/gated: adds real per-candidate timing overhead (~8500 matches() calls per lookup
 * all individually timed), so this is enabled only for a bounded profiling window via RCON,
 * not left on permanently.
 */
public final class RecipeMatchProfiler {

    private RecipeMatchProfiler() {}

    private static volatile boolean enabled = false;
    private static volatile long windowStartNanos = 0L;

    /** Threshold above which a single matches() call counts as "expensive". 50 microseconds
     *  is comfortably above the cost of a simple Ingredient.test() walk over a handful of
     *  ingredients, and comfortably below what StackedContents$RecipePicker's tree-building
     *  costs in practice (to be confirmed empirically by this very tool's own output). */
    private static final long EXPENSIVE_THRESHOLD_NANOS = 50_000L;

    private static final LongAdder totalCandidates = new LongAdder();
    private static final LongAdder cheapCount = new LongAdder();
    private static final LongAdder expensiveCount = new LongAdder();
    private static final LongAdder totalNanos = new LongAdder();
    private static final LongAdder expensiveNanos = new LongAdder();

    private record Key(String recipeId, String recipeClass, boolean shaped) {}

    private static final class Stats {
        final LongAdder count = new LongAdder();
        final LongAdder sumNanos = new LongAdder();
        final java.util.concurrent.atomic.AtomicLong maxNanos = new java.util.concurrent.atomic.AtomicLong(0L);
    }

    private static final Map<Key, Stats> perRecipe = new ConcurrentHashMap<>();

    public static void reset() {
        totalCandidates.reset();
        cheapCount.reset();
        expensiveCount.reset();
        totalNanos.reset();
        expensiveNanos.reset();
        perRecipe.clear();
        windowStartNanos = System.nanoTime();
        enabled = true;
    }

    public static void stop() {
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Called from the patched bcl_sort loop, once per candidate recipe, wrapping the
     *  matches() call itself. recipeId/recipeClass are cheap to obtain (no registry lookup). */
    public static void recordMatch(String recipeId, String recipeClass, boolean shaped, long durationNanos) {
        if (!enabled) return;
        totalCandidates.increment();
        totalNanos.add(durationNanos);
        boolean expensive = durationNanos >= EXPENSIVE_THRESHOLD_NANOS;
        if (expensive) {
            expensiveCount.increment();
            expensiveNanos.add(durationNanos);
        } else {
            cheapCount.increment();
        }

        Stats s = perRecipe.computeIfAbsent(new Key(recipeId, recipeClass, shaped), k -> new Stats());
        s.count.increment();
        s.sumNanos.add(durationNanos);
        s.maxNanos.accumulateAndGet(durationNanos, Math::max);
    }

    public static String report() {
        double windowSeconds = Math.max(0.001, (System.nanoTime() - windowStartNanos) / 1.0e9);
        long total = totalCandidates.sum();
        long cheap = cheapCount.sum();
        long expensive = expensiveCount.sum();
        long totalNs = totalNanos.sum();
        long expensiveNs = expensiveNanos.sum();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("RecipeMatchProfiler: window=%.1fs enabled=%s%n", windowSeconds, enabled));
        sb.append(String.format(
                "  total candidates checked=%d  cheap(<%.0fus)=%d  expensive(>=%.0fus)=%d%n",
                total, EXPENSIVE_THRESHOLD_NANOS / 1000.0, cheap, EXPENSIVE_THRESHOLD_NANOS / 1000.0, expensive));
        if (total > 0) {
            sb.append(String.format(
                    "  total matches() time=%.1fms  of which expensive-bucket time=%.1fms (%.1f%%)%n",
                    totalNs / 1.0e6, expensiveNs / 1.0e6, 100.0 * expensiveNs / Math.max(1, totalNs)));
        }

        record Row(String id, String cls, boolean shaped, long count, long sumNanos, long maxNanos) {}
        List<Row> rows = perRecipe.entrySet().stream()
                .map(e -> new Row(e.getKey().recipeId(), e.getKey().recipeClass(), e.getKey().shaped(),
                        e.getValue().count.sum(), e.getValue().sumNanos.sum(), e.getValue().maxNanos.get()))
                .sorted(Comparator.comparingLong(Row::sumNanos).reversed())
                .limit(30)
                .collect(Collectors.toList());

        sb.append(String.format("  top contributors by total time (of %d distinct recipes seen):%n", perRecipe.size()));
        for (Row r : rows) {
            sb.append(String.format(
                    "    %8.2fms total (%6d calls, mean=%7.2fus, max=%7.2fus)  %-10s %-6s  %s%n",
                    r.sumNanos() / 1.0e6, r.count(), (r.sumNanos() / (double) r.count()) / 1000.0,
                    r.maxNanos() / 1000.0, r.cls(), r.shaped() ? "shaped" : "shapeless", r.id()));
        }
        return sb.toString();
    }
}
