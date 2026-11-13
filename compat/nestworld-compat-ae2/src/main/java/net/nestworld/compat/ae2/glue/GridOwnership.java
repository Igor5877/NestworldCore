package net.nestworld.compat.ae2.glue;

import appeng.api.networking.IGridNode;
import appeng.me.Grid;
import appeng.parts.AEBasePart;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * AE2-GRID-DOMAIN-001 model A (TZ §3″): which block entities PARTICIPATE in a grid and may therefore be owned by the AE2
 * domain while it runs that grid. Participants = every node host BE of the grid (all dimensions) + every BE adjacent to a
 * host (the NeighborAffinity scope: bus/interface/storage-bus/pattern-provider targets) + every member of a Core group
 * (e.g. a MasterfulMachinery multiblock) that such a neighbour belongs to, so a group never splits.
 *
 * <p>Fail-closed: a grid is NOT claimable (stays model B) when a non-host participant is type/mod-pinned to MAIN, a
 * participant chunk is not loaded, or the set exceeds {@link #MAX_PARTICIPANTS}. MAIN only.
 */
final class GridOwnership {
    static final int MAX_PARTICIPANTS = Integer.getInteger("nestworld.compatAe2.ownershipMax", 50_000);
    static final int RECHECK_TICKS = 200;

    record Participants(Map<ResourceKey<Level>, long[]> positions, Map<ResourceKey<Level>, String[]> types, String refusal) {
        /** Same claimable content (positions + expected types), regardless of object identity / recompute. */
        int size() { int n = 0; for (long[] a : positions.values()) n += a.length; return n; }

        boolean sameAs(Participants o) {
            if (o == null || !java.util.Objects.equals(refusal, o.refusal) || !positions.keySet().equals(o.positions.keySet())) return false;
            for (var e : positions.entrySet()) {
                if (!java.util.Arrays.equals(e.getValue(), o.positions.get(e.getKey()))) return false;
                if (!java.util.Arrays.equals(types.get(e.getKey()), o.types.get(e.getKey()))) return false;
            }
            return true;
        }
    }

    private static final Map<Grid, Object[]> CACHE = new WeakHashMap<>();   // {nodeCount, tick, Participants}
    static final LongAdder computed = new LongAdder(), refused = new LongAdder();
    /** Last computed claimable set, by reason (owner request: claimedByReason=nodeHost / neighbor / MM-group). */
    static volatile int lastHosts, lastNeighbours, lastGroupMembers, lastTotal, lastForeign, lastTransitCovered, lastMainServiced, lastChestGuarded;
    /** Parts that never touch the block they face (fail-closed allowlist; everything else needs an adapter). */
    static final Set<String> NO_TOUCH_PARTS = Set.of("TerminalPart", "CraftingTerminalPart", "PatternEncodingTerminalPart",
            "PatternAccessTerminalPart", "PanelPart", "SemiDarkPanelPart", "DarkPanelPart", "ConversionMonitorPart", "StorageMonitorPart");
    /** Whole-block hosts that never touch neighbouring block entities (fail-closed allowlist). */
    static final Set<String> NO_TOUCH_BLOCK_HOSTS = Set.of("DriveBlockEntity", "ControllerBlockEntity", "EnergyCellBlockEntity",
            "DenseEnergyCellBlockEntity", "CreativeEnergyCellBlockEntity", "CraftingBlockEntity", "CraftingMonitorBlockEntity",
            "QuantumBridgeBlockEntity", "SpatialPylonBlockEntity", "CableBusBlockEntity",
            // Fast-Live audit 2026-11-12 (tools beaudit.py): no direct neighbour access
            "EntropyCradleBE", "EntropyCradleCapacitorBE", "TileWirelessConnector", "TileWirelessHub", "TileExDrive",
            "ResearchPedestalTopBE",
            // own block-state setBlock only — covered by Core BlockMutationOp when run on the domain thread
            "AdvCraftingBlockEntity", "SuperAssemblerMatrixWallBlockEntity", "HybridCoreBlockEntity",
            // extendedae_plus 1.6.3: its only neighbour read (refreshLoadedFrameModels) is client-side only (onLoad isClientSide)
            "SuperAssemblerMatrixFrameBlockEntity",
            // whole onTick (neighbour random ticks + own storedPower) hops to MAIN: GrowthAcceleratorMainHopMixin
            // (same thread as before model A; the domain thread never touches the world for it)
            "GrowthAcceleratorBlockEntity");
    static volatile String lastRefusal = "-";

    private GridOwnership() {}

    static Participants participants(Grid grid, int tick) {
        int n = grid.size();
        Object[] c = CACHE.get(grid);
        if (c != null && (int) c[0] == n && tick - (int) c[1] < RECHECK_TICKS) return (Participants) c[2];
        Participants p = compute(grid);
        CACHE.put(grid, new Object[] { n, tick, p });
        computed.increment();
        if (p.refusal != null) { refused.increment(); lastRefusal = p.refusal; }
        return p;
    }

    private static Participants compute(Grid grid) {
        Map<ResourceKey<Level>, Map<Long, String>> out = new HashMap<>();
        Set<Long> hostKeys = new HashSet<>();
        List<BlockEntity> hosts = new ArrayList<>();
        for (IGridNode node : grid.getNodes()) {
            Object o = node.getOwner();
            BlockEntity be = o instanceof BlockEntity b ? b : o instanceof AEBasePart part && part.getHost() != null ? part.getHost().getBlockEntity() : null;
            if (be == null || !(be.getLevel() instanceof ServerLevel)) continue;
            if (hostKeys.add(key(be))) hosts.add(be);
        }
        int mainServiced = 0, chestGuarded = 0;
        for (BlockEntity h : hosts) {
            if (MainServiced.host(h)) { mainServiced++; continue; }   // MVP-001: not claimed, its BE tick stays on MAIN
            put(out, h);
        }
        int neighbours = 0, groupMembers = 0, foreignNeighbours = 0, transitCovered = 0, uncoveredHosts = 0;
        String lastForeignType = "-";
        for (BlockEntity h : hosts) {
            ServerLevel level = (ServerLevel) h.getLevel();
            boolean partHost = h instanceof appeng.api.parts.IPartHost;
            if (!partHost && MainServiced.host(h)) continue;                 // MVP-001: MAIN-serviced host (not claimed)
            if (!partHost && MainServiced.isInterface(h)) {                  // MVP-001: interface on the worker under P(G)
                if (registerInterface(h)) { chestGuarded++; continue; }
                return refuse("interface inventory not guardable: " + h.getClass().getName());
            }
            if (!partHost && !NO_TOUCH_BLOCK_HOSTS.contains(h.getClass().getSimpleName())) {
                note("block host " + h.getClass().getName());
                if (!census) return refuse("uncovered block host (no transit/snapshot adapter yet): " + h.getClass().getName());
                uncoveredHosts++;
                continue;
            }
            for (Direction d : Direction.values()) {
                BlockPos np = h.getBlockPos().relative(d);
                if (level.getChunkSource().getChunkNow(np.getX() >> 4, np.getZ() >> 4) == null) {
                    return refuse("neighbour chunk not loaded at " + level.dimension().location() + " " + np.toShortString());
                }
                BlockEntity nb = level.getBlockEntity(np);
                if (nb == null || hostKeys.contains(key(nb))) continue;
                if (nb instanceof appeng.api.networking.IInWorldGridNodeHost) continue;   // another grid's host: its own owner
                String type = typeOf(nb);
                if (type == null) continue;
                long[] group = CoreGroupMembers.groupOf(level, np);
                if (group != null && CoreGroupMembers.wholeGroupClaimSafe(level, np)) {
                    // class 1′: an adapter-validated complete group (MasterfulMachinery) is owned whole
                    for (long m : group) {
                        BlockEntity gbe = level.getBlockEntity(BlockPos.of(m));
                        if (gbe == null || typeOf(gbe) == null) return refuse("claim-safe group member missing at " + BlockPos.of(m).toShortString());
                        if (put(out, gbe)) groupMembers++;
                    }
                    continue;
                }
                if (!partHost) continue;                        // whole-block NO_TOUCH host never touches neighbours
                var part = ((appeng.api.parts.IPartHost) h).getPart(d);
                if (part == null || NO_TOUCH_PARTS.contains(part.getClass().getSimpleName())) continue;
                if (MainServiced.part(part)) { mainServiced++; continue; }   // MVP-001: device served on MAIN
                if (MainServiced.isInterface(part)) {                         // MVP-001: interface on the worker under P(G)
                    if (registerInterface(part)) { chestGuarded++; continue; }
                }
                // MVP-001 (owner 2026-11-12): hot-grid storage bus on a radioactive waste barrel — chemical facade ops are
                // synchronous MAIN calls (MekanismChemicalFacadeMainServiceMixin); valve / IF fluid laser base — the tank
                // objects are P(G)-guarded by identity (Mekanism/Forge/Titanium tank guard mixins)
                if (part instanceof appeng.parts.storagebus.StorageBusPart sb2 && grid.size() >= ChestGuard.MIN_NODES) {
                    if ("mekanism:radioactive_waste_barrel".equals(type)) { mainServiced++; continue; }
                    if (("mekanism:thermal_evaporation_valve".equals(type) || "industrialforegoing:fluid_laser_base".equals(type))
                            && registerTanks(nb, d.getOpposite(), sb2)) { chestGuarded++; continue; }
                }
                // MVP-001: chemical P2P input of a hot grid — ingress serialised by P(G) (ChemicalP2PInputGuardMixin)
                if (part.getClass().getName().equals("me.ramidzkh.mekae2.ae2.ChemicalP2PTunnelPart") && grid.size() >= ChestGuard.MIN_NODES) {
                    chestGuarded++;
                    continue;
                }
                // MVP-001 storage-bus option (1), owner-approved exception: Sophisticated handler mounted by a storage bus
                // of the hot grid ⇒ serialised by P(G) for EVERY accessor (ChestGuard) ⇒ covered
                if ((part instanceof appeng.parts.storagebus.StorageBusPart || part.getClass().getName()
                        .equals("net.pedroksl.advanced_ae.common.parts.AdvancedIOBusPart")) && grid.size() >= ChestGuard.MIN_NODES
                        && nb.getClass().getName().startsWith("net.p3pp3rf1y.sophisticatedstorage.")) {
                    Object wrapper = sophisticatedWrapper(nb);
                    if (wrapper != null) {
                        if (part instanceof appeng.parts.storagebus.StorageBusPart sb) ChestGuard.register(wrapper, sb);
                        if (ChestGuard.isGuarded(wrapper)) { chestGuarded++; continue; }
                    }
                }
                if (part instanceof appeng.parts.automation.IOBusPart bus && BusTransit.transitSafe(bus, type)) {
                    transitCovered++;                            // class 2 via transit: NOT claimed, owner executes
                    continue;
                }
                // class 2 reached by a host without a transit-safe adapter: the grid stays model B (fallback, class 4)
                note("part " + part.getClass().getName() + " -> " + type
                        + (part instanceof appeng.parts.automation.IOBusPart b2 && BusTransit.manages(b2)
                                ? " (managed bus: " + BusTransit.whyNotTransitSafe(b2, type) + ")" : ""));
                foreignNeighbours++;
                lastForeignType = part.getClass().getSimpleName() + "->" + type;
            }
        }
        if (uncoveredHosts > 0) return refuse("uncovered block hosts: " + uncoveredHosts);
        if (foreignNeighbours > 0) return refuse("uncovered class-2 ingress: " + foreignNeighbours + " (e.g. " + lastForeignType + ")");
        int total = 0;
        for (var m : out.values()) total += m.size();
        if (total > MAX_PARTICIPANTS) return refuse("too many participants: " + total);
        lastHosts = hosts.size(); lastNeighbours = neighbours; lastGroupMembers = groupMembers; lastTotal = total;
        lastForeign = foreignNeighbours;
        lastMainServiced = mainServiced;
        lastChestGuarded = chestGuarded;
        lastTransitCovered = transitCovered;
        Map<ResourceKey<Level>, long[]> pos = new HashMap<>();
        Map<ResourceKey<Level>, String[]> types = new HashMap<>();
        for (var e : out.entrySet()) {
            var sorted = new java.util.TreeMap<>(e.getValue());
            e.getValue().clear();
            e.getValue().putAll(sorted);
            long[] p = new long[e.getValue().size()];
            String[] t = new String[p.length];
            int i = 0;
            for (var pe : e.getValue().entrySet()) { p[i] = pe.getKey(); t[i] = pe.getValue(); i++; }
            pos.put(e.getKey(), p);
            types.put(e.getKey(), t);
        }
        return new Participants(pos, types, null);
    }

    /** Coverage census (read-only diagnostics): every uncovered (host/part → neighbour type) pair of the last
     *  computation, aggregated per grid size class. Fast-Live closes exactly the paths that appear here. */
    static final Map<String, java.util.concurrent.atomic.AtomicLong> COVERAGE = new java.util.concurrent.ConcurrentHashMap<>();
    static volatile boolean census;

    private static void note(String key) {
        if (census) COVERAGE.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
    }

    static String coverage(int top) {
        var l = new ArrayList<>(COVERAGE.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()));
        StringBuilder sb = new StringBuilder("ownership coverage census (uncovered paths, count = occurrences in computations):");
        for (int i = 0; i < Math.min(top, l.size()); i++) sb.append("\n  ").append(l.get(i).getValue().get()).append("  ").append(l.get(i).getKey());
        return sb.toString();
    }

    static Participants coverageOf(Grid grid) {
        census = true;
        try { return compute(grid); } finally { census = false; }
    }

    /** Identity-register the fluid tank object(s) behind a storage-bus target; false ⇒ not guardable (stays a blocker). */
    private static boolean registerTanks(BlockEntity be, Direction fromBus, appeng.parts.storagebus.StorageBusPart bus) {
        try {
            if (be.getClass().getName().startsWith("mekanism.")) {
                Object data = be.getClass().getMethod("getMultiblock").invoke(be);
                if (data == null) return false;
                Object in = data.getClass().getField("inputTank").get(data), out = data.getClass().getField("outputTank").get(data);
                if (in == null || out == null) return false;
                ChestGuard.register(in, bus);
                ChestGuard.register(out, bus);
                return true;
            }
            var h = be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, fromBus).resolve().orElse(null);
            if (h instanceof net.minecraftforge.fluids.capability.templates.FluidTank tank) {
                ChestGuard.register(tank, bus);
                return true;
            }
            // Titanium MultiTankComponent$MultiTankCapabilityHandler: delegates to its FluidTankComponent list (each
            // guarded by TitaniumFluidTankGuardMixin) ⇒ register every inner tank by identity
            if (h != null && h.getClass().getName().equals("com.hrznstudio.titanium.component.fluid.MultiTankComponent$MultiTankCapabilityHandler")) {
                var f = h.getClass().getDeclaredField("tanks");
                f.setAccessible(true);
                java.util.List<?> tanks = (java.util.List<?>) f.get(h);
                if (tanks == null || tanks.isEmpty()) return false;
                for (Object t : tanks) {
                    if (!(t instanceof net.minecraftforge.fluids.capability.templates.FluidTank)) return false;
                }
                for (Object t : tanks) ChestGuard.register(t, bus);
                return true;
            }
            note("unguardable tank handler " + (h == null ? "null" : h.getClass().getName()) + " at " + be.getClass().getName());
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            note("tank registration failed " + be.getClass().getName() + ": " + e);
            return false;
        }
    }

    /** Lazily (here, not at construction: ExtendedAE Oversize swaps its inventories after the InterfaceLogic ctor)
     *  identity-register the interface's storage + config inventories with the logic as node source. */
    private static boolean registerInterface(Object host) {
        if (!(host instanceof appeng.helpers.InterfaceLogicHost ih)) return false;
        var logic = ih.getInterfaceLogic();
        if (logic == null || logic.getStorage() == null || logic.getConfig() == null) return false;
        ChestGuard.register(logic.getStorage(), logic);
        ChestGuard.register(logic.getConfig(), logic);
        return true;
    }

    private static Object sophisticatedWrapper(BlockEntity be) {
        try { return be.getClass().getMethod("getStorageWrapper").invoke(be); } catch (ReflectiveOperationException e) { return null; }
    }

    private static Participants refuse(String why) {
        return new Participants(Map.of(), Map.of(), why);
    }

    private static boolean put(Map<ResourceKey<Level>, Map<Long, String>> out, BlockEntity be) {
        String t = typeOf(be);
        if (t == null) return false;
        return out.computeIfAbsent(be.getLevel().dimension(), k -> new HashMap<>()).put(be.getBlockPos().asLong(), t) == null;
    }

    private static long key(BlockEntity be) {
        return be.getBlockPos().asLong() ^ ((long) System.identityHashCode(be.getLevel()) << 1);
    }

    private static String typeOf(BlockEntity be) {
        var k = BlockEntityType.getKey(be.getType());
        return k == null ? null : k.toString();
    }

    static String status() {
        return "ownership participants: computed=" + computed.sum() + " refused=" + refused.sum() + " lastRefusal=" + lastRefusal
                + " | last claimable set: positions=" + lastTotal + " claimedByReason{nodeHost=" + lastHosts + " MM-group=" + lastGroupMembers
                + "} class-2 via transit (not claimed)=" + lastTransitCovered + " MAIN-serviced (MVP-001)=" + lastMainServiced + " P(G)-guarded chests=" + lastChestGuarded + " uncovered class-2 (keeps grid B)=" + lastForeign;
    }
}
