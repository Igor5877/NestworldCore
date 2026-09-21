import net.minecraftforge.coremod.api.ASMAPI;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;
import org.openjdk.nashorn.api.scripting.ScriptObjectMirror;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import javax.script.ScriptEngine;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

public final class CoremodRegression {
    static int assertions;
    static void check(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }
    static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
    static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
    static ClassNode load(Path mods, String glob, String name) throws Exception {
        List<Path> matches;
        try (var paths = Files.newDirectoryStream(mods, glob)) {
            matches = new ArrayList<>();
            paths.forEach(matches::add);
        }
        check(matches.size() == 1, "Expected exactly one jar: " + glob);
        try (JarFile jar = new JarFile(matches.get(0).toFile())) {
            return read(jar.getInputStream(jar.getJarEntry(name + ".class")).readAllBytes());
        }
    }
    static ScriptObjectMirror transformer(Path root, String script) throws Exception {
        ScriptEngine engine = new NashornScriptEngineFactory().getScriptEngine();
        ASMAPI.engine = engine;
        ASMAPI.resources = root.resolve("src/main/resources");
        engine.eval(Files.readString(ASMAPI.resources.resolve("coremods/" + script)));
        ScriptObjectMirror map = (ScriptObjectMirror) engine.eval("initializeCoreMod()");
        ScriptObjectMirror definition = (ScriptObjectMirror) map.values().iterator().next();
        return (ScriptObjectMirror) definition.getMember("transformer");
    }
    static ClassNode transform(ScriptObjectMirror fn, ClassNode original) {
        return (ClassNode) fn.call(null, original);
    }
    static void verify(ClassNode node) throws Exception {
        // Round-trip compressed/expanded frames and validate instruction stack types.
        ClassNode roundTrip = read(bytes(node));
        for (MethodNode method : roundTrip.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                new Analyzer<>(new BasicVerifier()).analyze(roundTrip.name, method);
            }
        }
    }
    static void unchangedFailure(ScriptObjectMirror fn, ClassNode broken, String message) {
        byte[] before = bytes(broken);
        int errors = ASMAPI.errors;
        ClassNode after = transform(fn, broken);
        check(Arrays.equals(before, bytes(broken)), "Input mutated on rejection: " + message);
        check(after == broken, "Rejected class not retained: " + message);
        check(ASMAPI.errors == errors + 1, "No explicit diagnostic: " + message);
    }
    static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), mods = Path.of(args[1]);
        String[][] cases = {
            {"static_collection_race_transformer.js", "exdeorum-1.50.jar", "thedarkcolour/exdeorum/network/VisualUpdateTracker"},
            {"static_collection_race_transformer.js", "forestry-1.20.1-2.10.2.jar", "forestry/core/blocks/TileStreamUpdateTracker"},
            {"static_collection_race_transformer.js", "Mekanism-1.20.1-10.4.16.80.jar", "mekanism/common/content/transporter/TransporterManager"},
            {"static_collection_race_transformer.js", "industrial-foregoing-1.20.1-3.5.22.jar", "com/buuz135/industrial/utils/explosion/ExplosionTickHandler"},
            {"tomsstorage_terminal_atomic_publish.js", "toms_storage-1.20-1.7.1.jar", "com/tom/storagemod/tile/StorageTerminalBlockEntity"},
            {"mod_compound_races.js", "Mekanism-1.20.1-10.4.16.80.jar", "mekanism/common/content/transporter/PathfinderCache"},
            {"mod_compound_races.js", "sophisticatedcore-1.20.1-1.3.40.1925.jar", "net/p3pp3rf1y/sophisticatedcore/util/RecipeHelper"},
            {"mod_compound_races.js", "FluxNetworks-1.20.1-7.2.1.15.jar", "sonar/fluxnetworks/common/connection/FluxNetwork"},
            {"mod_compound_races.js", "FluxNetworks-1.20.1-7.2.1.15.jar", "sonar/fluxnetworks/common/connection/ServerFluxNetwork"},
            {"mod_compound_races.js", "Draconic-Evolution-1.20.1-3.1.2.621-universal.jar", "com/brandon3055/draconicevolution/network/CrystalUpdateBatcher"}
        };
        for (String[] test : cases) {
            ScriptObjectMirror fn = transformer(root, test[0]);
            ClassNode input = load(mods, test[1], test[2]);
            byte[] before = bytes(input);
            int errors = ASMAPI.errors;
            ClassNode output = transform(fn, input);
            check(output != input && ASMAPI.errors == errors, "Supported jar was not patched: " + test[2]);
            check(Arrays.equals(before, bytes(input)), "Input tree mutated: " + test[2]);
            verify(output);
            // A second pass may be idempotent (monitor flags) or reject, but must
            // never insert a second helper/field or corrupt the bytecode.
            verify(transform(fn, output));
            ClassNode broken = read(before);
            if (test[0].equals("static_collection_race_transformer.js")) {
                method(broken, "<clinit>").name = "changedInitializer";
            } else if (test[0].startsWith("tomsstorage")) {
                method(broken, "lambda$updateServer$2").desc = "()V";
            } else {
                String name = test[2].endsWith("RecipeHelper") ? "getFromCache"
                        : test[2].endsWith("PathfinderCache") ? "reset"
                        : test[2].endsWith("FluxNetwork") ? "onDelete" : "tickEnd";
                method(broken, name).name = "changedMethod";
            }
            unchangedFailure(fn, broken, test[2]);
            if (test[0].startsWith("static_collection")) {
                // Duplicate allocation pattern in a second method with the same
                // name/descriptor: neither arbitrary match may be selected.
                ClassNode ambiguous = read(before);
                MethodNode original = method(ambiguous, "<clinit>");
                MethodNode duplicate = new MethodNode(original.access, original.name, original.desc, null, null);
                original.accept(duplicate);
                ambiguous.methods.add(duplicate);
                unchangedFailure(fn, ambiguous, "ambiguous allocation " + test[2]);
            }
            System.out.println("PASS real jar: " + test[2]);
        }
        ConcurrencyRegression.run(root, mods);
        System.out.println("PASS: " + assertions + " regression assertions; all transformed methods pass ASM BasicVerifier");
    }
}
