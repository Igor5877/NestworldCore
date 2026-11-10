package net.nestworld.compat.ae2.glue;

import appeng.api.behaviors.StackExportStrategy;
import appeng.api.behaviors.StackImportStrategy;
import appeng.api.config.Actionable;
import appeng.api.config.SchedulingMode;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.IPartHost;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.blockentity.networking.CableBusBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.me.Grid;
import appeng.me.service.helpers.TickTracker;
import appeng.parts.automation.ExportBusPart;
import appeng.parts.automation.IOBusPart;
import appeng.parts.automation.ImportBusPart;
import appeng.parts.automation.StackImportFacade;
import appeng.parts.automation.StackExportFacade;
import appeng.parts.automation.StackWorldBehaviors;
import appeng.util.prioritylist.IPartitionList;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.nestworld.compat.ae2.mixin.BusTransitTmAccess;
import net.nestworld.compat.ae2.mixin.ExportBusPartAccessor;
import net.nestworld.compat.ae2.mixin.ImportBusPartAccessor;
import net.nestworld.compat.ae2.mixin.StackExportFacadeAccessor;
import net.nestworld.compat.ae2.mixin.StackImportFacadeAccessor;
import net.nestworld.compat.ae2.mixin.TickManagerServiceAccessor;

/**
 * AE2-DEMAIN-001 B4–B6 = WP3-B bus transit (design: docs/compat/AE2-DEMAIN-001.md "B4–B6").
 *
 * <p>AE2's TickManagerService keeps scheduling every import / export bus operation on MAIN, unchanged. For an
 * eligible operation of the exact classes {@link ImportBusPart} / {@link ExportBusPart}, {@code doBusWork} is replaced
 * by a transfer record: the neighbour half runs in the NEIGHBOUR's block-entity execution domain (Core
 * {@code enqueueForBlockEntityDomain}), the network half on MAIN in a per-grid commit scheduler (GRID_ISOLATION) at the
 * end of the server tick. The operation's FASTER / SLOWER is applied to the same AE2 tracker once the result is known.
 *
 * <p>Monitors: a record's monitor and nothing else — a leaf, held only for O(1) bookkeeping, never across AE2,
 * Core, foreign code or logging. No thread ever waits for another.
 */
public final class BusTransit {
    /** Feature flag (default OFF): {@code -Dnestworld.compatAe2.busTransit=true}. */
    public static final boolean FLAG = Boolean.getBoolean("nestworld.compatAe2.busTransit");
    /** Runtime switch; only the fixtures flag can change it (negative control). */
    public static volatile boolean active = FLAG;
    public static final String NBT_KEY = "nwTransit";
    public static final int INLINE_NOWORK = 0, INLINE_WORK = 1, DEFERRED = 2;
    /** Total MAIN commit budget per server tick (ns); every grid gets a fair quantum, at least one entry. */
    public static volatile long budgetNs = Long.getLong("nestworld.compatAe2.busTransitBudgetUs", 20_000L) * 1000L;
    static final int DROP_AFTER_TICKS = 200;

    // fixture-only switches (set by BusTransitFixtures when -Dnestworld.compatAe2.fixtures=true)
    public static volatile boolean fxHold, fxRecoveryOff, fxFifo;

    // ---------------------------------------------------------------- phases / results
    static final int PREPARED = 0, RUNNING = 1, PROBED = 2, HELD_MAIN = 3, HELD_REGION = 4, FINALIZE = 5, DONE = 9;
    static final int R_NONE = 0, R_COMMITTED = 1, R_NOWORK = 2, R_CANCELLED = 3, R_RECOVERED = 4, R_SEALED_NBT = 5,
            R_DUPLICATE = 6, R_VOIDED_NEG = 7;
    static final String[] RESULT = {"none", "COMMITTED", "COMMITTED_NOWORK", "CANCELLED", "RECOVERED", "SEALED_TO_NBT",
            "DUPLICATE", "VOIDED(negative control)"};

    private static final SecureRandom IDS = new SecureRandom();

    /** Session set of transfer ids that have (had) an NBT copy and are owned live or terminal (B6 dedup); bounded by persisted records. */
    static final Set<Long> CLAIMED = ConcurrentHashMap.newKeySet();

    // ---------------------------------------------------------------- counters (global)
    public static final class Counters {
        public final LongAdder scheduled = new LongAdder(), opsTransitImport = new LongAdder(), opsTransitExport = new LongAdder();
        public final LongAdder inlineInactive = new LongAdder(), inlineClass = new LongAdder(), inlineCrafting = new LongAdder(),
                inlineFuzzy = new LongAdder(), inlineRandom = new LongAdder(), inlineStrategy = new LongAdder(),
                inlineMainNeighbour = new LongAdder(), inlineNoNeighbourBe = new LongAdder(), inlineOverlapRisk = new LongAdder(),
                exportNoWorkMain = new LongAdder(), commitFailures = new LongAdder(), returnFailed = new LongAdder(),
                stuckNonItem = new LongAdder(), stopWaitTimeout = new LongAdder(), stopWaitNanos = new LongAdder(), inlineLinkedCrossDomain = new LongAdder(), transitLinkedSameDomain = new LongAdder();
        public final LongAdder mainOpNsInline = new LongAdder(), mainOpNsTransit = new LongAdder(), commitNs = new LongAdder(),
                regionNs = new LongAdder();
        public final LongAdder mainOpsInline = new LongAdder(), mainOpsTransit = new LongAdder();
        public final LongAdder modApplied = new LongAdder(), modLate = new LongAdder(), modStale = new LongAdder(),
                rrApplied = new LongAdder(), rrLate = new LongAdder();
        public final LongAdder completed = new LongAdder(), cancelled = new LongAdder(), missed = new LongAdder(),
                enqueueFailed = new LongAdder(), coreTerminal = new LongAdder(), regionWrongDomain = new LongAdder(),
                regionNotTicking = new LongAdder(), regionFailures = new LongAdder(), regionEnergyCalls = new LongAdder(),
                regionStorageMisuse = new LongAdder(), staleRegionStep = new LongAdder(), gridChanged = new LongAdder();
        public final LongAdder importRefusedReturned = new LongAdder(), exportLeftoverReturned = new LongAdder();
        public final LongAdder orphaned = new LongAdder(), recoveredNetwork = new LongAdder(), recoveredNeighbour = new LongAdder(),
                recoveredDrop = new LongAdder(), sealedToNbt = new LongAdder(), persisted = new LongAdder(), loaded = new LongAdder(),
                dedupDropped = new LongAdder(), voidedNegative = new LongAdder(), sealDirtyInconsistent = new LongAdder(),
                savedInflight = new LongAdder();
        // ledger (raw amounts): import: fromSource = toNetwork + backToSource + dropped + held; export likewise
        public final LongAdder impFromSource = new LongAdder(), impToNetwork = new LongAdder(), impBackToSource = new LongAdder(),
                expFromNetwork = new LongAdder(), expToNeighbour = new LongAdder(), expBackToNetwork = new LongAdder(),
                dropped = new LongAdder(), loadedHeld = new LongAdder(), sealedHeld = new LongAdder(), voidedHeld = new LongAdder();
        public final AtomicLong outstanding = new AtomicLong();
    }

    public static final Counters C = new Counters();

    // ---------------------------------------------------------------- per-bus state

    /** Per-bus transit state (lives in the part, {@code NwTransitBus}). */
    public static final class BusState {
        int incarnation;
        volatile boolean inWorld;
        long opSeq;
        /** Non-terminal records of this bus (MAIN adds / removes; snapshot under the list monitor). */
        final List<Rec> live = Collections.synchronizedList(new ArrayList<>());
        List<CompoundTag> loaded;
        long[] pin;
        ServerLevel pinLevel;
        final AtomicInteger regionRunning = new AtomicInteger();
        long saveSeq;

        public BusState() {
            STATES.add(this);
        }
    }

    // ---------------------------------------------------------------- transfer record

    static final class Rec {
        final long id;
        final boolean export;
        final IOBusPart bus;
        final int incarnation;
        final long opSeq;
        final Grid gridAtOp;
        final GridState gs;
        final ServerLevel level;
        final BlockPos hostPos, nPos;
        final Direction busSide, fromSide;
        final String nType;
        final IActionSource source;
        final long opTick;
        final int opServerTick;
        final TickTracker tt;
        final long ttGen;
        final IGridNode node;
        final boolean recoveryFromNbt;
        // import
        StackImportStrategy importStrategy;
        int ops;
        IPartitionList filter;
        boolean inverted;
        // export
        StackExportStrategy exportStrategy;
        AEKey[] keys;          // per loop index x (null = empty slot / not available in the network)
        long[] cap;            // probed capacity per x (by key, first occurrence)
        int availableSlots;
        SchedulingMode scheduling;
        int x;                 // AE2 loop counter at exit (round robin)
        StackExportStrategy pushFacade; // import returns / recovery pushes (region-created lazily)
        // state (guarded by this)
        int phase;
        int result = R_NONE;
        boolean orphan;        // bus left the world: the recovery owner drives it
        boolean modDone;
        boolean dirtySinceSave;
        long savedSeq = -1;
        int pushes, mainSteps;
        int firstHeldTick = -1;
        final LinkedHashMap<AEKey, Long> held = new LinkedHashMap<>();
        final LinkedHashMap<AEKey, Long> inflight = new LinkedHashMap<>();
        long extracted, committed, returned, droppedAmt;

