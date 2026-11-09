package net.nestworld.region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CORE-SPLIT-DENSE-BE-GUARD-001 Stage 0 (owner 2026-11-09): MEASUREMENT ONLY — never changes a split / merge decision.
 * Per dimension:
 * <ul>
 * <li>ghost-zone serialization cost: per owner region (EWMA ms/tick, BEs, lock misses), per BlockEntityType (EWMA µs per
 * save, count), and a sampled unchanged-NBT rate (1 in {@value #UNCHANGED_SAMPLE}; baseline for CORE-GHOST-ZONE-DELTA-001);</li>
 * <li>MAIN block-entity phase cost (EWMA ms/tick of the tickers routed to the server thread: pinned + border band);</li>
 * <li>a prediction for every split line about to be applied (shadow verdict, logged): new ghost-strip BEs and predicted
 * ghost cost G, border-band BEs by policy and the band load share M, parallel gain, net, would-veto;</li>
 * <li>a post-split record {@value #POST_SPLIT_SECONDS} s later: measured child cost, ghost delta, MAIN BE delta,
 * measured net, measured / predicted.</li>
 * </ul>
 * Main thread only (syncGhostZones, the BE phase, doSplit and the command all run on the server thread).
 */
public final class SplitGuardTelemetry {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/SplitGuard");
    public static final boolean ENABLED = !"false".equals(System.getProperty("nestworld.splitGuardTelemetry"));
    static final int UNCHANGED_SAMPLE = 16;
    static final int POST_SPLIT_SECONDS = Integer.getInteger("nestworld.splitGuardPostSplitSeconds", 30);
    static final double DEFAULT_BE_SAVE_US = Double.parseDouble(System.getProperty("nestworld.splitGuardDefaultBeSaveUs", "8"));
    static final double THREAD_OVERHEAD_MS = 0.3, MARGIN_ABS_MS = 2.0, MARGIN_REL = 0.2, ALPHA = 0.05;
    private static final int HISTORY = 32;

    private final ServerLevel level;
    private final String dim;

    // ---- ghost cost (per call accumulators, then EWMA)
    private final Map<BlockEntityType<?>, double[]> typeCost = new IdentityHashMap<>(); // {ewmaNs, count, totalNs}
    private final Map<Integer, double[]> regionGhost = new HashMap<>();                  // {ewmaMs, lastBes, lockMisses}
    private final Map<Integer, long[]> callRegion = new HashMap<>();                      // {ns, bes}
    private double ghostEwmaMs, nbtEwmaMs;
    private long ghostCalls, sampled, sampledUnchanged, sampleTick;

    // ---- MAIN BE phase
    private double mainBeEwmaMs;
    private long mainBeLastCount;

    // ---- split predictions
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final List<Pending> pending = new ArrayList<>();
    private long splits, wouldVeto;

    SplitGuardTelemetry(ServerLevel level) {
        this.level = level;
        this.dim = level.dimension().location().toString();
    }

    // ================================================================ ghost zones (BoundaryManager)

    void ghostBeginCall() {
        callRegion.clear();
    }

    /** True for 1 save in {@value #UNCHANGED_SAMPLE}: the caller then passes the previous tag to {@link #ghostSample}. */
    boolean ghostSampleNext() {
        return (++sampleTick % UNCHANGED_SAMPLE) == 0;
    }

    void ghostSample(net.minecraft.nbt.CompoundTag tag, net.minecraft.nbt.CompoundTag prev) {
        if (prev == null) return;
        sampled++;
        if (prev.equals(tag)) sampledUnchanged++;
    }

    /** One saveWithFullMetadata of {@code be} for owner {@code regionId}. */
    void ghostSaved(int regionId, BlockEntity be, long nanos) {
        long[] r = callRegion.computeIfAbsent(regionId, k -> new long[2]);
        r[0] += nanos;
        r[1]++;
        double[] t = typeCost.computeIfAbsent(be.getType(), k -> new double[]{nanos, 0, 0});
        t[0] += ALPHA * (nanos - t[0]);
        t[1]++;
        t[2] += nanos;
    }

    void ghostLockMiss(int regionId) {
        regionGhost.computeIfAbsent(regionId, k -> new double[3])[2]++;
    }

    void ghostEndCall(long totalNanos, long nbtNanos) {
        ghostCalls++;
        ghostEwmaMs += ALPHA * (totalNanos / 1e6 - ghostEwmaMs);
        nbtEwmaMs += ALPHA * (nbtNanos / 1e6 - nbtEwmaMs);
        for (Map.Entry<Integer, long[]> e : callRegion.entrySet()) {
            regionGhost.computeIfAbsent(e.getKey(), k -> new double[3])[1] = e.getValue()[1];
        }
        var it = regionGhost.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            long[] c = callRegion.get(e.getKey());
            double ms = c == null ? 0 : c[0] / 1e6;
            double[] g = e.getValue();
            g[0] += ALPHA * (ms - g[0]);
            if (c == null) g[1] = 0;
            if (c == null && g[0] < 0.0005 && g[2] == 0) it.remove();
        }
    }

    double regionGhostMs(int regionId) {
        double[] g = regionGhost.get(regionId);
        return g == null ? 0 : g[0];
    }

    /** EWMA save cost of {@code type}; unseen types use the mean of all measured saves, or the default (8 us, live
     *  2026-11-09: 26.7 ms / 3 316 BEs) when nothing was measured yet (e.g. a single-region layout has no ghost strips). */
    double typeSaveUs(BlockEntityType<?> type) {
        double[] t = typeCost.get(type);
        if (t != null && t[1] >= 32) return t[0] / 1000.0;
        double ns = 0, n = 0;
        for (double[] v : typeCost.values()) { ns += v[2]; n += v[1]; }
        return n >= 256 ? ns / n / 1000.0 : DEFAULT_BE_SAVE_US;
    }

    // ================================================================ MAIN BE phase (NestworldDimensionRegion)

    void mainBePhase(long nanos, int count) {
        mainBeEwmaMs += ALPHA * (nanos / 1e6 - mainBeEwmaMs);
        mainBeLastCount = count;
    }

    // ================================================================ split prediction (RegionSplitManager hook)

    private record Pending(long dueNanos, int parentId, int[] childIds, WorldRegion[] children, double tR,
                           double ghostBefore, double mainBefore, double parentGhostBefore, double predGain, double predG, double predM,
                           double predNet, String line) {
    }

    /** Before {@code tree.split}: prediction for the line about to be applied. Returns the log line. */
    String predict(WorldRegion region, SplitAxis axis, int cut, String reason, NestworldPins pins, NestworldDimensionRegion dr) {
        long t0 = System.nanoTime();
        boolean x = axis == SplitAxis.X;
        int bb = NestworldTuning.BORDER_BAND_CHUNKS, gd = BoundaryManager.GHOST_DEPTH;
        int min = x ? region.getMinChunkX() : region.getMinChunkZ(), max = x ? region.getMaxChunkX() : region.getMaxChunkZ();
        int stripLo = Math.max(min, cut - gd), stripHi = Math.min(max, cut + 1 + gd);   // A: [cut-gd, cut]  B: [cut+1, cut+1+gd]
        int bandLo = cut - bb + 1, bandHi = cut + bb;                                    // scorer's 2×band (cut-bb, cut+bb]
        double nsA = 0, nsB = 0, nsBand = 0, predGus = 0;
        int stripBes = 0, stripPinned = 0, bandNormal = 0, bandPinned = 0, bandCascade = 0, loaded = 0;
        Map<String, Integer> stripTypes = new HashMap<>();
        for (LevelChunk chunk : level.getChunkSource().nestworldLoadedFull.values()) {
            var cp = chunk.getPos();
            if (cp.x < region.getMinChunkX() || cp.x > region.getMaxChunkX() || cp.z < region.getMinChunkZ() || cp.z > region.getMaxChunkZ()) continue;
            loaded++;
            int c = x ? cp.x : cp.z;
            double ns = TickAttribution.chunkCostNanos(dim, cp.toLong());
            if (c <= cut) nsA += ns; else nsB += ns;
            boolean strip = c >= stripLo && c <= stripHi, band = c >= bandLo && c <= bandHi;
            if (band) nsBand += ns;
            if (!strip && !band) continue;
            for (Map.Entry<BlockPos, BlockEntity> e : new ArrayList<>(chunk.getBlockEntities().entrySet())) {
                BlockEntity be = e.getValue();
                ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
                String typeId = id == null ? null : id.toString();
                int policy = dr.blockEntityPolicy(pins, typeId, e.getKey());
                if (strip) {
                    stripBes++;
                    predGus += typeSaveUs(be.getType());
                    if (policy == NestworldDimensionRegion.BE_POLICY_PINNED) stripPinned++;
                    if (typeId != null) stripTypes.merge(typeId, 1, Integer::sum);
                }
                if (band) {
                    if (policy == NestworldDimensionRegion.BE_POLICY_PINNED) bandPinned++;
                    else if (policy == NestworldDimensionRegion.BE_POLICY_CASCADE_SAFE) bandCascade++;
                    else bandNormal++;
                }
            }
        }
        double tR = region.getAvgTickMs(), total = nsA + nsB;
        double shareMax = total > 0 ? Math.max(nsA, nsB) / total : 0.5, bandShare = total > 0 ? nsBand / total : 0;
        double gain = tR * (1 - shareMax) - THREAD_OVERHEAD_MS;
        double g = predGus / 1000.0;
        double m = tR * bandShare;          // upper bound: the band's whole chunk load (entities stay on the region)
        double net = gain - g - m, margin = Math.max(MARGIN_ABS_MS, MARGIN_REL * tR);
        boolean veto = net <= margin;
        splits++;
        if (veto) wouldVeto++;
        String top = topN(stripTypes, 4);
        String line = String.format(java.util.Locale.ROOT,
                "SPLIT_GUARD_PREDICT dim=%s region=#%d reason=%s line=%s=%d T_R=%.2fms parentGhost=%.2fms shareA/B=%.2f/%.2f gain=%.2fms "
                        + "G=%.2fms(stripBes=%d pinned=%d top=%s) M<=%.2fms(bandShare=%.2f band normal/cascade/pinned=%d/%d/%d) "
                        + "net=%.2fms margin=%.2fms shadowVerdict=%s loadedChunks=%d calcMs=%.2f mode=telemetry(no effect)",
                dim, region.getId(), reason, axis, cut, tR, regionGhostMs(region.getId()), total > 0 ? nsA / total : 0, total > 0 ? nsB / total : 0, gain,
                g, stripBes, stripPinned, top, m, bandShare, bandNormal, bandCascade, bandPinned, net, margin,
                veto ? "WOULD_VETO_LINE" : "accept", loaded, (System.nanoTime() - t0) / 1e6);
        LOGGER.info(line);
        pending.add(new Pending(0, region.getId(), null, null, tR, ghostEwmaMs, mainBeEwmaMs, regionGhostMs(region.getId()),
                gain, g, m, net, line));
        return line;
    }

    // ================================================================ Stage 1: shadow scorer over all candidate lines

    static final double SHADOW_MARGIN_MS = Double.parseDouble(System.getProperty("nestworld.splitGuardMarginMs", "3.0"));
    static final long SHADOW_INTERVAL_NANOS = 60_000_000_000L;
    private final Map<Integer, Long> lastShadow = new HashMap<>();
    private long shadowEvals, shadowNoGoodLine, shadowScorerChoiceRejected;

    /**
     * CORE-SPLIT-DENSE-BE-GUARD-001 Stage 1 (shadow, NO effect): whenever the split heuristic wants to split
     * {@code region} (applied or suppressed by autoSplit=false), score every candidate line on both axes with
     * {@code net = parallelGain − ghostCost − mainBandCost} and log the verdicts, including the line today's scorer
     * picks ({@code scorerAxis/scorerCut}, null when it picks none). At most once per region per minute.
     */
    void shadowScore(WorldRegion region, String reason, SplitAxis scorerAxis, Integer scorerCut, NestworldPins pins,
                     NestworldDimensionRegion dr) {
        long now = System.nanoTime();
        Long last = lastShadow.get(region.getId());
        if (last != null && now - last < SHADOW_INTERVAL_NANOS) return;
        lastShadow.put(region.getId(), now);
        long t0 = now;
        java.util.TreeMap<Integer, double[]> colX = new java.util.TreeMap<>(), colZ = new java.util.TreeMap<>(); // {ns, ghostUs, bes}
        for (LevelChunk chunk : level.getChunkSource().nestworldLoadedFull.values()) {
            var cp = chunk.getPos();
            if (cp.x < region.getMinChunkX() || cp.x > region.getMaxChunkX() || cp.z < region.getMinChunkZ() || cp.z > region.getMaxChunkZ()) continue;
            double ns = TickAttribution.chunkCostNanos(dim, cp.toLong()), gus = 0;
            int bes = 0;
            for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                gus += typeSaveUs(be.getType());
                bes++;
            }
            for (var m : java.util.List.of(colX, colZ)) {
                double[] v = m.computeIfAbsent(m == colX ? cp.x : cp.z, k -> new double[3]);
                v[0] += ns;
                v[1] += gus;
                v[2] += bes;
            }
        }
        double tR = region.getAvgTickMs();
        int bb = NestworldTuning.BORDER_BAND_CHUNKS, gd = BoundaryManager.GHOST_DEPTH;
        List<String> lines = new ArrayList<>();
        String bestLine = null, scorerVerdict = "none";
        double bestNet = Double.NEGATIVE_INFINITY;
        int candidates = 0, accepted = 0;
        for (SplitAxis axis : SplitAxis.values()) {
            java.util.TreeMap<Integer, double[]> col = axis == SplitAxis.X ? colX : colZ;
            if (col.size() < 2) continue;
            double total = 0;
            for (double[] v : col.values()) total += v[0];
            if (total <= 0) continue;
            int lo = col.firstKey(), hi = col.lastKey();
            for (int c = lo; c < hi; c++) {
                double nsA = 0, ghostUs = 0, band = 0, stripBes = 0;
                for (var e : col.entrySet()) {
                    int k = e.getKey();
                    double[] v = e.getValue();
                    if (k <= c) nsA += v[0];
                    if (k >= c - gd && k <= c + 1 + gd) { ghostUs += v[1]; stripBes += v[2]; }
                    if (k >= c - bb + 1 && k <= c + bb) band += v[0];
                }
                if (Math.min(nsA, total - nsA) < total * 0.10) continue;   // same separation rule as the scorer
                candidates++;
                double gain = tR * (1 - Math.max(nsA, total - nsA) / total) - THREAD_OVERHEAD_MS;
                double g = ghostUs / 1000.0, m = tR * band / total, net = gain - g - m;
                boolean ok = net > SHADOW_MARGIN_MS;
                if (ok) accepted++;
                String l = String.format(java.util.Locale.ROOT, "%s=%d:%+.1f(g%.1f,G%.1f,M%.1f,bes%d)%s", axis, c, net, gain, g, m,
                        (long) stripBes, ok ? "" : "R");
                lines.add(l);
                if (net > bestNet) { bestNet = net; bestLine = axis + "=" + c; }
                if (scorerAxis == axis && scorerCut != null && scorerCut == c) {
                    scorerVerdict = String.format(java.util.Locale.ROOT, "%s=%d net=%+.1fms %s", axis, c, net, ok ? "ACCEPT" : "WOULD_REJECT");
                    if (!ok) shadowScorerChoiceRejected++;
                }
            }
        }
        shadowEvals++;
        if (accepted == 0) shadowNoGoodLine++;
        if (scorerAxis != null && scorerCut != null && "none".equals(scorerVerdict)) {
            scorerVerdict = scorerAxis + "=" + scorerCut + " (not a scored candidate: fails separation)";
        }
        lines.sort((a, b) -> Double.compare(Double.parseDouble(b.substring(b.indexOf(':') + 1, b.indexOf('('))),
                Double.parseDouble(a.substring(a.indexOf(':') + 1, a.indexOf('(')))));
        String shown = lines.size() <= 6 ? String.join(" ", lines)
                : String.join(" ", lines.subList(0, 3)) + " … " + String.join(" ", lines.subList(lines.size() - 3, lines.size()));
        String line = String.format(java.util.Locale.ROOT,
                "SPLIT_GUARD_SHADOW dim=%s region=#%d reason=%s T_R=%.2fms margin=%.1fms candidates=%d accepted=%d best=%s(%+.1fms) "
                        + "verdict=%s scorerChoice=[%s] lines(net,gain,G,M,stripBes;R=reject)=[%s] calcMs=%.2f mode=shadow(no effect)",
                dim, region.getId(), reason, tR, SHADOW_MARGIN_MS, candidates, accepted, bestLine == null ? "-" : bestLine,
                bestLine == null ? 0 : bestNet, accepted == 0 ? "NO_GOOD_LINE(would stay whole)" : "SPLIT_AT_BEST", scorerVerdict, shown,
                (System.nanoTime() - t0) / 1e6);
        LOGGER.info(line);
        remember(line);
    }

    /** After the split was applied (or not: {@code children == null} drops the pending record). */
    void splitApplied(WorldRegion[] children) {
        if (pending.isEmpty()) return;
        Pending p = pending.remove(pending.size() - 1);
        if (children == null) {
            remember("SPLIT_GUARD_NOT_APPLIED region=#" + p.parentId());
            return;
        }
        pending.add(new Pending(System.nanoTime() + POST_SPLIT_SECONDS * 1_000_000_000L, p.parentId(),
                new int[]{children[0].getId(), children[1].getId()}, children, p.tR(), p.ghostBefore(), p.mainBefore(),
                p.parentGhostBefore(), p.predGain(), p.predG(), p.predM(), p.predNet(), p.line()));
    }

    /** Once per main tick (cheap: pending list is usually empty). */
    void tick() {
        if (pending.isEmpty()) return;
        long now = System.nanoTime();
        var it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (p.children() == null || now < p.dueNanos()) continue;
            it.remove();
            WorldRegion a = p.children()[0], b = p.children()[1];
            boolean intact = !a.nestworldIsRetired() && !b.nestworldIsRetired();
            double ca = a.getAvgTickMs(), cb = b.getAvgTickMs();
            double gain = p.tR() - Math.max(ca, cb);
            // per-region ghost delta (the split's own new strips): children's ghost minus the parent's before the split;
            // the dimension-wide deltas (ghost, MAIN BE) are reported too but are noisy at high totals
            double gA = regionGhostMs(a.getId()), gB = regionGhostMs(b.getId());
            double dG = gA + gB - p.parentGhostBefore(), dM = mainBeEwmaMs - p.mainBefore();
            double dGdim = ghostEwmaMs - p.ghostBefore();
            double net = gain - dG - Math.max(0, dM);
            String line = String.format(java.util.Locale.ROOT,
                    "SPLIT_GUARD_MEASURED dim=%s parent=#%d children=#%d/#%d intact=%s after=%ds child=%.2f/%.2fms gain=%.2fms "
                            + "ghostDelta=%.2fms(childGhost=%.2f/%.2fms parentBefore=%.2fms; dimension delta %.2fms) mainBeDelta=%.2fms(dimension) "
                            + "net=%.2fms | predicted gain=%.2f G=%.2f M<=%.2f net=%.2f "
                            + "| G meas/pred=%s",
                    dim, p.parentId(), p.childIds()[0], p.childIds()[1], intact, POST_SPLIT_SECONDS, ca, cb, gain, dG,
                    gA, gB, p.parentGhostBefore(), dGdim, dM, net, p.predGain(), p.predG(), p.predM(),
                    p.predNet(), p.predG() > 0.01 ? String.format(java.util.Locale.ROOT, "%.2f", dG / p.predG()) : "-");
            LOGGER.info(line);
            remember(p.line());
            remember(line);
        }
    }

    private void remember(String s) {
        history.addLast(s);
        while (history.size() > HISTORY) history.removeFirst();
    }

    private static String topN(Map<String, Integer> m, int n) {
        StringBuilder sb = new StringBuilder("[");
        m.entrySet().stream().sorted((p, q) -> q.getValue() - p.getValue()).limit(n)
                .forEach(e -> sb.append(sb.length() > 1 ? "," : "").append(e.getKey()).append('=').append(e.getValue()));
        return sb.append(']').toString();
    }

    // ================================================================ report (/nestworld splitguard)

    public String report(int topTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.ROOT,
                "split-guard telemetry [%s] (Stage 0, no effect on decisions): ghost ewma=%.3fms/tick nbt=%.3fms calls=%,d | "
                        + "MAIN BE phase ewma=%.3fms/tick (last %d tickers) | unchanged NBT (sampled 1/%d) %s | splits observed=%d wouldVetoLine=%d"
                        + " | shadow evals=%d noGoodLine=%d scorerChoiceRejected=%d%n",
                dim, ghostEwmaMs, nbtEwmaMs, ghostCalls, mainBeEwmaMs, mainBeLastCount, UNCHANGED_SAMPLE,
                sampled == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.1f%% of %,d", 100.0 * sampledUnchanged / sampled, sampled),
                splits, wouldVeto, shadowEvals, shadowNoGoodLine, shadowScorerChoiceRejected));
        sb.append("ghost per owner region:");
        regionGhost.entrySet().stream().sorted((p, q) -> Double.compare(q.getValue()[0], p.getValue()[0])).limit(8)
                .forEach(e -> sb.append(String.format(java.util.Locale.ROOT, " #%d=%.3fms(bes=%d,lockMiss=%d)", e.getKey(),
                        e.getValue()[0], (long) e.getValue()[1], (long) e.getValue()[2])));
        sb.append("\nghost save cost by BE type (ewma us/save, saves, share of total):");
        double all = 0;
        for (double[] t : typeCost.values()) all += t[2];
        final double totalNs = all;
        typeCost.entrySet().stream().sorted((p, q) -> Double.compare(q.getValue()[2], p.getValue()[2])).limit(topTypes)
                .forEach(e -> {
                    ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(e.getKey());
                    sb.append(String.format(java.util.Locale.ROOT, "\n  %s %.1fus x%,d (%.1f%%)", id, e.getValue()[0] / 1000.0,
                            (long) e.getValue()[1], totalNs > 0 ? 100.0 * e.getValue()[2] / totalNs : 0));
                });
        sb.append("\nrecent split predictions / measurements:");
        for (String h : history) sb.append("\n  ").append(h);
        for (Pending p : pending) sb.append("\n  pending ").append(p.children() == null ? "(applying)" : "measure in <=" + POST_SPLIT_SECONDS + "s").append(": ").append(p.line());
        return sb.toString();
    }
}
