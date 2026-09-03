package net.nestworld.region;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import net.minecraft.world.item.crafting.RecipeType;

/**
 * Diagnostic-only, no behavior change. Phase 1 of the Recipe Lookup Lag ТЗ
 * (2026-09-01): a runtime census of every call into {@code RecipeManager.byType(RecipeType)}.
 *
 * <p>Hooked at {@code byType} specifically -- NOT at {@code getRecipeFor} -- because BCLib's
 * {@code RecipeManagerMixin.bcl_sort} is a {@code cancellable @Inject(at = "HEAD")} on
 * {@code getRecipeFor} itself: when it cancels, none of getRecipeFor's OWN body (including any
 * instrumentation we could add there via a vanilla patch) ever executes. {@code byType} is a
 * shared dependency both the original vanilla body AND bcl_sort's own injected code call as
 * their very first step (bcl_sort's decompiled source: {@code this.m_44054_(recipeType)}), so
 * hooking there sees every call attempt regardless of which path (vanilla short-circuit or
 * BCLib's full-scan+sort) actually runs afterward. This deliberately does NOT attempt to time
 * each call's duration (that would require wrapping the post-byType matches()/sort() work,
 * which is exactly the part BCLib's cancellable injection can skip past our own code) -- for
 * duration/CPU-cost attribution, JFR execution sampling already does this precisely (see the
 * 2026-09-01 live investigation: 96%+ of Server-thread time during an incident sampled inside
 * this exact call chain). This class answers a different question JFR sampling cannot: exact
 * call COUNT and RATE per caller, which a time-proportional sampler systematically cannot give
 * you for a call that might individually be cheap but happen very often (the AE2
 * every-tick-autocrafting hypothesis this ТЗ exists to rule in or out).
 */
public final class RecipeLookupCensus {

    private RecipeLookupCensus() {}

    private static volatile boolean enabled = false;
    private static volatile long windowStartNanos = 0L;

    private record Key(String caller, String thread, String recipeType) {}

    private static final Map<Key, LongAdder> counts = new ConcurrentHashMap<>();

    /** Called from the main thread only (RCON). Clears all counters and starts a fresh
     *  measurement window -- matches the ТЗ's per-test-scenario reset requirement (Test A-G
     *  each need an independent count, not a cumulative one). */
    public static void reset() {
        counts.clear();
        windowStartNanos = System.nanoTime();
        enabled = true;
    }

    public static void stop() {
        enabled = false;
    }

    /** Called from {@code RecipeManager.byType(RecipeType)} -- see the patch in
     *  {@code patches/minecraft/net/minecraft/world/item/crafting/RecipeManager.java.patch}.
     *  Walks the stack to find the first frame outside this class and outside
     *  {@code RecipeManager}/{@code java.util.stream.*}/{@code java.util.Optional} plumbing --
     *  that frame is credited as the "caller" for census purposes. Deliberately cheap (bounded
     *  frame walk, no string formatting beyond what's needed for the key) since this runs on
     *  the hot path we're trying to diagnose, not just observe from the side. */
    public static void recordCall(RecipeType<?> type) {
        if (!enabled) return;
        String threadName = Thread.currentThread().getName();
        String threadClass = classifyThread(threadName);
        String caller = StackWalker.getInstance().walk(frames -> frames
                .skip(1) // this method itself
                .filter(RecipeLookupCensus::isAttributable)
                .findFirst()
                .map(f -> f.getClassName() + "#" + f.getMethodName())
                .orElse("unknown"));
        String recipeTypeName = safeRecipeTypeName(type);
        counts.computeIfAbsent(new Key(caller, threadClass, recipeTypeName), k -> new LongAdder()).increment();
    }

    private static boolean isAttributable(StackWalker.StackFrame f) {
        String cls = f.getClassName();
        if (cls.equals(RecipeLookupCensus.class.getName())) return false;
        if (cls.equals("net.minecraft.world.item.crafting.RecipeManager")) return false;
        if (cls.startsWith("java.util.stream.")) return false;
        if (cls.equals("java.util.Optional")) return false;
        if (cls.startsWith("java.lang.invoke.")) return false;
        return true;
    }

    private static String classifyThread(String name) {
        if (name.equals("Server thread")) return "Server thread";
        if (name.startsWith("NestWorld-Region-")) return "Region thread";
        if (name.startsWith("Netty ")) return "Netty thread";
        if (name.startsWith("ForkJoinPool")) return "ForkJoinPool";
        return "Other (" + name + ")";
    }

    private static String safeRecipeTypeName(RecipeType<?> type) {
        try {
            // RecipeType doesn't expose its registry name directly pre-1.20.5; identity-based
            // toString is the best we get without a registry lookup, which is fine for census
            // purposes (distinguishing "the crafting-table type" from "everything else").
            return type.toString();
        } catch (Exception e) {
            return "unknown-type";
        }
    }

    /** RCON-facing snapshot. Not synchronized against concurrent recordCall -- counts may be
     *  very slightly under-reported for calls racing the report, which is acceptable for a
     *  diagnostic tool (matches the ТЗ's "aggregate counters" ask, not an exact ledger). */
    public static String report() {
        double windowSeconds = Math.max(0.001, (System.nanoTime() - windowStartNanos) / 1.0e9);
        long total = counts.values().stream().mapToLong(LongAdder::sum).sum();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "RecipeLookupCensus: window=%.1fs enabled=%s totalCalls=%d callsPerSec=%.2f%n",
                windowSeconds, enabled, total, total / windowSeconds));

        record Row(String caller, String thread, String recipeType, long calls) {}
        List<Row> rows = counts.entrySet().stream()
                .map(e -> new Row(e.getKey().caller(), e.getKey().thread(), e.getKey().recipeType(), e.getValue().sum()))
                .sorted(Comparator.comparingLong(Row::calls).reversed())
                .limit(30)
                .collect(Collectors.toList());

        for (Row r : rows) {
            sb.append(String.format(
                    "  %6d calls (%6.2f/s)  thread=%-16s  type=%-40s  caller=%s%n",
                    r.calls(), r.calls() / windowSeconds, r.thread(), r.recipeType(), r.caller()));
        }
        if (counts.size() > 30) {
            sb.append("  ... ").append(counts.size() - 30).append(" more caller/thread/type combinations not shown\n");
        }
        return sb.toString();
    }

    /** Compact per-thread-class rollup -- answers ТЗ §3's "Server thread / Region threads /
     *  Netty threads / Other" breakdown directly without reading the full caller table. */
    public static String reportByThreadClass() {
        double windowSeconds = Math.max(0.001, (System.nanoTime() - windowStartNanos) / 1.0e9);
        Map<String, LongAdder> byThread = new ConcurrentHashMap<>();
        counts.forEach((k, v) -> byThread.computeIfAbsent(k.thread(), t -> new LongAdder()).add(v.sum()));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("RecipeLookupCensus by thread class (window=%.1fs):%n", windowSeconds));
        byThread.entrySet().stream()
                .sorted(Map.Entry.<String, LongAdder>comparingByValue(Comparator.comparingLong(LongAdder::sum)).reversed())
                .forEach(e -> sb.append(String.format(
                        "  %-16s %8d calls (%7.2f/s)%n", e.getKey(), e.getValue().sum(), e.getValue().sum() / windowSeconds)));
        return sb.toString();
    }
}