        Rec(long id, boolean export, IOBusPart bus, BusState bs, Grid grid, GridState gs, ServerLevel level, BlockPos hostPos,
            Direction busSide, String nType, IActionSource source, long opTick, int opServerTick, TickTracker tt, long ttGen,
            IGridNode node, boolean recoveryFromNbt) {
            this.id = id;
            this.export = export;
            this.bus = bus;
            this.incarnation = bs.incarnation;
            this.opSeq = bs.opSeq;
            this.gridAtOp = grid;
            this.gs = gs;
            this.level = level;
            this.hostPos = hostPos;
            this.busSide = busSide;
            this.nPos = hostPos.relative(busSide);
            this.fromSide = busSide.getOpposite();
            this.nType = nType;
            this.source = source;
            this.opTick = opTick;
            this.opServerTick = opServerTick;
            this.tt = tt;
            this.ttGen = ttGen;
            this.node = node;
            this.recoveryFromNbt = recoveryFromNbt;
        }

        long heldTotal() {
            long s = 0;
            for (long v : held.values()) s += v;
            return s;
        }

        long inflightTotal() {
            long s = 0;
            for (long v : inflight.values()) s += v;
            return s;
        }

        void addHeld(AEKey k, long v, int tick) {
            if (v <= 0) return;
            held.merge(k, v, Long::sum);
            if (firstHeldTick < 0) firstHeldTick = tick;
        }

        void subHeld(AEKey k, long v) {
            if (v <= 0) return;
            long n = held.getOrDefault(k, 0L) - v;
            if (n <= 0) held.remove(k);
            else held.put(k, n);
        }
    }

    // ---------------------------------------------------------------- per-grid state (GRID_ISOLATION)

    static final class GridState {
        final String id;
        final java.lang.ref.WeakReference<Grid> grid;
        final ConcurrentLinkedQueue<Rec> inbox = new ConcurrentLinkedQueue<>();
        final ArrayDeque<Rec> pending = new ArrayDeque<>();          // MAIN only
        final AtomicBoolean inReady = new AtomicBoolean();
        final LongAdder scheduled = new LongAdder(), completed = new LongAdder(), missed = new LongAdder(), cancelled = new LongAdder();
        final AtomicLong outstanding = new AtomicLong();
        long mainCommitNs, mainOpNs, budgetExhausted, starvationTicks, maxBacklog;   // MAIN only
        final long[] latImport = new long[66], latExport = new long[66];          // MAIN only; [65] = >64
        long recoveryDone;

        GridState(String id, Grid g) {
            this.id = id;
            this.grid = new java.lang.ref.WeakReference<>(g);
        }
    }

    private static final Map<Grid, GridState> GRIDS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final List<GridState> ALL_GRIDS = Collections.synchronizedList(new ArrayList<>());
    static final GridState ORPHANS = new GridState("orphans", null);
    private static final ConcurrentLinkedQueue<GridState> READY = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Rec> FIFO = new ConcurrentLinkedQueue<>();   // negative control only
    private static final ArrayDeque<Rec> FIFO_PENDING = new ArrayDeque<>();
    private static int rotation;
    private static volatile boolean stopping;

    static {
        ALL_GRIDS.add(ORPHANS);
    }

    static GridState gridState(Grid g) {
        if (g == null) return ORPHANS;
        synchronized (GRIDS) {
            GridState s = GRIDS.get(g);
            if (s == null) {
                s = new GridState(g.toString(), g);
                GRIDS.put(g, s);
                ALL_GRIDS.add(s);
            }
            return s;
        }
    }

    private BusTransit() {
    }

    // ---------------------------------------------------------------- Core domain resolution (reflective)

    private static final MethodHandle RESOLVE, IS_MAIN;

    static {
        MethodHandle r = null, m = null;
        try {
            ClassLoader cl = BusTransit.class.getClassLoader();
            Class<?> d = Class.forName("net.nestworld.region.BlockEntityExecutionDomain", true, cl);
            Class<?> dom = Class.forName("net.nestworld.region.BlockEntityExecutionDomain$Domain", true, cl);
            r = MethodHandles.publicLookup().findStatic(d, "resolveBlockEntityExecutionDomain",
                    MethodType.methodType(dom, ServerLevel.class, BlockPos.class, String.class));
            m = MethodHandles.publicLookup().findVirtual(dom, "isMain", MethodType.methodType(boolean.class));
            r = r.asType(MethodType.methodType(Object.class, ServerLevel.class, BlockPos.class, String.class));
            m = m.asType(MethodType.methodType(boolean.class, Object.class));
        } catch (Throwable ignored) {
            r = null;
            m = null;
        }
        RESOLVE = r;
        IS_MAIN = m;
    }

    /** True when work at {@code pos} (BE type {@code type}) belongs to MAIN now; true (fail-closed) when unknown. */
    static boolean domainIsMain(ServerLevel level, BlockPos pos, String type) {
        if (RESOLVE == null) return true;
        try {
            return (boolean) IS_MAIN.invokeExact((Object) RESOLVE.invokeExact(level, pos, type));
        } catch (Throwable t) {
            return true;
        }
    }

    private static final Map<Block, String> BE_TYPE_OF_BLOCK = new ConcurrentHashMap<>();
    private static final String NO_BE = "";

