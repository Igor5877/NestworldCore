package net.nestworld.racedetect;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Minimal Stage-B: in-memory holder + content-addressable on-disk cache for
 * {@link StaticCandidateAnalyzer}'s output. Persistence mirrors {@code NestworldPins}'
 * tolerant-plain-text-line convention (one file per jar, under {@code <world>/data/racedetect/
 * candidates/}, so "has this exact jar+schema+ruleset already been scanned" is a plain
 * file-exists check -- no parsing needed to determine cache HIT vs MISS).
 */
public final class CandidateRegistry {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/RaceDetect");

    private CandidateRegistry() {
    }

    public static final class ScanStats {
        public final long durationMillis;
        public final int cacheHits;
        public final int cacheMisses;
        public final int jarsScanned;
        public final long timestampMillis;

        ScanStats(long durationMillis, int cacheHits, int cacheMisses, int jarsScanned, long timestampMillis) {
            this.durationMillis = durationMillis;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.jarsScanned = jarsScanned;
            this.timestampMillis = timestampMillis;
        }
    }

    private static volatile List<Candidate> candidates = Collections.emptyList();
    private static volatile ScanStats lastScanStats = null;
    private static final AtomicBoolean SCAN_RUNNING = new AtomicBoolean(false);
    private static volatile boolean scannedThisBoot = false;

    private static final ThreadFactory THREAD_FACTORY = r -> {
        Thread t = new Thread(r, "NestWorld-RaceDetect-Scan");
        t.setDaemon(true);
        return t;
    };
    private static final java.util.concurrent.ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(THREAD_FACTORY);

    public static List<Candidate> all() {
        return candidates;
    }

    public static List<Candidate> byModId(String modId) {
        return candidates.stream().filter(c -> c.modId().equals(modId)).collect(Collectors.toList());
    }

    public static int count() {
        return candidates.size();
    }

    public static ScanStats lastScanStats() {
        return lastScanStats;
    }

    public static boolean isScanRunning() {
        return SCAN_RUNNING.get();
    }

    /** No-op if a scan already ran this boot. Called from {@code onServerStarting}. */
    public static void scanIfNeeded(MinecraftServer server) {
        if (scannedThisBoot) return;
        forceRescan(server);
    }

