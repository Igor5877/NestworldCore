package net.nestworld.compat.ae2.glue;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.IPartHost;
import appeng.api.parts.PartHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.util.AEColor;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.core.definitions.AEParts;
import appeng.me.Grid;
import appeng.parts.automation.ExportBusPart;
import appeng.parts.automation.IOBusPart;
import appeng.parts.automation.ImportBusPart;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * WP3-B deterministic fixtures (fixtures flag only). A station = creative energy cell + drive (item cells) + a row of
 * cable buses, each with one plain AE2 import or export bus facing its own barrel (vanilla, unpinned → REGION domain).
 * A generator keeps import barrels topped up / drains export barrels in the barrel's domain (created / consumed
 * counted), so the conservation equation is exact: network + barrels + transit + dropped = constant + created − consumed.
 * Windows count scheduled operations and network delta at exact server ticks (reference vs transit).
 */
public final class BusTransitFixtures {
    public static final boolean ENABLED = Boolean.getBoolean("nestworld.compatAe2.fixtures");

    static final class Station {
        final int id;
        final boolean export;
        final ServerLevel level;
        final BlockPos base;
        final int n;
        String sourceType = "minecraft:barrel";
        Direction busSide = Direction.SOUTH;
        final List<BlockPos> hosts = new ArrayList<>(), barrels = new ArrayList<>();
        final BlockPos drive, cell;
        final AtomicLong created = new AtomicLong(), consumed = new AtomicLong();
        volatile boolean gen;
        // count snapshot (barrels counted in their own domain)
        final AtomicLong barrelSum = new AtomicLong(), entityItems = new AtomicLong();
        final AtomicInteger countPending = new AtomicInteger();
        long countNetwork = -1, countHeld, countCreated, countConsumed, countDropped;
        String countLabel = "";
        // window
        int winStart = -1, winEnd = -1;
        long winSched0, winNet0, winSchedDelta = -1, winNetDelta, winCreated0, winCreatedDelta;
        long winMainInline0, winMainTransit0, winCommit0, winRegion0, winOpsInline0, winOpsTransit0;
        String winResult = "";

        Station(int id, boolean export, ServerLevel level, BlockPos base, int n) {
            this.id = id;
            this.export = export;
            this.level = level;
            this.base = base;
            this.n = n;
            this.cell = base;
            this.drive = base.east();
        }
    }

    static final List<Station> STATIONS = new ArrayList<>();
    static final AEItemKey COBBLE = AEItemKey.of(Items.COBBLESTONE);
    static final int GEN_LEVEL = 27 * 64 / 2;      // keep import barrels at least half full

    private BusTransitFixtures() {
    }

    static Station st(int id) {
        for (Station s : STATIONS) if (s.id == id) return s;
        throw new IllegalArgumentException("no station " + id);
    }

    // ---------------------------------------------------------------- build

