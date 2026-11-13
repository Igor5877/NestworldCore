import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * TEST-ONLY overlay for CORE-GRID-DOMAIN-001 (ExecutionDomain primitive) on an installed Core445 rig.
 * Usage: java -cp ASM_JAR:. DomainOverlay BASE_UNIVERSAL BASE_SERVER CORE_REOBF NEW_PROVIDER OUT_UNIVERSAL OUT_SERVER
 *  universal: + ExecutionDomains*.class from CORE_REOBF; provider jar-in-jar replaced; NestworldRegionSystem
 *             onServerStarting(+install at RETURN) / onServerStopping(+uninstall at HEAD).
 *  server:    Level.m_46463_ HEAD beforeBlockEntities; Level.nestworldGetBlockEntityRaw + ServerChunkCache.m_7131_
 *             `instanceof RegionThread` -> ExecutionDomains.isRegionOrLease; ServerChunkCache.m_7587_ HEAD lease branch;
 *             ServerChunkCache.m_8466_ + MainThreadExecutor.m_7245_ HEAD beforeChunkTasks; MinecraftServer.m_129885_ /
 *             m_7041_ HEAD awaitAllDomains. Every other byte is copied unchanged; each hook must apply exactly once.
 */
public final class DomainOverlay {
    static final String ED = "net/nestworld/region/ExecutionDomains";
    static final String GROUPS = "net/nestworld/region/BlockEntityGroups";
    static final Map<String, Integer> applied = new TreeMap<>();

    static void hit(String k) { applied.merge(k, 1, Integer::sum); }

