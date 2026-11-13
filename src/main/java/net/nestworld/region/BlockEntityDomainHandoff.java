package net.nestworld.region;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Opt-in, asynchronous ownership handoff for one block-entity position. No mod-specific rules.
 * Existing pin meanings are unchanged. A guard remains after unpin so precollected native callbacks
 * are re-resolved instead of running in an obsolete domain. Callers must prove the physical BE is
 * the actual owner of any shared handler, and must not use a lease after close.
 */
public final class BlockEntityDomainHandoff {
    private static final Logger LOG = LogManager.getLogger("NestWorld/BeHandoff");
    private static final ConcurrentHashMap<ServerLevel, ConcurrentHashMap<Long, Guard>> GUARDS = new ConcurrentHashMap<>();
    private static final AtomicBoolean HOOKS = new AtomicBoolean();
    private static volatile boolean nativeHookObserved, domainHookObserved;
    public static final AtomicLong requested = new AtomicLong(), readyCount = new AtomicLong(), failed = new AtomicLong(),
            nativeExecuted = new AtomicLong(), nativeRouted = new AtomicLong(), nativeCancelled = new AtomicLong(),
            callbackFailures = new AtomicLong();

    public enum Terminal { INVALID, SOURCE_GONE, DOMAIN_UNAVAILABLE, LEVEL_GONE, CALLBACK_FAILED }

    private interface Completion { void completed(Throwable failure); }

    private static final class Guard {
        final ServerLevel level;
        final BlockPos pos;
        final NestworldPins pins;
        final BlockEntityDomainHandoffGate gate = new BlockEntityDomainHandoffGate();
        final ConcurrentHashMap<Request, Boolean> requests = new ConcurrentHashMap<>();
        final ConcurrentHashMap<NativeTick, Boolean> nativeTicks = new ConcurrentHashMap<>();

        Guard(ServerLevel level, BlockPos pos) {
            this.level = level; this.pos = pos.immutable();
            this.pins = NestworldRegionSystem.get().getPins();
        }
    }

    private static final class Request {
        final Guard guard;
        final String type;
        final BooleanSupplier valid;
        final Consumer<Lease> ready;
        final Consumer<Terminal> fail;
        final AtomicBoolean notified = new AtomicBoolean();
        final AtomicBoolean failedOnce = new AtomicBoolean();
        final Lease lease;
        Request(Guard guard, String type, BooleanSupplier valid, Consumer<Lease> ready, Consumer<Terminal> fail) {
            this.guard = guard; this.type = type; this.valid = valid; this.ready = ready; this.fail = fail;
            this.lease = new Lease(this);
        }
    }

    /** A single reference to MAIN ownership. Close on MAIN after all source work is finished. */
    public static final class Lease implements AutoCloseable {
        private final Request request;
        private final AtomicBoolean pinHeld = new AtomicBoolean(), closeRequested = new AtomicBoolean(), released = new AtomicBoolean();
        private final AtomicBoolean releaseQueued = new AtomicBoolean();
        private volatile boolean readyReturned;
        private volatile BlockEntity source;
        private Lease(Request request) { this.request = request; }

        public boolean isValid() {
            Guard g = request.guard;
            return g.level.getServer().isSameThread() && !g.gate.closed() && !closeRequested.get()
                    && source != null && !source.isRemoved() && loaded(g) == source;
        }

        @Override public void close() {
            Guard g = request.guard;
            if (!g.level.getServer().isSameThread()) throw new IllegalStateException("BE handoff lease closes on MAIN");
            closeRequested.set(true);
            tryRelease(this);
        }
    }

    private BlockEntityDomainHandoff() {}

