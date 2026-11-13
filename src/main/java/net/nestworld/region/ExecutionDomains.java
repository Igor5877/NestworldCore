package net.nestworld.region;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.nestworld.modapi.domain.DomainBackend;
import net.nestworld.modapi.domain.DomainHandle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CORE-GRID-DOMAIN-001 / ExecutionDomain primitive backend (docs/contracts/ExecutionDomainPrimitive.md). Generic: no
 * AE2 types. A domain = one dedicated thread running submitted jobs FIFO, in parallel with MAIN.
 *
 * <p>Core-side guarantees, enforced through the vanilla hook points (Level.tickBlockEntities, ServerChunkCache.pollTask,
 * MinecraftServer.saveAllChunks/stopServer call {@link #beforeBlockEntities}, {@link #beforeChunkTasks},
 * {@link #awaitAllDomains}):
 * <ol>
 *   <li>exclusion: MAIN never starts a level's BE phase / chunk tasks / save / stop while a job touching that level is
 *       queued or running;</li>
 *   <li>acting-main lease: while a job runs, its thread passes the main-thread checks for exactly the touched levels
 *       ({@link #leaseHolds}); unloaded chunks ⇒ empty chunk, never a blocking hop to MAIN;</li>
 *   <li>fallback: disabled ({@code -Dnestworld.domains=false}), not on MAIN, or worker unavailable ⇒ submit returns
 *       false and the caller runs inline.</li>
 * </ol>
 */
public final class ExecutionDomains implements DomainBackend {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/Domains");
    public static final boolean ENABLED = !"false".equals(System.getProperty("nestworld.domains", "true"));
    static final long WARN_NANOS = TimeUnit.MILLISECONDS.toNanos(Long.getLong("nestworld.domains.awaitWarnMs", 200));

    private static volatile ExecutionDomains INSTANCE;
    private final MinecraftServer server;
    private final Map<String, Domain> domains = new ConcurrentHashMap<>();
    /** level → jobs (all domains) queued or running that touch it. */
    private static final Map<ResourceKey<Level>, AtomicInteger> PENDING_BY_LEVEL = new ConcurrentHashMap<>();
    private static final AtomicInteger PENDING_TOTAL = new AtomicInteger();
    private static final Object MONITOR = new Object();
    private static final ThreadLocal<Set<ResourceKey<Level>>> LEASE = new ThreadLocal<>();
    /** A-PERF attribution: is the running domain job an OWNED (model A) job (false = exclusive, model B)? */
    static final ThreadLocal<Boolean> JOB_OWNED = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Map<ResourceKey<Level>, EmptyLevelChunk> EMPTY = new ConcurrentHashMap<>();
    static final LongAdder awaitsBlocked = new LongAdder(), awaitNanos = new LongAdder(), emptyChunkReads = new LongAdder();
    static final AtomicLong maxAwaitNanos = new AtomicLong();

    private ExecutionDomains(MinecraftServer server) {
        this.server = server;
    }

    // ---------------------------------------------------------------- lifecycle

    public static void install(MinecraftServer server) {
        ExecutionDomains d = new ExecutionDomains(server);
        INSTANCE = d;
        DomainTickMetrics.install(server);
        net.nestworld.modapi.domain.DomainBackendRegistry.install(server, d);
        LOGGER.info("ExecutionDomain backend installed (enabled={})", ENABLED);
    }

    public static void uninstall(MinecraftServer server) {
        awaitAllDomains();
        net.nestworld.modapi.domain.DomainBackendRegistry.uninstall(server);
        ExecutionDomains d = INSTANCE;
        INSTANCE = null;
        if (d != null) for (Domain dom : d.domains.values()) dom.stop();
    }

    @Override
    public DomainHandle open(String name) {
        if (!ENABLED) return null;
        return domains.computeIfAbsent(name, n -> new Domain(n, server));
    }

    // ---------------------------------------------------------------- hooks (called from patched vanilla code)

    /** MAIN, at the start of {@code Level.tickBlockEntities}. */
    public static void beforeBlockEntities(Level level) {
        serviceMainCalls();
        if (level instanceof ServerLevel) awaitLevel(level.dimension());
        if (!WORLD_OPS.isEmpty() && LEASE.get() == null) drainWorldOps();
    }

    /** MAIN, at the start of {@code ServerChunkCache.pollTask}. */
    public static void beforeChunkTasks(Level level) {
        if (level != null) awaitLevel(level.dimension());
    }

    /** MAIN, before save / stop. */
    public static void awaitAllDomains() {
        serviceMainCalls();
        if (PENDING_TOTAL.get() != 0) await(() -> PENDING_TOTAL.get() == 0);
        if (!WORLD_OPS.isEmpty() && LEASE.get() == null) drainWorldOps();
    }

    // ---------------------------------------------------------------- synchronous MAIN service (AE2-GRID-DOMAIN-MVP-001)

    /**
     * Owner contract (2026-11-12, BARRIER_MAIN_SERVICE): a domain thread asks MAIN to run ONE specific operation and waits
     * for its result; MAIN runs only that operation at a TOP-LEVEL service point (domain await, region-round wait,
     * per-level BE start, between ticks) — never nested in another serviced call, never inside a domain await from a
     * serviced call. {@code delegation} is an opaque token (e.g. the grid whose phase lock the parked requester holds):
     * while MAIN runs the call, {@link #currentDelegation()} returns it so the adapter can treat MAIN as acting for the
     * parked requester. Timing per key: calls, total wait, p50/p95/p99/max.
     */
    private static final class MainCall {
        final String key; final Object delegation; final java.util.function.Supplier<?> body; final Thread waiter;
        final long enqueued = System.nanoTime();
        volatile boolean done; Object result; Throwable failure;
        MainCall(String key, Object delegation, java.util.function.Supplier<?> body) {
            this.key = key; this.delegation = delegation; this.body = body; this.waiter = Thread.currentThread();
        }
    }
    private static final java.util.concurrent.ConcurrentLinkedQueue<MainCall> MAIN_CALLS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicBoolean MAIN_PUMP_QUEUED = new java.util.concurrent.atomic.AtomicBoolean();
    private static boolean IN_MAIN_SERVICE;                       // MAIN only
    private static Object DELEGATION;                             // MAIN only, while a call runs
    static final LongAdder lifecycleSkippedInService = new LongAdder();
    static final LongAdder mainCalls = new LongAdder(), mainCallFailures = new LongAdder(), nestedServiceAwaits = new LongAdder(),
            mainCallsOffDomain = new LongAdder();
    static final Map<String, DomainTickMetrics.Reservoir> MAIN_CALL_WAIT = new ConcurrentHashMap<>();

    /** Domain thread: run {@code body} on MAIN and return its result (MAIN: runs directly). */
    @SuppressWarnings("unchecked")
    public static <T> T callMain(String key, Object delegation, java.util.function.Supplier<T> body) {
        ExecutionDomains d = INSTANCE;
        if (d == null) throw new IllegalStateException("ExecutionDomains not installed");
        if (d.server.isSameThread()) return body.get();
        if (LEASE.get() == null) {                                // only domain threads may park on MAIN
            mainCallsOffDomain.increment();
            throw new IllegalStateException("callMain from a non-domain thread: " + Thread.currentThread().getName());
        }
        MainCall c = new MainCall(key, delegation, body);
        MAIN_CALLS.add(c);
        mainCalls.increment();
        if (MAIN_PUMP_QUEUED.compareAndSet(false, true)) d.server.execute(() -> { MAIN_PUMP_QUEUED.set(false); serviceMainCalls(); });
        synchronized (MONITOR) { MONITOR.notifyAll(); }            // MAIN may sit in a domain await
        while (!c.done) java.util.concurrent.locks.LockSupport.parkNanos(c, 20_000L);
        MAIN_CALL_WAIT.computeIfAbsent(key, k -> new DomainTickMetrics.Reservoir()).add(System.nanoTime() - c.enqueued);
        if (c.failure != null) {
            if (c.failure instanceof RuntimeException re) throw re;
            if (c.failure instanceof Error er) throw er;
            throw new IllegalStateException(c.failure);
        }
        return (T) c.result;
    }

    /** MAIN, top-level service points only: run every queued synchronous call (FIFO). No nesting. */
    public static void serviceMainCalls() {
        if (MAIN_CALLS.isEmpty() || IN_MAIN_SERVICE) return;
        ExecutionDomains d = INSTANCE;
        if (d == null || !d.server.isSameThread() || LEASE.get() != null) return;
        IN_MAIN_SERVICE = true;
        try {
            for (MainCall c; (c = MAIN_CALLS.poll()) != null; ) {
                DELEGATION = c.delegation;
                try {
                    c.result = c.body.get();
                } catch (Throwable t) {
                    mainCallFailures.increment();
                    c.failure = t;
                } finally {
                    DELEGATION = null;
                    c.done = true;
                    java.util.concurrent.locks.LockSupport.unpark(c.waiter);
                }
            }
        } finally {
            IN_MAIN_SERVICE = false;
        }
    }

    /**
     * MAIN bounded wait on a region-thread latch (player/entity interaction dispatch) that keeps servicing synchronous
     * domain calls: a region thread may be blocked on a lock held by a domain worker that is parked on a MAIN call —
     * without servicing, MAIN ⇢ REGION ⇢ P(G) ⇢ worker ⇢ MAIN would stall until the budget expires. Same budget.
     */
    public static boolean awaitServicing(java.util.concurrent.CountDownLatch latch, long nanos) throws InterruptedException {
        long deadline = System.nanoTime() + nanos;
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return latch.getCount() == 0;
            if (latch.await(Math.min(left, 100_000L), java.util.concurrent.TimeUnit.NANOSECONDS)) return true;
            serviceMainCalls();
        }
    }

    /** MAIN: the delegation token of the serviced call currently running (null otherwise). */
    public static Object currentDelegation() {
        return IN_MAIN_SERVICE ? DELEGATION : null;
    }

    public static boolean inMainService() { return IN_MAIN_SERVICE; }

    static String mainCallStatus() {
        StringBuilder sb = new StringBuilder("mainCalls{calls=" + mainCalls.sum() + " failures=" + mainCallFailures.sum()
                + " awaitInService(skipped)=" + nestedServiceAwaits.sum() + " lifecycleSkippedInService=" + lifecycleSkippedInService.sum() + " offDomain(must be 0)=" + mainCallsOffDomain.sum()
                + " queued=" + MAIN_CALLS.size() + "} wait by key:");
        var l = new java.util.ArrayList<>(MAIN_CALL_WAIT.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue().total(), a.getValue().total()));
        for (var e : l) sb.append("\n    ").append(e.getKey()).append(" ").append(e.getValue().describe());
        return sb.toString();
    }

    /** Any thread: does the current thread hold an acting-main lease for {@code level}? */
    public static boolean leaseHolds(Level level) {
        Set<ResourceKey<Level>> l = LEASE.get();
        return l != null && level != null && l.contains(level.dimension());
    }

    /** Drop-in for {@code instanceof RegionThread} at sites that only need "may read loaded world data here". */
    public static boolean isRegionOrLease(Object thread) {
        return thread instanceof RegionThread || (thread == Thread.currentThread() && LEASE.get() != null);
    }

    /** Lease holder reading an unloaded chunk: vanilla "absent" semantics without a blocking hop to MAIN. */
    public static LevelChunk emptyChunk(Level level) {
        emptyChunkReads.increment();
        return EMPTY.computeIfAbsent(level.dimension(), k -> new EmptyLevelChunk(level, new net.minecraft.world.level.ChunkPos(0, 0),
                level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                        .getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS)));
    }

    /** Lease holder in {@code ServerChunkCache.getChunk}: loaded chunk, else vanilla "absent" (null / empty chunk). */
    public static net.minecraft.world.level.chunk.ChunkAccess leasedChunk(net.minecraft.server.level.ServerChunkCache cache,
            int x, int z, boolean load) {
        LevelChunk c = leaseLoadedChunk(cache, x, z);
        if (c != null) return c;
        return load ? emptyChunk(cache.getLevel()) : null;
    }

    /** Lease holder: an already-FULL chunk from MAIN's published snapshot (no region-thread cache, no blocking). */
    public static LevelChunk leaseLoadedChunk(net.minecraft.server.level.ServerChunkCache cache, int x, int z) {
        return cache.nestworldLoadedFull.get(net.minecraft.world.level.ChunkPos.asLong(x, z));
    }

    private static void awaitLevel(ResourceKey<Level> key) {
        AtomicInteger p = PENDING_BY_LEVEL.get(key);
        if (p == null || p.get() == 0) return;
        await(() -> p.get() == 0);
    }

    private static void await(java.util.function.BooleanSupplier done) {
        if (done.getAsBoolean()) return;
        if (LEASE.get() != null) {
            // a domain job must never wait for jobs (its own or another domain's): fail loud in logs, do not deadlock
            LOGGER.error("ExecutionDomains: await requested on a domain thread — skipped", new IllegalStateException());
            return;
        }
        if (IN_MAIN_SERVICE) {
            // A serviced MAIN call must never wait for domain jobs: its requester is parked inside one. With the single,
            // serial 'ae2' domain nothing else can be executing meanwhile, so there is nothing to wait for — proceed
            // (counted). Same-grid topology inside a serviced call stays fail-closed in the adapter's lockOnMain.
            nestedServiceAwaits.increment();
            return;
        }
        awaitsBlocked.increment();
        long t0 = System.nanoTime();
        synchronized (MONITOR) {
            while (!done.getAsBoolean()) {
                serviceMainCalls();                           // the domain may be parked on a synchronous MAIN call
                if (done.getAsBoolean()) break;
                try { MONITOR.wait(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        long d = System.nanoTime() - t0;
        awaitNanos.add(d);
        DomainTickMetrics.mainAwait(d);
        maxAwaitNanos.accumulateAndGet(d, Math::max);
        if (d > WARN_NANOS) LOGGER.warn("ExecutionDomains: MAIN waited {} ms for domain jobs", d / 1_000_000);
    }

    public static String status() {
        ExecutionDomains d = INSTANCE;
        StringBuilder sb = new StringBuilder("domains enabled=" + ENABLED + " installed=" + (d != null) + " pending="
                + PENDING_TOTAL.get() + " awaitsBlocked=" + awaitsBlocked.sum() + " awaitMs=" + awaitNanos.sum() / 1_000_000
                + " maxAwaitMs=" + maxAwaitNanos.get() / 1_000_000 + " emptyChunkReads=" + emptyChunkReads.sum());
        if (d != null) for (Domain dom : d.domains.values()) sb.append("\n  ").append(dom.stats());
        sb.append("\n").append(DomainTickMetrics.status());
        sb.append("\n").append(mainCallStatus());
        return sb.toString();
    }

    // ---------------------------------------------------------------- typed world side effects (TZ §3″.2)

    /** One typed world side effect requested from a domain thread; applied by MAIN, in request order. Never generic. */
    private sealed interface WorldOp permits BlockMutationOp, ScheduleTickOp, NeighborNotifyOp {}
    /** setBlock/removeBlock/destroyBlock funnel: result computed now from the safe read, write applied on MAIN. */
    private record BlockMutationOp(Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state,
            int flags, int recursionLeft, boolean owned) implements WorldOp {}
    /** LevelTicks.schedule (block or fluid ticks). */
    private record ScheduleTickOp(net.minecraft.world.ticks.LevelTicks<Object> ticks, net.minecraft.world.ticks.ScheduledTick<Object> tick) implements WorldOp {}
    /** Neighbour notification: kind 0 = updateNeighborsAt, 1 = neighborChanged(from), 2 = updateNeighbourForOutputSignal. */
    private record NeighborNotifyOp(Level level, int kind, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block,
            net.minecraft.core.BlockPos from, boolean owned) implements WorldOp {}

    private static final java.util.concurrent.ConcurrentLinkedQueue<WorldOp> WORLD_OPS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    static final LongAdder blockMutationOps = new LongAdder(), scheduleTickOps = new LongAdder(), neighborNotifyOps = new LongAdder(),
            worldOpsApplied = new LongAdder(), worldOpFailures = new LongAdder();
    private static final Map<String, LongAdder> WORLD_OP_BLOCKS = new ConcurrentHashMap<>();

    /** True on a thread currently executing a domain job (lease held). */
    public static boolean onDomainThread() {
        return LEASE.get() != null;
    }

    private static void noteBlock(String kind, net.minecraft.world.level.block.Block b) {
        var k = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
        WORLD_OP_BLOCKS.computeIfAbsent(kind + " " + k, x -> new LongAdder()).increment();
    }

    /**
     * BlockMutationOp call-site allowlist (owner 2026-11-12): a deferred setBlock is legal ONLY for call sites a compat
     * adapter registered after a call-site audit (return value ignored, no read-after-write, ≤1 tick deferral harmless).
     * Key = "declaringClass#method" of the first non-Minecraft / non-NestWorld frame.
     */
    private static final java.util.Set<String> DEFER_SETBLOCK_SITES = ConcurrentHashMap.newKeySet();
    private static final Map<String, LongAdder> UNHANDLED = new ConcurrentHashMap<>();
    static final LongAdder unhandledWorldSideEffects = new LongAdder();
    private static final StackWalker WALKER = StackWalker.getInstance();

    /** Adapter API: declare an audited setBlock call site safe for deferral from a domain thread. */
    public static void allowDeferredSetBlock(String declaringClass, String method) {
        DEFER_SETBLOCK_SITES.add(declaringClass + "#" + method);
    }

    private static String callSite() {
        return WALKER.walk(s -> s.map(f -> f.getClassName() + "#" + f.getMethodName())
                .filter(n -> !n.startsWith("net.minecraft.") && !n.startsWith("net.nestworld.region.") && !n.startsWith("java.")
                        && !n.startsWith("jdk.") && !n.startsWith("net.minecraftforge."))
                .findFirst().orElse("?"));
    }

    /** Level.setBlock(4) hook. -1 = not a domain thread, proceed normally; 0/1 = deferred, the vanilla boolean result. */
    public static int domainSetBlock(Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state,
            int flags, int recursionLeft) {
        if (LEASE.get() == null || level.isClientSide) return -1;
        String site = callSite();
        if (!DEFER_SETBLOCK_SITES.contains(site)) {
            // not audited: synchronous semantics cannot be honoured here. Deferred anyway (never a direct foreign write),
            // but counted as an UNHANDLED world side effect — the Fast-Live gate requires 0.
            unhandledWorldSideEffects.increment();
            UNHANDLED.computeIfAbsent("setBlock " + site, k -> new LongAdder()).increment();
            if (unhandledWorldSideEffects.sum() <= 20) LOGGER.warn("ExecutionDomains: UNHANDLED setBlock from domain thread at {} {} by {}",
                    level.dimension().location(), pos.toShortString(), site);
        }
        boolean wouldChange = !level.getBlockState(pos).equals(state);
        WORLD_OPS.add(new BlockMutationOp(level, pos.immutable(), state, flags, recursionLeft, JOB_OWNED.get()));
        blockMutationOps.increment();
        noteBlock("setBlock", state.getBlock());
        return wouldChange ? 1 : 0;
    }

    /** LevelTicks.schedule hook: true = deferred to MAIN. */
    @SuppressWarnings("unchecked")
    public static boolean domainScheduleTick(Object ticks, Object tick) {
        if (LEASE.get() == null) return false;
        WORLD_OPS.add(new ScheduleTickOp((net.minecraft.world.ticks.LevelTicks<Object>) ticks, (net.minecraft.world.ticks.ScheduledTick<Object>) tick));
        scheduleTickOps.increment();
        return true;
    }

    /** Neighbour-notification hook: true = deferred to MAIN. */
    public static boolean domainNeighbor(Level level, int kind, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.Block block,
            net.minecraft.core.BlockPos from) {
        if (LEASE.get() == null || level.isClientSide) return false;
        WORLD_OPS.add(new NeighborNotifyOp(level, kind, pos.immutable(), block, from == null ? null : from.immutable(), JOB_OWNED.get()));
        neighborNotifyOps.increment();
        noteBlock(kind == 2 ? "comparator" : "neighbor", block);
        return true;
    }

    /** MAIN: apply queued world side effects in request order (owner executes). */
    /** MAIN cost of applying deferred world ops (A-PERF: model A moves this work from domain threads to MAIN). */
    static final LongAdder worldOpsMainNanos = new LongAdder(), worldOpsDrains = new LongAdder();
    static volatile long worldOpsMaxDrainNanos;

    static void drainWorldOps() {
        if (WORLD_OPS.isEmpty()) return;
        long t0 = System.nanoTime();
        try {
            drainWorldOps0();
        } finally {
            long d = System.nanoTime() - t0;
            worldOpsMainNanos.add(d);
            worldOpsDrains.increment();
            DomainTickMetrics.worldOps(d);
            if (d > worldOpsMaxDrainNanos) worldOpsMaxDrainNanos = d;
        }
    }

    /** cached "kind block" keys for per-op cost (no string building per op) */
    @SuppressWarnings("unchecked")
    private static final Map<net.minecraft.world.level.block.Block, String>[] OP_KEYS = new Map[] {
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>() };
    private static final String[] OP_KIND = { "neighbor", "neighborChanged", "comparator", "setBlock", "scheduleTick" };

    private static String opKey(WorldOp op) {
        int k;
        net.minecraft.world.level.block.Block b;
        boolean owned;
        if (op instanceof NeighborNotifyOp n) { k = n.kind; b = n.block; owned = n.owned; }
        else if (op instanceof BlockMutationOp m) { k = 3; b = m.state.getBlock(); owned = m.owned; }
        else return "scheduleTick";
        final int kk = k;
        final String origin = owned ? "A(owned) " : "B(exclusive) ";
        return OP_KEYS[k + (owned ? 5 : 0)].computeIfAbsent(b, x -> origin + OP_KIND[kk] + " " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(x));
    }

    private static void drainWorldOps0() {
        for (WorldOp op; (op = WORLD_OPS.poll()) != null; ) {
            long o0 = System.nanoTime();
            try {
                if (op instanceof BlockMutationOp m) {
                    m.level.setBlock(m.pos, m.state, m.flags, m.recursionLeft);
                } else if (op instanceof ScheduleTickOp t) {
                    t.ticks.schedule(t.tick);
                } else if (op instanceof NeighborNotifyOp n) {
                    if (n.kind == 0) n.level.updateNeighborsAt(n.pos, n.block);
                    else if (n.kind == 1) n.level.neighborChanged(n.pos, n.block, n.from);
                    else n.level.updateNeighbourForOutputSignal(n.pos, n.block);
                }
                worldOpsApplied.increment();
            } catch (Throwable t) {
                worldOpFailures.increment();
                if (worldOpFailures.sum() <= 20) LOGGER.error("ExecutionDomains: world op {} failed", op, t);
            } finally {
                DomainTickMetrics.opCost(opKey(op), System.nanoTime() - o0);
            }
        }
    }

    static String worldOpsStatus() {
        var l = new java.util.ArrayList<>(WORLD_OP_BLOCKS.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
        StringBuilder sb = new StringBuilder("worldOps{unhandled(must be 0)=" + unhandledWorldSideEffects.sum() + " " + UNHANDLED
                + " blockMutation=" + blockMutationOps.sum() + " scheduleTick=" + scheduleTickOps.sum()
                + " neighborNotify=" + neighborNotifyOps.sum() + " applied=" + worldOpsApplied.sum() + " failures=" + worldOpFailures.sum()
                + " queued=" + WORLD_OPS.size() + " mainMs=" + worldOpsMainNanos.sum() / 1_000_000 + " drains=" + worldOpsDrains.sum()
                + " maxDrainUs=" + worldOpsMaxDrainNanos / 1000 + "} top:");
        for (int i = 0; i < Math.min(8, l.size()); i++) sb.append(" ").append(l.get(i).getKey()).append("=").append(l.get(i).getValue().sum());
        return sb.toString();
    }

    // ---------------------------------------------------------------- ownership (model A, TZ §3″)

    /** Immutable published ownership: level → (BlockPos long → claim). Replaced only at a barrier. */
    private record Claim(Domain domain, String typeId, int refs) {}
    private record Snapshot(long epoch, Map<ResourceKey<Level>, it.unimi.dsi.fastutil.longs.Long2ObjectMap<Claim>> byLevel) {}
    private static volatile Snapshot SNAP = new Snapshot(0, Map.of());
    private record Request(Domain domain, ResourceKey<Level> level, long[] positions, String[] types, boolean claim) {}
    private static final java.util.concurrent.ConcurrentLinkedQueue<Request> REQUESTS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** MAIN only: per level, per domain, the owned tickers diverted in the current BE phase. */
    private static final Map<Level, Map<Domain, java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity>>> DIVERTED = new java.util.IdentityHashMap<>();
    static final LongAdder diverted = new LongAdder(), domainTicked = new LongAdder(), typeMismatch = new LongAdder(),
            refusedClaims = new LongAdder(), unloadReleases = new LongAdder(), blockChangeWaits = new LongAdder(),
            blockChangeWaitTimeouts = new LongAdder(), divertFallbackInline = new LongAdder(), removedSkipped = new LongAdder(), inFlight = new LongAdder(), tickFailures = new LongAdder();

    /**
     * MAIN, from the patched BE-phase loop right after {@code shouldTickBlocksAt}: returns the value the loop should
     * use. An owned ticker (published snapshot, matching type) is collected for its domain and the loop skips it.
     */
    public static boolean keepOnMain(boolean shouldTick, Level level, net.minecraft.world.level.block.entity.TickingBlockEntity t) {
        if (!shouldTick) return false;
        Snapshot snap = SNAP;
        if (snap.byLevel.isEmpty()) return true;
        var m = snap.byLevel.get(level.dimension());
        if (m == null) return true;
        Claim c = m.get(t.getPos().asLong());
        if (c == null) return true;
        if (!c.typeId.equals(t.getType())) { typeMismatch.increment(); return true; }      // replaced block: MAIN ticks it
        DIVERTED.computeIfAbsent(level, k -> new java.util.IdentityHashMap<>())
                .computeIfAbsent(c.domain, k -> new java.util.ArrayList<>()).add(t);
        diverted.increment();
        return false;
    }

    /** MAIN, at the end of the BE phase of {@code level}: hand the diverted owned tickers to their domains. */
    public static void flushDiverted(Level level) {
        var byDomain = DIVERTED.remove(level);
        if (byDomain == null) return;
        for (var e : byDomain.entrySet()) {
            java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> list = e.getValue();
            inFlight.add(list.size());
            Runnable job = () -> {
                for (var t : list) {
                    try {
                        if (t.isRemoved()) { removedSkipped.increment(); continue; }
                        t.tick();
                        domainTicked.increment();
                    } catch (Throwable failure) {
                        // never let one BE abort the rest of the owned list (that would be a MISSED tick for the others)
                        tickFailures.increment();
                        if (tickFailures.sum() <= 20) LOGGER.error("ExecutionDomain owned BE tick failed at {} ({})", t.getPos(), t.getType(), failure);
                    } finally {
                        inFlight.decrement();
                    }
                }
            };
            if (!e.getKey().submitOwned(Set.of(level.dimension()), job)) {
                divertFallbackInline.increment();
                job.run();                                    // domain gone: exactly-once on MAIN, same tick
            }
        }
    }

    /** MAIN: a chunk is about to unload — no claimed host may outlive its chunk in a domain. */
    public static void beforeChunkUnload(Level level, net.minecraft.world.level.ChunkPos pos) {
        Snapshot snap = SNAP;
        var m = snap.byLevel.get(level.dimension());
        if (m == null || m.isEmpty()) return;
        java.util.List<Long> drop = null;
        for (long k : m.keySet()) {
            if ((net.minecraft.core.BlockPos.getX(k) >> 4) == pos.x && (net.minecraft.core.BlockPos.getZ(k) >> 4) == pos.z) {
                if (drop == null) drop = new java.util.ArrayList<>();
                drop.add(k);
            }
        }
        if (drop == null) return;
        awaitAllDomains();                                   // domains idle: nothing of this chunk is executing
        var copy = copyOf(snap);
        var lm = copy.get(level.dimension());
        for (long k : drop) lm.remove(k);
        SNAP = new Snapshot(snap.epoch + 1, freeze(copy));
        unloadReleases.add(drop.size());
    }

    /** Any non-domain thread: a block change at an owned position waits (bounded) until the owner is idle. */
    public static void beforeBlockChange(Level level, net.minecraft.core.BlockPos pos) {
        Snapshot snap = SNAP;
        if (snap.byLevel.isEmpty()) return;
        var m = snap.byLevel.get(level.dimension());
        if (m == null) return;
        Claim c = m.get(pos.asLong());
        if (c == null || c.domain.isDomainThread()) return;
        if (c.domain.pending.get() == 0) return;
        if (IN_MAIN_SERVICE) {
            // MVP-001: MAIN runs a synchronous call FOR the parked domain worker — the domain's pending job is the one
            // waiting for this very call; waiting here could only time out (livelock: 2026-11-13 overlapStart run)
            lifecycleSkippedInService.increment();
            return;
        }
        blockChangeWaits.increment();
        long t0 = System.nanoTime();
        long deadline = t0 + TimeUnit.MILLISECONDS.toNanos(LIFECYCLE_WAIT_MS);
        synchronized (MONITOR) {
            while (c.domain.pending.get() != 0) {
                if (System.nanoTime() > deadline) {
                    blockChangeWaitTimeouts.increment();
                    if (blockChangeWaitTimeouts.sum() <= 20) LOGGER.warn("ExecutionDomains: lifecycle wait timeout ({} ms) for owned {} {} on {} (domain '{}' pending={})",
                            LIFECYCLE_WAIT_MS, level.dimension().location(), pos.toShortString(), Thread.currentThread().getName(), c.domain.name, c.domain.pending.get());
                    break;
                }
                serviceMainCalls();                   // the domain may be parked on a synchronous MAIN call (MVP-001)
                if (c.domain.pending.get() == 0) break;
                try { MONITOR.wait(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        long us = (System.nanoTime() - t0) / 1000;
        LIFECYCLE_WAIT_US[Math.min(LIFECYCLE_WAIT_US.length - 1, 64 - Long.numberOfLeadingZeros(Math.max(1, us)))].increment();
        lifecycleWaitMaxUs.accumulateAndGet(us, Math::max);
    }

    static final long LIFECYCLE_WAIT_MS = Long.getLong("nestworld.domains.lifecycleWaitMs", 250);

    /** log2 buckets of lifecycle (block-change) wait, microseconds: [i] = up to 2^i µs. */
    private static final LongAdder[] LIFECYCLE_WAIT_US = new LongAdder[24];
    static { for (int i = 0; i < LIFECYCLE_WAIT_US.length; i++) LIFECYCLE_WAIT_US[i] = new LongAdder(); }
    static final AtomicLong lifecycleWaitMaxUs = new AtomicLong();

    private static String lifecycleWaitPercentile(double q) {
        long total = 0;
        for (LongAdder a : LIFECYCLE_WAIT_US) total += a.sum();
        if (total == 0) return "-";
        long need = (long) Math.ceil(total * q), acc = 0;
        for (int i = 0; i < LIFECYCLE_WAIT_US.length; i++) {
            acc += LIFECYCLE_WAIT_US[i].sum();
            if (acc >= need) return "<=" + (1L << i) + "us";
        }
        return ">" + (1L << (LIFECYCLE_WAIT_US.length - 1)) + "us";
    }

    /** Barrier (MAIN, all domains idle): apply pending claim/release requests and publish a new epoch. */
    private static void applyRequests() {
        if (REQUESTS.isEmpty()) return;
        Snapshot snap = SNAP;
        var copy = copyOf(snap);
        boolean changed = false;
        for (Request r; (r = REQUESTS.poll()) != null; ) {
            var lm = copy.computeIfAbsent(r.level, k -> new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>());
            for (int i = 0; i < r.positions.length; i++) {
                long p = r.positions[i];
                Claim cur = lm.get(p);
                if (r.claim) {
                    if (cur != null && cur.domain != r.domain) { refusedClaims.increment(); continue; }
                    if (cur != null && !cur.typeId.equals(r.types[i])) { refusedClaims.increment(); continue; }
                    lm.put(p, new Claim(r.domain, r.types[i], cur == null ? 1 : cur.refs + 1));   // ref-counted per domain
                    changed = true;
                } else if (cur != null && cur.domain == r.domain) {
                    if (cur.refs > 1) lm.put(p, new Claim(cur.domain, cur.typeId, cur.refs - 1));
                    else lm.remove(p);
                    changed = true;
                }
            }
        }
        if (changed) SNAP = new Snapshot(snap.epoch + 1, freeze(copy));
    }

    private static Map<ResourceKey<Level>, it.unimi.dsi.fastutil.longs.Long2ObjectMap<Claim>> copyOf(Snapshot s) {
        Map<ResourceKey<Level>, it.unimi.dsi.fastutil.longs.Long2ObjectMap<Claim>> m = new java.util.HashMap<>();
        for (var e : s.byLevel.entrySet()) m.put(e.getKey(), new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(e.getValue()));
        return m;
    }

    private static Map<ResourceKey<Level>, it.unimi.dsi.fastutil.longs.Long2ObjectMap<Claim>> freeze(
            Map<ResourceKey<Level>, it.unimi.dsi.fastutil.longs.Long2ObjectMap<Claim>> m) {
        m.values().removeIf(it.unimi.dsi.fastutil.longs.Long2ObjectMap::isEmpty);
        return Map.copyOf(m);
    }

    static String ownershipStatus() {
        Snapshot s = SNAP;
        int owned = 0;
        for (var m : s.byLevel.values()) owned += m.size();
        return "ownership epoch=" + s.epoch + " owned=" + owned + " diverted=" + diverted.sum() + " domainTicked="
                + domainTicked.sum() + " removedSkipped=" + removedSkipped.sum() + " typeMismatch=" + typeMismatch.sum() + " refusedClaims=" + refusedClaims.sum()
                + " unloadReleases=" + unloadReleases.sum() + " lifecycleWait{attempts=" + blockChangeWaits.sum() + " timeouts="
                + blockChangeWaitTimeouts.sum() + " p95=" + lifecycleWaitPercentile(0.95) + " max=" + lifecycleWaitMaxUs.get() + "us}"
                + " fallbackInline=" + divertFallbackInline.sum() + " inFlight=" + inFlight.sum()
                + " tickFailures=" + tickFailures.sum() + " conservation(diverted-domainTicked-removedSkipped-tickFailures-inFlight)="
                + (diverted.sum() - domainTicked.sum() - removedSkipped.sum() - tickFailures.sum() - inFlight.sum());
    }

    // ---------------------------------------------------------------- one domain

    private static final class Domain implements DomainHandle {
        private record Job(Set<ResourceKey<Level>> levels, boolean exclusive, Runnable body) {}

        private final String name;
        private final MinecraftServer server;
        private final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<>();
        final AtomicInteger pending = new AtomicInteger();
        private final Thread thread;
        private volatile boolean stopped;
        private final LongAdder jobs = new LongAdder(), failures = new LongAdder(), refused = new LongAdder(), jobNanos = new LongAdder(),
                ownedJobs = new LongAdder();

        Domain(String name, MinecraftServer server) {
            this.name = name;
            this.server = server;
            this.thread = new Thread(this::loop, "NestWorld-Domain-" + name);
            this.thread.setDaemon(true);
            this.thread.start();
            LOGGER.info("ExecutionDomain '{}' started", name);
        }

        @Override
        public boolean submit(Set<ResourceKey<Level>> touchedLevels, Runnable job) {
            return enqueue(touchedLevels, true, job);
        }

        @Override
        public boolean submitOwned(Set<ResourceKey<Level>> touchedLevels, Runnable job) {
            boolean ok = enqueue(touchedLevels, false, job);
            if (ok) ownedJobs.increment();
            return ok;
        }

        private boolean enqueue(Set<ResourceKey<Level>> touchedLevels, boolean exclusive, Runnable job) {
            if (stopped || !server.isSameThread() || touchedLevels == null || touchedLevels.isEmpty()) {
                refused.increment();
                return false;
            }
            Set<ResourceKey<Level>> levels = Set.copyOf(touchedLevels);
            if (exclusive) for (ResourceKey<Level> k : levels) PENDING_BY_LEVEL.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet();
            PENDING_TOTAL.incrementAndGet();
            pending.incrementAndGet();
            queue.add(new Job(levels, exclusive, job));
            return true;
        }

        @Override
        public void awaitAll() {
            if (pending.get() != 0) await(() -> pending.get() == 0);
            if (server.isSameThread() && !REQUESTS.isEmpty()) {
                awaitAllDomains();                       // barrier: every domain idle before ownership changes
                applyRequests();
            }
        }

        @Override
        public void claim(ResourceKey<Level> level, long[] positions, String[] beTypeIds) {
            if (positions.length != beTypeIds.length) throw new IllegalArgumentException("positions/types length");
            REQUESTS.add(new Request(this, level, positions.clone(), beTypeIds.clone(), true));
        }

        @Override
        public void release(ResourceKey<Level> level, long[] positions) {
            REQUESTS.add(new Request(this, level, positions.clone(), null, false));
        }

        @Override
        public long ownershipEpoch() {
            return SNAP.epoch;
        }

        @Override
        public boolean owns(ResourceKey<Level> level, long pos) {
            var m = SNAP.byLevel.get(level);
            Claim c = m == null ? null : m.get(pos);
            return c != null && c.domain == this;
        }

        @Override
        public boolean isDomainThread() {
            return Thread.currentThread() == thread;
        }

        private void loop() {
            while (!stopped) {
                Job j;
                try { j = queue.poll(250, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { return; }
                if (j == null) continue;
                long t0 = System.nanoTime();
                LEASE.set(j.levels);
                JOB_OWNED.set(!j.exclusive);
                try {
                    j.body.run();
                    jobs.increment();
                } catch (Throwable t) {
                    failures.increment();
                    LOGGER.error("ExecutionDomain '{}': job failed", name, t);
                } finally {
                    LEASE.remove();
                    long jd = System.nanoTime() - t0;
                    jobNanos.add(jd);
                    DomainTickMetrics.worker(jd, !j.exclusive);
                    if (j.exclusive) for (ResourceKey<Level> k : j.levels) PENDING_BY_LEVEL.get(k).decrementAndGet();
                    pending.decrementAndGet();
                    PENDING_TOTAL.decrementAndGet();
                    synchronized (MONITOR) { MONITOR.notifyAll(); }
                }
            }
        }

        void stop() {
            stopped = true;
            thread.interrupt();
        }

        @Override
        public String stats() {
            return "domain '" + name + "' jobs=" + jobs.sum() + " owned=" + ownedJobs.sum() + " failures=" + failures.sum()
                    + " refused=" + refused.sum() + " pending=" + pending.get() + " jobMs=" + jobNanos.sum() / 1_000_000
                    + " | " + ownershipStatus() + " | " + worldOpsStatus();
        }
    }
}
