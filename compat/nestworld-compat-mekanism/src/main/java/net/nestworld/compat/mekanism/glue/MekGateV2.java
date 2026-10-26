package net.nestworld.compat.mekanism.glue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;

/**
 * MEK-GATE-PERF-001: the same ACTIVE contract as {@link MekGate#evaluateLegacy()}, with narrow evidence collection.
 * <ol>
 * <li>A: exactly one loaded mod file provides modid {@code mekanism} (FML loading mod list).</li>
 * <li>B: full SHA-256 of that jar equals the pinned value; version equals 10.4.16.</li>
 * <li>C: only the two target entries are read; their class-file SHA-256 equal the pinned values.</li>
 * <li>D: foreign Mixins — every Mixin config registered with the Mixin runtime ({@link Mixins#getConfigs()}) is
 *     mapped by its mixin package to the mod jar(s) owning that package; only classes under that package are read
 *     and their {@code @Mixin(value/targets)} resolved (Type and dotted/slashed string forms). Every mixin of a
 *     config, static or plugin-supplied, must live in its package. A config whose package is in no mod jar is
 *     "empty" (it can apply nothing; same mod-jar coverage as the legacy gate). Configs registered after this point are caught by
 *     {@link MekCompatMixinPlugin#acceptTargets} (authoritative runtime targets; startup is refused).</li>
 * </ol>
 * Any uncertainty yields INELIGIBLE, never ACTIVE.
 */
public final class MekGateV2 {
    static final String OWN_CONFIG = "nestworld_compat_mekanism.mixins.json";
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

    public record Result(List<String> problems, Map<String, Long> timingsMs, String mekanismVersion, boolean jarShaMatch,
                         boolean classShaMatch, int foreignMixins, int configs, int emptyConfigs, int mixinClassesRead, long totalMs) {
        public boolean eligible() {
            return problems.isEmpty();
        }
    }

    private MekGateV2() {
    }

    public static Result evaluate() {
        long t0 = System.nanoTime(), t = t0;
        Map<String, Long> timings = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        String version = "?";
        boolean jarOk = false, classOk = false;

        // A — resolve the single Mekanism artifact
        List<ModFileInfo> mekFiles = new ArrayList<>();
        List<ModFileInfo> modFiles = FMLLoader.getLoadingModList().getModFiles();
        for (ModFileInfo mf : modFiles) {
            for (IModInfo mi : mf.getMods()) {
                if (MekGate.MOD_ID.equals(mi.getModId())) {
                    mekFiles.add(mf);
                    version = mi.getVersion().toString();
                }
            }
        }
        if (mekFiles.size() != 1) problems.add("MEKANISM_ARTIFACT_" + (mekFiles.isEmpty() ? "MISSING" : "AMBIGUOUS count=" + mekFiles.size()));
        t = lap(timings, "resolveMod", t);

        if (mekFiles.size() == 1) {
            ModFileInfo mek = mekFiles.get(0);
            // B — full jar fingerprint
            String jarSha = sha256(mek.getFile().getFilePath());
            jarOk = MekGate.VERSION.equals(version) && MekGate.JAR_SHA256.equals(jarSha);
            if (!jarOk) problems.add("MEKANISM_JAR_SHA_MISMATCH version=" + version + " sha256=" + jarSha + " audited="
                    + MekGate.VERSION + " " + MekGate.JAR_SHA256);
            t = lap(timings, "jarSha", t);
            // C — exactly the two target entries
            Path root = mek.getFile().getSecureJar().getRootPath();
            classOk = true;
            for (Map.Entry<String, String> e : MekGate.CLASS_SHA256.entrySet()) {
                try {
                    String sha = hex(Files.readAllBytes(root.resolve(e.getKey() + ".class")));
                    if (!e.getValue().equals(sha)) {
                        classOk = false;
                        problems.add("TARGET_CLASS_SHA_MISMATCH " + e.getKey() + " sha256=" + sha + " audited=" + e.getValue());
                    }
                } catch (IOException ex) {
                    classOk = false;
                    problems.add("TARGET_CLASS_UNREADABLE " + e.getKey() + ": " + ex);
                }
            }
            t = lap(timings, "classSha", t);
        }

        // D — foreign mixins from the Mixin runtime's registered configs
        // Index once: every package and each of its ancestors -> the mod jars holding classes under it.
        Map<String, List<ModFileInfo>> packageIndex = new java.util.HashMap<>();
        for (ModFileInfo mf : modFiles) {
            if (mf.getMods().stream().anyMatch(mi -> MekGate.OWN_MOD_ID.equals(mi.getModId()))) continue;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String p : mf.getFile().getSecureJar().getPackages()) {
                for (String q = p; seen.add(q); ) {
                    packageIndex.computeIfAbsent(q, k -> new ArrayList<>()).add(mf);
                    int dot = q.lastIndexOf('.');
                    if (dot < 0) break;
                    q = q.substring(0, dot);
                }
            }
        }
        Map<String, List<ModFileInfo>> owners = new LinkedHashMap<>();
        Set<Config> configs = Mixins.getConfigs();
        int configCount = 0, emptyConfigs = 0;
        for (Config config : configs) {
            if (OWN_CONFIG.equals(config.getName())) continue;
            configCount++;
            String pkg = config.getConfig().getMixinPackage();
            if (pkg == null || pkg.isEmpty()) {
                problems.add("MIXIN_CONFIG_UNRESOLVED config=" + config.getName() + " (no mixin package)");
                continue;
            }
            String pkgName = pkg.endsWith(".") ? pkg.substring(0, pkg.length() - 1) : pkg;
            List<ModFileInfo> found = packageIndex.getOrDefault(pkgName, List.of());
            if (found.isEmpty() && !ownPackage(pkgName, modFiles)) {
                // No mod jar holds a single class under this package. Every mixin of a config (static or
                // plugin-supplied) must be a class under its package, so the config can apply nothing. The legacy
                // gate scanned the same mod jars; acceptTargets() still refuses startup if any target appears.
                emptyConfigs++;
            }
            owners.computeIfAbsent(pkgName, k -> new ArrayList<>()).addAll(found);
        }
        t = lap(timings, "mixinConfigs", t);