    public static String build(ServerLevel level, String kind, BlockPos base, int n, int speedCards, long prefill) {
        boolean export = kind.startsWith("e");
        Station s = new Station(STATIONS.size() + 1, export, level, base, n);
        if (kind.equals("importenergy")) { s.sourceType = "minecraft:furnace"; s.busSide = Direction.UP; }
        if (kind.equals("importchest")) s.sourceType = "minecraft:chest";   // never LocalImportSource.local (handoff path)
        level.setBlockAndUpdate(s.cell, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        level.setBlockAndUpdate(s.drive, AEBlocks.DRIVE.block().defaultBlockState());
        if (level.getBlockEntity(s.drive) instanceof DriveBlockEntity d) {
            for (int i = 0; i < 4; i++) d.getInternalInventory().setItemDirect(i, AEItems.ITEM_CELL_256K.stack());
            if (kind.equals("importenergy")) {
                var energyCell = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        new net.minecraft.resources.ResourceLocation("appflux", "fe_1k_cell"));
                if (energyCell == Items.AIR) return "fixture requires AppliedFlux FE cell";
                d.getInternalInventory().setItemDirect(4, new ItemStack(energyCell));
            }
        } else return "drive not placed";
        for (int i = 0; i < n; i++) {
            BlockPos h = base.east(2 + i);
            BlockPos b = h.relative(s.busSide);
            if (kind.equals("importenergy")) BusImportCursorFixtures.prepare(level, b);
            level.setBlockAndUpdate(b, sourceBlock(s).defaultBlockState());
            if (kind.equals("importenergy") && level.getBlockEntity(b) instanceof Container source) {
                source.setItem(2, new ItemStack(Items.COBBLESTONE));
                s.created.incrementAndGet();
            }
            PartHelper.setPart(level, h, null, null, AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT));
            IOBusPart bus = export ? PartHelper.setPart(level, h, s.busSide, null, AEParts.EXPORT_BUS.asItem())
                    : PartHelper.setPart(level, h, s.busSide, null, AEParts.IMPORT_BUS.asItem());
            if (bus == null) return "bus not placed at " + h;
            for (int k = 0; k < speedCards; k++) bus.getUpgrades().addItems(AEItems.SPEED_CARD.stack());
            if (export) bus.getConfig().setStack(0, new GenericStack(COBBLE, 1));
            s.hosts.add(h);
            s.barrels.add(b);
        }
        STATIONS.add(s);
        if (prefill > 0) s.created.addAndGet(prefill);
        s.countLabel = "prefill-pending:" + prefill;
        pendingPrefill.add(new long[]{s.id, prefill});
        return "station " + s.id + " " + kind + " n=" + n + " speedCards=" + speedCards + " base=" + base.toShortString()
                + " barrel0 domain=" + BeDomainReady.domainName(level, s.barrels.get(0), s.sourceType)
                + " host0 domain=" + BeDomainReady.domainName(level, s.hosts.get(0), "ae2:cable_bus");
    }

    /** Re-register an existing station after a restart (no blocks placed; counters start at 0). */
    public static String attach(ServerLevel level, String kind, BlockPos base, int n) {
        Station s = new Station(STATIONS.size() + 1, kind.startsWith("e"), level, base, n);
        if (kind.equals("importenergy")) { s.sourceType = "minecraft:furnace"; s.busSide = Direction.UP; }
        if (kind.equals("importchest")) s.sourceType = "minecraft:chest";   // never LocalImportSource.local (handoff path)
        for (int i = 0; i < n; i++) {
            s.hosts.add(base.east(2 + i));
            s.barrels.add(base.east(2 + i).relative(s.busSide));
        }
        STATIONS.add(s);
        s.countLabel = "attached";
        return "attached station " + s.id + " " + kind + " n=" + n + " buses=" + busTypes(s.id);
    }

    static final List<long[]> pendingPrefill = new ArrayList<>();

    static net.minecraft.world.level.block.Block sourceBlock(Station s) {
        return switch (s.sourceType) {
            case "minecraft:furnace" -> Blocks.FURNACE;
            case "minecraft:chest" -> Blocks.CHEST;
            default -> Blocks.BARREL;
        };
    }

    static Grid gridOf(Station s) {
        if (!(s.level.getBlockEntity(s.drive) instanceof DriveBlockEntity d)) return null;
        IGridNode node = d.getMainNode().getNode();
        return node == null || !(node.getGrid() instanceof Grid g) ? null : g;
    }

    static long network(Station s) {
        Grid g = gridOf(s);
        if (g == null) return -1;
        // the grid may be run by the AE2 worker (model A/B): read it under P(G), never concurrently with its phase
        var p = ((NwGrid) g).nw$p();
        p.lock();
        try {
            return g.getStorageService().getInventory().getAvailableStacks().get(COBBLE);
        } finally {
            p.unlock();
        }
    }

    // ---------------------------------------------------------------- AE2-GRID-DOMAIN-001 (c) gate scenarios

    /** Target replacement between enqueue and execute: every barrel becomes stone (contents drop as loose items,
     *  counted), and back to an empty barrel on the second call. Runs in each barrel's own domain. */
    public static String replaceTargets(int id, boolean restore) {
        Station s = st(id);
        for (BlockPos b : s.barrels) inDomain(s, b, () -> {
            if (!restore && s.level.getBlockEntity(b) instanceof Container c) {
                // the replaced inventory's contents leave the world through the ledger (consumed), not as falling
                // item entities that drift out of the counting box — only transit accounting is under test here
                long n = 0;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (c.getItem(i).is(Items.COBBLESTONE)) n += c.getItem(i).getCount();
                    c.setItem(i, ItemStack.EMPTY);
                }
                s.consumed.addAndGet(n);
            }
            s.level.setBlockAndUpdate(b, restore ? sourceBlock(s).defaultBlockState()
                    : Blocks.STONE.defaultBlockState());
        });
        return "station " + id + " targets " + (restore ? "restored" : "replaced");
    }

    /** Owner/domain change before execution: position-pin the targets to MAIN (and release on the second call). */
    public static String pinTargets(int id, boolean pin) {
        Station s = st(id);
        long[] pos = new long[s.barrels.size()];
        for (int i = 0; i < pos.length; i++) pos[i] = s.barrels.get(i).asLong();
        if (pin) NeighborAffinity.pinRaw(s.level, pos); else NeighborAffinity.releaseRaw(s.level, pos);
        return "station " + id + " targets " + (pin ? "pinned to MAIN" : "released") + " now " + BeDomainReady.domainName(s.level, s.barrels.get(0), s.sourceType);
    }

    /** Duplicate delivery of one opId: re-publish every live record of the station once more. */
    public static String duplicateDelivery(int id) {
        Station s = st(id);
        int n = 0;
        for (BusTransit.Rec r : BusTransit.LIVE_INDEX()) {
            if (!s.hosts.contains(r.hostPos)) continue;
            BusTransit.publish(r);
            n++;
        }
        return "station " + id + " re-published " + n + " live record(s)";
    }

    // ---------------------------------------------------------------- per-tick (MAIN, server END)

    public static void onServerTickEnd(int tick) {
        if (!ENABLED) return;
        if (!pendingPrefill.isEmpty()) {
            for (var it = pendingPrefill.iterator(); it.hasNext(); ) {
                long[] p = it.next();
                Station s = st((int) p[0]);
                Grid g = gridOf(s);
                if (g == null) continue;
                long ins = g.getStorageService().getInventory().insert(COBBLE, p[1], Actionable.MODULATE, IActionSource.empty());
                if (ins == 0) continue;            // drive not booted yet
                if (ins != p[1]) s.created.addAndGet(ins - p[1]);
                s.countLabel = "prefilled:" + ins;
                it.remove();
            }
        }
        ChestGuardFixture.tick();
        for (Station s : STATIONS) {
            if (s.gen) for (BlockPos b : s.barrels) inDomain(s, b, () -> genStep(s, b));
            if (s.winStart == tick) {
                Grid g = gridOf(s);
                BusTransit.GridState gs = g == null ? null : BusTransit.stateOf(g);
                s.winSched0 = gs == null ? 0 : gs.scheduled.sum();
                s.winNet0 = network(s);
                s.winCreated0 = s.export ? s.consumed.get() : s.created.get();
                var c = BusTransit.C;
                s.winMainInline0 = c.mainOpNsInline.sum();
                s.winMainTransit0 = c.mainOpNsTransit.sum();
                s.winCommit0 = c.commitNs.sum();
                s.winRegion0 = c.regionNs.sum();
                s.winOpsInline0 = c.mainOpsInline.sum();
                s.winOpsTransit0 = c.mainOpsTransit.sum();
            }
            if (s.winEnd == tick) {
                Grid g = gridOf(s);
                BusTransit.GridState gs = g == null ? null : BusTransit.stateOf(g);
                s.winSchedDelta = (gs == null ? 0 : gs.scheduled.sum()) - s.winSched0;
                s.winNetDelta = network(s) - s.winNet0;
                s.winCreatedDelta = (s.export ? s.consumed.get() : s.created.get()) - s.winCreated0;
                var c = BusTransit.C;
                long opsI = c.mainOpsInline.sum() - s.winOpsInline0, opsT = c.mainOpsTransit.sum() - s.winOpsTransit0;
                s.winResult = "window station=" + s.id + (s.export ? " export" : " import") + " ticks=" + (s.winEnd - s.winStart)
                        + " transit=" + BusTransit.active + " scheduledOps=" + s.winSchedDelta + " networkDelta=" + s.winNetDelta
                        + " perOp=" + (s.winSchedDelta == 0 ? 0 : Math.abs(s.winNetDelta) / (double) s.winSchedDelta)
                        + " generator=" + s.winCreatedDelta + " mainOpInlineUs=" + (c.mainOpNsInline.sum() - s.winMainInline0) / 1000
                        + "/" + opsI + " mainOpTransitUs=" + (c.mainOpNsTransit.sum() - s.winMainTransit0) / 1000 + "/" + opsT
                        + " commitUs=" + (c.commitNs.sum() - s.winCommit0) / 1000 + " regionUs=" + (c.regionNs.sum() - s.winRegion0) / 1000;
                Ae2Compat.LOG.info("[nestworld-compat-ae2] bus transit fixture {}", s.winResult);
            }
        }
    }

    static void inDomain(Station s, BlockPos b, Runnable r) {
        try {
            if (BusTransit.domainIsMain(s.level, b, s.sourceType)) r.run();
            else BeDomainReady.enqueueTo(s.level, b, s.sourceType, r, k -> { });
        } catch (Throwable t) {
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] bus transit fixture enqueue failed: {}", t.toString());
        }
    }

    /** Barrel's domain: import → top up with cobblestone (created); export → empty it (consumed). */
    static void genStep(Station s, BlockPos b) {
        if (!(s.level.getBlockEntity(b) instanceof Container c)) return;
        if (s.export) {
            long took = 0;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st.isEmpty()) continue;
                took += st.getCount();
                c.setItem(i, ItemStack.EMPTY);
            }
            if (took > 0) {
                s.consumed.addAndGet(took);
                c.setChanged();
            }
            return;
        }
        int have = 0;
        for (int i = 0; i < c.getContainerSize(); i++) have += c.getItem(i).getCount();
        if (have >= GEN_LEVEL) return;
        int added = 0;
        for (int i = 0; i < c.getContainerSize() && have + added < GEN_LEVEL; i++) {
            ItemStack st = c.getItem(i);
            if (st.isEmpty()) {
                c.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
                added += 64;
            } else if (st.is(Items.COBBLESTONE) && st.getCount() < 64) {
                added += 64 - st.getCount();
                st.setCount(64);
            }
        }
        if (added > 0) {
            s.created.addAndGet(added);
            c.setChanged();
        }
    }

    // ---------------------------------------------------------------- commands

    public static String gen(int id, boolean on) {
        st(id).gen = on;
        return "station " + id + " generator " + (on ? "on" : "off");
    }

    public static String window(int id, int now, int ticks) {
        Station s = st(id);
        s.winStart = now + 2;
        s.winEnd = now + 2 + ticks;
        s.winResult = "pending";
        return "window station " + id + " ticks " + s.winStart + ".." + s.winEnd;
    }

    public static String windowStatus(int id) {
        return st(id).winResult;
    }

    /** Snapshot (call at quiescence): network (MAIN), barrels + loose item entities (their domain), transit held. */
    public static String count(int id, String label) {
        Station s = st(id);
        s.countLabel = label;
        s.barrelSum.set(0);
        s.entityItems.set(0);
        s.countNetwork = network(s);
        long held = 0;
        for (BusTransit.Rec r : BusTransit.LIVE_INDEX()) {
            if (!s.hosts.contains(r.hostPos)) continue;
            synchronized (r) {
                held += r.heldTotal() + r.inflightTotal();
            }
        }
        s.countHeld = held;                  // this station's records only
        s.countCreated = s.created.get();
        s.countConsumed = s.consumed.get();
        s.countDropped = BusTransit.C.dropped.sum();
        s.countPending.set(s.barrels.size() + 1);
        for (BlockPos b : s.barrels) {
            inDomain(s, b, () -> {
                if (s.level.getBlockEntity(b) instanceof Container c) {
                    long n = 0;
                    for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.COBBLESTONE)) n += c.getItem(i).getCount();
                    s.barrelSum.addAndGet(n);
                }
                s.countPending.decrementAndGet();
            });
        }
        BlockPos b0 = s.barrels.get(0);
        inDomain(s, b0, () -> {
            AABB box = new AABB(s.base.offset(-2, -3, -3), s.base.offset(s.n + 4, 4, 4));
            long n = 0;
            for (ItemEntity e : s.level.getEntitiesOfClass(ItemEntity.class, box)) {
                if (e.getItem().is(Items.COBBLESTONE)) n += e.getItem().getCount();
            }
            s.entityItems.set(n);
            s.countPending.decrementAndGet();
        });
        return "count " + label + " requested";
    }

    public static String countStatus(int id) {
        Station s = st(id);
        if (s.countPending.get() > 0) return "count " + s.countLabel + " pending " + s.countPending.get();
        long total = s.countNetwork + s.barrelSum.get() + s.countHeld + s.entityItems.get();
        return "count " + s.countLabel + " station=" + s.id + " network=" + s.countNetwork + " barrels=" + s.barrelSum.get()
                + " transitHeld=" + s.countHeld + " looseItems=" + s.entityItems.get() + " total=" + total + " created="
                + s.countCreated + " consumed=" + s.countConsumed + " invariant(total-created+consumed)="
                + (total - s.countCreated + s.countConsumed) + " droppedLedger=" + s.countDropped + " outstanding="
                + BusTransit.C.outstanding.get();
    }

    /** Fill every barrel of the station completely with cobblestone (created counted), in the barrel's domain. */
    public static String fill(int id) {
        Station s = st(id);
        for (BlockPos b : s.barrels) {
            inDomain(s, b, () -> {
                if (!(s.level.getBlockEntity(b) instanceof Container c)) return;
                long added = 0;
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack st = c.getItem(i);
                    if (st.isEmpty()) {
                        c.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
                        added += 64;
                    } else if (st.is(Items.COBBLESTONE)) {
                        added += 64 - st.getCount();
                        st.setCount(64);
                    }
                }
                if (added > 0) {
                    s.created.addAndGet(added);
                    c.setChanged();
                }
            });
        }
        return "filled barrels of station " + id;
    }

    /** Break k buses (part removal; host stays). */
    public static String breakBuses(int id, int k) {
        Station s = st(id);
        int done = 0;
        for (int i = 0; i < Math.min(k, s.hosts.size()); i++) {
            if (s.level.getBlockEntity(s.hosts.get(i)) instanceof IPartHost h && h.getPart(s.busSide) != null) {
                h.removePartFromSide(s.busSide);
                done++;
            }
        }
        return "broke " + done + " buses of station " + id + " outstanding=" + BusTransit.C.outstanding.get();
    }

    /** Re-add the buses (import/export, same speed cards / config) after a break. */
    public static String rebuild(int id, int speedCards) {
        Station s = st(id);
        int done = 0;
        for (BlockPos h : s.hosts) {
            if (s.level.getBlockEntity(h) instanceof IPartHost host && host.getPart(s.busSide) == null) {
                IOBusPart bus = s.export ? PartHelper.setPart(s.level, h, s.busSide, null, AEParts.EXPORT_BUS.asItem())
                        : PartHelper.setPart(s.level, h, s.busSide, null, AEParts.IMPORT_BUS.asItem());
                if (bus == null) continue;
                for (int k = 0; k < speedCards; k++) bus.getUpgrades().addItems(AEItems.SPEED_CARD.stack());
                if (s.export) bus.getConfig().setStack(0, new GenericStack(COBBLE, 1));
                done++;
            }
        }
        return "rebuilt " + done;
    }

    /** Quiescence for counting: clear (pause) / restore the station's export-bus config under P(G) — an export bus
     *  with nothing configured does no work and touches no neighbour, so network + barrels + transit stop moving.
     *  Import stations quiesce with the generator off (barrels drain). No topology change. */
    public static String pause(int id, boolean pause) {
        Station s = st(id);
        if (!s.export) return "station " + id + " is an import station (use gen off)";
        Grid g = gridOf(s);
        if (g == null) return "station " + id + " has no grid";
        var p = ((NwGrid) g).nw$p();
        int done = 0;
        p.lock();
        try {
            for (BlockPos h : s.hosts) {
                if (s.level.getBlockEntity(h) instanceof IPartHost host && host.getPart(s.busSide) instanceof IOBusPart bus) {
                    if (pause) bus.getConfig().setStack(0, null);
                    else bus.getConfig().setStack(0, new GenericStack(COBBLE, 1));
                    done++;
                }
            }
        } finally {
            p.unlock();
        }
        return (pause ? "paused " : "resumed ") + done + " export buses of station " + id + " outstanding=" + BusTransit.C.outstanding.get();
    }

    /** Grid split: remove the center cable of the first host (all buses lose the drive) / restore it. */
    public static String split(int id, boolean cut) {
        Station s = st(id);
        BlockPos h = s.hosts.get(0);
        if (s.level.getBlockEntity(h) instanceof IPartHost host) {
            if (cut) host.removePartFromSide(null);
            else PartHelper.setPart(s.level, h, null, null, AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT));
        }
        return (cut ? "cut" : "restored") + " cable of station " + id + " outstanding=" + BusTransit.C.outstanding.get();
    }

    /** Grid gone: the drive's network loses power (energy cell removed) / restored. */
    public static String power(int id, boolean on) {
        Station s = st(id);
        s.level.setBlockAndUpdate(s.cell, on ? AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState() : Blocks.AIR.defaultBlockState());
        return "station " + id + " power " + (on ? "on" : "off");
    }

    public static String busTypes(int id) {
        Station s = st(id);
        StringBuilder sb = new StringBuilder();
        for (BlockPos h : s.hosts) {
            if (s.level.getBlockEntity(h) instanceof IPartHost host) {
                var p = host.getPart(s.busSide);
                sb.append(p == null ? "-" : p instanceof ImportBusPart ? "I" : p instanceof ExportBusPart ? "E" : "?");
            }
        }
        return sb.toString();
    }

    public static String list() {
        StringBuilder sb = new StringBuilder();
        for (Station s : STATIONS) {
            Grid g = gridOf(s);
            sb.append("station ").append(s.id).append(s.export ? " export" : " import").append(" n=").append(s.n).append(" grid=")
                    .append(g).append(" buses=").append(busTypes(s.id)).append(" created=").append(s.created.get())
                    .append(" consumed=").append(s.consumed.get()).append(" gen=").append(s.gen).append(" barrel0=")
                    .append(BeDomainReady.domainName(s.level, s.barrels.get(0), s.sourceType)).append(" ")
                    .append(s.countLabel).append('\n');
        }
        return sb.toString();
    }

    public static String resetMetrics() {
        synchronized (BusTransit.ALL_GRIDS_VIEW()) {
            for (BusTransit.GridState g : BusTransit.ALL_GRIDS_VIEW()) {
                java.util.Arrays.fill(g.latImport, 0);
                java.util.Arrays.fill(g.latExport, 0);
                g.budgetExhausted = 0;
                g.starvationTicks = 0;
                g.maxBacklog = 0;
                g.mainCommitNs = 0;
                g.mainOpNs = 0;
                g.scheduled.reset();
                g.completed.reset();
                g.missed.reset();
                g.cancelled.reset();
            }
        }
        return "per-grid metrics reset";
    }
}
