import mekanism.common.content.network.transmitter.Transmitter;
import mekanism.common.lib.transmitter.DynamicNetwork;
import net.minecraft.core.Direction;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * MEKANISM-REGISTRY-RACE-001 fixture: the real NetworkAcceptorCache.acceptorChanged/commit bytecode, with
 * region-thread producers racing a main-thread commit loop. updateTransmitterOnSide is replaced by an
 * accounting hook, so every committed (transmitter, side) is counted.
 * Acceptance after drain: consumed == produced, lost == 0, duplicates == 0, consumer exceptions == 0.
 */
public final class MekanismAcceptorCacheRegression {
    static final String CACHE = "mekanism/common/lib/transmitter/acceptor/NetworkAcceptorCache";
    static final Map<Long, AtomicInteger> CONSUMED = new ConcurrentHashMap<>();

    public static void consumed(Object transmitter, Object side) {
        long key = ((long) ((Transmitter) transmitter).id << 3) | ((Direction) side).ordinal();
        CONSUMED.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    record Result(long produced, long consumed, long lost, long duplicates, long consumerExceptions,
                  long producerExceptions, String firstError, String hang) {
        boolean clean() {
            return hang == null && consumed == produced && lost == 0 && duplicates == 0 && consumerExceptions == 0
                    && producerExceptions == 0;
        }
        @Override public String toString() {
            if (hang != null) return "HANG (no progress within the round deadline): " + hang;
            return "produced=" + produced + " consumed=" + consumed + " lost=" + lost + " duplicates=" + duplicates
                    + " consumerExceptions=" + consumerExceptions + " producerExceptions=" + producerExceptions + (firstError == null ? "" : " firstError=" + firstError);
        }
    }

    /** Daemon threads: a corrupted open-hash map can spin forever; the JVM must still be able to exit. */
    static ExecutorService daemonPool(int n) {
        return Executors.newFixedThreadPool(n, r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
    }

    static String spinning(List<Thread> threads) {
        StringBuilder b = new StringBuilder();
        for (Thread t : threads) {
            if (!t.isAlive()) continue;
            StackTraceElement[] st = t.getStackTrace();
            b.append("\n  ").append(t.getName()).append(" state=").append(t.getState());
            for (int i = 0; i < Math.min(6, st.length); i++) b.append("\n    at ").append(st[i]);
        }
        return b.toString();
    }

    /** Keep only what the race needs; stub updateTransmitterOnSide with the accounting hook. */
    static ClassNode prepare(ClassNode node) {
        Set<String> keep = Set.of("<init>", "acceptorChanged", "commit", "updateTransmitterOnSide",
                "lambda$acceptorChanged$1", "adoptAcceptors", "deregister");
        node.methods.removeIf(m -> !keep.contains(m.name) && !m.name.startsWith("nestworld$"));
        for (MethodNode m : node.methods) {
            if (!m.name.equals("updateTransmitterOnSide")) continue;
            InsnList body = new InsnList();
            body.add(new VarInsnNode(Opcodes.ALOAD, 1));
            body.add(new VarInsnNode(Opcodes.ALOAD, 2));
            body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "MekanismAcceptorCacheRegression", "consumed",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            body.add(new InsnNode(Opcodes.RETURN));
            m.instructions = body;
            m.tryCatchBlocks.clear();
            m.localVariables = null;
            m.maxStack = 2;
        }
        return node;
    }

    static Result round(Class<?> cacheClass, int producers, int transmittersPerProducer) throws Exception {
        CONSUMED.clear();
        Object cache = cacheClass.getConstructor().newInstance();
        Method changed = cacheClass.getMethod("acceptorChanged", Transmitter.class, Direction.class);
        Method commit = cacheClass.getMethod("commit");
        DynamicNetwork network = new DynamicNetwork() {};
        Direction[] sides = Direction.values();
        AtomicBoolean producing = new AtomicBoolean(true);
        LongAdder exceptions = new LongAdder(), producerExceptions = new LongAdder();
        String[] firstError = {null};
        ExecutorService pool = daemonPool(producers + 1);
        List<Thread> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try {
            Future<?> consumer = pool.submit(() -> {
                threads.add(Thread.currentThread());
                Thread.currentThread().setName("Server thread");
                while (producing.get()) {
                    try { commit.invoke(cache); }
                    catch (InvocationTargetException e) {
                        exceptions.increment();
                        if (firstError[0] == null) firstError[0] = String.valueOf(e.getCause());
                    }
                }
                return null;
            });
            List<Future<?>> writers = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                int base = p * transmittersPerProducer;
                writers.add(pool.submit(() -> {
                    threads.add(Thread.currentThread());
                    Thread.currentThread().setName("NestWorld-Region-" + base);
                    for (int i = base; i < base + transmittersPerProducer; i++) {
                        Transmitter t = new Transmitter(i, network);
                        for (Direction side : sides) {
                            try { changed.invoke(cache, t, side); }
                            catch (InvocationTargetException e) {
                                producerExceptions.increment();
                                if (firstError[0] == null) firstError[0] = "producer: " + e.getCause();
                            }
                        }
                    }
                    return null;
                }));
            }
            try {
                for (Future<?> w : writers) w.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                producing.set(false);
                consumer.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                producing.set(false);
                return new Result(0, 0, 0, 0, exceptions.sum(), producerExceptions.sum(), firstError[0], spinning(threads));
            }
            // Drain: the next main tick. A consumer that survived corruption must still deliver what remains.
            Future<?> drain = pool.submit(() -> {
                for (int i = 0; i < 3; i++) {
                    try { commit.invoke(cache); }
                    catch (InvocationTargetException e) {
                        exceptions.increment();
                        if (firstError[0] == null) firstError[0] = String.valueOf(e.getCause());
                    }
                }
                return null;
            });
            try {
                drain.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                return new Result(0, 0, 0, 0, exceptions.sum(), producerExceptions.sum(), firstError[0], "drain commit" + spinning(threads));
            }
        } finally {
            pool.shutdownNow();
        }
        long produced = (long) producers * transmittersPerProducer * sides.length;
        long consumedTotal = 0, duplicates = 0;
        for (AtomicInteger n : CONSUMED.values()) {
            consumedTotal += n.get();
            if (n.get() > 1) duplicates += n.get() - 1;
        }
        long lost = produced - CONSUMED.size();
        return new Result(produced, consumedTotal, lost, duplicates, exceptions.sum(), producerExceptions.sum(), firstError[0], null);
    }

    /** Negative control: the unpatched cache must show the race (loss, duplicate or consumer exception). */
    static void negativeControl(Path mods) throws Exception {
        ClassNode original = prepare(CoremodRegression.load(mods, "Mekanism-1.20.1-10.4.16.80.jar", CACHE));
        Class<?> cls = ConcurrencyRegression.define(original);
        for (int attempt = 1; attempt <= 20; attempt++) {
            Result r = round(cls, 4, 20_000);
            System.out.println("NEGATIVE CONTROL attempt " + attempt + " (unpatched): " + r);
            if (!r.clean()) {
                System.out.println("PASS Mekanism NetworkAcceptorCache negative control: race reproduced unpatched");
                return;
            }
        }
        throw new AssertionError("Unpatched NetworkAcceptorCache did not show the race in 20 rounds: fixture too weak");
    }

    /** The patched cache: every round clean; adopt moves pending sides once; deregister drops them. */
    static void patched(Path root, Path mods) throws Exception {
        ClassNode transformed = CoremodRegression.transform(CoremodRegression.transformer(root, "mod_compound_races.js"),
                CoremodRegression.load(mods, "Mekanism-1.20.1-10.4.16.80.jar", CACHE));
        for (String name : List.of("nestworld$markChanged", "nestworld$takechangedAcceptors", "nestworld$mergeChanged")) {
            CoremodRegression.check((CoremodRegression.method(transformed, name).access & Opcodes.ACC_SYNCHRONIZED) != 0,
                    name + " is not synchronized");
        }
        for (String name : List.of("acceptorChanged", "commit", "adoptAcceptors")) {
            CoremodRegression.check((CoremodRegression.method(transformed, name).access & Opcodes.ACC_SYNCHRONIZED) == 0,
                    name + " must not hold the cache monitor across callbacks");
        }
        // registerChangedNetwork and updateTransmitterOnSide must be called outside any cache monitor.
        for (var insn : CoremodRegression.method(transformed, "nestworld$markChanged").instructions) {
            CoremodRegression.check(!(insn instanceof MethodInsnNode call && call.name.equals("registerChangedNetwork")),
                    "registerChangedNetwork called under the cache monitor");
        }
        Class<?> cls = ConcurrencyRegression.define(prepare(transformed));
        for (int round = 1; round <= 10; round++) {
            Result r = round(cls, 4, 20_000);
            CoremodRegression.check(r.clean(), "Patched NetworkAcceptorCache round " + round + ": " + r);
        }
        System.out.println("PASS Mekanism NetworkAcceptorCache patched: 10 rounds x 480000 produced, consumed == produced,"
                + " lost=0 duplicates=0 exceptions=0");

        // adoptAcceptors: B's pending generation is moved into A exactly once; deregister drops pending.
        CONSUMED.clear();
        Object a = cls.getConstructor().newInstance(), b = cls.getConstructor().newInstance();
        Method changed = cls.getMethod("acceptorChanged", Transmitter.class, Direction.class);
        Method commit = cls.getMethod("commit");
        DynamicNetwork network = new DynamicNetwork() {};
        Transmitter shared = new Transmitter(1, network), onlyB = new Transmitter(2, network);
        changed.invoke(a, shared, Direction.NORTH);
        changed.invoke(b, shared, Direction.NORTH);
        changed.invoke(b, shared, Direction.SOUTH);
        changed.invoke(b, onlyB, Direction.UP);
        cls.getMethod("adoptAcceptors", cls).invoke(a, b);
        commit.invoke(b);
        CoremodRegression.check(CONSUMED.isEmpty(), "Adopted cache still committed its moved generation");
        commit.invoke(a);
        CoremodRegression.check(CONSUMED.size() == 3 && CONSUMED.values().stream().allMatch(n -> n.get() == 1),
                "adoptAcceptors did not move pending sides exactly once: " + CONSUMED);
        CONSUMED.clear();
        changed.invoke(a, onlyB, Direction.DOWN);
        cls.getMethod("deregister").invoke(a);
        commit.invoke(a);
        CoremodRegression.check(CONSUMED.isEmpty(), "deregister did not drop the pending generation");
        System.out.println("PASS Mekanism NetworkAcceptorCache adopt/deregister semantics preserved");
    }

    static void run(Path root, Path mods) throws Exception {
        negativeControl(mods);
        patched(root, mods);
    }

    public static void main(String[] args) throws Exception {
        run(Path.of(args[0]), Path.of(args[1]));
    }
}
