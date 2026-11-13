package net.nestworld.compat.ae2.glue;

import appeng.api.parts.IPartHost;
import appeng.api.parts.PartHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.util.AEColor;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEParts;
import appeng.parts.automation.IOBusPart;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

/**
 * AE2-GRID-DOMAIN-MVP-001 storage-bus option (1) targeted fixture (test copy only, fixtures flag). One grid WITHOUT a
 * drive: the only network storage is a Sophisticated chest behind a storage bus, so every import/export goes through
 * the guarded handler. A vanilla hopper under the chest (its own domain — normally a REGION thread) pulls from the same
 * handler concurrently: the second accessor that must enter through P(G).
 * <pre>
 *   cell  h0[import→inBarrel]  h1[storage bus→chest]  h2[export→outBarrel]      chest ↓ hopper ↓ hopBarrel
 * </pre>
 * Conservation: chest + hopper + inBarrel + outBarrel + hopBarrel + transit held = created − consumed.
 */
public final class ChestGuardFixture {
    private ChestGuardFixture() {}

    private static ServerLevel level;
    private static BlockPos cell, h0, h1, h2, inBarrel, chest, outBarrel, hopper, hopBarrel;
    static final AtomicLong created = new AtomicLong(), consumed = new AtomicLong();
    private static volatile boolean gen;
    private static final AtomicLong sum = new AtomicLong();
    private static final AtomicInteger pending = new AtomicInteger();
    private static long held, createdAt, consumedAt;
    private static final AEItemKey COBBLE = AEItemKey.of(Items.COBBLESTONE);

    public static String build(ServerLevel lvl, BlockPos base) {
        level = lvl;
        cell = base; h0 = base.east(1); h1 = base.east(2); h2 = base.east(3);
        inBarrel = h0.north(); chest = h1.north(); outBarrel = h2.north(); hopper = chest.below(); hopBarrel = hopper.below();
        var chestBlock = BuiltInRegistries.BLOCK.get(new ResourceLocation("sophisticatedstorage", "chest"));
        if (chestBlock == Blocks.AIR) return "fixture requires sophisticatedstorage:chest";
        level.setBlockAndUpdate(cell, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        level.setBlockAndUpdate(inBarrel, Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(outBarrel, Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(chest, chestBlock.defaultBlockState());
        level.setBlockAndUpdate(hopper, Blocks.HOPPER.defaultBlockState());     // facing DOWN: pulls from chest, pushes below
        level.setBlockAndUpdate(hopBarrel, Blocks.BARREL.defaultBlockState());
        for (BlockPos h : new BlockPos[] { h0, h1, h2 }) PartHelper.setPart(level, h, null, null, AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT));
        IOBusPart in = PartHelper.setPart(level, h0, Direction.NORTH, null, AEParts.IMPORT_BUS.asItem());
        var sb = PartHelper.setPart(level, h1, Direction.NORTH, null, AEParts.STORAGE_BUS.asItem());
        IOBusPart out = PartHelper.setPart(level, h2, Direction.NORTH, null, AEParts.EXPORT_BUS.asItem());
        if (in == null || sb == null || out == null) return "parts not placed";
        out.getConfig().setStack(0, new GenericStack(COBBLE, 1));
        created.set(0); consumed.set(0);
        return "chestguard built base=" + base.toShortString() + " chest domain=" + BeDomainReady.domainName(level, chest, "sophisticatedstorage:chest")
                + " hopper domain=" + BeDomainReady.domainName(level, hopper, "minecraft:hopper")
                + " host domain=" + BeDomainReady.domainName(level, h1, "ae2:cable_bus");
    }

    /** Hopper on/off (blockstate ENABLED, set in the hopper's own domain) — quiescence of the REGION accessor. */
    public static String hopper(boolean on) {
        if (level == null) return "no fixture";
        inDomain(hopper, "minecraft:hopper", () -> {
            var st = level.getBlockState(hopper);
            if (st.hasProperty(net.minecraft.world.level.block.HopperBlock.ENABLED))
                level.setBlock(hopper, st.setValue(net.minecraft.world.level.block.HopperBlock.ENABLED, on), 4);
        });
        return "chestguard hopper " + (on ? "enabled" : "disabled");
    }

    public static String gen(boolean on) { gen = on; return "chestguard generator " + (on ? "on" : "off"); }

    /** Export pause = config cleared (no work, no neighbour access); under P(G) like the station pause fixture. */
    public static String pause(boolean on) {
        if (level == null || !(level.getBlockEntity(h2) instanceof IPartHost host) || !(host.getPart(Direction.NORTH) instanceof IOBusPart out)) return "no fixture";
        var node = out.getGridNode();
        var g = node == null ? null : (appeng.me.Grid) node.getGrid();
        if (g == null) return "no grid";
        var p = ((NwGrid) g).nw$p();
        p.lock();
        try { out.getConfig().setStack(0, on ? null : new GenericStack(COBBLE, 1)); } finally { p.unlock(); }
        return "chestguard export " + (on ? "paused" : "resumed");
    }

    /** Server tick (fixtures): generator tops up inBarrel, consumers empty outBarrel and hopBarrel — each in its domain. */
    static void tick() {
        if (level == null) return;
        if (gen) inDomain(inBarrel, "minecraft:barrel", () -> topUp(inBarrel));
        inDomain(outBarrel, "minecraft:barrel", () -> drain(outBarrel));
        inDomain(hopBarrel, "minecraft:barrel", () -> drain(hopBarrel));
    }

    private static void topUp(BlockPos b) {
        if (!(level.getBlockEntity(b) instanceof Container c)) return;
        int added = 0;
        for (int i = 0; i < 4; i++) {
            ItemStack st = c.getItem(i);
            if (st.isEmpty()) { c.setItem(i, new ItemStack(Items.COBBLESTONE, 64)); added += 64; }
            else if (st.is(Items.COBBLESTONE) && st.getCount() < 64) { added += 64 - st.getCount(); st.setCount(64); }
        }
        if (added > 0) { created.addAndGet(added); c.setChanged(); }
    }

    private static void drain(BlockPos b) {
        if (!(level.getBlockEntity(b) instanceof Container c)) return;
        long took = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            if (st.isEmpty()) continue;
            took += st.getCount();
            c.setItem(i, ItemStack.EMPTY);
        }
        if (took > 0) { consumed.addAndGet(took); c.setChanged(); }
    }

    private static void inDomain(BlockPos b, String type, Runnable r) {
        try {
            if (BusTransit.domainIsMain(level, b, type)) r.run();
            else BeDomainReady.enqueueTo(level, b, type, r, k -> { });
        } catch (Throwable t) {
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] chestguard fixture enqueue failed: {}", t.toString());
        }
    }