    /** MAIN entry; callbacks run on MAIN. Registration precedes any source-domain callback. */
    public static void request(ServerLevel level, BlockPos pos, String type, BooleanSupplier valid,
                               Consumer<Lease> ready, Consumer<Terminal> fail) {
        MinecraftServer server = level.getServer();
        if (!server.isSameThread()) throw new IllegalStateException("BE handoff request requires MAIN");
        if (HOOKS.compareAndSet(false, true)) MinecraftForge.EVENT_BUS.register(BlockEntityDomainHandoff.class);
        requested.incrementAndGet();
        if (!isAvailable()) {
            failed.incrementAndGet();
            try { fail.accept(Terminal.DOMAIN_UNAVAILABLE); }
            catch (Throwable error) { callbackFailures.incrementAndGet(); LOG.warn("BE handoff rejection callback failed", error); }
            return;
        }
        boolean initial;
        try { initial = valid.getAsBoolean() && server.getLevel(level.dimension()) == level; }
        catch (Throwable error) { initial = false; }
        if (!initial) {
            failed.incrementAndGet();
            try { fail.accept(Terminal.INVALID); }
            catch (Throwable error) { callbackFailures.incrementAndGet(); LOG.warn("BE handoff rejection callback failed", error); }
            return;
        }
        Guard g = GUARDS.computeIfAbsent(level, l -> new ConcurrentHashMap<>())
                .computeIfAbsent(pos.asLong(), p -> new Guard(level, pos));
        Request r = new Request(g, type, valid, ready, fail);
        g.requests.put(r, Boolean.TRUE);
        source(r);
    }