    /** BE type id expected at {@code pos} from its block state (no BE map read off its domain); null = no BE. */
    static String expectedBeType(ServerLevel level, BlockPos pos) {
        LevelChunk ch = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (ch == null) return null;
        BlockState st = ch.getBlockState(pos);
        if (!st.hasBlockEntity()) return null;
        String t = BE_TYPE_OF_BLOCK.computeIfAbsent(st.getBlock(), b -> {
            for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
                if (type.isValid(b.defaultBlockState())) return String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type));
            }
            return NO_BE;
        });
        return t.isEmpty() ? null : t;
    }

    // ---------------------------------------------------------------- linked neighbours (multiblock ports)

    /**
     * Neighbour BE namespaces whose capability state is shared with ANOTHER block entity (MasterfulMachinery ports: the
     * controller mutates the port handler from its own tick; the port has no back-pointer to its controller). Transit
     * is allowed only when the whole surrounding area (radius) is the port's own region domain and no BE type of the
     * namespace is pinned per type; otherwise fail-closed = AE2 reference on MAIN (counted inlineLinkedCrossDomain).
     * Live note 2026-11-10 00:49 (mm ItemPortHandler.reindexSlot CME).
     */
    static final Set<String> LINKED_NAMESPACES = Set.of(System.getProperty("nestworld.compatAe2.busTransitLinked", "mm").split(","));
    static final int LINKED_RADIUS = Integer.getInteger("nestworld.compatAe2.busTransitLinkedRadius", 32);
    private static final Map<String, Boolean> NS_TYPE_PINNED = new ConcurrentHashMap<>();
    private static volatile int nsPinnedTick = Integer.MIN_VALUE;

    static boolean isLinked(String type) {
        int c = type.indexOf(':');
        return c > 0 && LINKED_NAMESPACES.contains(type.substring(0, c));
    }

    static boolean namespaceHasTypePin(ServerLevel level, String ns) {
        int now = level.getServer().getTickCount();
        if (now - nsPinnedTick > 200 || now < nsPinnedTick) {
            NS_TYPE_PINNED.clear();
            nsPinnedTick = now;
        }
        return NS_TYPE_PINNED.computeIfAbsent(ns, n -> {
            for (var key : BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet()) {
                if (key.getNamespace().equals(n) && BeDomainReady.pinnedByPolicy(key.toString())) return true;
            }
            return false;
        });
    }

    static boolean linkedSameDomain(ServerLevel level, BlockPos port, String type) {
        if (RESOLVE == null) return false;
        if (namespaceHasTypePin(level, type.substring(0, type.indexOf(':')))) return false;
        try {
            Object d = RESOLVE.invokeExact(level, port, type);
            int r = LINKED_RADIUS;
            for (int dx = -r; dx <= r; dx += 16) {
                for (int dz = -r; dz <= r; dz += 16) {
                    for (int dy = -r; dy <= r; dy += Math.max(1, r)) {
                        Object o = RESOLVE.invokeExact(level, port.offset(dx, dy, dz), (String) null);
                        if (!d.equals(o)) return false;
                    }
                }
            }
            // the exact corners of the radius box too (chunk steps may skip the last partial chunk)
            for (int sx : new int[]{-r, r}) for (int sz : new int[]{-r, r}) {
                Object o = RESOLVE.invokeExact(level, port.offset(sx, 0, sz), (String) null);
                if (!d.equals(o)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean blockEntitiesTicking(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING);
    }

    static void markUnsaved(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) chunk.setUnsaved(true);
    }

    // ---------------------------------------------------------------- eligibility

    public static boolean manages(Object part) {
        return FLAG && part != null && (part.getClass() == ImportBusPart.class || part.getClass() == ExportBusPart.class);
    }

    static final Set<String> IMPORT_STRATEGIES = Set.of(
            "appeng.parts.automation.StorageImportStrategy",
            "me.ramidzkh.mekae2.ae2.stack.MekanismStackImportStrategy",
            "appbot.ae2.ManaStorageImportStrategy");
    static final Set<String> EXPORT_STRATEGIES = Set.of(
            "appeng.parts.automation.StorageExportStrategy",
            "me.ramidzkh.mekae2.ae2.stack.MekanismStackExportStrategy",
            "appbot.ae2.ManaStorageExportStrategy");

    /** Config-only eligibility (no domain check); null = eligible, else the counter of the reason. */
    static LongAdder configIneligible(IOBusPart bus) {
        if (!active) return C.inlineInactive;
        if (!manages(bus)) return C.inlineClass;
        if (bus.isUpgradedWith(AEItems.FUZZY_CARD)) return C.inlineFuzzy;
        if (bus instanceof ExportBusPart ex) {
            if (bus.isUpgradedWith(AEItems.CRAFTING_CARD) || ex.getConfigManager().getSetting(Settings.CRAFT_ONLY) == YesNo.YES
                    || !ex.getRequestedJobs().isEmpty()) return C.inlineCrafting;
            if (ex.getConfigManager().getSetting(Settings.SCHEDULING_MODE) == SchedulingMode.RANDOM) return C.inlineRandom;
        }
        return null;
    }

    static boolean strategiesAllowed(IOBusPart bus) {
        if (bus instanceof ImportBusPart ib) {
            StackImportStrategy s = importFacade(ib);
            if (!(s instanceof StackImportFacade f)) return false;
            for (var st : ((StackImportFacadeAccessor) f).nw$strategies()) {
                if (!IMPORT_STRATEGIES.contains(st.getClass().getName())) return false;
            }
            return true;
        }
        StackExportStrategy s = ((ExportBusPartAccessor) bus).nw$exportStrategy();
        if (!(s instanceof StackExportFacade f)) return false;
        for (var st : ((StackExportFacadeAccessor) f).nw$strategies()) {
            if (!EXPORT_STRATEGIES.contains(st.getClass().getName())) return false;
        }
        return true;
    }

    /** The bus's own import facade, created exactly as {@code ImportBusPart.doBusWork} does (MAIN). */
    static StackImportStrategy importFacade(ImportBusPart ib) {
        ImportBusPartAccessor a = (ImportBusPartAccessor) ib;
        StackImportStrategy s = a.nw$importStrategy();
        if (s == null) {
            var self = ib.getHost().getBlockEntity();
            s = StackWorldBehaviors.createImportFacade((ServerLevel) ib.getLevel(), self.getBlockPos().relative(ib.getSide()),
                    ib.getSide().getOpposite());
            a.nw$setImportStrategy(s);
        }
        return s;
    }

    // ---------------------------------------------------------------- neighbour affinity (dynamic for the two classes)

    static void ensurePinned(IOBusPart bus, BusState bs) {
        if (bs.pin != null || !(bus.getLevel() instanceof ServerLevel level) || bus.getHost() == null) return;
        long[] positions = {bus.getHost().getBlockEntity().getBlockPos().relative(bus.getSide()).asLong()};
        if (NeighborAffinity.pin(level, positions)) {
            bs.pin = positions;
            bs.pinLevel = level;
        }
    }

    static void releasePin(BusState bs) {
        if (bs.pin == null) return;
        NeighborAffinity.release(bs.pinLevel, bs.pin);
        bs.pin = null;
        bs.pinLevel = null;
    }

    public static void onAddToWorld(IOBusPart bus) {
        BusState bs = ((NwTransitBus) bus).nw$transit();
        bs.incarnation++;
        bs.inWorld = true;
        if (configIneligible(bus) != null) ensurePinned(bus, bs);   // MAIN may reach the neighbour (crafting push etc.)
        List<CompoundTag> loaded = bs.loaded;
        bs.loaded = null;
        if (loaded != null && bus.getLevel() instanceof ServerLevel level && bus.getHost() != null) {
            int tick = level.getServer().getTickCount();
            for (CompoundTag t : loaded) adoptLoaded(bus, bs, level, t, tick);
        }
    }

    // ---------------------------------------------------------------- operation entry (MAIN, AE2 tick pass)

    /**
     * Replaces one {@code doBusWork}. Returns {@link #DEFERRED} for a transit operation, else the inline result
     * (AE2's own {@code doBusWork}).
     */
    public static int onBusWork(IOBusPart bus, IGrid igrid, Supplier<Boolean> inline) {
        long t0 = System.nanoTime();
        C.scheduled.increment();
        BusState bs = ((NwTransitBus) bus).nw$transit();
        bs.opSeq++;
        Grid grid = igrid instanceof Grid g ? g : null;
        GridState gs = gridState(grid);
        gs.scheduled.increment();
        LongAdder why = configIneligible(bus);
        ServerLevel level = bus.getLevel() instanceof ServerLevel l ? l : null;
        String nType = null;
        BlockPos hostPos = null;
        if (why == null) {
            if (level == null || bus.getHost() == null || grid == null) why = C.inlineClass;
            else if (!strategiesAllowed(bus)) why = C.inlineStrategy;
            else {
                hostPos = bus.getHost().getBlockEntity().getBlockPos();
                BlockPos nPos = hostPos.relative(bus.getSide());
                nType = expectedBeType(level, nPos);
                if (nType == null) why = C.inlineNoNeighbourBe;
                else {
                    if (bs.pin != null) releasePin(bs);
                    if (domainIsMain(level, nPos, nType)) why = C.inlineMainNeighbour;
                    else if (isLinked(nType)) {
                        if (linkedSameDomain(level, nPos, nType)) C.transitLinkedSameDomain.increment();
                        else why = C.inlineLinkedCrossDomain;
                    }
                }
            }
        }
        if (why != null) {
            why.increment();
            if (why != C.inlineMainNeighbour && why != C.inlineNoNeighbourBe) ensurePinned(bus, bs);
            if (bs.regionRunning.get() > 0) C.inlineOverlapRisk.increment();
            boolean work;
            try {
                work = inline.get();
            } finally {
                long d = System.nanoTime() - t0;
                C.mainOpNsInline.add(d);
                C.mainOpsInline.increment();
                gs.mainOpNs += d;
                gs.completed.increment();
                C.completed.increment();
            }
            return work ? INLINE_WORK : INLINE_NOWORK;
        }
        try {
            var tm = grid.getTickManager();
            TickTracker tt = ((NwTickManager) tm).nw$suspendedTt();
            long gen = tt == null ? -1 : ((NwTickTracker) tt).nw$gen();
            long opTick = ((BusTransitTmAccess) tm).nw$currentTick();
            int st = level.getServer().getTickCount();
            boolean export = bus instanceof ExportBusPart;
            Rec r = new Rec(newId(), export, bus, bs, grid, gs, level, hostPos, bus.getSide(), nType,
                    ((NwTransitBus) bus).nw$source(), opTick, st, tt, gen, bus.getGridNode(), false);
            if (export) {
                if (!prepareExport((ExportBusPart) bus, grid, r)) {
                    // nothing configured / nothing extractable: AE2's loop moves nothing → its own SLOWER, no neighbour access
                    C.exportNoWorkMain.increment();
                    C.completed.increment();
                    gs.completed.increment();
                    return INLINE_NOWORK;
                }
                C.opsTransitExport.increment();
            } else {
                r.importStrategy = importFacade((ImportBusPart) bus);
                r.ops = ((NwTransitBus) bus).nw$opsPerTick();
                r.filter = ((NwTransitBus) bus).nw$filter();
                r.inverted = bus.isUpgradedWith(AEItems.INVERTER_CARD);
                C.opsTransitImport.increment();
            }
            bs.live.add(r);
            C.outstanding.incrementAndGet();
            gs.outstanding.incrementAndGet();
            enqueueRegion(r, export ? BusTransit::regionProbe : BusTransit::regionImport, true);
            return DEFERRED;
        } finally {
            long d = System.nanoTime() - t0;
            C.mainOpNsTransit.add(d);
            C.mainOpsTransit.increment();
            gs.mainOpNs += d;
        }
    }

    static long newId() {
        long id;
        do {
            id = IDS.nextLong() & Long.MAX_VALUE;
        } while (id == 0 || CLAIMED.contains(id));
        return id;
    }

    /** Export op on MAIN: AE2's slot order and network availability (no neighbour access). */
    static boolean prepareExport(ExportBusPart bus, Grid grid, Rec r) {
        NwTransitBus nb = (NwTransitBus) bus;
        r.exportStrategy = ((ExportBusPartAccessor) bus).nw$exportStrategy();
        r.ops = nb.nw$opsPerTick();
        r.availableSlots = nb.nw$availableSlots();
        r.scheduling = bus.getConfigManager().getSetting(Settings.SCHEDULING_MODE);
        int next = ((ExportBusPartAccessor) bus).nw$nextSlot();
        MEStorage inv = grid.getStorageService().getInventory();
        IEnergySource energy = grid.getEnergyService();
        r.keys = new AEKey[r.availableSlots];
        r.cap = new long[r.availableSlots];
        boolean any = false;
        for (int x = 0; x < r.availableSlots; x++) {
            int slot = r.scheduling == SchedulingMode.ROUNDROBIN ? (next + x) % r.availableSlots : x;
            AEKey what = bus.getConfig().getKey(slot);
            if (what == null) continue;
            long amount = (long) r.ops * what.getAmountPerOperation();
            if (StorageHelper.poweredExtraction(energy, inv, what, amount, r.source, Actionable.SIMULATE) <= 0) continue;
            r.keys[x] = what;
            any = true;
        }
        return any;
    }

    // ---------------------------------------------------------------- region steps (neighbour domain)

    interface Step {
        void run(Rec r);
    }

    static void enqueueRegion(Rec r, Step step, boolean first) {
        if (!BeDomainReady.primitive()) {
            // no Core primitive: cannot happen with transit eligible (domainIsMain fail-closed) — keep MAIN semantics
            C.enqueueFailed.increment();
            onCoreTerminal(r, first);
            return;
        }
        Consumer<Object> onTerminal = kind -> {
            C.coreTerminal.increment();
            onCoreTerminal(r, first);
        };
        try {
            BeDomainReady.enqueueTo(r.level, r.nPos, r.nType, () -> runStep(r, step), onTerminal);
        } catch (Throwable t) {
            C.enqueueFailed.increment();
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] bus transit enqueue failed at {}: {}", r.nPos.toShortString(), t.toString());
            onCoreTerminal(r, first);
        }
    }

    /** MAIN (Core onTerminal / enqueue failure): the step never ran. */
    static void onCoreTerminal(Rec r, boolean first) {
        boolean cancel;
        synchronized (r) {
            cancel = r.phase == PREPARED;
            if (cancel) {
                r.phase = DONE;
                r.result = R_CANCELLED;
            } else if (r.phase == HELD_REGION) {
                r.phase = HELD_MAIN;          // held stays; the network side retries
            }
        }
        if (cancel) {
            BusState bs = ((NwTransitBus) r.bus).nw$transit();
            if (bs.inWorld && bs.incarnation == r.incarnation) {
                C.missed.increment();
                r.gs.missed.increment();
            }
        }
        publish(r);
    }

    static void runStep(Rec r, Step step) {
        long t0 = System.nanoTime();
        BusState bs = ((NwTransitBus) r.bus).nw$transit();
        bs.regionRunning.incrementAndGet();
        REGION_STEPS.incrementAndGet();
        try {
            if (stopping) {            // a step that starts after the stop began does nothing (state unchanged)
                C.regionNotTicking.increment();
                synchronized (r) {
                    if (r.phase == HELD_REGION) r.phase = HELD_MAIN;
                }
                return;
            }
            step.run(r);
        } catch (Throwable t) {
            C.regionFailures.increment();
            Ae2Compat.LOG.error("[nestworld-compat-ae2] bus transit region step failed at {} on {}", r.nPos.toShortString(),
                    Thread.currentThread().getName(), t);
            synchronized (r) {
                if (r.phase == RUNNING) r.phase = r.export ? PROBED : (r.held.isEmpty() ? FINALIZE : HELD_MAIN);
                else if (r.phase == HELD_REGION) r.phase = HELD_MAIN;
                if (r.phase == FINALIZE && r.result == R_NONE) r.result = R_NOWORK;
            }
            publish(r);
        } finally {
            REGION_STEPS.decrementAndGet();
            bs.regionRunning.decrementAndGet();
            C.regionNs.add(System.nanoTime() - t0);
        }
    }

    /** Revalidates the thread against the neighbour's real BE type (the domain may have moved since enqueue). */
    static boolean inNeighbourDomain(Rec r) {
        BlockEntity be = r.level.getBlockEntity(r.nPos);
        return BeDomainReady.currentThreadIn(r.level, r.nPos, be == null ? null : BeDomainReady.typeOf(be));
    }

    static int tickNow(Rec r) {
        return r.level.getServer().getTickCount();
    }

    /** Import, neighbour half: AE2's own import facade with a transit context. */
    static void regionImport(Rec r) {
        synchronized (r) {
            if (r.phase != PREPARED) {
                C.staleRegionStep.increment();
                return;
            }
            r.phase = RUNNING;
        }
        if (stopping || !blockEntitiesTicking(r.level, r.nPos) || !inNeighbourDomain(r)) {
            (stopping || !blockEntitiesTicking(r.level, r.nPos) ? C.regionNotTicking : C.regionWrongDomain).increment();
        } else {
            BusTransitContext ctx = new BusTransitContext(r, tickNow(r));
            r.importStrategy.transfer(ctx);
        }
        synchronized (r) {
            if (r.held.isEmpty()) {
                r.phase = FINALIZE;
                if (r.result == R_NONE) r.result = r.extracted > 0 ? R_COMMITTED : R_NOWORK;
            } else {
                r.phase = HELD_MAIN;
            }
        }
        publish(r);
    }

    /** Export, neighbour half 1: capacity probe (simulate only). */
    static void regionProbe(Rec r) {
        synchronized (r) {
            if (r.phase != PREPARED) {
                C.staleRegionStep.increment();
                return;
            }
            r.phase = RUNNING;
        }
        boolean ok = !stopping && blockEntitiesTicking(r.level, r.nPos) && inNeighbourDomain(r);
        if (!ok) (stopping || !blockEntitiesTicking(r.level, r.nPos) ? C.regionNotTicking : C.regionWrongDomain).increment();
        Map<AEKey, Long> probed = new java.util.HashMap<>();
        for (int x = 0; x < r.keys.length; x++) {
            AEKey k = r.keys[x];
            if (k == null) continue;
            Long c = probed.get(k);
            if (c == null) {
                c = ok ? r.exportStrategy.push(k, (long) r.ops * k.getAmountPerOperation(), Actionable.SIMULATE) : 0L;
                probed.put(k, c);
            }
            r.cap[x] = c;
        }
        synchronized (r) {
            r.phase = PROBED;
        }
        publish(r);
    }

    /** Neighbour push: export commit (destination) or import return (source); reserves under the monitor. */
    static void regionPush(Rec r) {
        List<Map.Entry<AEKey, Long>> take;
        synchronized (r) {
            if (r.phase != HELD_REGION) {
                C.staleRegionStep.increment();
                return;
            }
            take = new ArrayList<>(r.held.entrySet());
            for (var e : take) r.inflight.merge(e.getKey(), e.getValue(), Long::sum);
            r.held.clear();
            r.pushes++;
        }
        boolean ok = !stopping && blockEntitiesTicking(r.level, r.nPos) && inNeighbourDomain(r);
        if (!ok) (stopping || !blockEntitiesTicking(r.level, r.nPos) ? C.regionNotTicking : C.regionWrongDomain).increment();
        StackExportStrategy facade = r.export ? r.exportStrategy : null;
        if (ok && facade == null) {
            if (r.pushFacade == null) r.pushFacade = StackWorldBehaviors.createExportFacade(r.level, r.nPos, r.fromSide);
            facade = r.pushFacade;
        }
        for (var e : take) {
            long n = 0;
            if (ok) {
                try {
                    n = Math.max(0, Math.min(e.getValue(), facade.push(e.getKey(), e.getValue(), Actionable.MODULATE)));
                } catch (Throwable t) {
                    C.regionFailures.increment();
                    Ae2Compat.LOG.error("[nestworld-compat-ae2] bus transit push failed at {}", r.nPos.toShortString(), t);
                }
            }
            synchronized (r) {
                r.inflight.remove(e.getKey());
                if (r.export) {
                    r.committed += n;
                    C.expToNeighbour.add(n);
                } else {
                    r.returned += n;
                    C.impBackToSource.add(n);
                }
                if (r.orphan || r.recoveryFromNbt) C.recoveredNeighbour.add(n);
                long left = e.getValue() - n;
                if (left > 0) r.addHeld(e.getKey(), left, tickNow(r));
            }
        }
        synchronized (r) {
            r.phase = r.held.isEmpty() ? FINALIZE : HELD_MAIN;
            if (r.phase == FINALIZE && r.result == R_NONE) r.result = (r.orphan || r.recoveryFromNbt) ? R_RECOVERED : R_COMMITTED;
        }
        publish(r);
    }

    static void publish(Rec r) {
        if (fxFifo) {
            FIFO.add(r);
            return;
        }
        GridState gs = r.gs;
        gs.inbox.add(r);
        if (gs.inReady.compareAndSet(false, true)) READY.add(gs);
    }

    // ---------------------------------------------------------------- MAIN commit scheduler

    /** Server tick END (priority LOWEST, after AE2's TickHandler). */
    public static void onServerTickEnd(MinecraftServer server) {
        if (!FLAG) return;
        int tick = server.getTickCount();
        long passStart = System.nanoTime();
        if (!FIFO.isEmpty() || !FIFO_PENDING.isEmpty()) {
            Rec r;
            while ((r = FIFO.poll()) != null) FIFO_PENDING.add(r);
            ArrayList<Rec> retry = new ArrayList<>();
            int served = 0;
            while ((r = FIFO_PENDING.peek()) != null) {
                if (served > 0 && System.nanoTime() - passStart > budgetNs) {
                    r.gs.budgetExhausted++;
                    break;
                }
                FIFO_PENDING.poll();
                long t0 = System.nanoTime();
                if (!processSafe(r, tick)) retry.add(r);
                r.gs.mainCommitNs += System.nanoTime() - t0;
                served++;
            }
            FIFO_PENDING.addAll(retry);
        }
        List<GridState> active = new ArrayList<>();
        GridState g;
        while ((g = READY.poll()) != null) {
            g.inReady.set(false);
            Rec r;
            while ((r = g.inbox.poll()) != null) g.pending.add(r);
            if (!g.pending.isEmpty()) active.add(g);
        }
        int n = active.size();
        if (n == 0) {
            C.commitNs.add(System.nanoTime() - passStart);
            return;
        }
        long quantum = Math.max(1, budgetNs / n);
        int start = Math.floorMod(rotation++, n);
        for (int i = 0; i < n; i++) {
            GridState gs = active.get((start + i) % n);
            long t0 = System.nanoTime();
            int served = 0;
            ArrayList<Rec> retry = new ArrayList<>();
            Rec r;
            while ((r = gs.pending.peek()) != null) {
                if (served > 0 && System.nanoTime() - t0 > quantum) {
                    gs.budgetExhausted++;
                    break;
                }
                gs.pending.poll();
                if (!processSafe(r, tick)) retry.add(r);
                served++;
            }
            gs.pending.addAll(retry);
            if (served == 0 && !gs.pending.isEmpty()) gs.starvationTicks++;
            gs.maxBacklog = Math.max(gs.maxBacklog, gs.outstanding.get());
            gs.mainCommitNs += System.nanoTime() - t0;
            if (!gs.pending.isEmpty() && gs.inReady.compareAndSet(false, true)) READY.add(gs);
        }
        C.commitNs.add(System.nanoTime() - passStart);
    }

    private static volatile long lastFailLog;

    /** {@link #process} contained: a failing record stays pending (its held amounts untouched), counted, logged rate-limited. */
    static boolean processSafe(Rec r, int tick) {
        try {
            return process(r, tick);
        } catch (Throwable t) {
            C.commitFailures.increment();
            long now = System.nanoTime();
            if (now - lastFailLog > 10_000_000_000L) {
                lastFailLog = now;
                Ae2Compat.LOG.error("[nestworld-compat-ae2] bus transit commit step failed for transfer {} at {} (kept pending)",
                        r.id, r.hostPos.toShortString(), t);
            }
            return false;
        }
    }

    /** One MAIN step of a record. Returns false when it must stay pending until the next tick. */
    static boolean process(Rec r, int tick) {
        int phase;
        synchronized (r) {
            phase = r.phase;
        }
        switch (phase) {
            case PROBED -> {
                return plan(r, tick);
            }
            case HELD_MAIN -> {
                return networkStep(r, tick);
            }
            case FINALIZE -> finish(r, tick);
            case DONE -> terminal(r, tick);
            default -> {
                // PREPARED / RUNNING / HELD_REGION: owned by the region step (a duplicate queue entry) — ignore
            }
        }
        return true;
    }

    /** Export plan on MAIN: AE2's export loop with the probed neighbour capacity in place of the adjacent simulate. */
    static boolean plan(Rec r, int tick) {
        BusState bs = ((NwTransitBus) r.bus).nw$transit();
        Grid grid = netGrid(r);
        boolean alive = bs.inWorld && bs.incarnation == r.incarnation && !r.orphan;
        if (grid == null || !alive) {
            synchronized (r) {
                r.phase = FINALIZE;
                r.result = R_CANCELLED;
            }
            finish(r, tick);
            return true;
        }
        if (grid != r.gridAtOp) C.gridChanged.increment();
        MEStorage inv = grid.getStorageService().getInventory();
        IEnergySource energy = grid.getEnergyService();
        int ops = r.ops;
        Map<AEKey, Long> planned = new java.util.HashMap<>();
        int x;
        long total = 0;
        for (x = 0; x < r.availableSlots && ops > 0; x++) {
            AEKey what = r.keys[x];
            if (what == null) continue;
            long factor = what.getAmountPerOperation();
            long amount = (long) ops * factor;
            long sim = StorageHelper.poweredExtraction(energy, inv, what, amount, r.source, Actionable.SIMULATE);
            long capLeft = r.cap[x] - planned.getOrDefault(what, 0L);
            long wasIns = Math.min(sim, capLeft);
            if (wasIns > 0) {
                long ext = StorageHelper.poweredExtraction(energy, inv, what, wasIns, r.source, Actionable.MODULATE);
                if (ext > 0) {
                    synchronized (r) {
                        r.addHeld(what, ext, tick);
                        r.extracted += ext;
                    }
                    C.expFromNetwork.add(ext);
                    planned.merge(what, ext, Long::sum);
                    total += ext;
                    ops -= (int) Math.max(1, ext / factor);
                }
            }
        }
        r.x = x;
        if (total == 0) {
            synchronized (r) {
                r.phase = FINALIZE;
                r.result = R_NOWORK;
            }
            finish(r, tick);
            return true;
        }
        synchronized (r) {
            r.phase = fxHold ? HELD_MAIN : HELD_REGION;   // fixture hold: keep the extracted amount in transit (MAIN-owned)
            r.dirtySinceSave = true;
        }
        markUnsaved(r.level, r.hostPos);
        if (fxHold) return false;      // fixture hold: stays MAIN-owned AND queued (was dropped from the queue: run4 stuck records)
        enqueueRegion(r, BusTransit::regionPush, false);
        return true;
    }

    /** The node's grid, or null when AE2 destroyed the node (getGrid would throw). */
    static IGrid liveGrid(IGridNode n) {
        if (n == null) return null;
        try {
            return n.getGrid();
        } catch (IllegalStateException destroyed) {
            return null;
        }
    }

    /** Grid that receives network-side moves: the bus's current grid, else the op grid while it lives, else the live part at the position. */
    static Grid netGrid(Rec r) {
        BusState bs = ((NwTransitBus) r.bus).nw$transit();
        if (bs.inWorld && bs.incarnation == r.incarnation && !r.orphan) {
            if (liveGrid(r.bus.getGridNode()) instanceof Grid g) return g;
        }
        if (r.gridAtOp != null && r.gridAtOp.getPivot() != null) return r.gridAtOp;
        // recovery: a replacement bus at the same place (e.g. the chunk reloaded)
        LevelChunk ch = r.level.getChunkSource().getChunkNow(r.hostPos.getX() >> 4, r.hostPos.getZ() >> 4);
        if (ch == null || !domainIsMain(r.level, r.hostPos, "ae2:cable_bus")) return null;
        if (ch.getBlockEntity(r.hostPos) instanceof IPartHost host && host.getPart(r.busSide) instanceof IOBusPart p
                && liveGrid(p.getGridNode()) instanceof Grid g) return g;
        return null;
    }

    /** Network side for held resources (import commit / export return / recovery). */
    static boolean networkStep(Rec r, int tick) {
        BusState bs = ((NwTransitBus) r.bus).nw$transit();
        boolean alive = bs.inWorld && bs.incarnation == r.incarnation && !r.orphan;
        Grid grid = netGrid(r);
        List<Map.Entry<AEKey, Long>> take;
        if (fxHold) return false;   // fixture hold: transit stays MAIN-owned, nothing moves
        if (r.export && r.pushes == 0) {
            // export: the destination is the neighbour — deliver first (normal path after a hold, and the recovery policy)
            synchronized (r) {
                if (r.phase != HELD_MAIN) return true;
                r.phase = HELD_REGION;
            }
            enqueueRegion(r, BusTransit::regionPush, false);
            return true;
        }
        synchronized (r) {
            if (r.phase != HELD_MAIN) return true;
            take = new ArrayList<>(r.held.entrySet());
            r.mainSteps++;
        }
        if (grid != null && grid != r.gridAtOp && alive) C.gridChanged.increment();
        boolean firstImportCommit = !r.export && !r.modDone;
        if (grid != null) {
            MEStorage inv = grid.getStorageService().getInventory();
            for (var e : take) {
                long ins = inv.insert(e.getKey(), e.getValue(), Actionable.MODULATE, r.source);
                if (ins <= 0) continue;
                synchronized (r) {
                    r.subHeld(e.getKey(), ins);
                    if (r.export) r.returned += ins;
                    else r.committed += ins;
                }
                if (r.export) {
                    C.expBackToNetwork.add(ins);
                    if (!r.orphan && !r.recoveryFromNbt) C.exportLeftoverReturned.add(ins);
                } else C.impToNetwork.add(ins);
                if (r.orphan || r.recoveryFromNbt) C.recoveredNetwork.add(ins);
            }
        }
        if (firstImportCommit && !r.recoveryFromNbt) applyModulation(r, r.extracted > 0);
        if (r.export && r.pushes > 0 && !r.recoveryFromNbt) applyModulation(r, r.committed > 0);
        long left;
        synchronized (r) {
            left = r.heldTotal();
            r.dirtySinceSave = true;
        }
        markUnsaved(r.level, r.hostPos);
        if (left == 0) {
            synchronized (r) {
                r.phase = FINALIZE;
                if (r.result == R_NONE) r.result = (r.orphan || r.recoveryFromNbt) ? R_RECOVERED : R_COMMITTED;
            }
            finish(r, tick);
            return true;
        }
        // something stays held
        boolean dead = !alive;
        if (dead && fxRecoveryOff) {
            // NEGATIVE CONTROL ONLY: what a plain cancel would do (the fixture must see the loss)
            synchronized (r) {
                C.voidedHeld.add(r.heldTotal());
                r.held.clear();
                r.phase = FINALIZE;
                r.result = R_VOIDED_NEG;
            }
            C.voidedNegative.increment();
            finish(r, tick);
            return true;
        }
        if (r.pushes > 0) C.returnFailed.increment();   // neighbour already refused once: network fallback is this step
        if ((dead || r.pushes > 0) && r.firstHeldTick >= 0 && tick - r.firstHeldTick > DROP_AFTER_TICKS) {
            if (dropItems(r)) return true;
            C.stuckNonItem.increment();                  // non-item keys are never dropped: kept, persisted, retried
        }
        if (!r.export && firstImportCommit) C.importRefusedReturned.add(left);
        if (stopping) return false;   // keep it MAIN-owned: the final save writes it
        synchronized (r) {
            r.phase = HELD_REGION;
        }
        enqueueRegion(r, BusTransit::regionPush, false);
        return true;
    }

    /** Safe recovery drop for item keys (only when the position's chunk is loaded); runs in the position's domain. */
    static boolean dropItems(Rec r) {
        if (r.level.getChunkSource().getChunkNow(r.hostPos.getX() >> 4, r.hostPos.getZ() >> 4) == null) return false;
        List<Map.Entry<AEKey, Long>> items = new ArrayList<>();
        synchronized (r) {
            for (var e : r.held.entrySet()) if (e.getKey() instanceof AEItemKey) items.add(Map.entry(e.getKey(), e.getValue()));
            if (items.isEmpty()) return false;
            for (var e : items) {
                r.subHeld(e.getKey(), e.getValue());
                r.inflight.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
        Runnable spawn = () -> {
            REGION_STEPS.incrementAndGet();
            try {
                if (stopping) {        // revert: the amounts stay held (persisted by the final save)
                    synchronized (r) {
                        for (var e : items) {
                            r.inflight.remove(e.getKey());
                            r.addHeld(e.getKey(), e.getValue(), r.firstHeldTick);
                        }
                        r.phase = HELD_MAIN;
                    }
                    publish(r);
                    return;
                }
                spawnItems(r, items);
            } finally {
                REGION_STEPS.decrementAndGet();
            }
        };
        synchronized (r) {
            r.phase = HELD_REGION;   // not processed by MAIN until the drop ran
        }
        try {
            if (domainIsMain(r.level, r.hostPos, null)) spawn.run();
            else BeDomainReady.enqueueTo(r.level, r.hostPos, null, spawn, kind -> {
                synchronized (r) {
                    for (var e : items) {
                        r.inflight.remove(e.getKey());
                        r.addHeld(e.getKey(), e.getValue(), r.firstHeldTick);
                    }
                    r.phase = HELD_MAIN;
                }
                publish(r);
            });
        } catch (Throwable t) {
            synchronized (r) {
                for (var e : items) {
                    r.inflight.remove(e.getKey());
                    r.addHeld(e.getKey(), e.getValue(), r.firstHeldTick);
                }
                r.phase = HELD_MAIN;
            }
            return false;
        }
        return true;
    }

    static void spawnItems(Rec r, List<Map.Entry<AEKey, Long>> items) {
            long total = 0;
            for (var e : items) {
                AEItemKey k = (AEItemKey) e.getKey();
                long left = e.getValue();
                while (left > 0) {
                    int n = (int) Math.min(left, k.getItem().getMaxStackSize());
                    var ent = new ItemEntity(r.level, r.hostPos.getX() + 0.5, r.hostPos.getY() + 0.5, r.hostPos.getZ() + 0.5, k.toStack(n));
                    r.level.addFreshEntity(ent);
                    left -= n;
                    total += n;
                }
            }
            synchronized (r) {
                for (var e : items) r.inflight.remove(e.getKey());
                r.droppedAmt += total;
                if (r.held.isEmpty()) {
                    r.phase = FINALIZE;
                    r.result = R_RECOVERED;
                } else r.phase = HELD_MAIN;     // non-item keys remain: back to the recovery owner
            }
            C.dropped.add(total);
            C.recoveredDrop.add(total);
            publish(r);
    }

    static void finish(Rec r, int tick) {
        if (!r.recoveryFromNbt) {
            if (r.export) applyModulation(r, r.committed > 0);
            else applyModulation(r, r.extracted > 0);
        }
        synchronized (r) {
            r.phase = DONE;
        }
        terminal(r, tick);
    }

    static void terminal(Rec r, int tick) {
        BusState bs = ((NwTransitBus) r.bus).nw$transit();
        if (!bs.live.remove(r)) return;   // already terminal (duplicate queue entry)
        C.outstanding.decrementAndGet();
        r.gs.outstanding.decrementAndGet();
        if (r.result == R_CANCELLED) {
            C.cancelled.increment();
            r.gs.cancelled.increment();
        } else {
            C.completed.increment();
            r.gs.completed.increment();
        }
        if (!r.orphan && !r.recoveryFromNbt && r.result != R_CANCELLED) {
            int lat = Math.max(0, tick - r.opServerTick);
            long[] h = r.export ? r.gs.latExport : r.gs.latImport;
            h[Math.min(lat, 65)]++;
        } else if (r.orphan || r.recoveryFromNbt) {
            r.gs.recoveryDone++;
        }
        if (r.savedSeq >= 0 || r.firstHeldTick >= 0) markUnsaved(r.level, r.hostPos);
    }

    /** AE2's FASTER / SLOWER for the op's tracker, applied when the result is known (same tracker, no later op). */
    static void applyModulation(Rec r, boolean doneWork) {
        if (r.modDone) return;
        r.modDone = true;
        BusState bs = ((NwTransitBus) r.bus).nw$transit();
        if (r.tt == null || r.gridAtOp == null || r.node == null || r.orphan || !bs.inWorld || bs.incarnation != r.incarnation) {
            C.modStale.increment();     // bus gone: no tracker to modulate (its node may be destroyed)
            return;
        }
        // round-robin cursor (export): AE2 advances it only when something was exported
        if (r.export && doneWork && r.scheduling == SchedulingMode.ROUNDROBIN) {
            if (bs.inWorld && bs.incarnation == r.incarnation && bs.opSeq == r.opSeq) {
                ExportBusPartAccessor a = (ExportBusPartAccessor) r.bus;
                a.nw$setNextSlot((a.nw$nextSlot() + r.x) % Math.max(1, r.availableSlots));
                C.rrApplied.increment();
            } else C.rrLate.increment();
        }
        IGridNode node = r.node;
        if (!(liveGrid(node) instanceof Grid g) || g != r.gridAtOp) {
            C.modStale.increment();
            return;
        }
        var tm = g.getTickManager();
        TickTracker tt = r.tt;
        Map<IGridNode, TickTracker> awake = ((TickManagerServiceAccessor) tm).nw$awake();
        if (awake.get(node) != tt || ((NwTickTracker) tt).nw$gen() != r.ttGen) {
            C.modStale.increment();
            return;
        }
        if (tt.getLastTick() != r.opTick || bs.opSeq != r.opSeq) {
            C.modLate.increment();
            return;
        }
        int newRate = doneWork ? tt.getCurrentRate() - 2 : tt.getCurrentRate() + 1;   // AE2 SPEED_UP 2 / SLOW_DOWN 1
        Ae2Compat.KERNEL.guardedStrictUnchecked(Specs.grid(g), () -> {
            BusTransitTmAccess a = (BusTransitTmAccess) tm;
            a.nw$removeFromQueue(node, tt);
            tt.setCurrentRate(newRate);
            a.nw$addToQueueT(node, tt);
            return null;
        });
        // addToQueue bumped the generation; nothing else holds the old one
        C.modApplied.increment();
    }

    // ---------------------------------------------------------------- lifecycle / persistence

    private static final Map<BlockEntity, Boolean> UNLOADING = Collections.synchronizedMap(new WeakHashMap<>());

    public static void markUnloading(CableBusBlockEntity be) {
        UNLOADING.put(be, Boolean.TRUE);
    }

    /** Region steps running right now (any bus). */
    static final AtomicInteger REGION_STEPS = new AtomicInteger();

    /**
     * Server stop (MAIN, ServerStoppingEvent, before the final save; MAIN holds nothing here): new region steps refuse to
     * start ({@code stopping}); a step already inside a neighbour call is waited for, bounded, so the final save never
     * sees an in-flight reservation (dup 0: no in-flight amount is ever written). Timeout (counted) → in-flight amounts
     * are NOT written (no dup) and are logged as at-risk.
     */
    public static void onServerStopping() {
        stopping = true;
        long t0 = System.nanoTime(), deadline = t0 + 5_000_000_000L;
        while (REGION_STEPS.get() > 0 && System.nanoTime() < deadline) Thread.onSpinWait();
        C.stopWaitNanos.add(System.nanoTime() - t0);
        if (REGION_STEPS.get() > 0) {
            C.stopWaitTimeout.increment();
            Ae2Compat.LOG.error("[nestworld-compat-ae2] bus transit: {} region step(s) still running at the final save; their "
                    + "in-flight amounts are not persisted (no duplicate)", REGION_STEPS.get());
        }
    }

    public static void onServerStarting() {
        stopping = false;
    }

    /** Part leaves the world (MAIN). Unload: records cleanly written by the last save are owned by that NBT. */
    public static void onRemoveFromWorld(IOBusPart bus) {
        BusState bs = ((NwTransitBus) bus).nw$transit();
        if (!bs.inWorld) return;
        bs.inWorld = false;
        releasePin(bs);
        BlockEntity host = bus.getHost() == null ? null : bus.getHost().getBlockEntity();
        boolean unloading = host != null && UNLOADING.containsKey(host);
        List<Rec> snapshot;
        synchronized (bs.live) {
            snapshot = new ArrayList<>(bs.live);
        }
        for (Rec r : snapshot) {
            boolean publishNow = false;
            synchronized (r) {
                if (r.phase == DONE) continue;
                if (r.phase == PREPARED) {
                    r.phase = DONE;
                    r.result = R_CANCELLED;
                    publishNow = true;
                } else if ((unloading || stopping) && r.phase == HELD_MAIN && r.savedSeq == bs.saveSeq && !r.dirtySinceSave) {
                    // NBT owns exactly this record's held amounts (written by the last save, untouched since)
                    C.sealedToNbt.increment();
                    C.sealedHeld.add(r.heldTotal());
                    r.held.clear();
                    r.orphan = true;
                    r.phase = DONE;
                    r.result = R_SEALED_NBT;
                    publishNow = true;
                } else {
                    if ((unloading || stopping) && r.savedSeq == bs.saveSeq && r.dirtySinceSave) C.sealDirtyInconsistent.increment();
                    r.orphan = true;
                    C.orphaned.increment();
                    if (r.phase == HELD_MAIN || r.phase == FINALIZE) publishNow = true;
                }
            }
            if (publishNow) publish(r);
        }
    }

    /** writeToNBT (any thread that saves): records MAIN owns now; during server stop also region-owned ones. */
    public static void write(IOBusPart bus, CompoundTag extra) {
        BusState bs = ((NwTransitBus) bus).nw$transit();
        bs.saveSeq++;
        ListTag list = new ListTag();
        List<Rec> snapshot;
        synchronized (bs.live) {
            snapshot = new ArrayList<>(bs.live);
        }
        for (Rec r : snapshot) {
            synchronized (r) {
                if (r.phase == DONE || r.orphan) continue;
                boolean mainOwned = r.phase == HELD_MAIN;
                if (!mainOwned && !stopping) continue;
                Map<AEKey, Long> all = new LinkedHashMap<>(r.held);
                if (!r.inflight.isEmpty()) C.savedInflight.increment();   // never written: dup 0 (only after a stop-wait timeout)
                if (all.isEmpty()) continue;
                CompoundTag t = new CompoundTag();
                t.putLong("id", r.id);
                t.putBoolean("export", r.export);
                ListTag res = new ListTag();
                for (var e : all.entrySet()) {
                    CompoundTag k = e.getKey().toTagGeneric();
                    k.putLong("nwAmount", e.getValue());
                    res.add(k);
                }
                t.put("held", res);
                list.add(t);
                r.savedSeq = bs.saveSeq;
                r.dirtySinceSave = false;
                CLAIMED.add(r.id);        // an NBT copy exists from now on: a later load of it must dedup
                C.persisted.increment();
            }
        }
        // records loaded but not adopted (flag off, or not yet in the world): keep them byte-identical in the NBT
        List<CompoundTag> pendingLoad = bs.loaded;
        if (pendingLoad != null) for (CompoundTag t : pendingLoad) list.add(t.copy());
        if (!list.isEmpty()) extra.put(NBT_KEY, list);
    }

    public static void read(IOBusPart bus, CompoundTag extra) {
        BusState bs = ((NwTransitBus) bus).nw$transit();
        ListTag list = extra.getList(NBT_KEY, Tag.TAG_COMPOUND);
        List<CompoundTag> l = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) l.add(list.getCompound(i));
        bs.loaded = l;
    }

    /** A record loaded from NBT: RECOVERY_REQUIRED, owned by the recovery owner (commit scheduler), deduplicated. */
    static void adoptLoaded(IOBusPart bus, BusState bs, ServerLevel level, CompoundTag t, int tick) {
        long id = t.getLong("id");
        C.loaded.increment();
        if (!CLAIMED.add(id)) {
            C.dedupDropped.increment();
            return;
        }
        Rec r = new Rec(id, t.getBoolean("export"), bus, bs, null, ORPHANS, level, bus.getHost().getBlockEntity().getBlockPos(),
                bus.getSide(), expectedBeType(level, bus.getHost().getBlockEntity().getBlockPos().relative(bus.getSide())),
                ((NwTransitBus) bus).nw$source(), -1, tick, null, -1, bus.getGridNode(), true);
        if (r.export) r.exportStrategy = ((ExportBusPartAccessor) bus).nw$exportStrategy();
        ListTag res = t.getList("held", Tag.TAG_COMPOUND);
        long total = 0;
        for (int i = 0; i < res.size(); i++) {
            CompoundTag k = res.getCompound(i);
            AEKey key = AEKey.fromTagGeneric(k);
            long amt = k.getLong("nwAmount");
            if (key == null || amt <= 0) continue;
            r.addHeld(key, amt, tick);
            total += amt;
        }
        C.loadedHeld.add(total);
        r.phase = total > 0 ? HELD_MAIN : DONE;
        r.result = total > 0 ? R_NONE : R_CANCELLED;
        r.modDone = true;
        r.dirtySinceSave = true;
        bs.live.add(r);
        C.outstanding.incrementAndGet();
        ORPHANS.outstanding.incrementAndGet();
        publish(r);
    }

    // ---------------------------------------------------------------- read-only reports

    public static String summary() {
        var c = C;
        long held = 0, inflight = 0;
        List<GridState> gl;
        synchronized (ALL_GRIDS) {
            gl = new ArrayList<>(ALL_GRIDS);
        }
        return "busTransit{flag=" + FLAG + " active=" + active + " budgetUs=" + budgetNs / 1000 + " hold=" + fxHold
                + " recoveryOff=" + fxRecoveryOff + " fifo=" + fxFifo + "}"
                + "\nops scheduled=" + c.scheduled.sum() + " transitImport=" + c.opsTransitImport.sum() + " transitExport="
                + c.opsTransitExport.sum() + " inline{inactive=" + c.inlineInactive.sum() + " class=" + c.inlineClass.sum()
                + " crafting=" + c.inlineCrafting.sum() + " fuzzy=" + c.inlineFuzzy.sum() + " random=" + c.inlineRandom.sum()
                + " strategy=" + c.inlineStrategy.sum() + " mainNeighbour=" + c.inlineMainNeighbour.sum() + " linkedCrossDomain="
                + c.inlineLinkedCrossDomain.sum() + " (linkedSameDomain transit=" + c.transitLinkedSameDomain.sum() + ") noNeighbourBe="
                + c.inlineNoNeighbourBe.sum() + " overlapRisk=" + c.inlineOverlapRisk.sum() + "}"
                + "\nrecords completed=" + c.completed.sum() + " cancelled=" + c.cancelled.sum() + " missed=" + c.missed.sum()
                + " outstanding=" + c.outstanding.get() + " commitFailures=" + c.commitFailures.sum() + " returnFailed="
                + c.returnFailed.sum() + " stuckNonItem=" + c.stuckNonItem.sum() + " stopWaitTimeout=" + c.stopWaitTimeout.sum()
                + " stopWaitUs=" + c.stopWaitNanos.sum() / 1000 + " enqueueFailed=" + c.enqueueFailed.sum() + " coreTerminal="
                + c.coreTerminal.sum() + " staleRegionStep=" + c.staleRegionStep.sum() + " regionWrongDomain="
                + c.regionWrongDomain.sum() + " regionNotTicking=" + c.regionNotTicking.sum() + " regionFailures="
                + c.regionFailures.sum() + " regionEnergyCalls=" + c.regionEnergyCalls.sum() + " regionStorageMisuse="
                + c.regionStorageMisuse.sum() + " gridChanged=" + c.gridChanged.sum()
                + "\nmodulation applied=" + c.modApplied.sum() + " late=" + c.modLate.sum() + " stale=" + c.modStale.sum()
                + " rrApplied=" + c.rrApplied.sum() + " rrLate=" + c.rrLate.sum() + " importRefusedReturned="
                + c.importRefusedReturned.sum() + " exportLeftoverReturned=" + c.exportLeftoverReturned.sum()
                + "\nrecovery orphaned=" + c.orphaned.sum() + " toNetwork=" + c.recoveredNetwork.sum() + " toNeighbour="
                + c.recoveredNeighbour.sum() + " drop=" + c.recoveredDrop.sum() + " sealedToNbt=" + c.sealedToNbt.sum()
                + " sealedHeld=" + c.sealedHeld.sum() + " persisted=" + c.persisted.sum() + " loaded=" + c.loaded.sum()
                + " loadedHeld=" + c.loadedHeld.sum() + " dedupDropped=" + c.dedupDropped.sum() + " sealDirtyInconsistent="
                + c.sealDirtyInconsistent.sum() + " savedInflight=" + c.savedInflight.sum() + " voidedNegative="
                + c.voidedNegative.sum() + " voidedHeld=" + c.voidedHeld.sum()
                + "\nledger import fromSource=" + c.impFromSource.sum() + " toNetwork=" + c.impToNetwork.sum() + " backToSource="
                + c.impBackToSource.sum() + " | export fromNetwork=" + c.expFromNetwork.sum() + " toNeighbour="
                + c.expToNeighbour.sum() + " backToNetwork=" + c.expBackToNetwork.sum() + " | dropped=" + c.dropped.sum()
                + " heldNow=" + heldNow()
                + "\ntime mainOpInline=" + c.mainOpNsInline.sum() / 1000 + "us/" + c.mainOpsInline.sum() + " mainOpTransit="
                + c.mainOpNsTransit.sum() / 1000 + "us/" + c.mainOpsTransit.sum() + " commit=" + c.commitNs.sum() / 1000
                + "us region=" + c.regionNs.sum() / 1000 + "us grids=" + gl.size();
    }

    /** Sum of held + in-flight amounts over all live records (MAIN read; region writes under the record monitor). */
    public static long heldNow() {
        long s = 0;
        java.util.Set<Rec> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Rec r : LIVE_INDEX()) {
            if (!seen.add(r)) continue;
            synchronized (r) {
                s += r.heldTotal() + r.inflightTotal();
            }
        }
        return s;
    }

    /** All live records (for the ledger): every bus registers its records in its BusState; index kept weakly here. */
    private static final Set<BusState> STATES = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    static void register(BusState bs) {
        STATES.add(bs);
    }

    static List<Rec> LIVE_INDEX() {
        List<Rec> out = new ArrayList<>();
        List<BusState> states;
        synchronized (STATES) {
            states = new ArrayList<>(STATES);
        }
        for (BusState bs : states) {
            synchronized (bs.live) {
                out.addAll(bs.live);
            }
        }
        return out;
    }

    public static String grids(int max) {
        List<GridState> gl;
        synchronized (ALL_GRIDS) {
            gl = new ArrayList<>(ALL_GRIDS);
        }
        gl.sort((a, b) -> Long.compare(b.scheduled.sum(), a.scheduled.sum()));
        StringBuilder sb = new StringBuilder("gridId scheduled completed missed cancelled backlog(max) mainCommitUs mainOpUs "
                + "import p50/p95/p99/p100 export p50/p95/p99/p100 budgetExhausted starvationTicks recovered");
        int n = 0;
        for (GridState g : gl) {
            if (g.scheduled.sum() == 0 && g.outstanding.get() == 0 && g.recoveryDone == 0) continue;
            if (n++ >= max) break;
            sb.append('\n').append(g.id).append(' ').append(g.scheduled.sum()).append(' ').append(g.completed.sum()).append(' ')
                    .append(g.missed.sum()).append(' ').append(g.cancelled.sum()).append(' ').append(g.outstanding.get())
                    .append('(').append(g.maxBacklog).append(") ").append(g.mainCommitNs / 1000).append(' ')
                    .append(g.mainOpNs / 1000).append(' ').append(pct(g.latImport)).append(' ').append(pct(g.latExport))
                    .append(' ').append(g.budgetExhausted).append(' ').append(g.starvationTicks).append(' ').append(g.recoveryDone);
        }
        return sb.toString();
    }

    static String pct(long[] h) {
        long n = 0;
        for (long v : h) n += v;
        if (n == 0) return "-/-/-/-";
        return q(h, n, 0.50) + "/" + q(h, n, 0.95) + "/" + q(h, n, 0.99) + "/" + q(h, n, 1.0);
    }

    static String q(long[] h, long n, double p) {
        long target = Math.max(1, (long) Math.ceil(p * n));
        long acc = 0;
        for (int i = 0; i < h.length; i++) {
            acc += h[i];
            if (acc >= target) return i == 65 ? ">64" : String.valueOf(i);
        }
        return "?";
    }

    /** Read-only dump of non-terminal records (debug / fixtures). */
    public static String records(int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Rec r : LIVE_INDEX()) {
            if (n++ >= max) break;
            synchronized (r) {
                sb.append(r.export ? "E" : "I").append(" id=").append(r.id).append(" phase=").append(r.phase).append(" result=")
                        .append(r.result).append(" held=").append(r.heldTotal()).append(" inflight=").append(r.inflightTotal())
                        .append(" pushes=").append(r.pushes).append(" mainSteps=").append(r.mainSteps).append(" orphan=").append(r.orphan)
                        .append(" nbt=").append(r.recoveryFromNbt).append(" gs=").append(r.gs.id).append(" inbox=").append(r.gs.inbox.size())
                        .append(" pending=").append(r.gs.pending.size()).append(" inReady=").append(r.gs.inReady.get())
                        .append(" host=").append(r.hostPos.toShortString()).append('\n');
            }
        }
        sb.append("READY=").append(READY.size()).append(" fifo=").append(FIFO.size()).append('/').append(FIFO_PENDING.size());
        return sb.toString();
    }

    static List<GridState> ALL_GRIDS_VIEW() {
        return ALL_GRIDS;
    }

    /** Fixture access: per-grid numbers for a grid. */
    static GridState stateOf(Grid g) {
        synchronized (GRIDS) {
            return GRIDS.get(g);
        }
    }
}
