import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import sonar.fluxnetworks.common.device.TileFluxDevice;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public final class FluxRegression implements Opcodes {
    static Object call(Method m, Object target, Object... args) {
        try { return m.invoke(target, args); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    static MethodNode constructor(ClassNode c, String parent) {
        MethodNode m = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, parent, "<init>", "()V", false);
        c.methods.add(m);
        return m;
    }
    static void initField(MethodNode m, String owner, String field, String type) {
        m.visitVarInsn(ALOAD, 0); m.visitTypeInsn(NEW, type); m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, type, "<init>", "()V", false);
        m.visitFieldInsn(PUTFIELD, owner, field, "L" + type + ";");
    }
    @SuppressWarnings("unchecked")
    static void run(Path root, Path mods) throws Exception {
        String baseName = "sonar/fluxnetworks/common/connection/FluxNetwork";
        String serverName = "sonar/fluxnetworks/common/connection/ServerFluxNetwork";
        var fn = CoremodRegression.transformer(root, "mod_compound_races.js");
        ClassNode base = CoremodRegression.transform(fn, CoremodRegression.load(mods, "FluxNetworks-1.20.1-7.2.1.15.jar", baseName));
        ClassNode server = CoremodRegression.transform(fn, CoremodRegression.load(mods, "FluxNetworks-1.20.1-7.2.1.15.jar", serverName));
        CoremodRegression.check((CoremodRegression.method(server, "onEndServerTick").access & ACC_SYNCHRONIZED) == 0,
                "Network monitor covers energy callbacks");
        // Keep actual collection/lifecycle methods. Replace only construction of
        // unrelated game services (statistics, security/player/NBT dependencies).
        base.methods.removeIf(m -> !Set.of("getConnectionByPos", "getAllConnections", "onDelete").contains(m.name));
        base.fields.removeIf(f -> !Set.of("mConnectionMap", "mMemberMap").contains(f.name));
        MethodNode initBase = constructor(base, "java/lang/Object");
        initField(initBase, baseName, "mConnectionMap", "java/util/HashMap");
        initField(initBase, baseName, "mMemberMap", "java/util/HashMap");
        initBase.visitInsn(RETURN); initBase.visitMaxs(3, 1);
        Set<String> methods = Set.of("handleConnectionQueue", "enqueueConnectionAddition", "enqueueConnectionRemoval",
                "getLogicalDevices", "nestworld$getLogicalDevices", "nestworld$detachDevices", "onDelete", "isValid",
                "markSortConnections", "lambda$static$0", "<clinit>");
        server.methods.removeIf(m -> !methods.contains(m.name));
        server.fields.removeIf(f -> !Set.of("mToAdd", "mToRemove", "mDevices", "mSortConnections",
                "sLogicalTypes", "sDescendingOrder", "$assertionsDisabled", "nestworld$deleted").contains(f.name));
        MethodNode initServer = constructor(server, baseName);
        initField(initServer, serverName, "mToAdd", "java/util/LinkedList");
        initField(initServer, serverName, "mToRemove", "java/util/LinkedList");
        initServer.visitVarInsn(ALOAD, 0); initServer.visitInsn(ICONST_5);
        initServer.visitTypeInsn(ANEWARRAY, "java/util/ArrayList");
        initServer.visitFieldInsn(PUTFIELD, serverName, "mDevices", "[Ljava/util/ArrayList;");
        for (int i = 0; i < 5; i++) {
            initServer.visitVarInsn(ALOAD, 0);
            initServer.visitFieldInsn(GETFIELD, serverName, "mDevices", "[Ljava/util/ArrayList;");
            initServer.visitInsn(ICONST_0 + i);
            initServer.visitTypeInsn(NEW, "java/util/ArrayList"); initServer.visitInsn(DUP);
            initServer.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            initServer.visitInsn(AASTORE);
        }
        initServer.visitInsn(RETURN); initServer.visitMaxs(4, 1);
        Map<String, byte[]> classes = Map.of(baseName.replace('/', '.'), CoremodRegression.bytes(base),
                serverName.replace('/', '.'), CoremodRegression.bytes(server));
        ClassLoader loader = new ClassLoader(FluxRegression.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = classes.get(name);
                if (b == null) throw new ClassNotFoundException(name);
                return defineClass(name, b, 0, b.length);
            }
        };
        Class<?> type = loader.loadClass(serverName.replace('/', '.'));
        Object network = type.getConstructor().newInstance();
        Method add = type.getMethod("enqueueConnectionAddition", TileFluxDevice.class);
        Method remove = type.getMethod("enqueueConnectionRemoval", TileFluxDevice.class, boolean.class);
        Method handle = type.getDeclaredMethod("handleConnectionQueue"); handle.setAccessible(true);
        Method logical = type.getMethod("getLogicalDevices", int.class);
        Method connections = type.getMethod("getAllConnections");
        Method delete = type.getMethod("onDelete"), valid = type.getMethod("isValid");
        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            TileFluxDevice same = new TileFluxDevice(-1);
            Future<?> a = executor.submit(() -> call(add, network, same));
            Future<?> b = executor.submit(() -> call(add, network, same));
            CoremodRegression.check(!a.get().equals(b.get()), "Duplicate addition was accepted twice");
            call(handle, network);
            List<?> snapshot = (List<?>) call(logical, network, 0);
            Collection<?> connectionSnapshot = (Collection<?>) call(connections, network);
            call(remove, network, same, false); call(handle, network);
            CoremodRegression.check(snapshot.size() == 1 && connectionSnapshot.size() == 1,
                    "Previously returned snapshots changed");
            CoremodRegression.check(((List<?>) call(logical, network, 0)).isEmpty(), "Removal modified a copy, not live devices");
            List<TileFluxDevice> devices = new ArrayList<>();
            for (int i = 0; i < 2000; i++) devices.add(new TileFluxDevice(i));
            List<Future<?>> producers = new ArrayList<>();
            for (int p = 0; p < 4; p++) {
                int offset = p * 500;
                producers.add(executor.submit(() -> {
                    for (int i = offset; i < offset + 500; i++) call(add, network, devices.get(i));
                }));
            }
            Future<?> consumer = executor.submit(() -> {
                do {
                    call(handle, network);
                    ((Collection<?>) call(connections, network)).forEach(ignored -> {});
                    ((List<?>) call(logical, network, 0)).forEach(ignored -> {});
                } while (producers.stream().anyMatch(f -> !f.isDone()));
                call(handle, network);
            });
            for (Future<?> producer : producers) producer.get(20, TimeUnit.SECONDS);
            consumer.get(20, TimeUnit.SECONDS);
            CoremodRegression.check(((List<?>) call(logical, network, 0)).size() == 2000
                    && ((Collection<?>) call(connections, network)).size() == 2000, "Queue/map diverged during concurrent registration");
            call(remove, network, devices.get(0), true); call(handle, network);
            CoremodRegression.check(((List<?>) call(logical, network, 0)).size() == 1999
                    && ((Collection<?>) call(connections, network)).size() == 2000, "Unloaded phantom connection was lost");
            devices.get(1).onDisconnect = () -> {
                CoremodRegression.check(!Thread.holdsLock(network), "Disconnect callback holds network monitor");
                CoremodRegression.check(!(Boolean) call(add, network, new TileFluxDevice(9999)), "Deleted network accepted connection");
            };
            call(delete, network);
            CoremodRegression.check(!(Boolean) call(valid, network), "Deleted network still valid");
            CoremodRegression.check(((List<?>) call(logical, network, 0)).isEmpty()
                    && ((Collection<?>) call(connections, network)).isEmpty(), "Deletion left connections behind");
            call(remove, network, devices.get(1), false);
            call(delete, network); // idempotent and no null device lists
        } finally { executor.shutdownNow(); }
        System.out.println("PASS Flux: concurrent registration, snapshots, phantom unload, deletion and unlocked callbacks");
    }
}
