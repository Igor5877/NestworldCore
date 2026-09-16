package net.nestworld.racedetect;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Stage A: static, general-rule (no hardcoded class/field names) bytecode analyzer that scans a
 * jar and produces {@link Candidate} records for shared-mutable-state fields and their access
 * sites. See docs plan "NestWorld Race Detector -- Stage A" for the full design rationale.
 *
 * Deliberately does NOT do full dataflow/frame analysis (no {@code asm-analysis} dependency) --
 * every rule here is plain instruction-pattern matching, the same technique already used
 * throughout this project's hand-written ASM coremods and verification harnesses. Precision is a
 * later classifier stage's job; this stage intentionally over-reports candidates.
 *
 * Self-exclusion: any class under {@code net/nestworld/racedetect/} (this detector's own
 * package) is skipped outright, regardless of which jar it's found in -- the rest of
 * NestworldCore (region/chunk/adapter/etc.) is a normal, in-scope analysis target.
 */
public final class StaticCandidateAnalyzer {
    public static final int ANALYZER_SCHEMA_VERSION = 1;
    public static final String RULESET_VERSION = "1";

    private StaticCandidateAnalyzer() {
    }

    private static final Set<String> MUTATE_METHOD_NAMES = Set.of(
            "put", "putIfAbsent", "add", "addAll", "remove", "removeAll", "removeIf", "clear",
            "merge", "compute", "computeIfAbsent", "computeIfPresent", "replace", "replaceAll",
            "set", "push", "pop", "offer", "poll", "addFirst", "addLast", "removeFirst", "removeLast",
            "sort"
    );
    private static final Set<String> ITERATE_METHOD_NAMES = Set.of(
            "iterator", "forEach", "stream", "entrySet", "keySet", "values", "listIterator"
    );
    private static final Set<String> RMW_READ_METHOD_NAMES = Set.of("get", "containsKey");
    private static final Set<String> RMW_WRITE_METHOD_NAMES = Set.of("put", "putIfAbsent");

    private static final String[] MAP_LIKE = {
            "java/util/Map", "java/util/HashMap", "java/util/TreeMap", "java/util/LinkedHashMap",
            "java/util/IdentityHashMap", "java/util/WeakHashMap", "java/util/EnumMap",
            "java/util/concurrent/ConcurrentMap", "java/util/concurrent/ConcurrentHashMap",
            "java/util/concurrent/ConcurrentSkipListMap", "java/util/SortedMap", "java/util/NavigableMap"
    };
    private static final String[] SET_LIKE = {
            "java/util/Set", "java/util/HashSet", "java/util/TreeSet", "java/util/LinkedHashSet",
            "java/util/concurrent/CopyOnWriteArraySet", "java/util/concurrent/ConcurrentSkipListSet",
            "java/util/EnumSet", "java/util/SortedSet", "java/util/NavigableSet"
    };
    private static final String[] LIST_LIKE = {
            "java/util/List", "java/util/ArrayList", "java/util/LinkedList",
            "java/util/concurrent/CopyOnWriteArrayList", "java/util/Vector"
    };
    private static final String[] QUEUE_LIKE = {
            "java/util/Queue", "java/util/Deque", "java/util/ArrayDeque", "java/util/PriorityQueue",
            "java/util/concurrent/ConcurrentLinkedQueue", "java/util/concurrent/ConcurrentLinkedDeque",
            "java/util/concurrent/LinkedBlockingQueue", "java/util/concurrent/LinkedBlockingDeque",
            "java/util/concurrent/ArrayBlockingQueue"
    };

    // ------------------------------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------------------------------