    public static CompletableFuture<Void> forceRescan(MinecraftServer server) {
        if (!SCAN_RUNNING.compareAndSet(false, true)) {
            LOGGER.info("Race-detect scan already running, ignoring duplicate trigger");
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                runScan(server);
            } catch (Throwable t) {
                LOGGER.warn("Race-detect scan failed: {}", t.toString(), t);
            } finally {
                SCAN_RUNNING.set(false);
                scannedThisBoot = true;
            }
        }, EXECUTOR);
    }

    private static void runScan(MinecraftServer server) {
        long start = System.currentTimeMillis();
        List<Candidate> found = new ArrayList<>();
        int hits = 0, misses = 0, jarsScanned = 0;

        List<JarSource> sources = collectJarSources();
        for (JarSource source : sources) {
            jarsScanned++;
            try {
                String jarSha256 = StaticCandidateAnalyzer.sha256OfFile(source.path);
                Path cacheFile = cacheFileFor(server, jarSha256);
                List<Candidate> jarCandidates = loadCache(cacheFile);
                if (jarCandidates != null) {
                    hits++;
                } else {
                    misses++;
                    jarCandidates = StaticCandidateAnalyzer.scanJar(source.path, source.modId);
                    saveCache(cacheFile, jarSha256, jarCandidates);
                }
                found.addAll(jarCandidates);
            } catch (Throwable t) {
                LOGGER.warn("Race-detect: failed to scan {} ({}): {}", source.path, source.modId, t.toString());
            }
        }

        candidates = Collections.unmodifiableList(found);
        long duration = System.currentTimeMillis() - start;
        lastScanStats = new ScanStats(duration, hits, misses, jarsScanned, System.currentTimeMillis());
        LOGGER.info("Race-detect scan complete: {} jars ({} cache hits, {} misses), {} candidates, {} ms",
                jarsScanned, hits, misses, found.size(), duration);
    }

    // --------------------------------------------------------------------------------------
    // Jar source enumeration
    // --------------------------------------------------------------------------------------

    private static final class JarSource {
        final Path path;
        final String modId;

        JarSource(Path path, String modId) {
            this.path = path;
            this.modId = modId;
        }
    }

    /** All third-party mod jars via {@code ModList} (minecraft/forge NOT skipped here -- unlike
     * {@code NestworldPins.buildClassToModIdMap()}'s attribution use case, Stage A's whole-runtime
     * scope requires scanning them too), plus the running NestWorldCore/Forge jar itself as one
     * more source (its classes get re-tagged per-package inside {@code StaticCandidateAnalyzer}
     * regardless of the modId passed in here). */
    private static List<JarSource> collectJarSources() {
        List<JarSource> sources = new ArrayList<>();
        try {
            for (IModFileInfo fileInfo : ModList.get().getModFiles()) {
                List<IModInfo> mods = fileInfo.getMods();
                if (mods.isEmpty()) continue;
                String modId = mods.get(0).getModId();
                IModFile file = fileInfo.getFile();
                if (file == null) continue;
                Path path = file.getFilePath();
                // Some mod entries (observed live: Sinytra/Forgified-Fabric jar-in-jar shims like
                // fabric_message_api_v1/kuma_api) resolve to a Path on a non-default NIO
                // FileSystemProvider (nested-jar virtual FS) whose toString() is blank --
                // Files.isRegularFile() can return true for these, but JarFile can't open them.
                // Filtered here up front rather than relying only on the per-jar try/catch in
                // runScan(), to avoid a guaranteed-to-fail re-attempt (and matching warn-log spam)
                // on every single boot for entries that can never be scanned.
                if (path == null || path.toString().isBlank() || !Files.isRegularFile(path)) continue;
                sources.add(new JarSource(path, modId));
            }
        } catch (Throwable t) {
            LOGGER.warn("Race-detect: ModList enumeration failed: {}", t.toString());
        }

        try {
            Path selfJar = Path.of(CandidateRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(selfJar) && sources.stream().noneMatch(s -> s.path.equals(selfJar))) {
                sources.add(new JarSource(selfJar, "nestworldcore"));
            }
        } catch (URISyntaxException | NullPointerException | SecurityException t) {
            LOGGER.warn("Race-detect: could not resolve running jar for self-scan: {}", t.toString());
        }

        // Vanilla Minecraft is NOT on the plain java.class.path on a real deployed server --
        // Forge's bootstraplauncher loads everything through ModLauncher's module path (`-p`)
        // plus a SEPARATE `-DlegacyClassPath` system property for non-modular libraries (verified
        // this session by reading a real unix_args.txt: the deobfuscated
        // `server-<mcVersion>-extra.jar` containing net.minecraft.* classes lives specifically in
        // legacyClassPath, not java.class.path, which would otherwise silently miss vanilla
        // Minecraft entirely -- a real gap caught during this pass's own verification, not
        // assumed away). Also covers third-party libraries (Netty, Guava, etc.) already named as
        // in-scope execution contexts in the detector's own spec.
        String legacyClassPath = System.getProperty("legacyClassPath");
        if (legacyClassPath != null && !legacyClassPath.isEmpty()) {
            for (String entry : legacyClassPath.split(java.io.File.pathSeparator)) {
                if (!entry.endsWith(".jar")) continue;
                Path path = Path.of(entry);
                if (!Files.isRegularFile(path)) continue;
                if (sources.stream().anyMatch(s -> s.path.equals(path))) continue; // already covered via ModList/self
                String fallbackModId = path.getFileName().toString().replace(".jar", "");
                sources.add(new JarSource(path, "library:" + fallbackModId));
            }
        }

        return sources;
    }

    // --------------------------------------------------------------------------------------
    // Persistence
    // --------------------------------------------------------------------------------------

    private static Path cacheDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("racedetect").resolve("candidates");
    }

    private static Path cacheFileFor(MinecraftServer server, String jarSha256) {
        String name = jarSha256 + "-" + StaticCandidateAnalyzer.ANALYZER_SCHEMA_VERSION
                + "-" + StaticCandidateAnalyzer.RULESET_VERSION + ".txt";
        return cacheDir(server).resolve(name);
    }

    /** Returns null on cache MISS (file absent); returns a (possibly empty) list on HIT. */
    private static List<Candidate> loadCache(Path file) {
        if (!Files.isRegularFile(file)) return null;
        List<Candidate> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String jarSha256 = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) {
                    if (line.startsWith("# jarSha256=")) jarSha256 = line.substring("# jarSha256=".length()).trim();
                    continue;
                }
                Candidate c = parseLine(line, jarSha256);
                if (c != null) result.add(c);
                else LOGGER.warn("Race-detect: skipping malformed cache line in {}", file);
            }
        } catch (IOException e) {
            LOGGER.warn("Race-detect: failed to read cache {}: {}", file, e.toString());
            return null; // treat as cache miss -- will be re-scanned and re-written
        }
        return result;
    }

    private static Candidate parseLine(String line, String jarSha256) {
        String[] parts = line.split("\\|", -1);
        if (parts.length != 10 || jarSha256 == null) return null;
        try {
            String className = parts[0], fieldName = parts[1], methodName = parts[2];
            int callsiteOffset = Integer.parseInt(parts[3]);
            HazardType hazardType = HazardType.valueOf(parts[4]);
            SharedStateKind kind = SharedStateKind.valueOf(parts[5]);
            String jdkType = parts[6].isEmpty() ? null : parts[6];
            HazardStatus status = HazardStatus.valueOf(parts[7]);
            String modId = parts[8];
            String detailNote = unescape(parts[9]);
            CandidateId id = new CandidateId(jarSha256, className, fieldName, methodName, callsiteOffset,
                    hazardType, StaticCandidateAnalyzer.ANALYZER_SCHEMA_VERSION, StaticCandidateAnalyzer.RULESET_VERSION);
            return new Candidate(id, "", kind, jdkType, status, detailNote, modId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void saveCache(Path file, String jarSha256, List<Candidate> jarCandidates) {
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write("# NestWorld race-detect candidate cache -- machine-generated, safe to delete\n");
                writer.write("# jarSha256=" + jarSha256 + "\n");
                writer.write("# analyzerSchemaVersion=" + StaticCandidateAnalyzer.ANALYZER_SCHEMA_VERSION + "\n");
                writer.write("# rulesetVersion=" + StaticCandidateAnalyzer.RULESET_VERSION + "\n");
                for (Candidate c : jarCandidates) {
                    writer.write(String.join("|",
                            c.id().className(), c.id().fieldName(), c.id().methodName(),
                            String.valueOf(c.id().callsiteBytecodeOffset()), c.id().hazardType().name(),
                            c.kind().name(), c.jdkCollectionTypeOrNull() == null ? "" : c.jdkCollectionTypeOrNull(),
                            c.status().name(), c.modId(), escape(c.detailNote())));
                    writer.write("\n");
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Race-detect: failed to write cache {}: {}", file, e.toString());
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", " ");
    }

    private static String unescape(String s) {
        return s.replace("\\p", "|").replace("\\\\", "\\");
    }
}
