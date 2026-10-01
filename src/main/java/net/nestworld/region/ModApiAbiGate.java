package net.nestworld.region;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PKG-002 Gate 2 — Provider SPI ABI compatibility (docs/contracts/PKG-002-distribution-model.md).
 *
 * <pre>
 * Gate 1  VERSION  (FML, Core's mods.toml range):  "does this provider CLAIM a supported version?"
 * Gate 2  ABI      (this class):                    "does it PHYSICALLY contain the contract this
 *                                                     Core build was compiled against?"
 * </pre>
 * Runs first in {@link NestworldMod}'s constructor, before any SPI-dependent Core class is touched.
 * The expected ABI is {@code META-INF/nestworld/spi-abi.json}, GENERATED at build time from Core's
 * own bytecode (every Core reference to a {@code net.nestworld.modapi} member, plus every modapi
 * interface Core implements, checked in BOTH directions: the provider must still declare each
 * method Core implements for it, and Core must implement each abstract method the provider
 * declares) — no hand-maintained second list.
 *
 * <p>Deliberately free of compile-time references to SPI types: classes are loaded by name with
 * {@code initialize=false}, and members are resolved through {@link MethodHandles.Lookup}, which
 * performs the same resolution the JVM would perform at the call site (a missing method, a changed
 * descriptor, a static/instance mismatch all fail here) without running any provider code.
 *
 * <p>Not a security mechanism: a deliberately malicious provider can pass this check and still
 * misbehave. Its job is narrower — no late {@code NoSuchMethodError}/{@code AbstractMethodError}/
 * {@code NoClassDefFoundError} from a damaged, mis-built or swapped provider (finding PKG-002-S5:
 * an ABI-broken provider claiming a verified version passed Gate 1).
 */
final class ModApiAbiGate {
    static final String MANIFEST = "/META-INF/nestworld/spi-abi.json";
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/ModAPI");

    private ModApiAbiGate() {}

    /** Throws {@link IllegalStateException} with a readable diagnostic if the selected provider is
     *  ABI-incompatible. Returns normally otherwise. */
    static void verifyOrFail(String providerDescription, boolean production) {
        JsonObject manifest;
        try (InputStream in = ModApiAbiGate.class.getResourceAsStream(MANIFEST)) {
            if (in == null) {
                if (production) {
                    throw new IllegalStateException("NestWorld Mod API ABI gate: " + MANIFEST
                            + " is missing from this NestWorldCore build -- cannot verify the provider ABI");
                }
                LOGGER.warn("Mod API ABI gate skipped: {} not on the dev classpath", MANIFEST);
                return;
            }
            manifest = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("NestWorld Mod API ABI gate: cannot read " + MANIFEST, e);
        }
        ClassLoader loader = ModApiAbiGate.class.getClassLoader();
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (JsonElement c : manifest.getAsJsonArray("classes")) {
            checked++;
            String name = c.getAsString();
            try {
                Class.forName(name.replace('/', '.'), false, loader);
            } catch (Throwable t) {
                problems.add("missing class " + name.replace('/', '.') + " (" + t.getClass().getSimpleName() + ")");
            }
        }
        for (JsonElement e : manifest.getAsJsonArray("members")) {
            checked++;
            JsonObject m = e.getAsJsonObject();
            String owner = m.get("owner").getAsString().replace('/', '.');
            String name = m.get("name").getAsString();
            String desc = m.get("desc").getAsString();
            int opcode = m.get("opcode").getAsInt();
            String what = owner + (m.get("kind").getAsString().equals("field") ? "." + name + ":" : "#" + name) + desc;
            try {
                Class<?> ownerClass = Class.forName(owner, false, loader);
                if (m.get("kind").getAsString().equals("field")) {
                    Class<?> type = MethodType.fromMethodDescriptorString("()" + desc, loader).returnType();
                    switch (opcode) {
                        case 178 -> lookup.findStaticGetter(ownerClass, name, type);
                        case 179 -> lookup.findStaticSetter(ownerClass, name, type);
                        case 180 -> lookup.findGetter(ownerClass, name, type);
                        case 181 -> lookup.findSetter(ownerClass, name, type);
                        default -> problems.add("unsupported field opcode " + opcode + " for " + what);
                    }
                } else {
                    MethodType type = MethodType.fromMethodDescriptorString(desc, loader);
                    switch (opcode) {
                        case 184 -> lookup.findStatic(ownerClass, name, type);                    // INVOKESTATIC
                        case 182, 185 -> lookup.findVirtual(ownerClass, name, type);              // INVOKEVIRTUAL/INTERFACE
                        case 183 -> {                                                             // INVOKESPECIAL
                            if (name.equals("<init>")) lookup.findConstructor(ownerClass, type);
                            else problems.add("unsupported invokespecial " + what);
                        }
                        default -> problems.add("unsupported method opcode " + opcode + " for " + what);
                    }
                }
            } catch (Throwable t) {
                problems.add("unresolvable " + what + " (" + t.getClass().getSimpleName() + ")");
            }
        }
        for (JsonElement e : manifest.getAsJsonArray("implementsTypes")) {
            checked++;
            JsonObject imp = e.getAsJsonObject();
            String type = imp.get("type").getAsString().replace('/', '.');
            String coreClass = imp.get("coreClass").getAsString().replace('/', '.');
            Set<String> implemented = new HashSet<>();
            for (JsonElement s : imp.getAsJsonArray("implemented")) implemented.add(s.getAsString());
            try {
                Class<?> api = Class.forName(type, false, loader);
                // Direction 1: every method Core implements for this type (the contract as compiled)
                // must still be declared by the provider -- the provider calls Core through them.
                for (JsonElement c : imp.getAsJsonArray("contract")) {
                    String sig = c.getAsString();
                    int p = sig.indexOf('(');
                    try {
                        lookup.findVirtual(api, sig.substring(0, p), MethodType.fromMethodDescriptorString(sig.substring(p), loader));
                    } catch (Throwable t) {
                        problems.add(type + " no longer declares " + sig + ", which " + coreClass
                                + " implements and the provider is expected to call (" + t.getClass().getSimpleName() + ")");
                    }
                }
                // Direction 2: every abstract method the provider declares must be implemented by Core.
                for (Method am : api.getMethods()) {
                    if (!Modifier.isAbstract(am.getModifiers())) continue;
                    String sig = am.getName() + MethodType.methodType(am.getReturnType(), am.getParameterTypes()).toMethodDescriptorString();
                    if (!implemented.contains(sig)) {
                        problems.add(type + " requires " + sig + ", which " + coreClass + " (built against the expected ABI) does not implement");
                    }
                }
            } catch (Throwable t) {
                problems.add("cannot inspect " + type + " implemented by " + coreClass + " (" + t.getClass().getSimpleName() + ")");
            }
        }

        String builtAgainst = manifest.get("builtAgainstModApi").getAsString();
        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder()
                    .append("NestWorld Mod API provider ").append(providerDescription)
                    .append(" is ABI-incompatible with this NestWorldCore build (built against Mod API ")
                    .append(builtAgainst).append("). Startup aborted before any SPI-dependent Core initialization.\n")
                    .append("Problems (").append(problems.size()).append(" of ").append(checked).append(" checks):");
            for (String p : problems) sb.append("\n  - ").append(p);
            sb.append("\nInstall a provider built from the same Mod API contract, or remove the mismatching provider from mods/.");
            throw new IllegalStateException(sb.toString());
        }
        LOGGER.info("Mod API ABI gate PASS: provider {} satisfies all {} checks of the ABI this Core was built against (Mod API {})",
                providerDescription, checked, builtAgainst);
    }
}
