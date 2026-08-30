package net.nestworld.startup;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NestWorld: Phase 1 of the startup-optimization project (docs/STARTUP_OPTIMIZATION_SPEC.md).
 * Purely additive/observational -- records timestamped milestones through the boot sequence and
 * samples total (all-core) CPU utilization, then writes a report at server-ready time. No behavior
 * change to the boot sequence itself: every call site this hooks into already runs that code, this
 * only adds a cheap timestamp/log line next to it.
 */
public final class NestworldStartupProfiler {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final long JVM_START_MILLIS = ManagementFactory.getRuntimeMXBean().getStartTime();
    private static final long CPU_SAMPLE_INTERVAL_MILLIS = 500L;
    private static final long REPORT_WINDOW_MILLIS = 5000L;

    private record Milestone(String name, long millisSinceJvmStart, String thread) {}
    private record CpuSample(long millisSinceJvmStart, double totalCpuPercent) {}

    private static final List<Milestone> MILESTONES = Collections.synchronizedList(new ArrayList<>());
    private static final List<CpuSample> CPU_SAMPLES = Collections.synchronizedList(new ArrayList<>());

    private static volatile ScheduledExecutorService cpuSampler;
    private static volatile boolean reported = false;
    private static final java.util.concurrent.atomic.AtomicBoolean FIRST_PLAYER_JOIN_MARKED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private NestworldStartupProfiler() {
    }

    /** Marks "first_player_join" exactly once, no-op on every join after the first. */
    public static void markFirstPlayerJoinOnce() {
        if (FIRST_PLAYER_JOIN_MARKED.compareAndSet(false, true)) {
            mark("first_player_join");
        }
    }

    public static void mark(String name) {
        long now = System.currentTimeMillis() - JVM_START_MILLIS;
        MILESTONES.add(new Milestone(name, now, Thread.currentThread().getName()));
        LOGGER.info("[Startup] {} at {}s ({})", name,
                String.format(Locale.ROOT, "%.3f", now / 1000.0), Thread.currentThread().getName());
        ensureCpuSamplingStarted();
    }

    private static synchronized void ensureCpuSamplingStarted() {
        if (cpuSampler != null || reported) {
            return;
        }
        final com.sun.management.OperatingSystemMXBean osBean;
        try {
            osBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        } catch (ClassCastException e) {
            LOGGER.warn("NestWorld: com.sun.management.OperatingSystemMXBean unavailable -- startup CPU sampling disabled");
            return;
        }
        final int cores = Runtime.getRuntime().availableProcessors();
        cpuSampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NestWorld-Startup-CPU-Sampler");
            t.setDaemon(true);
            return t;
        });
        cpuSampler.scheduleAtFixedRate(() -> {
            double load = osBean.getProcessCpuLoad();
            if (load >= 0) {
                long now = System.currentTimeMillis() - JVM_START_MILLIS;
                CPU_SAMPLES.add(new CpuSample(now, load * cores * 100.0));
            }
        }, 0, CPU_SAMPLE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Writes the full startup report (milestones, phase durations, CPU-utilization-by-window) to
     * {@code <serverDirectory>/logs/startup-profile-<timestamp>.txt} and logs a summary. Safe to
     * call more than once (e.g. once at server-ready, and mark() may still be called afterwards for
     * later events like first player join -- those just won't appear in this file) -- only the
     * first call produces the report, later calls are no-ops.
     */
    public static synchronized void report(File serverDirectory) {
        if (reported) {
            return;
        }
        reported = true;
        if (cpuSampler != null) {
            cpuSampler.shutdown();
        }

        List<Milestone> milestones = new ArrayList<>(MILESTONES);
        List<CpuSample> samples = new ArrayList<>(CPU_SAMPLES);

        String text = buildReport(milestones, samples);
        LOGGER.info("NestWorld startup profile:\n{}", text);

        try {
            File logsDir = new File(serverDirectory, "logs");
            logsDir.mkdirs();
            File reportFile = new File(logsDir, "startup-profile-" + System.currentTimeMillis() + ".txt");
            try (PrintWriter out = new PrintWriter(new FileWriter(reportFile))) {
                out.print(text);
            }
            LOGGER.info("NestWorld: startup profile written to {}", reportFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.warn("NestWorld: failed to write startup profile report (non-fatal)", e);
        }
    }

    private static String buildReport(List<Milestone> milestones, List<CpuSample> samples) {
        StringBuilder sb = new StringBuilder();
        sb.append("NestWorld Startup Profile\n");
        sb.append("==========================\n\n");

        sb.append("Milestones:\n");
        for (Milestone m : milestones) {
            sb.append(String.format(Locale.ROOT, "  %8.3fs  %-28s (%s)%n",
                    m.millisSinceJvmStart() / 1000.0, m.name(), m.thread()));
        }

        sb.append("\nPhase durations (between consecutive milestones):\n");
        for (int i = 1; i < milestones.size(); i++) {
            Milestone prev = milestones.get(i - 1);
            Milestone cur = milestones.get(i);
            double durSeconds = (cur.millisSinceJvmStart() - prev.millisSinceJvmStart()) / 1000.0;
            sb.append(String.format(Locale.ROOT, "  %-24s -> %-24s : %8.3fs%n",
                    prev.name(), cur.name(), durSeconds));
        }

        if (!samples.isEmpty()) {
            sb.append("\nCPU utilization (sum across all cores, ").append(CPU_SAMPLE_INTERVAL_MILLIS)
                    .append("ms samples, ").append(REPORT_WINDOW_MILLIS / 1000).append("s windows):\n");
            long maxMillis = samples.get(samples.size() - 1).millisSinceJvmStart();
            for (long windowStart = 0; windowStart <= maxMillis; windowStart += REPORT_WINDOW_MILLIS) {
                long windowEnd = windowStart + REPORT_WINDOW_MILLIS;
                double sum = 0;
                double peak = 0;
                int count = 0;
                for (CpuSample s : samples) {
                    if (s.millisSinceJvmStart() >= windowStart && s.millisSinceJvmStart() < windowEnd) {
                        sum += s.totalCpuPercent();
                        peak = Math.max(peak, s.totalCpuPercent());
                        count++;
                    }
                }
                if (count > 0) {
                    sb.append(String.format(Locale.ROOT, "  %3ds-%3ds  avg %6.1f%%  peak %6.1f%%%n",
                            windowStart / 1000, windowEnd / 1000, sum / count, peak));
                }
            }
        }

        return sb.toString();
    }
}
