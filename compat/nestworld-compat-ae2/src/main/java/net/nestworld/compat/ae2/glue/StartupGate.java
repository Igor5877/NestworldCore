package net.nestworld.compat.ae2.glue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * M5c startup verification (AE2-COMPAT-DESIGN-001.2 §1a, AE2-COMPAT-ALLOWLIST-001): the addon activates
 * only if AE2 is exactly the audited jar and every class in the loaded mod set that reaches
 * {@code IAEPowerStorage} (own interfaces or parent chain, transitively; a mixin declaring it counts
 * for its targets and their subclasses) is on the allowlist with its exact mod, version and jar hash.
 * Any mismatch: no mixin applies, the environment runs exactly as without the addon, and the pins stay.
 *
 * <p>AE2-RELEASE-EQUIV-001: the audit is of AE2 {@link #AE2_BASE}; a later AE2 release is admitted only through
 * {@link #AE2_RELEASES}, with its exact jar hash and the classes whose bytecode differs from the base. An AE2 class
 * from such a release counts as audited only when it is NOT in that set, and the release is refused when any mixin of
 * this addon targets a changed class.
 */
public final class StartupGate {
    static final String TARGET = "appeng/api/networking/energy/IAEPowerStorage";
    static final String AE2_SHA256 = net.nestworld.compat.ae2.registry.Ae2CompatibilityRegistry.AE2_BASE_SHA256;

    record Allowed(String modId, String version, String sha256) {
    }

    static final String AE2_BASE = net.nestworld.compat.ae2.registry.Ae2CompatibilityRegistry.AE2_BASE_VERSION;
    static final String SELF_MOD_ID = "nestworld_compat_ae2";

    /** An audited AE2 release: exact jar, and the classes (internal names) whose bytecode differs from {@link #AE2_BASE}. */
    record Ae2Release(String version, String sha256, Set<String> changedVsBase) {
    }

    /** View of {@link net.nestworld.compat.ae2.registry.Ae2CompatibilityRegistry#ae2Releases()} (WP1). */
    static final List<Ae2Release> AE2_RELEASES = net.nestworld.compat.ae2.registry.Ae2CompatibilityRegistry.ae2Releases()
            .stream().map(d -> new Ae2Release(d.version(), d.sha256(), d.ae2ChangedVsBase())).toList();

    static Ae2Release ae2Release(String version, String sha) {
        for (Ae2Release r : AE2_RELEASES) {
            if (r.version().equals(version) && r.sha256().equals(sha)) return r;
        }
        return null;
    }

    /**
     * An audited (mod, version, sha) for {@code className} matches the loaded owner: exactly, or — for an AE2 class
     * audited at the base — the owner is an admitted AE2 release in which this class is unchanged.
     */
    static boolean audited(String className, String auditedMod, String auditedVersion, String auditedSha,
                           String mod, String version, String sha) {
        if (auditedMod.equals(mod) && auditedVersion.equals(version) && auditedSha.equals(sha)) return true;
        if (!"ae2".equals(auditedMod) || !"ae2".equals(mod) || !AE2_BASE.equals(auditedVersion) || !AE2_SHA256.equals(auditedSha)) {
            return false;
        }
        Ae2Release r = ae2Release(version, sha);
        return r != null && !r.changedVsBase().contains(className);
    }
    /** View of {@link net.nestworld.compat.ae2.registry.Ae2CompatibilityRegistry#powerAllowlist()} (WP1):
     *  closure class (internal name) → audited releases (any match admits; ALLOWLIST-001, ALLOWLIST-002). */
    static final Map<String, List<Allowed>> ALLOWLIST = buildAllowlist();

    private static Map<String, List<Allowed>> buildAllowlist() {
        Map<String, List<Allowed>> m = new java.util.LinkedHashMap<>();
        net.nestworld.compat.ae2.registry.Ae2CompatibilityRegistry.powerAllowlist().forEach((c, owners) ->
                m.put(c, owners.stream().map(o -> new Allowed(o.modId(), o.version(), o.sha256())).toList()));
        return java.util.Collections.unmodifiableMap(m);
    }

    public record Result(boolean active, List<String> problems, long scannedClasses, long millis, List<String> manifestProblems) {
    }

    static final String MANIFEST_CONTRACT = "AE2-COMPAT-MANIFEST-1";
    static final List<String> MANIFEST_INTERFACES = List.of(
            "appeng/api/networking/IGridServiceProvider",
            "appeng/api/storage/IStorageProvider",
            "appeng/api/networking/crafting/ICraftingProvider");

    private record ManifestEntry(String iface, String modId, String version, String sha256) {
    }

    /** iface simple name + "|" + internal class name → audited entries (one per audited release); view of
     *  {@link net.nestworld.compat.ae2.registry.Ae2CompatibilityRegistry#manifestEntries} (WP1), file order kept. */
    static Map<String, List<ManifestEntry>> loadManifest(List<String> problems) {
        Map<String, List<ManifestEntry>> m = new HashMap<>();
        for (var r : net.nestworld.compat.ae2.registry.Ae2CompatibilityRegistry.manifestEntries(problems)) {
            m.computeIfAbsent(r.iface() + "|" + r.internalClass(), k -> new ArrayList<>())
                    .add(new ManifestEntry(r.iface(), r.owner().modId(), r.owner().version(), r.owner().sha256()));
        }
        return m;
    }

    private record Header(String superName, String[] interfaces, List<String> mixinTargets, Owner owner) {
    }

    private record Owner(String modId, String version, Path file) {
    }

    public static Result evaluate() {
        long t0 = System.nanoTime();
        List<String> problems = new ArrayList<>();
        Map<String, Header> classes = new HashMap<>();
        Map<Path, String> shaCache = new HashMap<>();
        boolean ae2Seen = false;
        Ae2Release ae2Release = null;
        for (ModFileInfo mf : FMLLoader.getLoadingModList().getModFiles()) {
            if (mf.getMods().isEmpty()) continue;
            String modId = mf.getMods().get(0).getModId();
            String version = mf.getMods().get(0).getVersion().toString();
            Path file = mf.getFile().getFilePath();
            Owner owner = new Owner(modId, version, file);
            if (modId.equals("ae2")) {
                ae2Seen = true;
                String sha = sha256(file, shaCache);
                ae2Release = ae2Release(version, sha);
                if (ae2Release == null) {
                    problems.add("ae2 " + version + " sha256 " + sha + " is not an audited AE2 release " + AE2_RELEASES.stream()
                            .map(r -> r.version() + "/" + r.sha256().substring(0, 12)).toList());
                }
            }
            scan(mf.getFile().getSecureJar().getRootPath(), owner, classes, problems);
        }
        if (!ae2Seen) problems.add("ae2 is not loaded");
        if (ae2Release != null && !ae2Release.changedVsBase().isEmpty()) {
            // this addon's mixins must not target a class that changed since the audited base
            for (String name : new TreeSet<>(classes.keySet())) {
                Header h = classes.get(name);
                if (!SELF_MOD_ID.equals(h.owner().modId())) continue;
                for (String t : h.mixinTargets()) {
                    if (ae2Release.changedVsBase().contains(t)) {
                        problems.add("mixin " + name + " targets " + t + ", changed in ae2 " + ae2Release.version()
                                + " since the audited " + AE2_BASE);
                    }
                }
            }
        }
        // mixins that declare the interface count for their targets
        Map<String, List<String>> addedInterfaces = new HashMap<>();
        classes.forEach((name, h) -> {
            if (!h.mixinTargets().isEmpty() && reaches(name, classes, new HashMap<>(), 0)) {
                for (String t : h.mixinTargets()) addedInterfaces.computeIfAbsent(t, k -> new ArrayList<>()).add(name);
            }
        });
        Map<String, Boolean> memo = new HashMap<>();
        TreeSet<String> closure = new TreeSet<>();
        for (String name : classes.keySet()) {
            if (!name.equals(TARGET) && reachesWithMixins(name, classes, addedInterfaces, memo, 0)) closure.add(name);
        }
        for (String name : closure) {
            Header h = classes.get(name);
            List<Allowed> as = ALLOWLIST.get(name);
            if (as == null) {
                problems.add("unaudited IAEPowerStorage implementation " + name + " in " + h.owner().modId() + " "
                        + h.owner().version() + " (" + h.owner().file().getFileName() + ")");
                continue;
            }
            String sha = sha256(h.owner().file(), shaCache);
            if (as.stream().noneMatch(a -> audited(name, a.modId(), a.version(), a.sha256(), h.owner().modId(), h.owner().version(), sha))) {
                problems.add(name + " comes from " + h.owner().modId() + " " + h.owner().version() + " sha256 " + sha
                        + ", audited " + as.stream().map(a -> a.modId() + " " + a.version() + " " + a.sha256()).toList());
            }
        }
        // compatibility manifest (provider / service sets) — eligibility, not activation (owner: no fail-open)
        List<String> manifestProblems = new ArrayList<>();
        Map<String, List<ManifestEntry>> manifest = loadManifest(manifestProblems);
        for (String iface : MANIFEST_INTERFACES) {
            String simple = iface.substring(iface.lastIndexOf('/') + 1);
            Map<String, List<String>> added = new HashMap<>();
            classes.forEach((name, h) -> {
                if (!h.mixinTargets().isEmpty() && reachesTarget(iface, name, classes, Map.of(), new HashMap<>(), 0)) {
                    for (String tgt : h.mixinTargets()) added.computeIfAbsent(tgt, k -> new ArrayList<>()).add(name);
                }
            });
            Map<String, Boolean> memo2 = new HashMap<>();
            for (String name : new TreeSet<>(classes.keySet())) {
                if (name.equals(iface) || !reachesTarget(iface, name, classes, added, memo2, 0)) continue;
                Header h = classes.get(name);
                List<ManifestEntry> es = manifest.get(simple + "|" + name);
                if (es == null) {
                    manifestProblems.add("COMPAT_MANIFEST_UNKNOWN_PROVIDER mod=" + h.owner().modId() + " version=" + h.owner().version()
                            + " class=" + name.replace('/', '.') + " interface=" + simple);
                    continue;
                }
                String sha = sha256(h.owner().file(), shaCache);
                if (es.stream().noneMatch(e -> audited(name, e.modId(), e.version(), e.sha256(), h.owner().modId(), h.owner().version(), sha))) {
                    manifestProblems.add("COMPAT_MANIFEST_VERSION_MISMATCH mod=" + h.owner().modId() + " version=" + h.owner().version()
                            + " class=" + name.replace('/', '.') + " interface=" + simple + " audited="
                            + es.stream().map(e -> e.modId() + " " + e.version()).toList());
                }
            }
        }
        return new Result(problems.isEmpty(), problems, classes.size(), (System.nanoTime() - t0) / 1_000_000, manifestProblems);
    }

    private static boolean reachesWithMixins(String name, Map<String, Header> classes, Map<String, List<String>> added,
                                             Map<String, Boolean> memo, int depth) {
        return reachesTarget(TARGET, name, classes, added, memo, depth);
    }

    private static boolean reachesTarget(String target, String name, Map<String, Header> classes, Map<String, List<String>> added,
                                         Map<String, Boolean> memo, int depth) {
        if (name == null || depth > 64) return false;
        if (name.equals(target)) return true;
        Boolean m = memo.get(name);
        if (m != null) return m;
        memo.put(name, false);
        boolean r = added.containsKey(name);
        Header h = classes.get(name);
        if (!r && h != null) {
            for (String i : h.interfaces()) r |= reachesTarget(target, i, classes, added, memo, depth + 1);
            r = r || reachesTarget(target, h.superName(), classes, added, memo, depth + 1);
        }
        memo.put(name, r);
        return r;
    }

    private static boolean reaches(String name, Map<String, Header> classes, Map<String, Boolean> memo, int depth) {
        return reachesWithMixins(name, classes, Map.of(), memo, depth);
    }

    private static void scan(Path root, Owner owner, Map<String, Header> classes, List<String> problems) {
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                try (InputStream in = Files.newInputStream(p)) {
                    ClassReader cr = new ClassReader(in);
                    List<String> targets = new ArrayList<>();
                    cr.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                            if (!desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) return null;
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public AnnotationVisitor visitArray(String name) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visit(String n, Object value) {
                                            if (value instanceof Type t) targets.add(t.getInternalName());
                                            else if (value instanceof String s) targets.add(s.replace('.', '/'));
                                        }
                                    };
                                }
                            };
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    classes.putIfAbsent(cr.getClassName(), new Header(cr.getSuperName(), cr.getInterfaces(), targets, owner));
                } catch (IOException | RuntimeException e) {
                    // unreadable class file: not loadable either
                }
            });
        } catch (IOException e) {
            problems.add("cannot scan " + owner.file() + ": " + e);
        }
    }

    private static String sha256(Path file, Map<Path, String> cache) {
        return cache.computeIfAbsent(file, f -> {
            try (InputStream in = Files.newInputStream(f)) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
                return HexFormat.of().formatHex(md.digest());
            } catch (Exception e) {
                return "unreadable:" + e;
            }
        });
    }

    private StartupGate() {
    }
}
