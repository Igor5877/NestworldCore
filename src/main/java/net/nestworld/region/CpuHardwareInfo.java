package net.nestworld.region;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CPU Capacity Planner -- hardware detection, Phase 1 (ТЗ, 2026-08-30 §3). Distinguishes PHYSICAL
 * cores (true independent execution units) from LOGICAL CPUs (schedulable units, including
 * SMT/Hyper-Threading siblings) -- the user's explicit requirement that these never be conflated
 * (§4: "SMT/Hyper-Threading може використовуватися як додатковий ресурс, але не як повноцінне
 * фізичне ядро").
 *
 * <p><b>Container-aware by necessity</b>: naive host-topology parsing is unreliable in
 * containers, where {@code /proc/cpuinfo} can reflect HOST-wide metadata the cgroup doesn't
 * actually grant. Verified directly on this project's own LXC-containerized bench box: the host
 * is an 18-physical-core/36-thread Xeon E5-2699 v3 (visible via {@code cpuset.cpus} pinning 20
 * specific host logical IDs), but {@code /proc/cpuinfo} INSIDE the container shows exactly {@code
 * Runtime.availableProcessors()}=20 "processor" blocks (LXC virtualizes/renumbers them 0-19) --
 * each block's {@code physical id}/{@code core id} fields correctly describe whichever real host
 * CPU that virtual slot maps to, so deduping on those pairs across exactly those 20 blocks gives
 * an accurate 16-physical-core count (4 of the 20 logical CPUs are SMT siblings). This class
 * trusts that dedup ONLY when the block count matches {@code availableProcessors()} exactly (the
 * container-consistency check) -- otherwise it falls back to assuming no SMT (physical=logical),
 * since overestimating parallel compute capacity is the unsafe direction for a capacity planner.
 *
 * <p>{@link Runtime#availableProcessors()} remains authoritative for LOGICAL cpu count (already
 * cgroup/container-aware in modern JVMs) -- unchanged from every prior {@code oversubscriptionRatio}
 * calculation this session ({@code BurstAdmissionMetrics.threadCensus()}), which is correct for
 * OS-scheduling-contention purposes (threads compete for logical time slices). Physical core count
 * serves a DIFFERENT purpose: true independent parallel COMPUTE throughput, where SMT siblings do
 * not scale linearly.
 */
public final class CpuHardwareInfo {
    public final int logicalCpus;
    public final int physicalCores;
    public final String cpuModel;
    public final boolean smtDetected;
    public final boolean detectionReliable;

    private CpuHardwareInfo(int logicalCpus, int physicalCores, String cpuModel, boolean reliable) {
        this.logicalCpus = logicalCpus;
        this.physicalCores = physicalCores;
        this.cpuModel = cpuModel;
        this.smtDetected = physicalCores > 0 && physicalCores < logicalCpus;
        this.detectionReliable = reliable;
    }

    private static volatile CpuHardwareInfo instance;

    public static CpuHardwareInfo detect() {
        CpuHardwareInfo local = instance;
        if (local != null) return local;
        synchronized (CpuHardwareInfo.class) {
            if (instance != null) return instance;
            instance = doDetect();
            return instance;
        }
    }

    private static final Pattern PROCESSOR_PATTERN = Pattern.compile("^processor\\s*:");
    private static final Pattern MODEL_PATTERN = Pattern.compile("^model name\\s*:\\s*(.+)$");
    private static final Pattern PHYS_ID_PATTERN = Pattern.compile("^physical id\\s*:\\s*(\\d+)$");
    private static final Pattern CORE_ID_PATTERN = Pattern.compile("^core id\\s*:\\s*(\\d+)$");

    private static CpuHardwareInfo doDetect() {
        int logical = Runtime.getRuntime().availableProcessors();
        String model = "unknown";
        Set<String> physCombos = new HashSet<>();
        int processorBlocks = 0;
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/cpuinfo"));
            String curPhys = null, curCore = null;
            for (String line : lines) {
                if (PROCESSOR_PATTERN.matcher(line).find()) {
                    processorBlocks++;
                    curPhys = null;
                    curCore = null;
                }
                Matcher m;
                if ("unknown".equals(model) && (m = MODEL_PATTERN.matcher(line)).find()) {
                    model = m.group(1).trim();
                } else if ((m = PHYS_ID_PATTERN.matcher(line)).find()) {
                    curPhys = m.group(1).trim();
                } else if ((m = CORE_ID_PATTERN.matcher(line)).find()) {
                    curCore = m.group(1).trim();
                    if (curPhys != null) physCombos.add(curPhys + ":" + curCore);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Not Linux, /proc unavailable, or a parse hiccup -- fall back below.
        }
        int physical;
        boolean reliable;
        if (!physCombos.isEmpty() && processorBlocks == logical) {
            physical = physCombos.size();
            reliable = true;
        } else {
            physical = logical;
            reliable = false;
        }
        return new CpuHardwareInfo(logical, physical, model, reliable);
    }
}
