import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;
import java.security.MessageDigest;

/** Test-only overlay: retain the exact installed Core; replace three bounded invocation sites and add new helpers.
 * Usage: java -cp ASM_JAR:. CoreHandoffOverlay ORIGINAL_UNIVERSAL REOBF_HELPERS OUTPUT
 */
public final class CoreHandoffOverlay {
    static final String PREFIX = "net/nestworld/region/BlockEntityDomainHandoff";
    static final String TICKER = "net/minecraft/world/level/block/entity/TickingBlockEntity";
    static final String LEVEL = "Lnet/minecraft/server/level/ServerLevel;";
    static int ticks, lambdas, tasks, helpers;
    static byte[] patch(String name, byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] errors) {
                MethodVisitor mv = super.visitMethod(access, method, desc, signature, errors);
                if (name.equals("net/nestworld/region/NestworldDimensionRegion.class") && method.equals("tickBlockEntityList")) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        void captureLevel() {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, "net/nestworld/region/NestworldDimensionRegion", "level", LEVEL);
                            super.visitInsn(Opcodes.SWAP);
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String called, String d, boolean itf) {
                            if (opcode == Opcodes.INVOKEINTERFACE && owner.equals(TICKER)
                                    && called.equals("m_142224_") && d.equals("()V")) {
                                captureLevel();
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, PREFIX, "tickOrRoute", "(" + LEVEL + "L" + TICKER + ";)V", false);
                                ticks++;
                            } else super.visitMethodInsn(opcode, owner, called, d, itf);
                        }
                        @Override public void visitInvokeDynamicInsn(String n, String d, Handle bootstrap, Object... args) {
                            if (d.equals("(L" + TICKER + ";)Ljava/lang/Runnable;") && args.length == 3
                                    && args[1] instanceof Handle h && h.getOwner().equals(TICKER)
                                    && h.getName().equals("m_142224_") && h.getDesc().equals("()V")) {
                                captureLevel();
                                Object[] replacement = args.clone();
                                replacement[1] = new Handle(Opcodes.H_INVOKESTATIC, PREFIX, "tickOrRoute", "(" + LEVEL + "L" + TICKER + ";)V", false);
                                super.visitInvokeDynamicInsn(n, "(" + LEVEL + "L" + TICKER + ";)Ljava/lang/Runnable;", bootstrap, replacement);
                                lambdas++;
                            } else super.visitInvokeDynamicInsn(n,d,bootstrap,args);
                        }
                    };
                }
                if (name.equals("net/nestworld/region/BlockEntityExecutionDomain.class") && method.equals("execute")) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override public void visitMethodInsn(int opcode, String owner, String called, String d, boolean itf) {
                            if (opcode == Opcodes.INVOKEINTERFACE && owner.equals("java/lang/Runnable")
                                    && called.equals("run") && d.equals("()V")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, PREFIX, "runDomainTask", "(Ljava/lang/Runnable;)V", false);
                                tasks++;
                            } else super.visitMethodInsn(opcode,owner,called,d,itf);
                        }
                    };
                }
                return mv;
            }
        }, 0);
        return cw.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), compiled = Path.of(args[1]), output = Path.of(args[2]);
        if (Files.exists(output)) throw new IllegalArgumentException("Output exists");
        Map<String,byte[]> extra = new TreeMap<>();
        try (JarFile j = new JarFile(compiled.toFile())) {
            for (var e : Collections.list(j.entries())) if (e.getName().startsWith(PREFIX) && e.getName().endsWith(".class")) {
                extra.put(e.getName(), j.getInputStream(e).readAllBytes());
            }
        }
        if (extra.isEmpty()) throw new IllegalStateException("No compiled handoff helpers");
        // Assemble in memory before opening output: unknown bytecode fails without emitting an invalid artifact.
        Map<String,byte[]> entries = new LinkedHashMap<>();
        try (JarFile j = new JarFile(input.toFile())) {
            for (var e : Collections.list(j.entries())) {
                if (extra.containsKey(e.getName())) throw new IllegalStateException("Input already patched");
                byte[] bytes = j.getInputStream(e).readAllBytes();
                if (e.getName().equals("net/nestworld/region/NestworldDimensionRegion.class")
                        || e.getName().equals("net/nestworld/region/BlockEntityExecutionDomain.class")) bytes = patch(e.getName(), bytes);
                entries.put(e.getName(),bytes);
            }
        }
        if (ticks != 1 || lambdas != 1 || tasks != 1) throw new IllegalStateException("Unexpected hooks tick="+ticks+" lambda="+lambdas+" task="+tasks);
        entries.putAll(extra);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(output))) {
            for (var e : entries.entrySet()) { out.putNextEntry(new JarEntry(e.getKey())); out.write(e.getValue()); out.closeEntry(); }
        }
        System.out.println("Added "+extra.size()+" helper classes; patched tick=1 observeLambda=1 domainTask=1");
        System.out.println("SHA256 "+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output))));
    }
}