    private static BlockEntity loaded(Guard g) {
        LevelChunk chunk = g.level.getChunkSource().getChunkNow(g.pos.getX() >> 4, g.pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockEntity(g.pos, LevelChunk.EntityCreationType.CHECK);
    }

    private static void source(Request r) {
        if (r.guard.gate.closed()) { fail(r, Terminal.LEVEL_GONE); return; }
        try {
            BlockEntityExecutionDomain.enqueueForBlockEntityDomain(r.guard.level, r.guard.pos, r.type,
                    new Source(r), terminal -> fail(r, Terminal.DOMAIN_UNAVAILABLE));
        } catch (Throwable unavailable) { fail(r, Terminal.DOMAIN_UNAVAILABLE); }
    }

    private static final class Source implements Runnable, Completion {
        final Request r;
        boolean entered, retry;
        Terminal rejection;
        Source(Request r) { this.r = r; }
        @Override public void run() {
            Guard g = r.guard;
            if (g.gate.closed()) { rejection = Terminal.LEVEL_GONE; return; }
            if (!g.gate.enterOwner()) { retry = true; return; }
            entered = true;
            if (!BlockEntityExecutionDomain.isCurrentThreadIn(g.level, g.pos, r.type)) {
                rejection = Terminal.DOMAIN_UNAVAILABLE; return;
            }
            BlockEntity be = loaded(g);
            if (be == null || be.isRemoved() || (r.type != null
                    && !r.type.equals(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()))))) {
                rejection = Terminal.SOURCE_GONE; return;
            }
            r.lease.source = be;
            // Source-domain callback runs under the existing owner safe-point lock.
            g.pins.pinBlockEntityPosition(g.level.dimension(), g.pos.asLong());
            r.lease.pinHeld.set(true);
        }
        @Override public void completed(Throwable failure) {
            Guard g = r.guard;
            if (entered) g.gate.leaveOwner();
            if (g.gate.closed()) { fail(r, Terminal.LEVEL_GONE); return; }
            if (failure != null) { fail(r, Terminal.CALLBACK_FAILED); return; }
            if (rejection != null) { fail(r, rejection); return; }
            if (retry) { source(r); return; }
            // This point is reached only AFTER Source.run has returned.
            try {
                BlockEntityExecutionDomain.enqueueForBlockEntityDomain(g.level, g.pos, r.type,
                        new Ready(r), terminal -> fail(r, Terminal.DOMAIN_UNAVAILABLE));
            } catch (Throwable unavailable) { fail(r, Terminal.DOMAIN_UNAVAILABLE); }
        }
    }

    private static final class Ready implements Runnable, Completion {
        final Request r;
        Ready(Request r) { this.r = r; }
        @Override public void run() {
            Guard g = r.guard;
            if (!g.level.getServer().isSameThread()) { fail(r, Terminal.DOMAIN_UNAVAILABLE); return; }
            if (!r.lease.isValid() || !r.valid.getAsBoolean() || g.level.getServer().getLevel(g.level.dimension()) != g.level) {
                fail(r, Terminal.INVALID); return;
            }
            if (r.notified.compareAndSet(false, true)) {
                readyCount.incrementAndGet();
                r.ready.accept(r.lease);
            }
        }
        @Override public void completed(Throwable failure) {
            r.lease.readyReturned = true;
            if (failure != null) fail(r, Terminal.CALLBACK_FAILED);
            tryRelease(r.lease);
        }
    }

    private static void fail(Request r, Terminal reason) {
        // No source work is permitted after failure. Pin cleanup is still performed if a source step
        // raced shutdown and acquired its reference after the failure notification.
        r.lease.closeRequested.set(true);
        r.lease.readyReturned = true;
        Runnable notify = () -> {
            if (r.failedOnce.compareAndSet(false, true)) {
                failed.incrementAndGet();
                r.notified.set(true);
                try { r.fail.accept(reason); }
                catch (Throwable error) { callbackFailures.incrementAndGet(); LOG.warn("BE handoff failure callback failed", error); }
            }
            tryRelease(r.lease);
        };
        MinecraftServer server = r.guard.level.getServer();
        if (server.isSameThread()) notify.run(); else server.execute(notify);
        if (r.guard.gate.closed()) tryRelease(r.lease);
    }

    private static void tryRelease(Lease lease) {
        Guard g = lease.request.guard;
        if (!lease.closeRequested.get() || !lease.readyReturned) return;
        if (!g.gate.closed() && !g.level.getServer().isSameThread()) { queueRelease(lease); return; }
        if (!g.gate.enterReleaseOwner()) { if (!g.gate.closed()) queueRelease(lease); return; }
        try {
            // Unpin and new native entry are one fenced ownership transition, including during shutdown.
            if (lease.pinHeld.get()) {
                g.pins.unpinBlockEntityPosition(g.level.dimension(), g.pos.asLong());
                lease.pinHeld.set(false);
            }
            if (!lease.pinHeld.get() && lease.released.compareAndSet(false, true)) g.requests.remove(lease.request);
        } finally { g.gate.leaveOwner(); }
    }

    private static void queueRelease(Lease lease) {
        if (!lease.releaseQueued.compareAndSet(false, true)) return;
        MinecraftServer server = lease.request.guard.level.getServer();
        // tell never runs inline (execute may), so closing from inside a native callback cannot recurse/spin.
        server.tell(new TickTask(server.getTickCount(), () -> {
            lease.releaseQueued.set(false);
            tryRelease(lease);
        }));
    }

    /** Exactly-once native invocation token retained across every Core domain reroute. */
    private static final class NativeTick implements Runnable {
        final Guard g;
        final TickingBlockEntity ticker;
        final String type;
        final BlockEntityDomainHandoffToken token = new BlockEntityDomainHandoffToken();
        volatile boolean routed;
        NativeTick(Guard g, TickingBlockEntity ticker) {
            this.g = g; this.ticker = ticker; this.type = ticker.getType();
            g.nativeTicks.put(this, Boolean.TRUE);
        }
        @Override public void run() {
            if (!token.pending()) return;
            if (g.gate.closed() || ticker.isRemoved()) { cancel(); return; }
            if (!BlockEntityExecutionDomain.isCurrentThreadIn(g.level, g.pos, type)) { route(); return; }
            if (!g.gate.enterNative()) { route(); return; }
            try {
                // A pin may have changed between the optimistic check and gate acquisition.
                if (!BlockEntityExecutionDomain.isCurrentThreadIn(g.level, g.pos, type)) { route(); return; }
                BlockEntity actual = loaded(g);
                if (actual == null || actual.isRemoved()) { cancel(); return; }
                // Native tokens belong to ticker callbacks, not to an earlier lease's BE identity. Vanilla
                // invalidates an old ticker on BE removal; a newly collected ticker may belong to a replacement.
                if (ticker.isRemoved()) { cancel(); return; }
                if (!token.start()) return;
                try { ticker.tick(); }
                catch (Throwable error) {
                    if (!routed) throw BlockEntityDomainHandoff.<RuntimeException>rethrow(error);
                    // The original enclosing BE-loop catch is no longer on the stack after rerouting.
                    if (g.pins.isAutoPinEnabled()) g.pins.noteBlockEntityTickError(type, error);
                    LOG.warn("Guarded block entity tick failed at {}", g.pos, error);
                }
                finally { token.finish(); nativeExecuted.incrementAndGet(); g.nativeTicks.remove(this); }
            } finally {
                g.gate.leaveNative();
                for (Request r : g.requests.keySet()) {
                    if (r.lease.closeRequested.get()) {
                        MinecraftServer server = g.level.getServer();
                        if (server.isSameThread() || g.gate.closed()) tryRelease(r.lease); else queueRelease(r.lease);
                    }
                }
            }
        }
        void route() {
            routed = true;
            nativeRouted.incrementAndGet();
            try { BlockEntityExecutionDomain.enqueueForBlockEntityDomain(g.level, g.pos, type, this, terminal -> cancel()); }
            catch (Throwable unavailable) { cancel(); }
        }
        void cancel() {
            if (token.cancel()) { nativeCancelled.incrementAndGet(); g.nativeTicks.remove(this); }
        }
    }

    /** 445-overlay-friendly replacement of the raw ticker.tick() call. Unguarded positions keep the original call. */
    public static void tickOrRoute(ServerLevel level, TickingBlockEntity ticker) {
        if (!nativeHookObserved) nativeHookObserved = true;
        var byPos = GUARDS.get(level);
        Guard g = byPos == null ? null : byPos.get(ticker.getPos().asLong());
        if (g == null) { ticker.tick(); return; }
        new NativeTick(g, ticker).run();
    }

    /** 445-overlay-friendly replacement of Runnable.run inside Core domain-task execution. */
    public static void runDomainTask(Runnable op) {
        if (!domainHookObserved) domainHookObserved = true;
        Throwable failure = null;
        try { op.run(); }
        catch (Throwable error) { failure = error; throw BlockEntityDomainHandoff.<RuntimeException>rethrow(error); }
        finally { afterDomainTask(op, failure); }
    }

    @SuppressWarnings("unchecked") private static <T extends Throwable> RuntimeException rethrow(Throwable error) throws T {
        throw (T) error;
    }

    public static void afterDomainTask(Runnable op, Throwable failure) {
        if (op instanceof Completion completion) {
            try { completion.completed(failure); }
            catch (Throwable error) { callbackFailures.incrementAndGet(); LOG.error("BE handoff completion failed", error); }
        }
    }

    public static String stats() {
        long positions = 0, requests = 0, leases = 0, activeNative = 0, activeOwner = 0, closedLevels = 0;
        for (var level : GUARDS.values()) {
            boolean closed = !level.isEmpty();
            for (Guard g : level.values()) {
                positions++; requests += g.requests.size(); activeNative += g.gate.nativeRunning();
                if (g.gate.ownerRunning()) activeOwner++;
                closed &= g.gate.closed();
                for (Request r : g.requests.keySet()) if (r.lease.pinHeld.get()) leases++;
            }
            if (closed) closedLevels++;
        }
        return "beHandoff available=" + isAvailable() + " requested=" + requested.get() + " ready=" + readyCount.get() + " failed=" + failed.get()
                + " nativeExecuted=" + nativeExecuted.get() + " nativeRouted=" + nativeRouted.get()
                + " nativeCancelled=" + nativeCancelled.get() + " callbackFailures=" + callbackFailures.get()
                + " activeRequests=" + requests + " activeLeases=" + leases + " activeNative=" + activeNative
                + " activeOwner=" + activeOwner + " guardedPositions=" + positions + " closedLevels=" + closedLevels;
    }

    /** Refuse ownership changes until both opt-in call-site hooks have actually executed in this JVM. */
    public static boolean isAvailable() { return nativeHookObserved && domainHookObserved; }

    public static void shutdownLevel(ServerLevel level) {
        // Keep tombstones until ServerStopped so a previously collected native batch cannot fall through
        // to the unguarded fast path between LevelEvent.Unload and the worker's actual termination.
        var byPos = GUARDS.get(level);
        if (byPos == null) return;
        for (Guard g : byPos.values()) {
            g.gate.close();
            for (NativeTick tick : new ArrayList<>(g.nativeTicks.keySet())) tick.cancel();
            for (Request r : new ArrayList<>(g.requests.keySet())) fail(r, Terminal.LEVEL_GONE);
        }
    }

    public static void shutdownAll() {
        for (ServerLevel level : new ArrayList<>(GUARDS.keySet())) shutdownLevel(level);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) shutdownLevel(level);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStopping(ServerStoppingEvent event) {
        for (ServerLevel level : new ArrayList<>(GUARDS.keySet()))
            if (level.getServer() == event.getServer()) shutdownLevel(level);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStopped(ServerStoppedEvent event) {
        GUARDS.keySet().removeIf(level -> level.getServer() == event.getServer());
    }
}
