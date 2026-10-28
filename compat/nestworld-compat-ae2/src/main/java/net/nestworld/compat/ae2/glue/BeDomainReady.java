package net.nestworld.compat.ae2.glue;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * AE2-BE-READY-001 R1-a scope 2 (owner decisions 2026-10-28 (7), option C): AE2 first-tick readiness
 * ({@code FirstTickInfo.callInit}) runs in the block entity's EXECUTION DOMAIN, resolved by NestWorld Core's canonical
 * policy ({@code net.nestworld.region.BlockEntityExecutionDomain}, looked up by name — no Core compile dependency).
 * Domain-generic: no knowledge of pins or of which host it is (AE2, SolarFlux, other AE2 hosts all alike). For a
 * pinned type the domain is MAIN, so readiness stays where AE2 runs it today; this does NOT address foreign
 * callbacks entering a pinned host's lifecycle (AE2-PIN-LEAK-001).
 *
 * <p>Per host lifecycle (one {@link Request} per readiness request = one generation):
 * {@code OWNER_ENQUEUED → INITIALIZING → READY}, or {@code CANCELLED} (removed / replaced / dropped / handed back to
 * AE2's queue because the chunk stopped BE-ticking). A request supersedes an older one only after that one ended,
 * so at most one {@code onReady} runs per generation and a stale task (its token no longer current) never runs.
 *
 * <p>Failure semantics: AE2 turns a readiness exception into a server crash; a deferred readiness runs inside the
 * Core primitive, which contains op exceptions. The failure is therefore counted ({@code readyInitFailed}) and
 * logged with its stack here — never silent.
 */
public final class BeDomainReady {
    public enum Mode { PRIMITIVE, NO_PRIMITIVE, NO_NESTWORLD }

    static final int OWNER_ENQUEUED = 1, INITIALIZING = 2, READY = 3, CANCELLED = 4;

    public static final Mode MODE;
    private static final MethodHandle IS_CURRENT_THREAD_IN;
    private static final MethodHandle ENQUEUE;
    private static final AtomicLong TOKENS = new AtomicLong();

    /** Current request per host (identity; BlockEntity does not override equals/hashCode). */
    private static final Map<BlockEntity, Request> REQUESTS = Collections.synchronizedMap(new WeakHashMap<>());

    static final class Request {
        final long token;
        volatile int state = OWNER_ENQUEUED;

        Request(long token) {
            this.token = token;
        }
    }

    static {
        ClassLoader cl = BeDomainReady.class.getClassLoader();
        MethodHandle in = null, enq = null;
        Mode m;
        try {
            Class<?> d = Class.forName("net.nestworld.region.BlockEntityExecutionDomain", true, cl);
            in = MethodHandles.publicLookup().findStatic(d, "isCurrentThreadIn",
                    MethodType.methodType(boolean.class, ServerLevel.class, BlockPos.class, String.class));
            enq = MethodHandles.publicLookup().findStatic(d, "enqueueForBlockEntityDomain",
                    MethodType.methodType(void.class, ServerLevel.class, BlockPos.class, String.class, Runnable.class, Consumer.class));
            m = Mode.PRIMITIVE;
        } catch (ReflectiveOperationException | LinkageError e) {
            m = classPresent("net.nestworld.region.NestworldRegionSystem", cl) ? Mode.NO_PRIMITIVE : Mode.NO_NESTWORLD;
        }
        MODE = m;
        IS_CURRENT_THREAD_IN = m == Mode.PRIMITIVE ? in : null;
        ENQUEUE = m == Mode.PRIMITIVE ? enq : null;
        if (m == Mode.NO_PRIMITIVE) {
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] BE readiness: Core lacks BlockEntityExecutionDomain -- AE2 "
                    + "readiness stays on the server thread (AE2-BE-READY-001 not mitigated)");
        } else {
            Ae2Compat.LOG.info("[nestworld-compat-ae2] BE readiness: mode={}", m);
        }
    }

    private BeDomainReady() {
    }

    private static boolean classPresent(String name, ClassLoader cl) {
        try {
            Class.forName(name, false, cl);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Readiness body run in the domain: the caller's lock + revalidation + AE2 init (FirstTickInfoMixin). */
    public interface Body {
        void run();
    }

    /** Re-queues the host in AE2's own first-tick queue (FirstTickInfoMixin: GridHelper.onFirstTick). */
    public interface Requeue {
        void run();
    }

    /**
     * Entry from {@code FirstTickInfo.callInit} (server thread, AE2's ready loop). Runs {@code body} now when the
     * current thread is the host's execution domain; otherwise defers it to that domain. Returns without running
     * when an earlier request of the same host is still pending (coalesced: one onReady per generation).
     */
    public static void ready(BlockEntity be, Body body, Requeue requeue) {
        if (MODE != Mode.PRIMITIVE || !(be.getLevel() instanceof ServerLevel level)) {
            body.run();
            return;
        }
        BlockPos pos = be.getBlockPos();
        String type = typeId(be);
        if (inDomain(level, pos, type)) {
            COUNTERS.readyInDomain.increment();
            body.run();
            return;
        }
        Request req;
        synchronized (REQUESTS) {
            Request prev = REQUESTS.get(be);
            if (prev != null && (prev.state == OWNER_ENQUEUED || prev.state == INITIALIZING)) {
                COUNTERS.readyCoalesced.increment();
                return;
            }
            req = new Request(TOKENS.incrementAndGet());
            REQUESTS.put(be, req);
        }
        COUNTERS.readyDeferred.increment();
        Consumer<Object> onTerminal = kind -> {
            if (end(be, req, OWNER_ENQUEUED, CANCELLED)) {
                COUNTERS.readyDropped.increment();
                Ae2Compat.LOG.warn("[nestworld-compat-ae2] AE2 readiness of {} at {} dropped by the BE domain: {}",
                        type, pos.toShortString(), kind);
            }
        };
        try {
            ENQUEUE.invokeExact(level, pos, type, (Runnable) () -> runInDomain(be, req, level, body, requeue), onTerminal);
        } catch (Throwable t) {
            // the primitive refused/failed synchronously: fall back to AE2's own (main-thread) behaviour, counted
            end(be, req, OWNER_ENQUEUED, CANCELLED);
            COUNTERS.readyEnqueueFailed.increment();
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] BE-domain enqueue failed for {} at {}: {} -- readied inline",
                    type, pos.toShortString(), t.toString());
            body.run();
        }
    }

    /** Executed by the Core primitive on the (re-resolved) domain thread. */
    private static void runInDomain(BlockEntity be, Request req, ServerLevel level, Body body, Requeue requeue) {
        if (REQUESTS.get(be) != req || req.state != OWNER_ENQUEUED) {
            COUNTERS.readyStale.increment();
            return;
        }
        if (!blockEntitiesTicking(level, be.getBlockPos())) {
            // AE2 only readies a host whose chunk BE-ticks (its neighbours are then FULL): hand it back to AE2.
            if (end(be, req, OWNER_ENQUEUED, CANCELLED)) {
                COUNTERS.readyRequeuedNotTicking.increment();
                requeue.run();
            }
            return;
        }
        if (!transition(req, OWNER_ENQUEUED, INITIALIZING)) {
            COUNTERS.readyStale.increment();
            return;
        }
        COUNTERS.readyRanInDomain.increment();
        try {
            body.run();
        } catch (Throwable t) {
            COUNTERS.readyInitFailed.increment();
            Ae2Compat.LOG.error("[nestworld-compat-ae2] AE2 readiness of {} at {} failed in its BE domain on {}",
                    typeId(be), be.getBlockPos().toShortString(), Thread.currentThread().getName(), t);
            throw t;
        } finally {
            req.state = READY;
        }
    }

    private static boolean transition(Request req, int from, int to) {
        synchronized (REQUESTS) {
            if (req.state != from) return false;
            req.state = to;
            return true;
        }
    }

    private static boolean end(BlockEntity be, Request req, int from, int to) {
        synchronized (REQUESTS) {
            if (req.state != from) return false;
            req.state = to;
            if (REQUESTS.get(be) == req) REQUESTS.remove(be);
            return true;
        }
    }

    private static boolean inDomain(ServerLevel level, BlockPos pos, String type) {
        try {
            return (boolean) IS_CURRENT_THREAD_IN.invokeExact(level, pos, type);
        } catch (Throwable t) {
            COUNTERS.domainLookupFailed.increment();      // visible, not silent (fail-open to AE2's own behaviour)
            return true;                                  // cannot resolve: keep AE2's own behaviour (inline)
        }
    }

    // ---- shared with TopologyCallbacks (R1-a scope 3) ----

    static boolean primitive() {
        return MODE == Mode.PRIMITIVE;
    }

    static boolean currentThreadIn(ServerLevel level, BlockPos pos, String type) {
        return inDomain(level, pos, type);
    }

    static void enqueueTo(ServerLevel level, BlockPos pos, String type, Runnable op, Consumer<Object> onTerminal) throws Throwable {
        ENQUEUE.invokeExact(level, pos, type, op, onTerminal);
    }

    static String typeOf(BlockEntity be) {
        return typeId(be);
    }

    /** Thread-safe BE-ticking check from the domain thread: the chunk's own full status (no DistanceManager read). */
    private static boolean blockEntitiesTicking(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING);
    }

    private static String typeId(BlockEntity be) {
        return String.valueOf(BlockEntityType.getKey(be.getType()));
    }
}