    public static String sha256OfFile(Path jarPath) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            try (InputStream in = Files.newInputStream(jarPath)) {
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    /**
     * Scans one jar and returns every candidate found. {@code modId} is the attribution to apply
     * to classes that don't fall under {@code net/minecraft/}, {@code net/minecraftforge/}, or
     * {@code net/nestworld/} (those three get their own fixed modId regardless of what jar they're
     * found in, so the "scan the running NestWorldCore/Forge jar too" case (f) works with zero
     * special-casing beyond this one lookup).
     */
    public static List<Candidate> scanJar(Path jarPath, String modId) throws IOException {
        String jarSha256 = sha256OfFile(jarPath);
        List<Candidate> results = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                String internalName = entry.getName().substring(0, entry.getName().length() - 6);
                if (internalName.startsWith("net/nestworld/racedetect/")) continue; // self-exclusion
                byte[] bytes;
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                try {
                    scanClass(bytes, jarSha256, modId, results);
                } catch (Exception e) {
                    // one malformed/unreadable class entry must never abort the whole jar scan
                }
            }
        }
        return results;
    }

    // ------------------------------------------------------------------------------------------
    // Per-class scan
    // ------------------------------------------------------------------------------------------

    private static void scanClass(byte[] bytes, String jarSha256, String modId, List<Candidate> out) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, ClassReader.SKIP_DEBUG);

        if (classNode.name.startsWith("net/nestworld/racedetect/")) return; // belt-and-suspenders

        String effectiveModId = modId;
        if (classNode.name.startsWith("net/minecraft/")) effectiveModId = "minecraft";
        else if (classNode.name.startsWith("net/minecraftforge/")) effectiveModId = "forge";
        else if (classNode.name.startsWith("net/nestworld/")) effectiveModId = "nestworldcore";

        Map<String, FieldNode> candidateFields = new HashMap<>();
        for (Object fo : classNode.fields) {
            FieldNode f = (FieldNode) fo;
            if (classifyField(f) != null) candidateFields.put(f.name, f);
        }
        if (candidateFields.isEmpty()) return;

        for (Object mo : classNode.methods) {
            MethodNode method = (MethodNode) mo;
            scanMethodAccesses(classNode, method, candidateFields, jarSha256, effectiveModId, out);
        }
    }

    private static SharedStateKind classifyField(FieldNode f) {
        String desc = f.desc;
        boolean isFinal = (f.access & Opcodes.ACC_FINAL) != 0;
        boolean isStatic = (f.access & Opcodes.ACC_STATIC) != 0;

        if (desc.length() == 1) return null; // primitive (I, J, Z, B, C, S, D, F)
        if (desc.equals("Ljava/lang/String;")) return null; // truly immutable
        if (isFinal && isImmutableBoxedType(desc)) return null;

        if (desc.startsWith("[")) return SharedStateKind.ARRAY;

        String internal = internalNameFromDescriptor(desc);
        if (internal == null) return isStatic ? SharedStateKind.STATIC_FIELD : SharedStateKind.INSTANCE_FIELD;

        if (matchesAny(internal, MAP_LIKE) || isFastutilOrGuava(internal, "Map")) return SharedStateKind.MAP;
        if (matchesAny(internal, SET_LIKE) || isFastutilOrGuava(internal, "Set")) return SharedStateKind.SET;
        if (matchesAny(internal, LIST_LIKE) || isFastutilOrGuava(internal, "List")) return SharedStateKind.LIST;
        if (matchesAny(internal, QUEUE_LIKE) || isFastutilOrGuava(internal, "Queue")
                || isFastutilOrGuava(internal, "Deque")) return SharedStateKind.QUEUE;
        if (internal.equals("java/util/Collection")) return SharedStateKind.COLLECTION;

        // any other non-primitive, non-String, non-immutable-boxed reference type: broad catch-all
        // per section 3's "mutable objects" -- intentionally permissive, precision is a later stage
        return isStatic ? SharedStateKind.STATIC_FIELD : SharedStateKind.MUTABLE_REFERENCE;
    }

    private static boolean isImmutableBoxedType(String desc) {
        switch (desc) {
            case "Ljava/lang/Integer;":
            case "Ljava/lang/Long;":
            case "Ljava/lang/Double;":
            case "Ljava/lang/Float;":
            case "Ljava/lang/Boolean;":
            case "Ljava/lang/Byte;":
            case "Ljava/lang/Short;":
            case "Ljava/lang/Character;":
                return true;
            default:
                return false;
        }
    }

    private static String internalNameFromDescriptor(String desc) {
        if (desc.length() < 3 || desc.charAt(0) != 'L' || !desc.endsWith(";")) return null;
        return desc.substring(1, desc.length() - 1);
    }

    private static boolean matchesAny(String internal, String[] table) {
        for (String candidate : table) if (internal.equals(candidate)) return true;
        return false;
    }

    private static boolean isFastutilOrGuava(String internal, String suffixKeyword) {
        boolean fromKnownLib = internal.startsWith("it/unimi/dsi/fastutil/")
                || internal.startsWith("com/google/common/collect/");
        if (!fromKnownLib) return false;
        int slash = internal.lastIndexOf('/');
        String simpleName = slash >= 0 ? internal.substring(slash + 1) : internal;
        return simpleName.contains(suffixKeyword);
    }

    // ------------------------------------------------------------------------------------------
    // Per-method access-site scan
    // ------------------------------------------------------------------------------------------

    private static final class AccessEvent {
        final int offset;
        final String fieldName;
        HazardType hazardType;
        String note;
        boolean readShaped;   // lookahead call was get/containsKey -- candidate for RMW pairing
        boolean writeShapedOrFullWrite; // lookahead call was put/putIfAbsent, OR this is a raw PUTFIELD/PUTSTATIC
        boolean rmw;

        AccessEvent(int offset, String fieldName, HazardType hazardType, String note) {
            this.offset = offset;
            this.fieldName = fieldName;
            this.hazardType = hazardType;
            this.note = note;
        }
    }

    private static void scanMethodAccesses(ClassNode classNode, MethodNode method,
            Map<String, FieldNode> candidateFields, String jarSha256, String modId, List<Candidate> out) {
        AbstractInsnNode[] nodes = method.instructions.toArray();
        List<AccessEvent> events = new ArrayList<>();

        for (int i = 0; i < nodes.length; i++) {
            AbstractInsnNode node = nodes[i];
            int opcode = node.getOpcode();
            if (opcode != Opcodes.GETFIELD && opcode != Opcodes.PUTFIELD
                    && opcode != Opcodes.GETSTATIC && opcode != Opcodes.PUTSTATIC) continue;
            FieldInsnNode fin = (FieldInsnNode) node;
            if (!fin.owner.equals(classNode.name)) continue;
            FieldNode field = candidateFields.get(fin.name);
            if (field == null || !fin.desc.equals(field.desc)) continue;

            boolean isGet = (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC);

            if (!isGet) {
                AccessEvent ev = new AccessEvent(i, field.name, HazardType.WRITE,
                        "PUTFIELD/PUTSTATIC " + field.name);
                ev.writeShapedOrFullWrite = true;
                events.add(ev);
                continue;
            }

            // reference-escape: this exact GETFIELD/GETSTATIC is immediately followed by ARETURN
            // -- the method hands out the live reference directly (matches toms_storage's
            // `return this.items;` shape exactly).
            AbstractInsnNode nextReal = nextRealInsn(nodes, i);
            if (nextReal != null && nextReal.getOpcode() == Opcodes.ARETURN) {
                events.add(new AccessEvent(i, field.name, HazardType.REFERENCE_ESCAPE,
                        "GETFIELD/GETSTATIC " + field.name + "; immediate ARETURN (live reference handed out)"));
                continue;
            }

            LookaheadCall look = lookaheadCall(nodes, i);
            if (look == null) {
                events.add(new AccessEvent(i, field.name, HazardType.READ,
                        "GETFIELD/GETSTATIC " + field.name + ", no mutating call observed nearby"));
                continue;
            }

            if (MUTATE_METHOD_NAMES.contains(look.methodName)) {
                AccessEvent ev = new AccessEvent(i, field.name, HazardType.MUTATE,
                        "GETFIELD " + field.name + "; nearby INVOKE " + look.owner + "." + look.methodName + "(...)");
                if (RMW_WRITE_METHOD_NAMES.contains(look.methodName)) ev.writeShapedOrFullWrite = true;
                events.add(ev);
                // nested access: get(...).mutate() -- an immediately-following further INVOKE on
                // the result of a get()-shaped call
                if (look.methodName.equals("get") && look.followedByFurtherInvoke) {
                    events.add(new AccessEvent(i, field.name, HazardType.MUTATE,
                            "GETFIELD " + field.name + "; nested access via " + look.methodName + "(...)." + look.furtherInvokeName + "(...)"));
                }
                continue;
            }
            if (ITERATE_METHOD_NAMES.contains(look.methodName)) {
                events.add(new AccessEvent(i, field.name, HazardType.ITERATE,
                        "GETFIELD " + field.name + "; nearby INVOKE " + look.owner + "." + look.methodName + "(...)"));
                continue;
            }
            if (RMW_READ_METHOD_NAMES.contains(look.methodName)) {
                AccessEvent ev = new AccessEvent(i, field.name, HazardType.READ,
                        "GETFIELD " + field.name + "; nearby INVOKE " + look.owner + "." + look.methodName + "(...)");
                ev.readShaped = true;
                events.add(ev);
                if (look.followedByFurtherInvoke) {
                    events.add(new AccessEvent(i, field.name, HazardType.MUTATE,
                            "GETFIELD " + field.name + "; nested access via " + look.methodName + "(...)." + look.furtherInvokeName + "(...)"));
                }
                continue;
            }
            // plain read followed by some other call unrelated to our known name sets
            events.add(new AccessEvent(i, field.name, HazardType.READ,
                    "GETFIELD/GETSTATIC " + field.name + "; nearby INVOKE " + look.owner + "." + look.methodName + "(...)"));
        }

        applyRmwPairing(events);

        for (AccessEvent ev : events) {
            FieldNode field = candidateFields.get(ev.fieldName);
            String jdkType = collectionTypeNameOrNull(field.desc);
            out.add(new Candidate(
                    new CandidateId(jarSha256, classNode.name, ev.fieldName, method.name, ev.offset,
                            ev.hazardType, ANALYZER_SCHEMA_VERSION, RULESET_VERSION),
                    field.desc, classifyField(field), jdkType,
                    HazardStatus.STATIC_ONLY_CANDIDATE, ev.note, modId));
            if (ev.rmw) {
                out.add(new Candidate(
                        new CandidateId(jarSha256, classNode.name, ev.fieldName, method.name, ev.offset,
                                HazardType.RMW, ANALYZER_SCHEMA_VERSION, RULESET_VERSION),
                        field.desc, classifyField(field), jdkType,
                        HazardStatus.STATIC_ONLY_CANDIDATE,
                        "compound read-modify-write pattern: " + ev.note, modId));
            }
        }
    }

    /** RMW pairing: a read-shaped access followed (before any full-field-reassignment write) by a
     * write-shaped access to the SAME field, within the same method, gets both ends tagged RMW.
     * A raw PUTFIELD/PUTSTATIC (full reassignment of the field itself, not a compound-op call on
     * its contents) resets the pairing window, since that's a fresh collection instance. */
    private static void applyRmwPairing(List<AccessEvent> events) {
        Map<String, List<AccessEvent>> byField = new LinkedHashMap<>();
        for (AccessEvent ev : events) byField.computeIfAbsent(ev.fieldName, k -> new ArrayList<>()).add(ev);
        for (List<AccessEvent> list : byField.values()) {
            AccessEvent pendingRead = null;
            for (AccessEvent ev : list) {
                if (ev.hazardType == HazardType.WRITE) {
                    pendingRead = null; // full reassignment resets the window
                    continue;
                }
                if (ev.readShaped) {
                    pendingRead = ev;
                } else if (ev.writeShapedOrFullWrite && pendingRead != null) {
                    ev.rmw = true;
                    pendingRead.rmw = true;
                    pendingRead = null;
                }
            }
        }
    }

    private static AbstractInsnNode nextRealInsn(AbstractInsnNode[] nodes, int fromIndex) {
        for (int i = fromIndex + 1; i < nodes.length; i++) {
            AbstractInsnNode n = nodes[i];
            if (n.getOpcode() >= 0) return n; // skip labels/line-numbers/frames (opcode == -1)
        }
        return null;
    }

    private static final class LookaheadCall {
        String owner;
        String methodName;
        boolean followedByFurtherInvoke;
        String furtherInvokeName;
    }

    /** Walks forward from a GETFIELD/GETSTATIC, tracking stack depth ABOVE the loaded field value
     * (depth 0 == field is top-of-stack, right after the load), to find the call that actually
     * CONSUMES the field as its receiver -- not just the first INVOKE* encountered. This matters
     * because building a compound-op call's arguments (e.g. {@code flowingStacks.computeIfAbsent(
     * new Coord4D(stack.getDest(), level), k -> new ObjectOpenHashSet())}) routinely contains its
     * OWN nested invokes (here, {@code TransporterStack.getDest()}) that must NOT be mistaken for
     * the outer call -- confirmed via real evidence against Mekanism's TransporterManager.add(),
     * which was silently missed entirely before this depth-tracking was added (the naive
     * first-invoke-wins version stopped at getDest() and never saw computeIfAbsent()).
     *
     * Handles NEW/DUP/constructor-call sequences correctly for free: NEW and DUP are each just
     * "push 1", and INVOKESPECIAL (including &lt;init&gt;) is handled by the same generic
     * pop(argCount+1)/push(returnsVoid?0:1) formula as any other invoke -- the two separately
     * depth-tracked pushes from NEW and DUP naturally leave exactly one initialized reference
     * behind after the constructor call consumes the other, with no special-casing needed.
     *
     * Deliberately linear (no branch-following) -- a bounded heuristic, not a real dataflow pass.
     * Bails out (returns null) on anything it can't confidently account for, rather than guessing. */
    private static LookaheadCall lookaheadCall(AbstractInsnNode[] nodes, int fromIndex) {
        int limit = Math.min(nodes.length, fromIndex + 40);
        int depth = 0; // number of stack slots pushed since the field load (0 == field is on top)
        for (int i = fromIndex + 1; i < limit; i++) {
            AbstractInsnNode n = nodes[i];
            int op = n.getOpcode();
            if (op == -1) continue; // label/frame/line-number

            if (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE
                    || op == Opcodes.INVOKESPECIAL || op == Opcodes.INVOKESTATIC) {
                MethodInsnNode min = (MethodInsnNode) n;
                boolean hasReceiver = op != Opcodes.INVOKESTATIC;
                int argCount = Type.getArgumentTypes(min.desc).length;
                int popCount = argCount + (hasReceiver ? 1 : 0);
                boolean returnsValue = !Type.getReturnType(min.desc).equals(Type.VOID_TYPE);

                if (hasReceiver && depth == popCount - 1) {
                    // this call's receiver is exactly our field's loaded value -- found it
                    LookaheadCall call = new LookaheadCall();
                    call.owner = min.owner;
                    call.methodName = min.name;
                    AbstractInsnNode after = nextRealInsn(nodes, i);
                    if (after != null && (after.getOpcode() == Opcodes.INVOKEVIRTUAL || after.getOpcode() == Opcodes.INVOKEINTERFACE)) {
                        call.followedByFurtherInvoke = true;
                        call.furtherInvokeName = ((MethodInsnNode) after).name;
                    }
                    return call;
                }
                if (depth < popCount) return null; // this call reaches below what we're tracking -- give up, don't guess
                depth -= popCount;
                if (returnsValue) depth += 1;
                continue;
            }
            if (op == Opcodes.INVOKEDYNAMIC) {
                // lambda-argument construction (LambdaMetafactory) -- conservatively assume it
                // pushes exactly 1 (a functional-interface instance), the overwhelmingly common
                // case for the compound-op-argument shape this heuristic targets.
                depth += 1;
                continue;
            }
            // stack-shuffle tolerance: NEW/DUP (constructor-call sequences), ALOAD, ICONST*/LDC/
            // BIPUSH/SIPUSH (argument pushes), GETFIELD/GETSTATIC (e.g. loading a key from
            // another field), CHECKCAST (net 0, pop-then-push same slot).
            if (op == Opcodes.CHECKCAST) continue; // net 0
            if (op == Opcodes.GETFIELD) continue; // pop 1 (some other receiver) push 1 -- net 0
            boolean pushesOne = op == Opcodes.NEW || op == Opcodes.DUP || op == Opcodes.ALOAD
                    || (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5)
                    || op == Opcodes.LDC || op == Opcodes.BIPUSH || op == Opcodes.SIPUSH
                    || op == Opcodes.GETSTATIC;
            if (pushesOne) {
                depth += 1;
                continue;
            }
            return null; // anything else -- don't guess
        }
        return null;
    }

    private static String collectionTypeNameOrNull(String desc) {
        String internal = internalNameFromDescriptor(desc);
        return internal; // simple's-enough for Stage A: the raw internal type name, or null for arrays/primitives
    }
}
