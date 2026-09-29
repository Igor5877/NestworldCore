package net.nestworld.region;

import java.util.ArrayList;
import java.util.List;

/**
 * CPU Capacity Planner, Phase 1 (ТЗ, 2026-08-30). NestWorldCore's own accounting of "how much CPU
 * does this machine actually have, and how much does the CONFIGURED workload potentially demand" --
 * per the user's closing framing, the point is NOT "automatically set Netty = N", that is only one
 * OUTPUT of this system, not its purpose.
 *
 * <p><b>Two-phase design, forced by a real class-initialization constraint</b>: {@link
 * #quickNettyRecommendation()} must be callable from {@code NestworldTuning}'s own static
 * initializer (resolving {@code NETTY_WORKER_THREADS} when set to {@code auto}) -- calling back
 * INTO other not-yet-initialized {@code NestworldTuning} fields from there would be a circular
 * class-init hazard (JLS 12.4.2: a class reentering its own static init sees not-yet-assigned
 * static finals as their default zero value, silently corrupting the inventory). So the quick
 * path uses ONLY hardware info + the bounded benchmark -- no other subsystem's configuration.
 * The FULL parameter inventory, demand model, and startup warning report ({@link
 * #buildFullReport()}) run separately, later, once the rest of the mod has finished loading
 * (hooked from server startup, well after all static initialization is complete) -- by which
 * point it's safe to read every other class's configured values directly.
 *
 * <p>Explicitly deferred to a later phase (NOT built here, per the ТЗ's own §18 acknowledging
 * this needs phasing): runtime CPU pressure tiers with hysteresis (§16-17) and adaptive budget
 * throttling driven by that pressure (§18). This phase is static/startup-only: hardware detection,
 * parameter inventory, demand model, Netty auto-sizing, and one-time startup warnings.
 */
public final class CpuCapacityPlanner {
    private CpuCapacityPlanner() {}

