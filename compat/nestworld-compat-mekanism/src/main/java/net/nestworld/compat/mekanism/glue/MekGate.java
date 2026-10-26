package net.nestworld.compat.mekanism.glue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Exact compatibility gate. The M-A mixins apply only if Mekanism is exactly the audited jar, both target
 * classes have the audited bytecode (SHA-256 of the class file as shipped), and no other mod declares a Mixin
 * on either target. Any mismatch: no mixin applies, Mekanism runs unmodified, and the addon reports
 * INELIGIBLE (the race is then NOT mitigated).
 */
public final class MekGate {
    static final String MOD_ID = "mekanism";
    static final String VERSION = "10.4.16";
    static final String JAR_SHA256 = "3b0d191fa4a45e662f9725991d76a30192f30186abb2ba694b873b5f7b9358d2";
    static final String CACHE = "mekanism/common/lib/transmitter/acceptor/NetworkAcceptorCache";
    static final String REGISTRY = "mekanism/common/lib/transmitter/TransmitterNetworkRegistry";
    /** class-file SHA-256 of the audited 10.4.16.80 classes (structural fingerprint for the @Overwrite ports) */
    static final Map<String, String> CLASS_SHA256 = Map.of(
            CACHE, "9b119c3b3e27485849f303d1d88fc06bba818e43e73ab7d8168c0a410f51168d",
            REGISTRY, "c932a16aa12af57f5740b122da23d42c94ea239d909873fcda96f1ff9b5e2d85");
    static final String OWN_MOD_ID = "nestworld_compat_mekanism";
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

    public record Result(List<String> problems, long millis) {
        public boolean eligible() {
            return problems.isEmpty();
        }
    }

    /** Gate implementation version, shown by /nwcompatmek status. */
    public static final String IMPL = "mixin-config-v2";

    private MekGate() {
    }

    /** The original (pre MEK-GATE-PERF-001) gate: walks every class of every mod. Kept for the equivalence check. */
    public static Result evaluateLegacy() {
        long t0 = System.nanoTime();
        List<String> problems = new ArrayList<>();
        ModFileInfo mek = FMLLoader.getLoadingModList().getModFileById(MOD_ID);
        if (mek == null) {
            problems.add("mekanism is not loaded");
        } else {
            String version = mek.getMods().get(0).getVersion().toString();
            String jarSha = sha256(mek.getFile().getFilePath());
            if (!VERSION.equals(version) || !JAR_SHA256.equals(jarSha)) {
                problems.add("mekanism " + version + " sha256 " + jarSha + " is not the audited " + VERSION + " (" + JAR_SHA256 + ")");
            }
            Path root = mek.getFile().getSecureJar().getRootPath();
            for (Map.Entry<String, String> e : CLASS_SHA256.entrySet()) {
                Path cls = root.resolve(e.getKey() + ".class");
                try {
                    String sha = hex(Files.readAllBytes(cls));
                    if (!e.getValue().equals(sha)) problems.add(e.getKey() + " bytecode sha256 " + sha + " != audited " + e.getValue());
                } catch (IOException ex) {
                    problems.add(e.getKey() + " unreadable: " + ex);
                }
            }
        }
        byte[] cacheName = CACHE.getBytes(StandardCharsets.UTF_8), registryName = REGISTRY.getBytes(StandardCharsets.UTF_8);
        for (ModFileInfo mf : FMLLoader.getLoadingModList().getModFiles()) {
            if (mf.getMods().isEmpty()) continue;
            String modId = mf.getMods().get(0).getModId();
            if (modId.equals(OWN_MOD_ID)) continue;
            Path root = mf.getFile().getSecureJar().getRootPath();
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    try {
                        byte[] b = Files.readAllBytes(p);
                        if ((contains(b, cacheName) || contains(b, registryName)) && foreignMixin(b)) {
                            problems.add("foreign Mixin on a Mekanism registry target: " + p + " in " + modId);
                        }
                    } catch (IOException ignored) {
                        // unreadable entries cannot carry a Mixin that the loader would apply
                    }
                });
            } catch (IOException ex) {
                problems.add("cannot scan " + modId + ": " + ex);
            }
        }
        return new Result(List.copyOf(problems), (System.nanoTime() - t0) / 1_000_000);
    }

    /** true if the class is annotated @Mixin and the annotation names one of the two targets */
    private static boolean foreignMixin(byte[] bytes) {
        boolean[] hit = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!MIXIN_DESC.equals(desc)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        check(value);
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        return this;
                    }

                    private void check(Object value) {
                        String v = value instanceof org.objectweb.asm.Type t ? t.getInternalName() : String.valueOf(value).replace('.', '/');
                        if (v.equals(CACHE) || v.equals(REGISTRY)) hit[0] = true;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return hit[0];
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return hex(in.readAllBytes());
        } catch (IOException e) {
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
