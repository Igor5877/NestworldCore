package net.nestworld.compat.ae2.glue;

import appeng.api.networking.IGrid;
import appeng.api.parts.PartHelper;
import appeng.api.util.AEColor;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEParts;
import appeng.me.service.P2PService;
import appeng.parts.p2p.P2PTunnelPart;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;

/**
 * AE2-COMPAT-P2P-001 P2P-9 (owner, 2026-10-15): reproduce H3 WITHOUT any fix. Conservation of one
 * synchronous P2P transfer whose input is driven on the input region's thread and whose output faces
 * a target in (A) the same owner, (B) another region's ghost zone, (C) deep in another region.
 *
 * <pre>
 * setup (main):  glass cable line in → out, creative cell above in; P2P input on in (side DOWN),
 *                P2P output on out (side UP) facing target = out.above(); one frequency.
 * run:           host near target (owner B): read live target amount E0
 *                host near in     (thread A): view = level.getBlockEntity(target)   [the same call AE2 makes]
 *                                             accepted = input.receiveEnergy / insertItem
 *                host near target (owner B): read live target amount E1
 * verdict:       conservation  accepted == E1 - E0      (no transport buffer exists)
 *                view          LIVE (same instance as the owner's) | DETACHED (other instance) | NULL
 *                crossOwner    LIVE && threadA != threadB && E1 > E0
 * </pre>
 * Fixture mode only. Changes nothing in AE2's P2P behaviour.
 */
public final class P2PFixtures {
    private static volatile String status = "idle";
    private static volatile Runnable pending;
    private static volatile int evaluateAt = Integer.MAX_VALUE;

    public static String status() {
        return status;
    }

    public static void onServerTickEnd(int tick) {
        Runnable p = pending;
        if (p == null || tick < evaluateAt) return;
        pending = null;
        evaluateAt = Integer.MAX_VALUE;
        p.run();
    }

    private static UUID host(ServerLevel level, BlockPos near) {
        var hosts = level.getEntitiesOfClass(ArmorStand.class, new AABB(near).inflate(6));
        return hosts.isEmpty() ? null : hosts.get(0).getUUID();
    }

