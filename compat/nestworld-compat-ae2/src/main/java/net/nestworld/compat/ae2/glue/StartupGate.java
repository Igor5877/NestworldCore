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
 */
public final class StartupGate {
    static final String TARGET = "appeng/api/networking/energy/IAEPowerStorage";
    static final String AE2_SHA256 = "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc";

    record Allowed(String modId, String version, String sha256) {
    }

    private static final Allowed AE2 = new Allowed("ae2", "15.4.10", AE2_SHA256);
    private static final Allowed EXTENDEDAE = new Allowed("expatternprovider", "1.20-1.4.15-forge",
            "999281e5e12efc7b2b947a05c698a35b315502a07a99be9e6ad3f9499821b29f");
    private static final Allowed ADVANCEDAE = new Allowed("advanced_ae", "1.3.5-1.20.1",
            "a580ee70f20c6465f3d7d68fa99fba91f6885efe1dc9df70b5af951ed64c211c");
    private static final Allowed SOLARFLUX = new Allowed("solarflux", "20.1.11",
            "b8460262e2e657af8ce5ea4dd85fae5e611c7685e363d58c0f4389765ff1d463");

    /** Closure classes (internal names) and their audited owner; interfaces of the chain included. */
    static final Map<String, Allowed> ALLOWLIST = Map.ofEntries(
            Map.entry("appeng/blockentity/grid/AENetworkPowerBlockEntity", AE2),
            Map.entry("appeng/blockentity/misc/ChargerBlockEntity", AE2),
            Map.entry("appeng/blockentity/misc/InscriberBlockEntity", AE2),
            Map.entry("appeng/blockentity/networking/ControllerBlockEntity", AE2),
            Map.entry("appeng/blockentity/networking/CreativeEnergyCellBlockEntity", AE2),
            Map.entry("appeng/blockentity/networking/EnergyAcceptorBlockEntity", AE2),
            Map.entry("appeng/blockentity/networking/EnergyCellBlockEntity", AE2),
            Map.entry("appeng/blockentity/powersink/AEBasePoweredBlockEntity", AE2),
            Map.entry("appeng/blockentity/powersink/IExternalPowerSink", AE2),
            Map.entry("appeng/blockentity/storage/ChestBlockEntity", AE2),
            Map.entry("appeng/me/energy/GridEnergyStorage", AE2),
            Map.entry("appeng/parts/networking/EnergyAcceptorPart", AE2),
            Map.entry("com/glodblock/github/extendedae/common/tileentities/TileCaner", EXTENDEDAE),
            Map.entry("com/glodblock/github/extendedae/common/tileentities/TileCircuitCutter", EXTENDEDAE),
            Map.entry("com/glodblock/github/extendedae/common/tileentities/TileExCharger", EXTENDEDAE),
            Map.entry("com/glodblock/github/extendedae/common/tileentities/TileExInscriber", EXTENDEDAE),
            Map.entry("net/pedroksl/advanced_ae/common/entities/QuantumCrafterEntity", ADVANCEDAE),
            Map.entry("net/pedroksl/advanced_ae/common/entities/ReactionChamberEntity", ADVANCEDAE),
            Map.entry("org/zeith/solarflux/mixins/compat/ae2/SolarPanelTileMixin", SOLARFLUX),
            Map.entry("org/zeith/solarflux/block/SolarPanelTile", SOLARFLUX));

    public record Result(boolean active, List<String> problems, long scannedClasses, long millis) {
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
        for (ModFileInfo mf : FMLLoader.getLoadingModList().getModFiles()) {
            if (mf.getMods().isEmpty()) continue;
            String modId = mf.getMods().get(0).getModId();
            String version = mf.getMods().get(0).getVersion().toString();
            Path file = mf.getFile().getFilePath();
            Owner owner = new Owner(modId, version, file);
            if (modId.equals("ae2")) {
                ae2Seen = true;
                String sha = sha256(file, shaCache);
                if (!AE2.version().equals(version) || !AE2_SHA256.equals(sha)) {
                    problems.add("ae2 " + version + " sha256 " + sha + " is not the audited 15.4.10 (" + AE2_SHA256 + ")");
                }
            }
            scan(mf.getFile().getSecureJar().getRootPath(), owner, classes, problems);
        }
        if (!ae2Seen) problems.add("ae2 is not loaded");
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
            Allowed a = ALLOWLIST.get(name);
            if (a == null) {
                problems.add("unaudited IAEPowerStorage implementation " + name + " in " + h.owner().modId() + " "
                        + h.owner().version() + " (" + h.owner().file().getFileName() + ")");
                continue;
            }
            String sha = sha256(h.owner().file(), shaCache);
            if (!a.modId().equals(h.owner().modId()) || !a.version().equals(h.owner().version()) || !a.sha256().equals(sha)) {
                problems.add(name + " comes from " + h.owner().modId() + " " + h.owner().version() + " sha256 " + sha
                        + ", audited " + a.modId() + " " + a.version() + " " + a.sha256());
            }
        }
        return new Result(problems.isEmpty(), problems, classes.size(), (System.nanoTime() - t0) / 1_000_000);
    }

    private static boolean reachesWithMixins(String name, Map<String, Header> classes, Map<String, List<String>> added,
                                             Map<String, Boolean> memo, int depth) {
        if (name == null || depth > 64) return false;
        if (name.equals(TARGET)) return true;
        Boolean m = memo.get(name);
        if (m != null) return m;
        memo.put(name, false);
        boolean r = added.containsKey(name);
        Header h = classes.get(name);
        if (!r && h != null) {
            for (String i : h.interfaces()) r |= reachesWithMixins(i, classes, added, memo, depth + 1);
            r = r || reachesWithMixins(h.superName(), classes, added, memo, depth + 1);
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