    /** Snapshot request: every container counted in its own domain; transit held for the three hosts now. */
    public static String count() {
        if (level == null) return "no fixture";
        sum.set(0);
        pending.set(5);
        long h = 0;
        for (BusTransit.Rec r : BusTransit.LIVE_INDEX()) {
            if (!r.hostPos.equals(h0) && !r.hostPos.equals(h1) && !r.hostPos.equals(h2)) continue;
            synchronized (r) { h += r.heldTotal() + r.inflightTotal(); }
        }
        held = h;
        createdAt = created.get();
        consumedAt = consumed.get();
        for (BlockPos b : new BlockPos[] { inBarrel, outBarrel, hopBarrel }) inDomain(b, "minecraft:barrel", () -> { sum.addAndGet(cobble(b)); pending.decrementAndGet(); });
        inDomain(hopper, "minecraft:hopper", () -> { sum.addAndGet(cobble(hopper)); pending.decrementAndGet(); });
        inDomain(chest, "sophisticatedstorage:chest", () -> {
            long n = 0;
            var be = level.getBlockEntity(chest);
            var cap = be == null ? null : be.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
            if (cap != null) for (int i = 0; i < cap.getSlots(); i++) { var st = cap.getStackInSlot(i); if (st.is(Items.COBBLESTONE)) n += st.getCount(); }
            sum.addAndGet(n);
            pending.decrementAndGet();
        });
        return "chestguard count requested";
    }

    private static long cobble(BlockPos b) {
        if (!(level.getBlockEntity(b) instanceof Container c)) return 0;
        long n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.COBBLESTONE)) n += c.getItem(i).getCount();
        return n;
    }

    public static String status() {
        if (pending.get() > 0) return "chestguard count pending " + pending.get();
        long total = sum.get() + held;
        return "chestguard total=" + total + " (containers=" + sum.get() + " transitHeld=" + held + ") created=" + createdAt + " consumed="
                + consumedAt + " invariant(total-created+consumed)=" + (total - createdAt + consumedAt) + " outstanding="
                + BusTransit.C.outstanding.get() + " | " + ChestGuard.status().replace("\n", " ");
    }
}