    /** Builds the in → out tunnel pair (main thread). Returns null on success, else a reason. */
    public static String setup(ServerLevel level, String kind, BlockPos in, BlockPos out) {
        if (in.getY() != out.getY()) return "in and out must share y";
        var cable = AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT);
        List<BlockPos> line = new ArrayList<>();
        int x = in.getX(), z = in.getZ();
        line.add(new BlockPos(x, in.getY(), z));
        while (x != out.getX()) { x += Integer.signum(out.getX() - x); line.add(new BlockPos(x, in.getY(), z)); }
        while (z != out.getZ()) { z += Integer.signum(out.getZ() - z); line.add(new BlockPos(x, in.getY(), z)); }
        for (BlockPos p : line) {
            if (PartHelper.setPart(level, p, null, null, cable) == null) return "cable placement failed at " + p;
        }
        level.setBlock(in.above(), AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState(), 3);
        appeng.api.parts.IPartItem<?> item = kind.equals("item") ? AEParts.ITEM_P2P_TUNNEL.asItem()
                : kind.equals("redstone") ? AEParts.REDSTONE_P2P_TUNNEL.asItem() : AEParts.FE_P2P_TUNNEL.asItem();
        if (PartHelper.setPart(level, in, Direction.DOWN, null, item) == null) return "input tunnel placement failed";
        if (PartHelper.setPart(level, out, Direction.UP, null, item) == null) return "output tunnel placement failed";
        Block target = kind.equals("item") ? BuiltInRegistries.BLOCK.get(new ResourceLocation("minecraft", "chest"))
                : kind.equals("redstone") ? BuiltInRegistries.BLOCK.get(new ResourceLocation("minecraft", "redstone_lamp"))
                : BuiltInRegistries.BLOCK.get(new ResourceLocation("mekanism", "basic_energy_cube"));
        if (target == null || target == net.minecraft.world.level.block.Blocks.AIR) return "target block missing for " + kind;
        level.setBlock(out.above(), target.defaultBlockState(), 3);
        return null;
    }

    /** Binds input and output to one frequency (main, after the grid booted). */
    public static String bind(ServerLevel level, BlockPos in, BlockPos out) {
        P2PTunnelPart<?> pin = tunnel(level, in, Direction.DOWN), pout = tunnel(level, out, Direction.UP);
        if (pin == null || pout == null) return "tunnels not found (in=" + pin + " out=" + pout + ")";
        IGrid grid = pin.getMainNode().getGrid();
        if (grid == null || pout.getMainNode().getGrid() != grid) return "tunnels are not on one grid";
        try {
            Method setOutput = P2PTunnelPart.class.getDeclaredMethod("setOutput", boolean.class);
            setOutput.setAccessible(true);
            setOutput.invoke(pout, true);
        } catch (ReflectiveOperationException e) {
            return "setOutput: " + e;
        }
        P2PService p2p = P2PService.get(grid);
        short freq = p2p.newFrequency();
        p2p.updateFreq(pin, freq);
        p2p.updateFreq(pout, freq);
        return "bound freq=" + freq + " inActive=" + pin.isActive() + " outActive=" + pout.isActive();
    }

    private static P2PTunnelPart<?> tunnel(ServerLevel level, BlockPos pos, Direction side) {
        return PartHelper.getPart(level, pos, side) instanceof P2PTunnelPart<?> t ? t : null;
    }

    private static long amount(ServerLevel level, BlockPos target, String kind) {
        BlockEntity be = level.getBlockEntity(target);
        if (be == null) return -1;
        if (kind.equals("item")) {
            IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN).orElse(null);
            if (h == null) return -2;
            long n = 0;
            for (int i = 0; i < h.getSlots(); i++) n += h.getStackInSlot(i).getCount();
            return n;
        }
        IEnergyStorage e = be.getCapability(ForgeCapabilities.ENERGY, Direction.DOWN).orElse(null);
        return e == null ? -2 : e.getEnergyStored();
    }

    /** P2P-9 run; label = 9A | 9B | 9C (recorded only; the geometry decides the case). */
    public static String conserve(ServerLevel level, String label, String kind, BlockPos in, BlockPos out, int amount) {
        if (pending != null) return "a fixture is running: " + status;
        BlockPos target = out.above();
        UUID hostA = host(level, in), hostB = host(level, target);
        if (hostA == null || hostB == null) return "need armor stand hosts near in and near target";
        String[] tA = {null}, tB = {null}, view = {null}, err = {null};
        long[] e0 = {Long.MIN_VALUE}, e1 = {Long.MIN_VALUE}, accepted = {Long.MIN_VALUE};
        int[] liveId = {0};
        boolean[] done = {false};
        Runnable step3 = () -> {
            e1[0] = amount(level, target, kind);
            done[0] = true;
        };
        Runnable step2 = () -> {
            try {
                tA[0] = Thread.currentThread().getName();
                BlockEntity seen = level.getBlockEntity(target);                  // the routing AE2's CapabilityGuard.get uses
                view[0] = seen == null ? "NULL" : (System.identityHashCode(seen) == liveId[0] ? "LIVE" : "DETACHED")
                        + "(" + seen.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(seen)) + ")";
                BlockEntity inBe = level.getBlockEntity(in);
                if (kind.equals("item")) {
                    IItemHandler h = inBe.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN).orElse(null);
                    ItemStack offer = new ItemStack(Items.COBBLESTONE, amount);
                    accepted[0] = h == null ? -2 : amount - h.insertItem(0, offer, false).getCount();
                } else {
                    IEnergyStorage h = inBe.getCapability(ForgeCapabilities.ENERGY, Direction.DOWN).orElse(null);
                    accepted[0] = h == null ? -2 : h.receiveEnergy(amount, false);
                }
            } catch (Throwable t) {
                err[0] = t.toString();
                Ae2Compat.LOG.warn("[nestworld-compat-ae2] p2p fixture {} transfer threw on {}", label, Thread.currentThread().getName(), t);
            }
            RegionTasks.submit(hostB, step3);
        };
        Runnable step1 = () -> {
            tB[0] = Thread.currentThread().getName();
            BlockEntity live = level.getBlockEntity(target);
            liveId[0] = live == null ? -1 : System.identityHashCode(live);
            e0[0] = amount(level, target, kind);
            RegionTasks.submit(hostA, step2);
        };
        RegionTasks.submit(hostB, step1);
        evaluateAt = level.getServer().getTickCount() + 60;
        pending = () -> {
            List<String> f = new ArrayList<>();
            String detail;
            if (done[0] && err[0] != null) {
                status = "P2P-9 " + label + " " + kind + ": VIOLATION [exception escaped the P2P transfer into the caller: " + err[0]
                        + "] e0=" + e0[0] + " e1=" + e1[0] + " threadA=" + tA[0] + " threadB=" + tB[0] + " view=" + view[0];
            } else if (!done[0] || accepted[0] < 0 || e0[0] < 0) {
                status = "P2P-9 " + label + " " + kind + ": INVALID done=" + done[0] + " err=" + err[0] + " accepted=" + accepted[0]
                        + " e0=" + e0[0] + " e1=" + e1[0] + " threadA=" + tA[0] + " threadB=" + tB[0] + " view=" + view[0];
            } else {
                long delta = e1[0] - e0[0];
                boolean live = view[0].startsWith("LIVE");
                boolean crossOwner = live && !tA[0].equals(tB[0]) && delta > 0;
                if (accepted[0] != delta) f.add("conservation: source decrease " + accepted[0] + " != destination increase " + delta);
                if (crossOwner) f.add("cross-owner mutation: target owned by " + tB[0] + " mutated on " + tA[0]);
                detail = "accepted=" + accepted[0] + " delta=" + delta + " e0=" + e0[0] + " e1=" + e1[0] + " view=" + view[0] + " threadA=" + tA[0] + " threadB=" + tB[0];
                status = "P2P-9 " + label + " " + kind + (f.isEmpty() ? ": CONSERVED/LOCAL " : ": VIOLATION " + f + " ") + detail
                        + (accepted[0] == 0 ? " (WARNING: nothing transferred)" : "");
            }
            Ae2Compat.LOG.info("[nestworld-compat-ae2] p2p fixture {}", status);
        };
        status = "P2P-9 " + label + ": running";
        return status;
    }

    // ---------------------------------------------------------------- P2P-10 (Redstone P2P callback boundary)

    private static volatile boolean rsArmed;
    private static final List<String> RS_RECORDS = java.util.Collections.synchronizedList(new ArrayList<>());
    private static final ThreadLocal<long[]> RS_AT_HEAD = new ThreadLocal<>();

    /** Called by RedstoneP2PProbeMixin at notifyNeighbors HEAD / RETURN. */
    public static void rsNotify(appeng.parts.p2p.RedstoneP2PTunnelPart part, boolean head) {
        if (!rsArmed) return;
        var k = Ae2Compat.KERNEL.metrics;
        if (head) {
            RS_AT_HEAD.set(new long[]{k.acquisitions.sum(), k.refusals.sum(), k.unauditedPaths.sum(), Ae2Compat.COUNTERS.pTryFailures.sum()});
            IGrid g = part.getMainNode().getGrid();
            BlockPos pos = part.getBlockEntity().getBlockPos();
            ServerLevel level = (ServerLevel) part.getLevel();
            BlockPos lamp = pos.above();
            RS_RECORDS.add("HEAD thread=" + Thread.currentThread().getName()
                    + " P(G)held=" + (g instanceof appeng.me.Grid gg && GridPhaseGate.holds(gg))
                    + " phaseActive=" + (g instanceof appeng.me.Grid gg2 && GridPhaseGate.phaseActive(gg2))
                    + " kernelInSection=" + Ae2Compat.KERNEL.inSection()
                    + " lampBefore=" + level.getBlockState(lamp).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT)
                    + " | output " + owner(level, pos)
                    + " | stack=" + stackSummary());
        } else {
            long[] h = RS_AT_HEAD.get();
            RS_AT_HEAD.remove();
            ServerLevel level = (ServerLevel) part.getLevel();
            BlockPos lamp = part.getBlockEntity().getBlockPos().above();
            RS_RECORDS.add("RETURN thread=" + Thread.currentThread().getName()
                    + " lampAfter=" + level.getBlockState(lamp).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT)
                    + (h == null ? "" : " kernelAcquisitions+" + (k.acquisitions.sum() - h[0]) + " refusals+" + (k.refusals.sum() - h[1])
                    + " unaudited+" + (k.unauditedPaths.sum() - h[2]) + " pTryFailures+" + (Ae2Compat.COUNTERS.pTryFailures.sum() - h[3])));
        }
    }

    private static String stackSummary() {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.contains("GridConnection") || c.contains("P2PService") || c.contains("GridPhaseGate") || c.contains("Ae2Kernel")
                    || c.contains("RegionTasks") || c.contains("PartHelper") || c.contains("Grid.") || c.endsWith(".Grid")) {
                sb.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName()).append('>');
            }
        }
        return sb.toString();
    }

    /** Main: remove the cable part at {@code pos} (splits the grid there). */
    public static String rsCut(ServerLevel level, BlockPos pos) {
        var host = PartHelper.getPartHost(level, pos);
        if (host == null) return "no part host at " + pos;
        host.removePartFromSide(null);
        if (host.isEmpty()) host.cleanup();
        return "cable removed at " + pos.toShortString();
    }

    /** Region thread of the host near {@code gap}: put the cable back (grid merge → P2PService.addNode). */
    public static String rsReconnect(ServerLevel level, BlockPos gap) {
        if (pending != null) return "a fixture is running: " + status;
        UUID h = host(level, gap);
        if (h == null) return "need an armor stand host near " + gap;
        RS_RECORDS.clear();
        String[] t = {null};
        rsArmed = true;
        RegionTasks.submit(h, () -> {
            t[0] = Thread.currentThread().getName();
            PartHelper.setPart(level, gap, null, null, AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT));
        });
        evaluateAt = level.getServer().getTickCount() + 40;
        pending = () -> {
            rsArmed = false;
            status = "P2P-10: reconnect thread=" + t[0] + " records=" + RS_RECORDS.size() + " :: " + String.join(" || ", RS_RECORDS);
            Ae2Compat.LOG.info("[nestworld-compat-ae2] p2p fixture {}", status);
        };
        status = "P2P-10: running";
        return status;
    }

    /**
     * Fixture-only diagnostic (reflection; never on a production path): the NestWorld region owning
     * {@code pos}, its chunk bounds, the chunk's distance to the nearest region edge (ghost zone when
     * <= 2, BoundaryManager.GHOST_DEPTH) and the owner thread.
     */
    public static String owner(ServerLevel level, BlockPos pos) {
        try {
            Class<?> sysC = Class.forName("net.nestworld.region.NestworldRegionSystem");
            Object sys = sysC.getMethod("get").invoke(null);
            Object dr = sysC.getMethod("getDimensionRegion", net.minecraft.world.level.Level.class).invoke(sys, level);
            Object grid = dr.getClass().getMethod("getGrid").invoke(dr);
            Object r = grid.getClass().getMethod("getRegionForChunk", int.class, int.class).invoke(grid, pos.getX() >> 4, pos.getZ() >> 4);
            if (r == null) return "no region owns " + pos;
            Class<?> rc = r.getClass();
            int minX = (int) rc.getMethod("getMinChunkX").invoke(r), maxX = (int) rc.getMethod("getMaxChunkX").invoke(r);
            int minZ = (int) rc.getMethod("getMinChunkZ").invoke(r), maxZ = (int) rc.getMethod("getMaxChunkZ").invoke(r);
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            int edge = Math.min(Math.min(cx - minX, maxX - cx), Math.min(cz - minZ, maxZ - cz));
            java.lang.reflect.Field f = rc.getDeclaredField("owningThread");
            f.setAccessible(true);
            Object t = f.get(r);
            return "pos=" + pos.toShortString() + " chunk=" + cx + "," + cz + " region#" + rc.getMethod("getId").invoke(r)
                    + " chunks[" + minX + ".." + maxX + "," + minZ + ".." + maxZ + "] edgeDist=" + edge
                    + (edge <= 2 ? " (GHOST ZONE)" : " (deep)") + " thread=" + (t == null ? "null" : ((Thread) t).getName());
        } catch (ReflectiveOperationException e) {
            return "owner query failed: " + e;
        }
    }

    private P2PFixtures() {
    }
}