    public enum WarningLevel { INFO, WARNING, CRITICAL }

    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("nestworld.cpuPlanner", "true"));
    public static final int MIN_NETTY_WORKERS = Integer.getInteger("nestworld.cpuPlannerMinNettyWorkers", 8);
    public static final int MAX_NETTY_WORKERS = Integer.getInteger("nestworld.cpuPlannerMaxNettyWorkers", 32);
    public static final double RESERVE_FRACTION = parseReserve();

    private static double parseReserve() {
        try {
            double pct = Double.parseDouble(System.getProperty("nestworld.cpuPlannerReservePercent", "20"));
            return Math.max(0.0, Math.min(0.9, pct / 100.0));
        } catch (NumberFormatException e) {
            return 0.20;
        }
    }

    /** EARLY-safe: hardware + benchmark only, no cross-reference to other NestworldTuning fields.
     *  See class javadoc for why. Biased conservative by design (never recommends above {@code
     *  physicalCores} even at measured-perfect scaling efficiency -- going beyond physical cores
     *  by default is exactly the class of mistake this planner exists to prevent; see
     *  netty-workers-ab-breakthrough-2026-08-30 for the empirical justification: on this
     *  project's own 16-physical/20-logical bench box, the empirically-best worker count (16)
     *  landed exactly at physicalCores, not logicalCpus). */
    public static int quickNettyRecommendation() {
        CpuHardwareInfo hw = CpuHardwareInfo.detect();
        if (!ENABLED) {
            return 2 * hw.logicalCpus; // unmodified vanilla/Netty default
        }
        CpuBenchmark bench = CpuBenchmark.run();
        // Netty workers are I/O/scheduling-bound, not compute-bound -- the benchmark's
        // parallelEfficiency (raw ALU scaling) is only a MILD adjustment here, not the primary
        // driver, per the empirical validation in netty-workers-ab-breakthrough-2026-08-30 +
        // netty-steady-state-validation-2026-08-30: on this exact box (16 physical/20 logical,
        // measured parallelEfficiency ~0.65), the empirically-best worker count across both a
        // burst A/B sweep AND a full steady-state grid was physicalCores itself (16) -- an
        // earlier formula that scaled recommendation down proportionally with parallelEfficiency
        // under-recommended (10) relative to that validated result. Physical core count is the
        // anchor; efficiency only nudges it, never dominates it.
        double scalingFactor = bench.ran
                ? Math.max(0.85, Math.min(1.0, 0.85 + 0.15 * bench.parallelEfficiency))
                : 0.9; // benchmark disabled -- still anchored near physicalCores, not vanilla's naive 2x
        int recommended = (int) Math.round(hw.physicalCores * scalingFactor);
        return Math.max(MIN_NETTY_WORKERS, Math.min(MAX_NETTY_WORKERS, recommended));
    }

    /** LATE-safe: full parameter inventory + demand model + warning. Call only after all other
     *  NestworldTuning/subsystem static state has settled (e.g. from server-startup, not from any
     *  other class's own static initializer). */
    public static Report buildFullReport() {
        CpuHardwareInfo hw = CpuHardwareInfo.detect();
        CpuBenchmark bench = CpuBenchmark.run();
        List<CpuConcurrencyParameter> params = collectParameters();

        double totalDemand = 0;
        for (CpuConcurrencyParameter p : params) totalDemand += p.effectiveDemand();

        double capacity = hw.logicalCpus * (1.0 - RESERVE_FRACTION);
        double oversubscription = capacity > 0 ? totalDemand / capacity : 0;

        WarningLevel level;
        if (oversubscription <= 1.0) level = WarningLevel.INFO;
        else if (oversubscription <= 1.5) level = WarningLevel.WARNING;
        else level = WarningLevel.CRITICAL;

        return new Report(hw, bench, params, totalDemand, capacity, oversubscription, level);
    }

    /** Every known CPU-concurrent parameter this build is aware of. Deliberately NOT a static
     *  list -- architecture allows growing this via future registration hooks (ТЗ §6's "не
     *  хардкодити лише наведений список"), but Phase 1 reads the current fixed set of known
     *  subsystems directly, which is simpler and sufficient until a real registration API is
     *  needed. */
    private static List<CpuConcurrencyParameter> collectParameters() {
        List<CpuConcurrencyParameter> list = new ArrayList<>();
        int logical = CpuHardwareInfo.detect().logicalCpus;

        String nettyRaw = System.getProperty("nestworld.nettyWorkerThreads");
        boolean nettyAuto = nettyRaw != null && nettyRaw.equalsIgnoreCase("auto");
        list.add(new CpuConcurrencyParameter("nettyWorkerThreads",
                CpuConcurrencyParameter.WorkType.NETWORK_WORK,
                NestworldTuning.NETTY_WORKER_THREADS > 0 ? NestworldTuning.NETTY_WORKER_THREADS : 2.0 * logical,
                1.0, nettyAuto));

        // Region-thread concurrency is DYNAMIC (one RegionThread per active WorldRegion, driven
        // by RegionSplitManager's split/merge heuristic), not a fixed pool-size field -- this
        // duplicates RegionSplitManager's own SPLIT_TARGET_PARALLELISM formula (same property,
        // same default) rather than widening that class's field visibility just for this reader;
        // it's the STEADY-STATE region-thread count the split manager targets, not the hard cap
        // (MAX_REGIONS_TOTAL) which only matters transiently during a split storm.
        int regionTargetParallelism = Math.max(1,
                Integer.getInteger("nestworld.splitTargetParallelism", Math.max(1, logical - 1)));
        list.add(new CpuConcurrencyParameter("regionThreads(targetParallelism)",
                CpuConcurrencyParameter.WorkType.REGION_WORK, regionTargetParallelism, 1.0, false));

        // Chunk-gen concurrency caps: 0 means "unlimited", which in practice means it draws from
        // the shared region-thread pool rather than a separate dedicated pool -- counting it
        // again on top of regionThreads above would double-count the same physical threads, so
        // it's only added as a DISTINCT demand contributor when explicitly bounded (>0).
        int chunkGenMaxConcurrent = NestworldTuning.CHUNK_GEN_MAX_CONCURRENT;
        if (chunkGenMaxConcurrent > 0) {
            list.add(new CpuConcurrencyParameter("chunkGenMaxConcurrent",
                    CpuConcurrencyParameter.WorkType.CPU_CONCURRENCY, chunkGenMaxConcurrent, 1.0, false));
        }
        list.add(new CpuConcurrencyParameter("worldgenShards",
                CpuConcurrencyParameter.WorkType.CPU_CONCURRENCY, NestworldTuning.WORLDGEN_SHARDS, 1.0, false));
        list.add(new CpuConcurrencyParameter("chunkGenBudget",
                CpuConcurrencyParameter.WorkType.CPU_WORK_BUDGET, NestworldTuning.CHUNK_GEN_BUDGET, 0.01, false));

        list.add(new CpuConcurrencyParameter("pairingAdmissionGlobalBudget",
                CpuConcurrencyParameter.WorkType.CPU_WORK_BUDGET,
                PairingAdmissionGate.GLOBAL_BUDGET_PER_TICK, 0.01, false));
        list.add(new CpuConcurrencyParameter("chunkOutboundBudget",
                CpuConcurrencyParameter.WorkType.CPU_WORK_BUDGET,
                ChunkOutboundQueue.BUDGET_PER_TICK, 0.01, false));
        list.add(new CpuConcurrencyParameter("pairingOutboundBudget",
                CpuConcurrencyParameter.WorkType.CPU_WORK_BUDGET,
                PairingOutboundQueue.BUDGET_PER_TICK, 0.01, false));
        list.add(new CpuConcurrencyParameter("outboundBatchMaxBundleSize",
                CpuConcurrencyParameter.WorkType.QUEUE_LIMIT,
                OutboundBatchQueue.MAX_BUNDLE_SIZE, 0.0, false));
        list.add(new CpuConcurrencyParameter("joinAdmissionBudget",
                CpuConcurrencyParameter.WorkType.CPU_WORK_BUDGET,
                AdmissionController.BUDGET_PER_TICK, 0.02, false));
        list.add(new CpuConcurrencyParameter("connectionAdmissionMaxPerWindow",
                CpuConcurrencyParameter.WorkType.NETWORK_WORK,
                ConnectionAdmissionGate.MAX_NEW_CONNECTIONS_PER_WINDOW, 0.05, false));
        return list;
    }

    // -------------------------------------------------------------------------------------
    // Independent recommendation + live-apply experiment (2026-09-23, user-requested "what
    // would the analyzer set everything to on its own, and what changes if it actually does").
    // -------------------------------------------------------------------------------------
    //
    // Two honest categories, not glossed over:
    //  - Boot-time-only parameters (nettyWorkerThreads, regionThreads target parallelism,
    //    worldgenShards, chunkGenMaxConcurrent) size an actual OS thread pool or are read once
    //    into a `static final` at class-init. Recommending a number for these is real, but
    //    APPLYING it live is not -- resizing a running thread pool safely is out of scope here
    //    (this Phase 1 planner's own class javadoc already defers "adaptive... throttling" to a
    //    later phase). Reported as "recommended (needs restart)".
    //  - The six admission/outbound budgets are already `public static volatile int`, read fresh
    //    every tick by design (existing individual RCON setters already mutate them live) --
    //    genuinely safe to apply immediately, no restart needed.
    //
    // Budget recommendation formula: scale each CURRENT default proportionally to detected
    // physicalCores against REFERENCE_PHYSICAL_CORES -- the box these specific default numbers
    // (64/200/200/8/8/512) were empirically tuned on across the #31 admission-gate sweep
    // (burst-admission-step1/2/3, 2026-08-29/30, see project memory), which was the same class of
    // machine CpuHardwareInfo's own javadoc describes (16 physical / 20 logical). This is a
    // transparent, defensible scale factor, not an invented formula -- on hardware matching that
    // reference box, it recommends "no change" (scale ~1.0), which is itself a real, checkable
    // experimental result, not just an assumption. Rounded up (Math.ceil) so a scaled-DOWN
    // recommendation on smaller hardware still never rounds to zero and disables the mechanism.
    private static final double REFERENCE_PHYSICAL_CORES = 16.0;

    private static double budgetScaleFactor() {
        int physical = CpuHardwareInfo.detect().physicalCores;
        if (physical <= 0) return 1.0;
        return physical / REFERENCE_PHYSICAL_CORES;
    }

    private static int scaledBudget(int current) {
        return Math.max(1, (int) Math.ceil(current * budgetScaleFactor()));
    }

    /** One recommendation: current value, planner's independent recommendation, whether it was
     *  (or could be) applied live without a restart, and why/why-not in one short phrase. */
    public record Recommendation(String name, double current, double recommended,
                                  boolean liveApplicable, String basis) {
        public String format() {
            String delta = Math.abs(recommended - current) < 0.05 ? "no change"
                    : String.format("%+.1f", recommended - current);
            return String.format("  %-32s current=%-8.1f recommended=%-8.1f (%s) [%s]%s",
                    name, current, recommended, delta, basis,
                    liveApplicable ? "" : " -- needs restart");
        }
    }

    /** Computes what this planner would independently choose for every known parameter, without
     *  changing anything. Pure/side-effect-free -- see {@link #applyRecommendations()} for the
     *  version that actually mutates live state. */
    public static List<Recommendation> computeRecommendations() {
        List<Recommendation> out = new ArrayList<>();
        CpuHardwareInfo hw = CpuHardwareInfo.detect();

        out.add(new Recommendation("nettyWorkerThreads",
                NestworldTuning.NETTY_WORKER_THREADS > 0 ? NestworldTuning.NETTY_WORKER_THREADS : 2.0 * hw.logicalCpus,
                quickNettyRecommendation(), false,
                "physicalCores * benchmark-scaling-factor (I/O-bound anchor)"));

        int currentRegionThreads = Math.max(1,
                Integer.getInteger("nestworld.splitTargetParallelism", Math.max(1, hw.logicalCpus - 1)));
        out.add(new Recommendation("regionThreads(targetParallelism)",
                currentRegionThreads, quickNettyRecommendation(), false,
                "same physicalCores-anchored formula as Netty, compute-bound region ticking"));

        out.add(new Recommendation("worldgenShards", NestworldTuning.WORLDGEN_SHARDS,
                NestworldTuning.WORLDGEN_SHARDS, false,
                "no independent formula built yet -- static final, reported only"));
        out.add(new Recommendation("chunkGenMaxConcurrent", NestworldTuning.CHUNK_GEN_MAX_CONCURRENT,
                NestworldTuning.CHUNK_GEN_MAX_CONCURRENT, false,
                "no independent formula built yet -- static final, reported only"));

        out.add(new Recommendation("pairingAdmissionGlobalBudget", PairingAdmissionGate.GLOBAL_BUDGET_PER_TICK,
                scaledBudget(64), true, "64 (reference default) * physicalCores/16"));
        out.add(new Recommendation("chunkOutboundBudget", ChunkOutboundQueue.BUDGET_PER_TICK,
                scaledBudget(200), true, "200 (reference default) * physicalCores/16"));
        out.add(new Recommendation("pairingOutboundBudget", PairingOutboundQueue.BUDGET_PER_TICK,
                scaledBudget(200), true, "200 (reference default) * physicalCores/16"));
        out.add(new Recommendation("outboundBatchMaxBundleSize", OutboundBatchQueue.MAX_BUNDLE_SIZE,
                scaledBudget(512), true, "512 (reference default) * physicalCores/16"));
        out.add(new Recommendation("joinAdmissionBudget", AdmissionController.BUDGET_PER_TICK,
                scaledBudget(8), true, "8 (reference default) * physicalCores/16"));
        out.add(new Recommendation("connectionAdmissionMaxPerWindow", ConnectionAdmissionGate.MAX_NEW_CONNECTIONS_PER_WINDOW,
                scaledBudget(8), true, "8 (reference default) * physicalCores/16"));
        return out;
    }

    /** Actually applies every LIVE-APPLICABLE recommendation from {@link #computeRecommendations()}
     *  (the six volatile admission/outbound budgets) and returns the same list so the caller can
     *  show a true before/after. Boot-time-only parameters (Netty, region threads, worldgen
     *  shards) are left untouched -- see the class-level note above for why. */
    public static List<Recommendation> applyRecommendations() {
        List<Recommendation> recs = computeRecommendations();
        for (Recommendation r : recs) {
            if (!r.liveApplicable()) continue;
            int v = (int) Math.round(r.recommended());
            switch (r.name()) {
                case "pairingAdmissionGlobalBudget" -> PairingAdmissionGate.GLOBAL_BUDGET_PER_TICK = v;
                case "chunkOutboundBudget" -> ChunkOutboundQueue.BUDGET_PER_TICK = v;
                case "pairingOutboundBudget" -> PairingOutboundQueue.BUDGET_PER_TICK = v;
                case "outboundBatchMaxBundleSize" -> OutboundBatchQueue.MAX_BUNDLE_SIZE = v;
                case "joinAdmissionBudget" -> AdmissionController.BUDGET_PER_TICK = v;
                case "connectionAdmissionMaxPerWindow" -> ConnectionAdmissionGate.MAX_NEW_CONNECTIONS_PER_WINDOW = v;
                default -> { }
            }
        }
        return recs;
    }

    public static final class Report {
        public final CpuHardwareInfo hardware;
        public final CpuBenchmark benchmark;
        public final List<CpuConcurrencyParameter> parameters;
        public final double totalEffectiveDemand;
        public final double availableCapacity;
        public final double oversubscriptionRatio;
        public final WarningLevel level;

        Report(CpuHardwareInfo hardware, CpuBenchmark benchmark, List<CpuConcurrencyParameter> parameters,
               double totalEffectiveDemand, double availableCapacity, double oversubscriptionRatio, WarningLevel level) {
            this.hardware = hardware;
            this.benchmark = benchmark;
            this.parameters = parameters;
            this.totalEffectiveDemand = totalEffectiveDemand;
            this.availableCapacity = availableCapacity;
            this.oversubscriptionRatio = oversubscriptionRatio;
            this.level = level;
        }

        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("NestWorldCore CPU Capacity Report\n");
            sb.append(String.format("CPU: model=%s physicalCores=%d logicalCpus=%d smtDetected=%s detectionReliable=%s%n",
                    hardware.cpuModel, hardware.physicalCores, hardware.logicalCpus, hardware.smtDetected, hardware.detectionReliable));
            if (benchmark.ran) {
                sb.append(String.format("Benchmark: singleCoreScore=%,d parallelScore=%,d parallelEfficiency=%.2f%n",
                        benchmark.singleCoreScore, benchmark.parallelScore, benchmark.parallelEfficiency));
            } else {
                sb.append("Benchmark: disabled (nestworld.cpuBenchmark=false)\n");
            }
            sb.append("Configured parameters:\n");
            for (CpuConcurrencyParameter p : parameters) {
                sb.append(String.format("  %-32s type=%-16s value=%.1f%s effectiveDemand=%.2f%n",
                        p.name, p.type, p.value, p.isAuto ? " (auto)" : "", p.effectiveDemand()));
            }
            sb.append(String.format("Planner: totalEffectiveDemand=%.2f availableCapacity=%.2f oversubscriptionRatio=%.2f%n",
                    totalEffectiveDemand, availableCapacity, oversubscriptionRatio));
            sb.append("Status: ").append(level);
            if (level != WarningLevel.INFO) {
                sb.append(String.format(" -- configured workload may exceed available CPU capacity "
                        + "(estimated demand %.1f effective cores vs %.1f available, %.2fx oversubscription). "
                        + "Potential symptoms: high MSPT, admission backlog, reduced TPS, Netty contention, watchdog risk.",
                        totalEffectiveDemand, availableCapacity, oversubscriptionRatio));
            }
            return sb.toString();
        }
    }
}