        // group the package prefixes per jar, then read each jar once
        Map<ModFileInfo, java.util.Set<String>> perJar = new LinkedHashMap<>();
        owners.forEach((pkg, files) -> files.forEach(mf -> perJar.computeIfAbsent(mf, k -> new java.util.HashSet<>()).add(pkg.replace('.', '/') + "/")));
        int read = 0, foreign = 0;
        // diagnostic only (-Dnestworld.compatMekanism.gateDump=<file>): every class read, as "jarFile|entry"
        String dumpPath = System.getProperty("nestworld.compatMekanism.gateDump");
        List<String> dump = dumpPath == null ? null : new ArrayList<>();
        for (Map.Entry<ModFileInfo, java.util.Set<String>> e : perJar.entrySet()) {
            ModFileInfo mf = e.getKey();
            String modId = mf.getMods().get(0).getModId();
            // Read through FML's already-open SecureJar view (no jar re-open, no full entry enumeration); each class
            // once: a prefix nested under another prefix of the same jar is already covered by the outer walk.
            Path root = mf.getFile().getSecureJar().getRootPath();
            for (String prefix : e.getValue()) {
                if (coveredByOther(prefix, e.getValue())) continue;
                Path dir = root.resolve(prefix);
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> files = Files.walk(dir)) {
                    for (Path p : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".class"))::iterator) {
                        read++;
                        if (dump != null) dump.add(mf.getFile().getFilePath().getFileName() + "|" + root.relativize(p));
                        String target = mixinTarget(Files.readAllBytes(p));
                        if (target != null) {
                            foreign++;
                            problems.add("FOREIGN_MIXIN mod=" + modId + " mixin=" + p + " target=" + target);
                        }
                    }
                } catch (IOException ex) {
                    problems.add("MIXIN_PACKAGE_UNREADABLE " + prefix + " in " + modId + ": " + ex);
                }
            }
        }
        t = lap(timings, "mixinTargets", t);
        if (dump != null) {
            try {
                List<String> lines = new ArrayList<>(dump);
                perJar.forEach((mf, prefixes) -> lines.add("#prefixes " + mf.getFile().getFilePath() + " " + prefixes));
                Files.write(Path.of(dumpPath), lines);
            } catch (IOException ignored) {
                // diagnostics must never influence the decision
            }
        }
        long total = (System.nanoTime() - t0) / 1_000_000;
        timings.put("total", total);
        return new Result(List.copyOf(problems), timings, version, jarOk, classOk, foreign, configCount, emptyConfigs, read, total);
    }

    /** true if another prefix of the same jar is a strict ancestor of this one (its walk already covers it) */
    private static boolean coveredByOther(String prefix, java.util.Set<String> prefixes) {
        for (String p : prefixes) if (!p.equals(prefix) && prefix.startsWith(p)) return true;
        return false;
    }

    /** true only for packages of this addon itself (its own config is skipped above, this is a safety net). */
    private static boolean ownPackage(String pkg, List<ModFileInfo> modFiles) {
        for (ModFileInfo mf : modFiles) {
            if (mf.getMods().stream().anyMatch(mi -> MekGate.OWN_MOD_ID.equals(mi.getModId()))
                    && ownsPackageTree(mf, pkg)) return true;
        }
        return false;
    }

    /**
     * A jar owns a config's mixin package if it contains classes in that package or any subpackage: Mixin allows
     * mixin classes anywhere under the declared package, and SecureJar lists only packages that hold classes.
     */
    private static boolean ownsPackageTree(ModFileInfo mf, String pkg) {
        String prefix = pkg + ".";
        for (String p : mf.getFile().getSecureJar().getPackages()) {
            if (p.equals(pkg) || p.startsWith(prefix)) return true;
        }
        return false;
    }

    private static final byte[] SIMPLE_CACHE = "NetworkAcceptorCache".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] SIMPLE_REGISTRY = "TransmitterNetworkRegistry".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /**
     * The protected target this class is a @Mixin for, or null. Handles Class literals and dotted/slashed strings.
     * Byte prefilter: every form that can name a target (descriptor, slashed or dotted string) contains the target's
     * simple name in the constant pool; classes without it cannot name a target and are not parsed.
     */
    static String mixinTarget(byte[] bytes) {
        if (!contains(bytes, SIMPLE_CACHE) && !contains(bytes, SIMPLE_REGISTRY)) return null;
        String[] hit = {null};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!MIXIN_DESC.equals(desc)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        String v = value instanceof Type type ? type.getInternalName() : String.valueOf(value).replace('.', '/');
                        if (v.equals(MekGate.CACHE) || v.equals(MekGate.REGISTRY)) hit[0] = v;
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        return this;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return hit[0];
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        byte first = needle[0];
        outer:
        for (int i = 0, max = haystack.length - needle.length; i <= max; i++) {
            if (haystack[i] != first) continue;
            for (int j = 1; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    private static long lap(Map<String, Long> timings, String phase, long since) {
        long now = System.nanoTime();
        timings.put(phase, (now - since) / 1_000_000);
        return now;
    }

    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "unreadable(" + e + ")";
        }
    }

    private static String hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