    static MethodVisitor head(MethodVisitor mv, String key, Runnable emit) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override public void visitCode() { super.visitCode(); emit.run(); hit(key); }
        };
    }

    static byte[] patchServer(String name, byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String m, String d, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, m, d, sig, ex);
                switch (name) {
                    case "net/minecraft/world/level/Level.class" -> {
                        if (m.equals("m_46463_") && d.equals("()V")) {
                            final MethodVisitor t = mv;
                            MethodVisitor loop = new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override public void visitMethodInsn(int op, String owner, String nm, String ds, boolean itf) {
                                    super.visitMethodInsn(op, owner, nm, ds, itf);
                                    if (op == Opcodes.INVOKEVIRTUAL && nm.equals("m_220393_") && ds.equals("(Lnet/minecraft/core/BlockPos;)Z")) {
                                        super.visitVarInsn(Opcodes.ALOAD, 0);
                                        super.visitVarInsn(Opcodes.ALOAD, 4);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "keepOnMain",
                                                "(ZLnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/TickingBlockEntity;)Z", false);
                                        hit("Level.keepOnMain");
                                    }
                                }
                                @Override public void visitInsn(int op) {
                                    if (op == Opcodes.RETURN) {
                                        super.visitVarInsn(Opcodes.ALOAD, 0);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "flushDiverted", "(Lnet/minecraft/world/level/Level;)V", false);
                                        hit("Level.flushDiverted");
                                    }
                                    super.visitInsn(op);
                                }
                            };
                            return head(loop, "Level.tickBlockEntities", () -> {
                                t.visitVarInsn(Opcodes.ALOAD, 0);
                                t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "beforeBlockEntities", "(Lnet/minecraft/world/level/Level;)V", false);
                            });
                        }
                        if (m.equals("nestworldGetBlockEntityRaw")) return instanceofSwap(mv, "Level.getBlockEntityRaw");
                        if (m.equals("m_6933_") && d.equals("(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")) {
                            final MethodVisitor t = mv;
                            return head(mv, "Level.setBlock", () -> {
                                t.visitVarInsn(Opcodes.ALOAD, 0);
                                t.visitVarInsn(Opcodes.ALOAD, 1);
                                t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "beforeBlockChange", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V", false);
                                // BlockMutationOp: r = domainSetBlock(...); if (r >= 0) return r != 0;
                                Label notDeferred = new Label(), ret0 = new Label();
                                t.visitVarInsn(Opcodes.ALOAD, 0);
                                t.visitVarInsn(Opcodes.ALOAD, 1);
                                t.visitVarInsn(Opcodes.ALOAD, 2);
                                t.visitVarInsn(Opcodes.ILOAD, 3);
                                t.visitVarInsn(Opcodes.ILOAD, 4);
                                t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "domainSetBlock",
                                        "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)I", false);
                                t.visitInsn(Opcodes.DUP);
                                t.visitJumpInsn(Opcodes.IFLT, notDeferred);
                                t.visitJumpInsn(Opcodes.IFEQ, ret0);
                                t.visitInsn(Opcodes.ICONST_1);
                                t.visitInsn(Opcodes.IRETURN);
                                t.visitLabel(ret0);
                                t.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                                t.visitInsn(Opcodes.ICONST_0);
                                t.visitInsn(Opcodes.IRETURN);
                                t.visitLabel(notDeferred);
                                t.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] { Opcodes.INTEGER });
                                t.visitInsn(Opcodes.POP);
                                hit("Level.domainSetBlock");
                            });
                        }
                        if ((m.equals("m_46672_") || m.equals("m_46717_")) && d.equals("(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V")) {
                            final MethodVisitor t = mv;
                            final int kind = m.equals("m_46672_") ? 0 : 2;
                            return head(mv, "Level.neighbor" + kind, () -> {
                                Label go = new Label();
                                t.visitVarInsn(Opcodes.ALOAD, 0);
                                t.visitInsn(kind == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_2);
                                t.visitVarInsn(Opcodes.ALOAD, 1);
                                t.visitVarInsn(Opcodes.ALOAD, 2);
                                t.visitInsn(Opcodes.ACONST_NULL);
                                t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "domainNeighbor",
                                        "(Lnet/minecraft/world/level/Level;ILnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;)Z", false);
                                t.visitJumpInsn(Opcodes.IFEQ, go);
                                t.visitInsn(Opcodes.RETURN);
                                t.visitLabel(go);
                                t.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            });
                        }
                        if (m.equals("m_46586_") && d.equals("(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;)V")) {
                            final MethodVisitor t = mv;
                            return head(mv, "Level.neighbor1", () -> {
                                Label go = new Label();
                                t.visitVarInsn(Opcodes.ALOAD, 0);
                                t.visitInsn(Opcodes.ICONST_1);
                                t.visitVarInsn(Opcodes.ALOAD, 1);
                                t.visitVarInsn(Opcodes.ALOAD, 2);
                                t.visitVarInsn(Opcodes.ALOAD, 3);
                                t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "domainNeighbor",
                                        "(Lnet/minecraft/world/level/Level;ILnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;)Z", false);
                                t.visitJumpInsn(Opcodes.IFEQ, go);
                                t.visitInsn(Opcodes.RETURN);
                                t.visitLabel(go);
                                t.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            });
                        }
                    }
                    case "net/minecraft/server/level/ServerChunkCache.class" -> {
                        if (m.equals("m_7131_") && d.equals("(II)Lnet/minecraft/world/level/chunk/LevelChunk;")) {
                            final MethodVisitor t = mv;
                            return head(mv, "ServerChunkCache.getChunkNow", () -> {
                                Label skip = new Label();
                                t.visitVarInsn(Opcodes.ALOAD, 0);
                                t.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/server/level/ServerChunkCache", "f_8329_", "Lnet/minecraft/server/level/ServerLevel;");
                                t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "leaseHolds", "(Lnet/minecraft/world/level/Level;)Z", false);
                                t.visitJumpInsn(Opcodes.IFEQ, skip);
                                t.visitVarInsn(Opcodes.ALOAD, 0);
                                t.visitVarInsn(Opcodes.ILOAD, 1);
                                t.visitVarInsn(Opcodes.ILOAD, 2);
                                t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "leaseLoadedChunk",
                                        "(Lnet/minecraft/server/level/ServerChunkCache;II)Lnet/minecraft/world/level/chunk/LevelChunk;", false);
                                t.visitInsn(Opcodes.ARETURN);
                                t.visitLabel(skip);
                                t.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            });
                        }
                        if (m.equals("m_8466_") && d.equals("()Z")) {
                            final MethodVisitor t = mv;
                            return head(mv, "ServerChunkCache.pollTask", () -> {
                                t.visitVarInsn(Opcodes.ALOAD, 0);
                                t.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/server/level/ServerChunkCache", "f_8329_", "Lnet/minecraft/server/level/ServerLevel;");
                                t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "beforeChunkTasks", "(Lnet/minecraft/world/level/Level;)V", false);
                            });
                        }
                        if (m.equals("m_7587_") && d.equals("(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;")) {
                            final MethodVisitor t = mv;
                            return head(mv, "ServerChunkCache.getChunk", () -> {
                                Label skip = new Label();
                                t.visitVarInsn(Opcodes.ALOAD, 0);
                                t.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/server/level/ServerChunkCache", "f_8329_", "Lnet/minecraft/server/level/ServerLevel;");
                                t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "leaseHolds", "(Lnet/minecraft/world/level/Level;)Z", false);
                                t.visitJumpInsn(Opcodes.IFEQ, skip);
                                t.visitVarInsn(Opcodes.ALOAD, 0);
                                t.visitVarInsn(Opcodes.ILOAD, 1);
                                t.visitVarInsn(Opcodes.ILOAD, 2);
                                t.visitVarInsn(Opcodes.ILOAD, 4);
                                t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "leasedChunk",
                                        "(Lnet/minecraft/server/level/ServerChunkCache;IIZ)Lnet/minecraft/world/level/chunk/ChunkAccess;", false);
                                t.visitInsn(Opcodes.ARETURN);
                                t.visitLabel(skip);
                                t.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            });
                        }
                    }
                    case "net/minecraft/world/ticks/LevelTicks.class" -> {
                        if (m.equals("m_183393_") && d.equals("(Lnet/minecraft/world/ticks/ScheduledTick;)V")) {
                            final MethodVisitor t = mv;
                            return head(mv, "LevelTicks.schedule", () -> {
                                Label go = new Label();
                                t.visitVarInsn(Opcodes.ALOAD, 0);
                                t.visitVarInsn(Opcodes.ALOAD, 1);
                                t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "domainScheduleTick", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                                t.visitJumpInsn(Opcodes.IFEQ, go);
                                t.visitInsn(Opcodes.RETURN);
                                t.visitLabel(go);
                                t.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            });
                        }
                    }
                    case "net/minecraft/server/level/ServerLevel.class" -> {
                        if (m.equals("m_8712_") && d.equals("(Lnet/minecraft/world/level/chunk/LevelChunk;)V")) {
                            final MethodVisitor t = mv;
                            return head(mv, "ServerLevel.unload", () -> {
                                t.visitVarInsn(Opcodes.ALOAD, 0);
                                t.visitVarInsn(Opcodes.ALOAD, 1);
                                t.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/chunk/LevelChunk", "m_7697_", "()Lnet/minecraft/world/level/ChunkPos;", false);
                                t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "beforeChunkUnload", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/ChunkPos;)V", false);
                            });
                        }
                    }
                    case "net/minecraft/server/level/ServerChunkCache$MainThreadExecutor.class" -> {
                        if (m.equals("m_7245_") && d.equals("()Z")) {
                            final MethodVisitor t = mv;
                            return head(mv, "MainThreadExecutor.pollTask", () -> {
                                t.visitVarInsn(Opcodes.ALOAD, 0);
                                t.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/server/level/ServerChunkCache$MainThreadExecutor", "f_8491_", "Lnet/minecraft/server/level/ServerChunkCache;");
                                t.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/server/level/ServerChunkCache", "f_8329_", "Lnet/minecraft/server/level/ServerLevel;");
                                t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "beforeChunkTasks", "(Lnet/minecraft/world/level/Level;)V", false);
                            });
                        }
                    }
                    case "net/minecraft/server/MinecraftServer.class" -> {
                        if ((m.equals("m_129885_") && d.equals("(ZZZ)Z")) || (m.equals("m_7041_") && d.equals("()V"))) {
                            final MethodVisitor t = mv;
                            return head(mv, "MinecraftServer." + m, () ->
                                    t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "awaitAllDomains", "()V", false));
                        }
                    }
                    default -> {}
                }
                return mv;
            }
        }, 0);
        return cw.toByteArray();
    }

    static MethodVisitor instanceofSwap(MethodVisitor mv, String key) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override public void visitTypeInsn(int op, String type) {
                if (op == Opcodes.INSTANCEOF && type.equals("net/nestworld/region/RegionThread")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "isRegionOrLease", "(Ljava/lang/Object;)Z", false);
                    hit(key);
                } else super.visitTypeInsn(op, type);
            }
        };
    }

    /** MVP-001: synchronous MAIN calls are serviced in the MAIN region-round wait (top-level point). */
    static byte[] patchRegionThreadPool(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String m, String d, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, m, d, sig, ex);
                if (!m.equals("awaitLatch")) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                        if (owner.equals("net/minecraft/server/level/ServerLevel") && name.equals("m_7726_")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "serviceMainCalls", "()V", false);
                            hit("RegionThreadPool.awaitLatch");
                        }
                        super.visitMethodInsn(op, owner, name, desc, itf);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    static byte[] patchRegionSystem(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String m, String d, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, m, d, sig, ex);
                if (m.equals("onServerStarting") && d.equals("(Lnet/minecraftforge/event/server/ServerStartingEvent;)V")) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override public void visitInsn(int op) {
                            if (op == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraftforge/event/server/ServerStartingEvent", "getServer", "()Lnet/minecraft/server/MinecraftServer;", false);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "install", "(Lnet/minecraft/server/MinecraftServer;)V", false);
                                hit("RegionSystem.install");
                            }
                            super.visitInsn(op);
                        }
                    };
                }
                if (m.equals("onServerStopping") && d.equals("(Lnet/minecraftforge/event/server/ServerStoppingEvent;)V")) {
                    final MethodVisitor t = mv;
                    return head(mv, "RegionSystem.uninstall", () -> {
                        t.visitVarInsn(Opcodes.ALOAD, 0);
                        t.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraftforge/event/server/ServerStoppingEvent", "getServer", "()Lnet/minecraft/server/MinecraftServer;", false);
                        t.visitMethodInsn(Opcodes.INVOKESTATIC, ED, "uninstall", "(Lnet/minecraft/server/MinecraftServer;)V", false);
                    });
                }
                return mv;
            }
        }, 0);
        return cw.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        Path baseU = Path.of(a[0]), baseS = Path.of(a[1]), core = Path.of(a[2]), provider = Path.of(a[3]), outU = Path.of(a[4]), outS = Path.of(a[5]);
        Map<String, byte[]> extra = new TreeMap<>();
        try (JarFile j = new JarFile(core.toFile())) {
            for (var e : Collections.list(j.entries()))
                if ((e.getName().startsWith(ED) || e.getName().startsWith(GROUPS) || e.getName().startsWith("net/nestworld/region/DomainTickMetrics")) && e.getName().endsWith(".class"))
                    extra.put(e.getName(), j.getInputStream(e).readAllBytes());
        }
        if (extra.isEmpty()) throw new IllegalStateException("ExecutionDomains classes missing in " + core);
        byte[] prov = Files.readAllBytes(provider);
        int replacedProvider = 0, replacedGroups = 0;
        try (JarFile in = new JarFile(baseU.toFile()); JarOutputStream out = new JarOutputStream(Files.newOutputStream(outU))) {
            for (var e : Collections.list(in.entries())) {
                byte[] b = in.getInputStream(e).readAllBytes();
                if (e.getName().equals("META-INF/jarjar/nestworld-modapi-provider-1.0.0-SNAPSHOT.jar")) { b = prov; replacedProvider++; }
                if (e.getName().equals("net/nestworld/region/NestworldRegionSystem.class")) b = patchRegionSystem(b);
                if (e.getName().equals("net/nestworld/region/RegionThreadPool.class")) b = patchRegionThreadPool(b);
                if (extra.containsKey(e.getName())) {
                    if (!e.getName().startsWith(GROUPS)) throw new IllegalStateException("base already has " + e.getName());
                    b = extra.remove(e.getName());       // replace the older BlockEntityGroups shipped by the groups overlay
                    replacedGroups++;
                }
                out.putNextEntry(new JarEntry(e.getName()));
                out.write(b);
                out.closeEntry();
            }
            for (var x : extra.entrySet()) { out.putNextEntry(new JarEntry(x.getKey())); out.write(x.getValue()); out.closeEntry(); }
        }
        if (replacedProvider != 1) throw new IllegalStateException("provider jar-in-jar not found exactly once");
        try (JarFile in = new JarFile(baseS.toFile()); JarOutputStream out = new JarOutputStream(Files.newOutputStream(outS))) {
            for (var e : Collections.list(in.entries())) {
                byte[] b = in.getInputStream(e).readAllBytes();
                if (e.getName().startsWith("net/minecraft/") && e.getName().endsWith(".class")) b = patchServer(e.getName(), b);
                JarEntry ne = new JarEntry(e.getName());
                out.putNextEntry(ne);
                out.write(b);
                out.closeEntry();
            }
        }
        System.out.println("added " + extra.size() + " Core classes, replaced " + replacedGroups + " BlockEntityGroups class(es); hooks applied: " + applied);
        String[] expect = { "Level.tickBlockEntities", "Level.getBlockEntityRaw", "ServerChunkCache.getChunkNow",
                "ServerChunkCache.pollTask", "ServerChunkCache.getChunk", "MainThreadExecutor.pollTask",
                "MinecraftServer.m_129885_", "MinecraftServer.m_7041_", "RegionSystem.install", "RegionSystem.uninstall",
                "Level.keepOnMain", "Level.flushDiverted", "Level.setBlock", "ServerLevel.unload",
                "Level.domainSetBlock", "Level.neighbor0", "Level.neighbor1", "Level.neighbor2", "LevelTicks.schedule",
                "RegionThreadPool.awaitLatch" };
        for (String k : expect) if (!Integer.valueOf(1).equals(applied.get(k))) throw new IllegalStateException("hook " + k + " applied " + applied.get(k) + " times (expected 1)");
    }
}
