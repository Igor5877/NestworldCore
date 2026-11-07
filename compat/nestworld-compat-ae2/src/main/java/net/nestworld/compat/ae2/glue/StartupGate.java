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
    static final String AE2_SHA256 = "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc";

    record Allowed(String modId, String version, String sha256) {
    }

    private static final Allowed AE2 = new Allowed("ae2", "15.4.10", AE2_SHA256);
    static final String AE2_BASE = "15.4.10";
    static final String SELF_MOD_ID = "nestworld_compat_ae2";

    /** An audited AE2 release: exact jar, and the classes (internal names) whose bytecode differs from {@link #AE2_BASE}. */
    record Ae2Release(String version, String sha256, Set<String> changedVsBase) {
    }

    static final List<Ae2Release> AE2_RELEASES = List.of(
            new Ae2Release(AE2_BASE, AE2_SHA256, Set.of()),
            // 15.4.11: per-class bytecode compare vs 15.4.10 — 5 changed + 2 added (client overlay, tooltips, crafting
            // terminal packet, test plots, CraftingCPUCalculator.verifyInternalStructure: same predicate, since
            // CraftingBlockEntity.isValid() is constantly true). Evidence: docs/compat/AE2-RELEASE-EQUIV-001.md
            new Ae2Release("15.4.11", "f2ff9ee5122b037d4e93537425ab9fbe49860fc9c3977db13b20a50ca7d30f9e", Set.of(
                    "appeng/client/render/overlay/OverlayManager",
                    "appeng/core/localization/Tooltips",
                    "appeng/core/sync/packets/FillCraftingGridFromRecipePacket",
                    "appeng/core/sync/packets/FillCraftingGridFromRecipePacket$DisplacedItem",
                    "appeng/me/cluster/implementations/CraftingCPUCalculator",
                    "appeng/server/testplots/CraftingTerminalTestPlots",
                    "appeng/server/testplots/TestPlots")));

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
    private static final Allowed EXTENDEDAE = new Allowed("expatternprovider", "1.20-1.4.15-forge",
            "999281e5e12efc7b2b947a05c698a35b315502a07a99be9e6ad3f9499821b29f");
    private static final Allowed ADVANCEDAE = new Allowed("advanced_ae", "1.3.5-1.20.1",
            "a580ee70f20c6465f3d7d68fa99fba91f6885efe1dc9df70b5af951ed64c211c");
    // AE2-COMPAT-ALLOWLIST-002 (owner pack project-infinity, 2026-11-07): bytecode-audited releases
    private static final Allowed EXTENDEDAE_1421 = new Allowed("expatternprovider", "1.20-1.4.21-forge",
            "30fc8abe66d4b96919f0fd7b33badd0bb2013b31e2ee57f6aeaa63ffae37df4b");
    private static final Allowed ADVANCEDAE_136 = new Allowed("advanced_ae", "1.3.6-1.20.1",
            "e216f95ac73eb4c1e8f3db22cc3d30f37f2612a7cb1762b9ebce148d0d5657b2");
    private static final Allowed CRAZYAE2_262 = new Allowed("crazyae2addons", "2.6.2",
            "6a0e023beefd183fc5995b3e65b1a3392b846d8a7ff3f56c17c9092201025e22");
    private static final Allowed NEOECOAE_2042 = new Allowed("neoecoae", "20.4.2",
            "e6ba909b2bf4ccce2ab055a7293d487b8df1ae8239220181e0f9d4bf0cf95571");
    private static final Allowed SOLARFLUX = new Allowed("solarflux", "20.1.11",
            "b8460262e2e657af8ce5ea4dd85fae5e611c7685e363d58c0f4389765ff1d463");

    /** Closure classes (internal names) and their audited owner; interfaces of the chain included. */
    /** Audited releases per class (any match admits; ALLOWLIST-001, ALLOWLIST-002). */
    static final Map<String, List<Allowed>> ALLOWLIST = Map.ofEntries(
            Map.entry("appeng/blockentity/grid/AENetworkPowerBlockEntity", List.of(AE2)),
            Map.entry("appeng/blockentity/misc/ChargerBlockEntity", List.of(AE2)),
            Map.entry("appeng/blockentity/misc/InscriberBlockEntity", List.of(AE2)),
            Map.entry("appeng/blockentity/networking/ControllerBlockEntity", List.of(AE2)),
            Map.entry("appeng/blockentity/networking/CreativeEnergyCellBlockEntity", List.of(AE2)),
            Map.entry("appeng/blockentity/networking/EnergyAcceptorBlockEntity", List.of(AE2)),
            Map.entry("appeng/blockentity/networking/EnergyCellBlockEntity", List.of(AE2)),
            Map.entry("appeng/blockentity/powersink/AEBasePoweredBlockEntity", List.of(AE2)),
            Map.entry("appeng/blockentity/powersink/IExternalPowerSink", List.of(AE2)),
            Map.entry("appeng/blockentity/storage/ChestBlockEntity", List.of(AE2)),
            Map.entry("appeng/me/energy/GridEnergyStorage", List.of(AE2)),
            Map.entry("appeng/parts/networking/EnergyAcceptorPart", List.of(AE2)),
            Map.entry("com/glodblock/github/extendedae/common/tileentities/TileCaner", List.of(EXTENDEDAE, EXTENDEDAE_1421)),
            Map.entry("com/glodblock/github/extendedae/common/tileentities/TileCircuitCutter", List.of(EXTENDEDAE, EXTENDEDAE_1421)),
            Map.entry("com/glodblock/github/extendedae/common/tileentities/TileExCharger", List.of(EXTENDEDAE, EXTENDEDAE_1421)),
            Map.entry("com/glodblock/github/extendedae/common/tileentities/TileExInscriber", List.of(EXTENDEDAE, EXTENDEDAE_1421)),
            Map.entry("net/pedroksl/advanced_ae/common/entities/QuantumCrafterEntity", List.of(ADVANCEDAE, ADVANCEDAE_136)),
            Map.entry("net/pedroksl/advanced_ae/common/entities/ReactionChamberEntity", List.of(ADVANCEDAE, ADVANCEDAE_136)),
            Map.entry("org/zeith/solarflux/mixins/compat/ae2/SolarPanelTileMixin", List.of(SOLARFLUX)),
            Map.entry("org/zeith/solarflux/block/SolarPanelTile", List.of(SOLARFLUX)),
            Map.entry("net/oktawia/crazyae2addons/entities/EnergyStorage1kBE", List.of(CRAZYAE2_262)),
            Map.entry("net/oktawia/crazyae2addons/entities/EnergyStorage4kBE", List.of(CRAZYAE2_262)),
            Map.entry("net/oktawia/crazyae2addons/entities/EnergyStorage16kBE", List.of(CRAZYAE2_262)),
            Map.entry("net/oktawia/crazyae2addons/entities/EnergyStorage64kBE", List.of(CRAZYAE2_262)),
            Map.entry("net/oktawia/crazyae2addons/entities/EnergyStorage256kBE", List.of(CRAZYAE2_262)),
            Map.entry("net/oktawia/crazyae2addons/entities/EnergyStorage1mBE", List.of(CRAZYAE2_262)),
            Map.entry("net/oktawia/crazyae2addons/entities/EnergyStorage4mBE", List.of(CRAZYAE2_262)),
            Map.entry("net/oktawia/crazyae2addons/entities/EnergyStorage16mBE", List.of(CRAZYAE2_262)),
            Map.entry("net/oktawia/crazyae2addons/entities/EnergyStorage64mBE", List.of(CRAZYAE2_262)),
            Map.entry("net/oktawia/crazyae2addons/entities/EnergyStorage256mBE", List.of(CRAZYAE2_262)),
            Map.entry("cn/dancingsnow/neoecoae/blocks/entity/ECOIntegratedWorkingStationBlockEntity", List.of(NEOECOAE_2042)),
            Map.entry("cn/dancingsnow/neoecoae/blocks/entity/storage/ECOEnergyCellBlockEntity", List.of(NEOECOAE_2042)));

    public record Result(boolean active, List<String> problems, long scannedClasses, long millis, List<String> manifestProblems) {
    }

    static final String MANIFEST_CONTRACT = "AE2-COMPAT-MANIFEST-1";
    static final List<String> MANIFEST_INTERFACES = List.of(
            "appeng/api/networking/IGridServiceProvider",
            "appeng/api/storage/IStorageProvider",
            "appeng/api/networking/crafting/ICraftingProvider");

    private record ManifestEntry(String iface, String modId, String version, String sha256) {
    }

    /** iface simple name + "|" + internal class name → audited entries (one per audited release). */
    private static Map<String, List<ManifestEntry>> loadManifest(List<String> problems) {
        Map<String, List<ManifestEntry>> m = new HashMap<>();
        try (InputStream in = StartupGate.class.getResourceAsStream("/compat-manifest.tsv")) {
            if (in == null) {
                problems.add("COMPAT_MANIFEST_MISSING");
                return m;
            }
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                m.computeIfAbsent(f[0] + "|" + f[1], k -> new ArrayList<>()).add(new ManifestEntry(f[0], f[2], f[3], f[4]));
            }
        } catch (IOException e) {
            problems.add("COMPAT_MANIFEST_UNREADABLE " + e);
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
