package net.nestworld.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Startup-only loader for trusted jars in {@code <gameDir>/nestworld-modules}. */
public final class ExternalModuleLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalModuleLoader.class);
    private static final Set<String> KEYS = Set.of("schema", "id", "version", "displayName", "entrypoint",
            "spi", "capabilities", "side", "critical", "mappings", "dependencies");
    private static final List<String> RESERVED = List.of("java/", "javax/", "jdk/", "sun/", "com/sun/",
            "net/minecraft/", "com/mojang/", "net/minecraftforge/", "cpw/mods/", "org/spongepowered/",
            "org/objectweb/asm/", "org/slf4j/", "org/apache/logging/log4j/", "net/nestworld/");
    private static final Set<String> SPI_TYPES = Set.of(
            "net/nestworld/module/NestWorldModule", "net/nestworld/module/ModuleContext",
            "net/nestworld/module/ModuleDescriptor", "net/nestworld/module/ModuleId",
            "net/nestworld/module/ModuleVersion", "net/nestworld/module/ModuleIdentity",
            "net/nestworld/module/ModuleGeneration", "net/nestworld/module/ModuleExecutionContext",
            "net/nestworld/module/ModuleLifecycleState", "net/nestworld/module/ModuleFailure",
            "net/nestworld/module/ResourceHandle", "net/nestworld/module/LoginBudget",
            "net/nestworld/module/ChunkPreparation", "net/nestworld/module/ChunkPreparationRequest",
            "net/nestworld/module/ContentGeneration", "net/nestworld/module/SyncDataSnapshot",
            "net/nestworld/module/ModuleSpi");
    private record MemberReference(String owner, String name, String descriptor, boolean method) {}
    private static final class SkippedModuleException extends Exception {
        SkippedModuleException(String message) { super(message); }
    }

    private ExternalModuleLoader() {}

    public static void discover(MinecraftServer server, ModuleRegistry registry) {
        if ("false".equalsIgnoreCase(System.getProperty("nestworld.modules.external", "true"))) {
            LOGGER.info("NestWorld external modules disabled by -Dnestworld.modules.external=false");
            return;
        }
        Path dir = moduleDirectory();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            registry.recordExternalFailure("cannot create " + dir + ": " + e.getMessage());
            return;
        }

        List<Path> jars;
        try (var stream = Files.list(dir)) {
            jars = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .map(path -> {
                        try { return path.toRealPath(); } catch (Exception e) { return path.toAbsolutePath().normalize(); }
                    })
                    .distinct().sorted().toList();
        } catch (Exception e) {
            registry.recordExternalFailure("cannot scan " + dir + ": " + e.getMessage());
            return;
        }
        warnIgnoredSubdirectories(dir);
        if (jars.isEmpty()) {
            LOGGER.info("NestWorld external module directory is empty: {}", dir);
            return;
        }

        Map<Path, ExternalModuleDescriptor> parsed = new LinkedHashMap<>();
        for (Path jar : jars) {
            try {
                ExternalModuleDescriptor descriptor = parseAndValidate(jar, server);
                parsed.put(jar, descriptor);
            } catch (SkippedModuleException e) {
                LOGGER.info("NestWorld external module {} SKIPPED: {}", jar.getFileName(), e.getMessage());
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable e) {
                registry.recordExternalFailure(jar.getFileName() + " FAILED/DISCOVERY: " + e.getMessage());
            }
        }

        Map<ModuleId, List<ExternalModuleDescriptor>> byId = new TreeMap<>(Comparator.comparing(ModuleId::value));
        parsed.values().forEach(d -> byId.computeIfAbsent(d.module().id(), ignored -> new ArrayList<>()).add(d));
        Set<ModuleId> refused = new HashSet<>();
        for (var entry : byId.entrySet()) {
            if (entry.getValue().size() > 1 || registry.contains(entry.getKey())) {
                refused.add(entry.getKey());
                registry.recordExternalFailure("duplicate module id " + entry.getKey().value() + " (" +
                        entry.getValue().stream().map(d -> d.jar().toString()).toList() + ")");
            }
        }

        List<ExternalModuleDescriptor> candidates = parsed.values().stream()
                .filter(d -> !refused.contains(d.module().id())).toList();
        Map<ModuleId, ExternalModuleDescriptor> available = new HashMap<>();
        candidates.forEach(d -> available.put(d.module().id(), d));
        Set<ModuleId> dependencyFailed = new HashSet<>();
        for (ExternalModuleDescriptor d : candidates) {
            for (ExternalModuleDescriptor.Dependency dep : d.dependencies()) {
                ModuleVersion version = available.containsKey(dep.id())
                        ? available.get(dep.id()).module().version() : registry.versionOf(dep.id());
                if (version == null) {
                    if (dep.required()) {
                        dependencyFailed.add(d.module().id());
                        registry.recordExternalFailure(d.module().id().value() + " missing dependency " + dep.id().value());
                    }
                    continue;
                }
                try {
                    if (!VersionRange.createFromVersionSpec(dep.versionRange())
                            .containsVersion(new DefaultArtifactVersion(version.value())) && dep.required()) {
                        dependencyFailed.add(d.module().id());
                        registry.recordExternalFailure(d.module().id().value() + " dependency " + dep.id().value()
                                + " version " + version.value() + " outside " + dep.versionRange());
                    }
                } catch (Exception e) {
                    dependencyFailed.add(d.module().id());
                    registry.recordExternalFailure(d.module().id().value() + " invalid dependency range " + dep.versionRange());
                }
            }
        }
        for (ExternalModuleDescriptor d : candidates) {
            if (d.critical() && dependencyFailed.contains(d.module().id()))
                throw new IllegalStateException("critical NestWorld module failed dependency validation: " + d.module().id().value());
        }

        List<ExternalModuleDescriptor> ordered = topological(candidates.stream()
                .filter(d -> !dependencyFailed.contains(d.module().id())).toList(), available, registry);
        Set<ModuleId> loaded = new HashSet<>();
        for (ExternalModuleDescriptor d : ordered) {
            boolean unavailable = d.dependencies().stream().anyMatch(dep -> dep.required()
                    && available.containsKey(dep.id()) && !loaded.contains(dep.id()));
            if (unavailable) {
                registry.recordExternalFailure(d.module().id().value() + " DEPENDENCY_UNAVAILABLE");
                continue;
            }
            try {
                ModuleClassLoader loader = ModuleJarCache.obtain(d.jar(), d.module().id());
                NestWorldModule module = instantiate(d, loader);
                Set<ModuleId> required = new LinkedHashSet<>();
                d.dependencies().stream().filter(ExternalModuleDescriptor.Dependency::required)
                        .forEach(dep -> required.add(dep.id()));
                registry.discoverExternal(module, required, d.critical(), loader, d.capabilities().keySet());
                loaded.add(d.module().id());
                LOGGER.info("NestWorld external module DISCOVERED: {} from {}", d.module().id().value(), d.jar());
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable e) {
                registry.recordExternalFailure(d.module().id().value() + " FAILED/LOAD: " + e.getMessage());
                if (d.critical()) throw new IllegalStateException("critical NestWorld module failed to load: "
                        + d.module().id().value(), e);
            }
        }
    }

    public static Path moduleDirectory() {
        String override = System.getProperty("nestworld.modules.dir");
        return override == null || override.isBlank()
                ? FMLPaths.GAMEDIR.get().resolve("nestworld-modules")
                : Path.of(override).toAbsolutePath().normalize();
    }

    private static void warnIgnoredSubdirectories(Path dir) {
        try (var children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                try (var nested = Files.list(child)) {
                    if (nested.anyMatch(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith(".jar"))) {
                        LOGGER.warn("NestWorld external module subdirectories are not scanned: {}", child);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Cannot inspect ignored NestWorld module subdirectory {}", child, e);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Cannot inspect NestWorld module subdirectories under {}", dir, e);
        }
    }

    private static NestWorldModule instantiate(ExternalModuleDescriptor d, ModuleClassLoader loader) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> raw = Class.forName(d.entrypoint(), false, loader);
            if (!NestWorldModule.class.isAssignableFrom(raw) || raw.isInterface() || Modifier.isAbstract(raw.getModifiers())
                    || !Modifier.isPublic(raw.getModifiers()))
                throw new IllegalArgumentException("entrypoint must be a public concrete NestWorldModule");
            var constructor = raw.getConstructor();
            NestWorldModule module = (NestWorldModule) constructor.newInstance();
            ModuleDescriptor actual = module.descriptor();
            if (!actual.id().equals(d.module().id()) || !actual.version().equals(d.module().version()))
                throw new IllegalArgumentException("descriptor mismatch: static=" + d.module() + " runtime=" + actual);
            return module;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static ExternalModuleDescriptor parseAndValidate(Path path, MinecraftServer server) throws Exception {
        try (JarFile jar = new JarFile(path.toFile(), true)) {
            JarEntry descriptorEntry = jar.getJarEntry("META-INF/nestworld/module.json");
            if (descriptorEntry == null) throw new IllegalArgumentException("missing META-INF/nestworld/module.json");
            if (descriptorEntry.getSize() > 65536) throw new IllegalArgumentException("descriptor exceeds 64 KiB");
            String json;
            try (InputStream in = jar.getInputStream(descriptorEntry)) {
                byte[] bytes = in.readNBytes(65537);
                if (bytes.length > 65536) throw new IllegalArgumentException("descriptor exceeds 64 KiB");
                json = new String(bytes, StandardCharsets.UTF_8);
            }
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (String key : root.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown field " + key);
            if (integer(root, "schema") != 1) throw new IllegalArgumentException("schema must be 1");
            ModuleId id = new ModuleId(string(root, "id"));
            ModuleVersion version = new ModuleVersion(string(root, "version"));
            String displayName = root.has("displayName") ? root.get("displayName").getAsString() : id.value();
            if (displayName.length() > 64) throw new IllegalArgumentException("displayName exceeds 64 characters");
            String entrypoint = string(root, "entrypoint");
            String spi = string(root, "spi");
            checkSpi(spi);
            Map<String, Integer> capabilities = new TreeMap<>();
            JsonObject caps = root.getAsJsonObject("capabilities");
            if (caps == null) throw new IllegalArgumentException("capabilities must be an object");
            for (var cap : caps.entrySet()) {
                int required = cap.getValue().getAsInt();
                if (required < 1 || ModuleSpi.capabilityVersion(cap.getKey()) < required)
                    throw new IllegalArgumentException("unsupported capability " + cap.getKey() + " v" + required);
                capabilities.put(cap.getKey(), required);
            }
            String side = root.has("side") ? root.get("side").getAsString() : "server";
            if (!Set.of("server", "dedicated", "integrated").contains(side)) throw new IllegalArgumentException("invalid side " + side);
            boolean dedicated = server.isDedicatedServer();
            if ((side.equals("dedicated") && !dedicated) || (side.equals("integrated") && dedicated))
                throw new SkippedModuleException("side=" + side);
            boolean critical = root.has("critical") && root.get("critical").getAsBoolean();
            String mappings = string(root, "mappings");
            if (mappings.equals("official")) mappings = "mcp";
            if (!Set.of("mcp", "srg").contains(mappings) || !mappings.equals(FMLEnvironment.naming))
                throw new IllegalArgumentException("mappings " + mappings + " do not match runtime " + FMLEnvironment.naming);
            List<ExternalModuleDescriptor.Dependency> dependencies = parseDependencies(root);
            validateJar(jar, path);
            return new ExternalModuleDescriptor(path, new ModuleDescriptor(id, version, displayName), entrypoint,
                    spi, Map.copyOf(capabilities), side, critical, mappings, dependencies);
        }
    }

    private static void validateJar(JarFile jar, Path path) throws Exception {
        if (jar.getEntry("META-INF/mods.toml") != null) throw new IllegalArgumentException("this is a Forge mod; install it in mods/");
        if (jar.getEntry("META-INF/accesstransformer.cfg") != null || jar.getEntry("META-INF/coremods.json") != null)
            throw new IllegalArgumentException("unsupported transformer metadata");
        if (jar.getManifest() != null) {
            Attributes a = jar.getManifest().getMainAttributes();
            if (a.getValue("MixinConfigs") != null || a.getValue("FMLCorePlugin") != null || a.getValue("FMLAT") != null)
                throw new IllegalArgumentException("unsupported transformer manifest attribute");
            if (a.getValue(Attributes.Name.CLASS_PATH) != null) throw new IllegalArgumentException("manifest Class-Path is unsupported");
        }
        ClassLoader parent = NestWorldModule.class.getClassLoader();
        var entries = jar.stream().filter(e -> !e.isDirectory()).toList();
        for (JarEntry entry : entries) {
            String name = entry.getName();
            if (name.endsWith(".jar")) throw new IllegalArgumentException("nested jars are unsupported: " + name);
            if (!name.endsWith(".class")) continue;
            for (String prefix : RESERVED) if (name.startsWith(prefix))
                throw new IllegalArgumentException("reserved package class " + name);
            if (parent.getResource(name) != null) throw new IllegalArgumentException("shadows parent class " + name);
            try (InputStream in = jar.getInputStream(entry)) {
                validateClassReferences(in.readAllBytes(), path + "!" + name);
            }
        }
    }

    private static void validateClassReferences(byte[] bytes, String source) {
        Set<String> owners = new HashSet<>();
        Set<MemberReference> members = new HashSet<>();
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9) {
            private void type(Type type) {
                if (type.getSort() == Type.ARRAY) type(type.getElementType());
                else if (type.getSort() == Type.OBJECT) owners.add(type.getInternalName());
                else if (type.getSort() == Type.METHOD) {
                    for (Type arg : type.getArgumentTypes()) type(arg);
                    type(type.getReturnType());
                }
            }
            @Override public void visit(int v, int a, String n, String sig, String sup, String[] ifaces) {
                if (sup != null) owners.add(sup);
                if (ifaces != null) java.util.Collections.addAll(owners, ifaces);
            }
            @Override public FieldVisitor visitField(int a, String n, String d, String s, Object value) {
                type(Type.getType(d)); return null;
            }
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] exceptions) {
                type(Type.getMethodType(d));
                if (exceptions != null) java.util.Collections.addAll(owners, exceptions);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitTypeInsn(int opcode, String type) { owners.add(type); }
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        owners.add(owner); type(Type.getType(descriptor));
                        if (owner.startsWith("net/nestworld/")) members.add(new MemberReference(owner, name, descriptor, false));
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                        owners.add(owner); type(Type.getMethodType(descriptor));
                        if (owner.startsWith("net/nestworld/")) members.add(new MemberReference(owner, name, descriptor, true));
                    }
                    @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                                                  Object... bootstrapMethodArguments) {
                        type(Type.getMethodType(descriptor));
                        for (Object arg : bootstrapMethodArguments) if (arg instanceof Handle handle) {
                            owners.add(handle.getOwner());
                            if (handle.getOwner().startsWith("net/nestworld/"))
                                members.add(new MemberReference(handle.getOwner(), handle.getName(), handle.getDesc(),
                                        handle.getTag() != Opcodes.H_GETFIELD && handle.getTag() != Opcodes.H_GETSTATIC
                                                && handle.getTag() != Opcodes.H_PUTFIELD && handle.getTag() != Opcodes.H_PUTSTATIC));
                        }
                    }
                    @Override public void visitLdcInsn(Object value) { if (value instanceof Type t) type(t); }
                };
            }
        };
        new ClassReader(bytes).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (String owner : owners) {
            boolean published = SPI_TYPES.contains(owner) || SPI_TYPES.stream().anyMatch(type -> owner.startsWith(type + "$"));
            if (owner.startsWith("net/nestworld/") && !published)
                throw new IllegalArgumentException(source + " references non-SPI NestWorld type " + owner);
        }
        for (MemberReference member : members) validateSpiMember(member, source);
    }

    private static void validateSpiMember(MemberReference ref, String source) {
        if (!ref.owner().startsWith("net/nestworld/") ||
                !(SPI_TYPES.contains(ref.owner()) || SPI_TYPES.stream().anyMatch(t -> ref.owner().startsWith(t + "$")))) return;
        try {
            ClassLoader loader = NestWorldModule.class.getClassLoader();
            Class<?> owner = Class.forName(ref.owner().replace('/', '.'), false, loader);
            if (!ref.method()) {
                var field = owner.getField(ref.name());
                if (!Type.getDescriptor(field.getType()).equals(ref.descriptor())) throw new NoSuchFieldException();
                return;
            }
            Type mt = Type.getMethodType(ref.descriptor());
            Class<?>[] parameters = new Class<?>[mt.getArgumentTypes().length];
            for (int i = 0; i < parameters.length; i++) parameters[i] = runtimeClass(mt.getArgumentTypes()[i], loader);
            if (ref.name().equals("<init>")) {
                owner.getConstructor(parameters);
                return;
            }
            Class<?> returns = runtimeClass(mt.getReturnType(), loader);
            for (var method : owner.getMethods()) {
                if (method.getName().equals(ref.name()) && java.util.Arrays.equals(method.getParameterTypes(), parameters)
                        && method.getReturnType().equals(returns)) return;
            }
            throw new NoSuchMethodException();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(source + " links against absent SPI member " + ref.owner() + "."
                    + ref.name() + ref.descriptor());
        }
    }

    private static Class<?> runtimeClass(Type type, ClassLoader loader) throws ClassNotFoundException {
        return switch (type.getSort()) {
            case Type.VOID -> void.class;
            case Type.BOOLEAN -> boolean.class;
            case Type.CHAR -> char.class;
            case Type.BYTE -> byte.class;
            case Type.SHORT -> short.class;
            case Type.INT -> int.class;
            case Type.FLOAT -> float.class;
            case Type.LONG -> long.class;
            case Type.DOUBLE -> double.class;
            case Type.ARRAY -> Class.forName(type.getDescriptor().replace('/', '.'), false, loader);
            case Type.OBJECT -> Class.forName(type.getClassName(), false, loader);
            default -> throw new ClassNotFoundException(type.getDescriptor());
        };
    }

    private static List<ExternalModuleDescriptor> topological(List<ExternalModuleDescriptor> input,
                                                               Map<ModuleId, ExternalModuleDescriptor> available,
                                                               ModuleRegistry registry) {
        Map<ModuleId, ExternalModuleDescriptor> remaining = new TreeMap<>(Comparator.comparing(ModuleId::value));
        input.forEach(d -> remaining.put(d.module().id(), d));
        List<ExternalModuleDescriptor> out = new ArrayList<>();
        Set<ModuleId> done = new HashSet<>();
        while (!remaining.isEmpty()) {
            List<ExternalModuleDescriptor> ready = remaining.values().stream().filter(d -> d.dependencies().stream()
                    .filter(dep -> available.containsKey(dep.id()))
                    .allMatch(dep -> done.contains(dep.id()))).toList();
            if (ready.isEmpty()) {
                registry.recordExternalFailure("dependency cycle " + remaining.keySet().stream().map(ModuleId::value).toList());
                break;
            }
            for (ExternalModuleDescriptor d : ready) {
                remaining.remove(d.module().id()); done.add(d.module().id()); out.add(d);
            }
        }
        return out;
    }

    private static List<ExternalModuleDescriptor.Dependency> parseDependencies(JsonObject root) {
        if (!root.has("dependencies")) return List.of();
        if (!root.get("dependencies").isJsonArray()) throw new IllegalArgumentException("dependencies must be an array");
        List<ExternalModuleDescriptor.Dependency> out = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("dependencies")) {
            JsonObject dep = element.getAsJsonObject();
            if (!dep.keySet().equals(Set.of("id", "version", "kind"))) throw new IllegalArgumentException("invalid dependency fields");
            String kind = string(dep, "kind");
            if (!kind.equals("required") && !kind.equals("optional")) throw new IllegalArgumentException("invalid dependency kind " + kind);
            out.add(new ExternalModuleDescriptor.Dependency(new ModuleId(string(dep, "id")), string(dep, "version"), kind.equals("required")));
        }
        return List.copyOf(out);
    }

    private static void checkSpi(String value) {
        String[] requested = value.split("\\.", -1), runtime = ModuleSpi.VERSION.split("\\.", -1);
        if (requested.length != 2 || runtime.length != 2) throw new IllegalArgumentException("spi must be MAJOR.MINOR");
        try {
            if (Integer.parseInt(requested[0]) != Integer.parseInt(runtime[0])
                    || Integer.parseInt(requested[1]) > Integer.parseInt(runtime[1]))
                throw new IllegalArgumentException("incompatible spi " + value + ", runtime " + ModuleSpi.VERSION);
        } catch (NumberFormatException e) { throw new IllegalArgumentException("spi must be MAJOR.MINOR"); }
    }

    private static String string(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) throw new IllegalArgumentException("missing/invalid field " + key);
        return root.get(key).getAsString();
    }
    private static int integer(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) throw new IllegalArgumentException("missing/invalid field " + key);
        return root.get(key).getAsInt();
    }
}
