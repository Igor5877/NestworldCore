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

    /** Main-thread producer of the H2 stress (null otherwise). */
    private static volatile Runnable mainProducer;

    public static void onServerTickEnd(int tick) {
        Runnable mp = mainProducer;
        if (mp != null) mp.run();
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
                : kind.equals("fluid") ? AEParts.FLUID_P2P_TUNNEL.asItem()
                : kind.equals("redstone") ? AEParts.REDSTONE_P2P_TUNNEL.asItem() : AEParts.FE_P2P_TUNNEL.asItem();
        if (PartHelper.setPart(level, in, Direction.DOWN, null, item) == null) return "input tunnel placement failed";
        if (PartHelper.setPart(level, out, Direction.UP, null, item) == null) return "output tunnel placement failed";
        Block target = kind.equals("item") ? BuiltInRegistries.BLOCK.get(new ResourceLocation("minecraft", "chest"))
                : kind.equals("redstone") ? BuiltInRegistries.BLOCK.get(new ResourceLocation("minecraft", "redstone_lamp"))
                : kind.equals("fluid") ? BuiltInRegistries.BLOCK.get(new ResourceLocation("mekanism", "basic_fluid_tank"))
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
        if (kind.equals("fluid")) {
            var h = be.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.DOWN).orElse(null);
            if (h == null) return -2;
            long n = 0;
            for (int i = 0; i < h.getTanks(); i++) n += h.getFluidInTank(i).getAmount();
            return n;
        }
        IEnergyStorage e = be.getCapability(ForgeCapabilities.ENERGY, Direction.DOWN).orElse(null);
        return e == null ? -2 : e.getEnergyStored();
    }

    /** One synchronous transfer of {@code amount} into the input tunnel at {@code in} (side DOWN), on the
     *  current thread; returns the accepted amount (negative = no capability). */
    static long transfer(ServerLevel level, BlockPos in, String kind, int amount) {
        return transfer(level, in, Direction.DOWN, kind, amount);
    }

    static long transfer(ServerLevel level, BlockPos in, Direction side, String kind, int amount) {
        BlockEntity inBe = level.getBlockEntity(in);
        if (inBe == null) return -3;
        if (kind.equals("item")) {
            IItemHandler h = inBe.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            return h == null ? -2 : amount - h.insertItem(0, new ItemStack(Items.COBBLESTONE, amount), false).getCount();
        }
        if (kind.equals("fluid")) {
            var h = inBe.getCapability(ForgeCapabilities.FLUID_HANDLER, side).orElse(null);
            return h == null ? -2 : h.fill(new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, amount),
                    net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        }
        IEnergyStorage h = inBe.getCapability(ForgeCapabilities.ENERGY, side).orElse(null);
        return h == null ? -2 : h.receiveEnergy(amount, false);
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

    // ---------------------------------------------------------------- H1 (P2P-MAP-STATE-001) deterministic fixtures

    /** Grid G1 at {@code g1}: 5 cable blocks along +x, an FE tunnel on side DOWN of each (T0..T4), creative
     *  cell above T0. Grid G2 at {@code g2}: 1 cable + FE tunnel (X), creative cell. Main thread. */
    public static String h1Setup(ServerLevel level, BlockPos g1, BlockPos g2) {
        var cable = AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT);
        var fe = AEParts.FE_P2P_TUNNEL.asItem();
        for (int i = 0; i < 5; i++) {
            BlockPos p = g1.offset(i, 0, 0);
            if (PartHelper.setPart(level, p, null, null, cable) == null || PartHelper.setPart(level, p, Direction.DOWN, null, fe) == null)
                return "G1 placement failed at " + p;
        }
        level.setBlock(g1.above(), AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState(), 3);
        if (PartHelper.setPart(level, g2, null, null, cable) == null || PartHelper.setPart(level, g2, Direction.DOWN, null, fe) == null)
            return "G2 placement failed";
        level.setBlock(g2.above(), AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState(), 3);
        return null;
    }

    private static P2PTunnelPart<?> t(ServerLevel level, BlockPos p) {
        return tunnel(level, p, Direction.DOWN);
    }

    private static void setOutput(P2PTunnelPart<?> t, boolean out) throws ReflectiveOperationException {
        Method m = P2PTunnelPart.class.getDeclaredMethod("setOutput", boolean.class);
        m.setAccessible(true);
        m.invoke(t, out);
    }

    private static Thread fork(String name, Runnable r) {
        Thread th = new Thread(r, name);
        th.setDaemon(true);
        th.start();
        return th;
    }

    private static boolean join(Thread... ts) {
        for (Thread th : ts) {
            try {
                th.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (th.isAlive()) return false;
        }
        return true;
    }

    /** One H1 case, asynchronous: a coordinator thread drives the case so main keeps ticking (AE2's
     *  markForSave / chunk access from fixture threads hops to main). Poll {@code p2p status}. */
    public static String h1(ServerLevel level, String kase, BlockPos g1, BlockPos g2) {
        if (status.endsWith(": running")) return "a fixture is running: " + status;
        P2PTunnelPart<?>[] T = new P2PTunnelPart<?>[5];                // resolved on main: off-thread getBlockEntity is null
        for (int i = 0; i < 5; i++) T[i] = t(level, g1.offset(i, 0, 0));
        P2PTunnelPart<?> X = t(level, g2);
        status = "H1 " + kase + ": running";
        fork("nw-h1-coordinator", () -> {
            String r;
            try {
                r = h1Run(level, kase, T, X);
            } catch (Throwable e) {
                r = "H1 " + kase + ": INVALID " + e;
            }
            status = r;
        });
        return status;
    }

    private static String h1Run(ServerLevel level, String kase, P2PTunnelPart<?>[] T, P2PTunnelPart<?> X) {
        for (var tt : T) if (tt == null || tt.getMainNode().getGrid() == null) return "H1 " + kase + ": INVALID (run h1setup; G1 tunnel missing)";
        if (X == null || X.getMainNode().getGrid() == null) return "H1 " + kase + ": INVALID (G2 tunnel missing)";
        IGrid grid1 = T[0].getMainNode().getGrid(), grid2 = X.getMainNode().getGrid();
        if (grid1 == grid2) return "H1 " + kase + ": INVALID (G1 and G2 are one grid)";
        P2PService s1 = P2PService.get(grid1), s2 = P2PService.get(grid2);
        NwP2PService n1 = (NwP2PService) (Object) s1;
        List<String> f = new ArrayList<>();
        String detail;
        try {
            switch (kase) {
                case "liveview" -> {
                    // T0 input, T1+T2 outputs on F; reader pauses mid-iteration of getOutputs while a writer moves T2 away.
                    short F = 101, F2 = 102;
                    setOutput(T[0], false); setOutput(T[1], true); setOutput(T[2], true);
                    s1.updateFreq(T[0], F); s1.updateFreq(T[1], F); s1.updateFreq(T[2], F);
                    java.util.concurrent.CountDownLatch mid = new java.util.concurrent.CountDownLatch(1), wrote = new java.util.concurrent.CountDownLatch(1);
                    String[] rErr = {null};
                    int[] seen = {-1};
                    boolean[] once = {false};
                    Thread r = fork("nw-h1-reader", () -> {
                        try {
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            List<?> l = s1.getOutputs(F, (Class) T[1].getClass()).peek(x -> {
                                if (!once[0]) {
                                    once[0] = true;
                                    mid.countDown();
                                    try {
                                        wrote.await(5, java.util.concurrent.TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            }).toList();
                            seen[0] = l.size();
                        } catch (Throwable e) {
                            rErr[0] = e.toString();
                        }
                    });
                    Thread w = fork("nw-h1-writer", () -> {
                        try {
                            mid.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            s1.updateFreq(T[2], F2);
                        } catch (Throwable e) {
                            rErr[0] = "writer: " + e;
                        } finally {
                            wrote.countDown();
                        }
                    });
                    if (!join(r, w)) f.add("threads did not finish (deadlock?)");
                    if (rErr[0] != null) f.add("reader/writer failed: " + rErr[0]);
                    else if (seen[0] != 2) f.add("reader did not see a stable snapshot: size " + seen[0] + " (expected 2)");
                    detail = "readerSaw=" + seen[0] + " err=" + rErr[0] + " T2.membership=" + n1.nw$membership(T[2]);
                }
                case "allocrace" -> {
                    // Two fake players shift-activate T3 and T4 with memory cards through the REAL call site; both
                    // newFrequency() calls are forced to the same candidate, then meet at a barrier before the bind.
                    setOutput(T[3], true); setOutput(T[4], true);
                    s1.updateFreq(T[3], (short) 0); s1.updateFreq(T[4], (short) 0);
                    short C = 777;
                    var barrier = new java.util.concurrent.CyclicBarrier(2);
                    P2PState.hookAfterCandidate = () -> {
                        try {
                            barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    };
                    String[] err = {null, null};
                    var pa = fakePlayer(level, "nwh1a");
                    var pb = fakePlayer(level, "nwh1b");                         // created on main, before the threads
                    Thread a = fork("nw-h1-alloc-A", () -> err[0] = shiftActivate(pa, T[3], C));
                    Thread b = fork("nw-h1-alloc-B", () -> err[1] = shiftActivate(pb, T[4], C));
                    boolean ok = join(a, b);
                    P2PState.hookAfterCandidate = null;
                    if (!ok) f.add("threads did not finish");
                    if (err[0] != null || err[1] != null) f.add("activate failed: " + err[0] + " / " + err[1]);
                    short fa = T[3].getFrequency(), fb = T[4].getFrequency();
                    if (fa == fb) f.add("duplicate frequency " + fa + " for two inputs");
                    if (s1.getInput(fa) != T[3]) f.add("input A displaced (inputs[" + fa + "]=" + s1.getInput(fa) + ")");
                    if (s1.getInput(fb) != T[4]) f.add("input B displaced (inputs[" + fb + "]=" + s1.getInput(fb) + ")");
                    detail = "A.freq=" + fa + " B.freq=" + fb + " A.isOutput=" + T[3].isOutput() + " B.isOutput=" + T[4].isOutput()
                            + " allocRebound=" + Ae2Compat.COUNTERS.p2pAllocRebound.sum() + " inputDisplaced=" + Ae2Compat.COUNTERS.p2pInputDisplaced.sum();
                }
                case "updaterace" -> {
                    // Writer 1 pauses inside updateFreq (tunnel removed, not yet re-inserted) waiting up to 300 ms for
                    // writer 2. Guarded: writer 2 is blocked → serial. Unguarded: writer 2 runs inside the gap.
                    setOutput(T[1], true);
                    s1.updateFreq(T[1], (short) 201);
                    var reached = new java.util.concurrent.CountDownLatch(1);
                    var w2done = new java.util.concurrent.CountDownLatch(1);
                    Thread[] w1ref = {null};
                    P2PState.hookAtSetFrequency = () -> {
                        if (Thread.currentThread() != w1ref[0]) return;
                        reached.countDown();
                        try {
                            w2done.await(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    };
                    String[] err = {null};
                    w1ref[0] = new Thread(() -> s1.updateFreq(T[1], (short) 202), "nw-h1-upd-1");
                    w1ref[0].setDaemon(true);
                    w1ref[0].start();
                    Thread w2 = fork("nw-h1-upd-2", () -> {
                        try {
                            reached.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            s1.updateFreq(T[1], (short) 203);
                        } catch (Throwable e) {
                            err[0] = e.toString();
                        } finally {
                            w2done.countDown();
                        }
                    });
                    boolean ok = join(w1ref[0], w2);
                    P2PState.hookAtSetFrequency = null;
                    if (!ok) f.add("threads did not finish");
                    List<Short> keys = n1.nw$membership(T[1]);
                    short fr = T[1].getFrequency();
                    if (keys.size() != 1 || keys.get(0) != fr)
                        f.add("state matches no serial order: freq=" + fr + " but member under " + keys);
                    detail = "finalFreq=" + fr + " membership=" + keys + " err=" + err[0];
                }
                case "independent" -> {
                    // G1 writer parked 2 s inside its guard; a G2 writer must still complete; a second G1 writer must wait.
                    setOutput(T[1], true);
                    setOutput(X, true);
                    var parked = new java.util.concurrent.CountDownLatch(1);
                    Thread[] w1ref = {null};
                    P2PState.hookAtSetFrequency = () -> {
                        if (Thread.currentThread() != w1ref[0]) return;
                        parked.countDown();
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    };
                    long[] g2ms = {-1}, g1bms = {-1};
                    w1ref[0] = new Thread(() -> s1.updateFreq(T[1], (short) 301), "nw-h1-g1-parked");
                    w1ref[0].setDaemon(true);
                    w1ref[0].start();
                    Thread g2w = fork("nw-h1-g2", () -> {
                        try {
                            parked.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            long t0 = System.nanoTime();
                            s2.updateFreq(X, (short) 302);
                            g2ms[0] = (System.nanoTime() - t0) / 1_000_000;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    Thread g1b = fork("nw-h1-g1-second", () -> {
                        try {
                            parked.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            long t0 = System.nanoTime();
                            s1.updateFreq(T[2], (short) 303);
                            g1bms[0] = (System.nanoTime() - t0) / 1_000_000;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    boolean ok = join(w1ref[0], g2w, g1b);
                    P2PState.hookAtSetFrequency = null;
                    if (!ok) f.add("threads did not finish");
                    if (g2ms[0] < 0 || g2ms[0] > 500) f.add("G2 writer did not complete while G1 was parked (" + g2ms[0] + " ms)");
                    if (P2PState.PROTECT && g1bms[0] < 1000) f.add("second G1 writer was not serialized behind the parked one (" + g1bms[0] + " ms)");
                    detail = "g2WriterMs=" + g2ms[0] + " secondG1WriterMs=" + g1bms[0];
                }
                default -> {
                    return "unknown H1 case " + kase + " (liveview|allocrace|updaterace|independent)";
                }
            }
        } catch (ReflectiveOperationException e) {
            return "H1 " + kase + ": INVALID " + e;
        } finally {
            P2PState.hookAfterCandidate = null;
            P2PState.hookAtSetFrequency = null;
        }
        status = "H1 " + kase + " [guard=" + (P2PState.PROTECT ? "ON" : "OFF") + "]" + (f.isEmpty() ? ": PASS " : ": FAIL " + f + " ") + detail;
        Ae2Compat.LOG.info("[nestworld-compat-ae2] p2p fixture {}", status);
        return status;
    }

    /** H1 stress: readers + updateFreq / allocateAndBind writers on G1, region-thread topology churn on
     *  G1's 5th tunnel (host near it), continuous atomic invariant checks. Asynchronous; poll status. */
    public static String h1Stress(ServerLevel level, BlockPos g1, BlockPos g2, int seconds) {
        if (status.endsWith(": running")) return "a fixture is running: " + status;
        P2PTunnelPart<?>[] T = new P2PTunnelPart<?>[4];
        for (int i = 0; i < 4; i++) T[i] = t(level, g1.offset(i, 0, 0));
        P2PTunnelPart<?> X = t(level, g2);
        BlockPos churnPos = g1.offset(4, 0, 0);
        UUID churnHost = host(level, churnPos);
        for (var tt : T) if (tt == null || tt.getMainNode().getGrid() == null) return "H1 stress: INVALID (G1 tunnel missing)";
        if (churnHost == null) return "H1 stress: INVALID (need an armor stand host near " + churnPos.toShortString() + ")";
        P2PService s1 = P2PService.get(T[0].getMainNode().getGrid());
        NwP2PService n1 = (NwP2PService) (Object) s1;
        var fakes = new net.minecraftforge.common.util.FakePlayer[]{fakePlayer(level, "nwh1s0"), fakePlayer(level, "nwh1s1")};
        try {                                // T0 = the input of 401; T1, T2 = outputs moved by writers; T3 = allocateAndBind target
            setOutput(T[0], false); setOutput(T[1], true); setOutput(T[2], true); setOutput(T[3], true);
        } catch (ReflectiveOperationException e) {
            return "H1 stress: INVALID " + e;
        }
        s1.updateFreq(T[0], (short) 401); s1.updateFreq(T[1], (short) 401); s1.updateFreq(T[2], (short) 402); s1.updateFreq(T[3], (short) 0);
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        var c = Ae2Compat.COUNTERS;
        long disp0 = c.p2pInputDisplaced.sum(), reb0 = c.p2pAllocRebound.sum(), w0 = c.p2pWrites.sum(), r0 = c.p2pReads.sum();
        java.util.concurrent.atomic.AtomicLong reads = new java.util.concurrent.atomic.AtomicLong(), writes = new java.util.concurrent.atomic.AtomicLong(),
                allocs = new java.util.concurrent.atomic.AtomicLong(), checks = new java.util.concurrent.atomic.AtomicLong(),
                churn = new java.util.concurrent.atomic.AtomicLong();
        List<String> violations = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicLong maxRead = new java.util.concurrent.atomic.AtomicLong();
        status = "H1 stress: running";
        List<Thread> threads = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            threads.add(fork("nw-h1s-reader-" + r, () -> {
                java.util.Random rnd = new java.util.Random();
                while (System.nanoTime() < end && violations.size() < 20) {
                    try {
                        short f = (short) (401 + rnd.nextInt(3));
                        long t0 = System.nanoTime();
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        List<?> l = s1.getOutputs(f, (Class) T[1].getClass()).toList();
                        s1.getInput(f);
                        T[rnd.nextInt(4)].getOutputs();
                        maxRead.accumulateAndGet(System.nanoTime() - t0, Math::max);
                        reads.incrementAndGet();
                        if (l.size() > 4) violations.add("outputs snapshot larger than the tunnel set: " + l.size());
                    } catch (Throwable e) {
                        violations.add("reader: " + e);
                    }
                }
            }));
        }
        for (int w = 0; w < 2; w++) {
            final int wi = w;
            threads.add(fork("nw-h1s-writer-" + w, () -> {
                java.util.Random rnd = new java.util.Random();
                while (System.nanoTime() < end && violations.size() < 20) {
                    try {
                        if (rnd.nextInt(50) == 0) {
                            synchronized (T[3]) {                                  // one fake-player activation of T3 at a time
                                shiftActivate(fakes[wi], T[3], (short) 0);         // real call site → allocateAndBind
                            }
                            allocs.incrementAndGet();
                        } else {
                            s1.updateFreq(T[1 + rnd.nextInt(2)], (short) (401 + rnd.nextInt(3)));
                        }
                        writes.incrementAndGet();
                    } catch (Throwable e) {
                        violations.add("writer: " + e);
                    }
                }
            }));
        }
        threads.add(fork("nw-h1s-checker", () -> {
            while (System.nanoTime() < end && violations.size() < 20) {
                for (P2PTunnelPart<?> tt : T) {
                    String v = n1.nw$check(tt);
                    if (v != null) violations.add("invariant: " + v);
                }
                checks.incrementAndGet();
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }));
        Runnable[] step = {null};
        step[0] = () -> {                                              // RegionThread: real topology churn on G1
            if (PartHelper.getPart(level, churnPos, Direction.DOWN) != null) {
                PartHelper.getPartHost(level, churnPos).removePartFromSide(Direction.DOWN);
            } else {
                PartHelper.setPart(level, churnPos, Direction.DOWN, null, AEParts.FE_P2P_TUNNEL.asItem());
            }
            churn.incrementAndGet();
            if (System.nanoTime() < end) RegionTasks.submit(churnHost, step[0]);
        };
        RegionTasks.submit(churnHost, step[0]);
        fork("nw-h1s-coordinator", () -> {
            long left = end - System.nanoTime();                       // the joins' 10 s budget starts at the end of the run
            if (left > 0) {
                try {
                    Thread.sleep(left / 1_000_000 + 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            boolean ok = join(threads.toArray(new Thread[0]));
            List<String> f = new ArrayList<>(violations.subList(0, Math.min(5, violations.size())));
            if (!ok) {
                List<String> alive = new ArrayList<>();
                for (Thread th : threads) {
                    if (!th.isAlive()) continue;
                    StackTraceElement[] st = th.getStackTrace();
                    alive.add(th.getName() + "@" + (st.length > 0 ? st[Math.min(st.length - 1, 8)] : "?"));
                }
                f.add("threads did not finish " + alive);
            }
            if (c.p2pInputDisplaced.sum() != disp0) f.add("input displaced x" + (c.p2pInputDisplaced.sum() - disp0));
            status = "H1 stress " + seconds + "s [guard=" + (P2PState.PROTECT ? "ON" : "OFF") + "]" + (f.isEmpty() ? ": PASS " : ": FAIL " + f + " ")
                    + "reads=" + reads.get() + " writes=" + writes.get() + " allocs=" + allocs.get() + " churn=" + churn.get()
                    + " checks=" + checks.get() + " violations=" + violations.size() + " maxReadUs=" + maxRead.get() / 1000
                    + " p2pWrites+" + (c.p2pWrites.sum() - w0) + " p2pReads+" + (c.p2pReads.sum() - r0)
                    + " allocRebound+" + (c.p2pAllocRebound.sum() - reb0);
            Ae2Compat.LOG.info("[nestworld-compat-ae2] p2p fixture {}", status);
        });
        return status;
    }

    /** The real memory-card call site (onPartShiftActivate) for a fake player; the first newFrequency() on
     *  this thread returns {@code forced}. Returns null on success, else an error. */
    private static net.minecraftforge.common.util.FakePlayer fakePlayer(ServerLevel level, String name) {
        var fp = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        fp.getInventory().items.set(fp.getInventory().selected, appeng.core.definitions.AEItems.MEMORY_CARD.stack());
        return fp;
    }

    private static String shiftActivate(net.minecraftforge.common.util.FakePlayer fp, P2PTunnelPart<?> t, short forced) {
        try {
            if (forced != 0) P2PState.FORCED_CANDIDATE.set(forced);
            t.onPartShiftActivate(fp, net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.phys.Vec3.ZERO);
            return null;
        } catch (Throwable e) {
            return e.toString();
        } finally {
            P2PState.FORCED_CANDIDATE.remove();
        }
    }

    // ---------------------------------------------------------------- H2 (P2P-ACCESS-DEPTH-001)

    /** Recursion loop at {@code base} (one grid, one region), tunnels on NORTH / SOUTH sides so the
     *  cable centres stay connected through an east column:
     *  P = base: I1 (input, freq A) on NORTH, O1 (output A) on SOUTH → faces R = base.south()
     *  R = base.south(): I2 (input, freq B) on NORTH (faced by O1)
     *  Q = base.north(): O2 (output B) on SOUTH → faces P, whose NORTH part is I1
     *  cables at base.east(), base.south().east(), base.north().east(); creative cell above P.
     *  I1 → O1 → I2 → O2 → I1 → O1 again: depth(O1) = 2 → AE2 returns the empty handler. */
    public static String h2LoopSetup(ServerLevel level, BlockPos base) {
        var cable = AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT);
        var fe = AEParts.FE_P2P_TUNNEL.asItem();
        BlockPos p = base, r = base.south(), q = base.north();
        for (BlockPos b : List.of(p, r, q, p.east(), r.east(), q.east()))
            if (PartHelper.setPart(level, b, null, null, cable) == null) return "cable at " + b;
        if (PartHelper.setPart(level, p, Direction.NORTH, null, fe) == null || PartHelper.setPart(level, p, Direction.SOUTH, null, fe) == null
                || PartHelper.setPart(level, r, Direction.NORTH, null, fe) == null || PartHelper.setPart(level, q, Direction.SOUTH, null, fe) == null)
            return "tunnel placement failed";
        level.setBlock(p.above(), AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState(), 3);
        return null;
    }

    private static P2PTunnelPart<?>[] loop(ServerLevel level, BlockPos base) {
        return new P2PTunnelPart<?>[]{tunnel(level, base, Direction.NORTH), tunnel(level, base, Direction.SOUTH),
                tunnel(level, base.south(), Direction.NORTH), tunnel(level, base.north(), Direction.SOUTH)};
    }

    public static String h2LoopBind(ServerLevel level, BlockPos base) {
        P2PTunnelPart<?>[] l = loop(level, base);
        P2PTunnelPart<?> i1 = l[0], o1 = l[1], i2 = l[2], o2 = l[3];
        if (i1 == null || o1 == null || i2 == null || o2 == null) return "loop tunnels missing";
        IGrid g = i1.getMainNode().getGrid();
        if (g == null || o1.getMainNode().getGrid() != g || i2.getMainNode().getGrid() != g || o2.getMainNode().getGrid() != g)
            return "loop tunnels are not on one grid";
        try {
            setOutput(i1, false); setOutput(i2, false); setOutput(o1, true); setOutput(o2, true);
        } catch (ReflectiveOperationException e) {
            return e.toString();
        }
        P2PService p2p = P2PService.get(g);
        p2p.updateFreq(i1, (short) 601); p2p.updateFreq(o1, (short) 601);
        p2p.updateFreq(i2, (short) 602); p2p.updateFreq(o2, (short) 602);
        return "loop bound active=" + i1.isActive() + "," + o1.isActive() + "," + i2.isActive() + "," + o2.isActive();
    }

    private static int depth(P2PTunnelPart<?> t) {
        return ((NwCapTunnel) t).nw$depth();
    }

    /** H2-1 (+H2-4): real recursion on the main thread. Expect: accepted 0, exactly the vanilla rejection
     *  (O1 at depth 2), no StackOverflowError, every depth back to 0 with no residual entry. */
    public static String h2Recursion(ServerLevel level, BlockPos base) {
        P2PTunnelPart<?>[] l = loop(level, base);
        P2PTunnelPart<?> i1 = l[0], o1 = l[1], i2 = l[2], o2 = l[3];
        if (i1 == null || o1 == null || i2 == null || o2 == null) return "H2-1: INVALID (run h2loop)";
        if (!(i1.isActive() && o1.isActive() && i2.isActive() && o2.isActive())) return "H2-1: INVALID (loop tunnels not all active)";
        List<String> f = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        P2PDepth.hookInGet = (tun, d) -> seen.add((tun == o1 ? "O1" : tun == o2 ? "O2" : tun == i1 ? "I1" : tun == i2 ? "I2" : "?") + "@" + d);
        long rej0 = Ae2Compat.COUNTERS.p2pDepthRejected.sum();
        long accepted;
        String err = null;
        try {
            accepted = transfer(level, base, Direction.NORTH, "fe", 1000);
        } catch (Throwable t) {
            accepted = -9;
            err = t.toString();
        } finally {
            P2PDepth.hookInGet = null;
        }
        long rejected = Ae2Compat.COUNTERS.p2pDepthRejected.sum() - rej0;
        if (err != null) f.add("transfer threw " + err);
        if (accepted != 0) f.add("loop accepted " + accepted + " (expected 0: recursion must end in the empty handler)");
        if (rejected != 1) f.add("recursion rejections " + rejected + " (expected exactly 1: O1 at depth 2)");
        if (!seen.equals(List.of("O1@1", "O2@1", "O1@2"))) f.add("guard sequence " + seen + " (expected [O1@1, O2@1, O1@2])");
        for (var t : List.of(i1, o1, i2, o2)) {
            if (depth(t) != 0) f.add("depth leak " + t + "=" + depth(t));
            if (P2PDepth.PROTECT && ((NwCapTunnel) t).nw$hasEntry()) f.add("ThreadLocal entry left on " + t);
        }
        status = "H2-1 recursion [depth=" + (P2PDepth.PROTECT ? "THREAD" : "SHARED") + "]" + (f.isEmpty() ? ": PASS " : ": FAIL " + f + " ")
                + "accepted=" + accepted + " rejected=" + rejected + " sequence=" + seen + " thread=" + Thread.currentThread().getName();
        Ae2Compat.LOG.info("[nestworld-compat-ae2] p2p fixture {}", status);
        return status;
    }

    /** H2-2 / H2-5: thread A (main, this command) is parked INSIDE a transfer through output O (depth 1);
     *  thread B (RegionThread host near {@code in}) performs an independent transfer through the same O.
     *  THREAD depth: B succeeds, A resumes and succeeds, both depths 0. SHARED int: B sees depth 2 → empty. */
    public static String h2Concurrent(ServerLevel level, String kind, BlockPos in, BlockPos out, int amount) {
        P2PTunnelPart<?> o = tunnel(level, out, Direction.UP);
        UUID hostB = host(level, in);
        if (o == null || hostB == null) return "H2-2 " + kind + ": INVALID (output tunnel / host missing)";
        Thread main = Thread.currentThread();
        var bDone = new java.util.concurrent.CountDownLatch(1);
        long[] b = {Long.MIN_VALUE};
        String[] bThread = {null}, bErr = {null};
        int[] bDepthAfter = {-1};
        boolean[] bEntry = {false}, parked = {false};
        int[] aDepthSeen = {-1};
        P2PDepth.falseRejectWatch = java.util.Set.of(o);
        long fr0 = Ae2Compat.COUNTERS.p2pDepthFalseReject.sum();
        P2PDepth.hookInGet = (tun, d) -> {
            if (tun != o || Thread.currentThread() != main || parked[0]) return;
            parked[0] = true;
            aDepthSeen[0] = d;
            RegionTasks.submit(hostB, () -> {
                try {
                    bThread[0] = Thread.currentThread().getName();
                    b[0] = transfer(level, in, kind, amount);
                    bDepthAfter[0] = depth(o);
                    bEntry[0] = ((NwCapTunnel) o).nw$hasEntry();
                } catch (Throwable t) {
                    bErr[0] = t.toString();
                } finally {
                    bDone.countDown();
                }
            });
            try {
                bDone.await(5, java.util.concurrent.TimeUnit.SECONDS);   // A parked inside its transfer, depth(O, A) = 1
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        long a;
        String aErr = null;
        try {
            a = transfer(level, in, kind, amount);
        } catch (Throwable t) {
            a = -9;
            aErr = t.toString();
        } finally {
            P2PDepth.hookInGet = null;
            P2PDepth.falseRejectWatch = null;
        }
        List<String> f = new ArrayList<>();
        if (!parked[0]) f.add("A never reached the guard of O");
        if (bDone.getCount() != 0) f.add("B did not run within 5 s");
        if (aErr != null || bErr[0] != null) f.add("exception A=" + aErr + " B=" + bErr[0]);
        if (b[0] <= 0) f.add("B rejected while A was inside the same tunnel (accepted " + b[0] + ")");
        if (a <= 0) f.add("A failed after resuming (accepted " + a + ")");
        if (depth(o) != 0 || bDepthAfter[0] != 0) f.add("depth not restored A=" + depth(o) + " B=" + bDepthAfter[0]);
        if (P2PDepth.PROTECT && (((NwCapTunnel) o).nw$hasEntry() || bEntry[0])) f.add("ThreadLocal entry leaked");
        long falseRejects = Ae2Compat.COUNTERS.p2pDepthFalseReject.sum() - fr0;
        status = "H2-2 " + kind + " [depth=" + (P2PDepth.PROTECT ? "THREAD" : "SHARED") + "]" + (f.isEmpty() ? ": PASS " : ": FAIL " + f + " ")
                + "A(main) depthSeen=" + aDepthSeen[0] + " accepted=" + a + " | B(" + bThread[0] + ") accepted=" + b[0]
                + " | falseRejects+" + falseRejects;
        Ae2Compat.LOG.info("[nestworld-compat-ae2] p2p fixture {}", status);
        return status;
    }

    /** H2 stress: one tunnel per kind (fe z=1200, item z=1205, fluid z=1210; outputs at x=-70) shared by
     *  4 plain producer threads, the owner RegionThread (real targets + the recursion loop every 20th step)
     *  and main (every tick); producer 0 makes the handler throw on every 100th transfer. Asynchronous. */
    public static String h2Stress(ServerLevel level, BlockPos loopBase, int seconds) {
        if (status.endsWith(": running")) return "a fixture is running: " + status;
        String[] kinds = {"fe", "item", "fluid"};
        int[] amounts = {1000, 16, 1000};
        BlockPos[] ins = {new BlockPos(-80, -60, 1200), new BlockPos(-80, -60, 1205), new BlockPos(-80, -60, 1210)};
        P2PTunnelPart<?>[] outs = new P2PTunnelPart<?>[3];
        for (int k = 0; k < 3; k++) outs[k] = tunnel(level, ins[k].offset(10, 0, 0), Direction.UP);
        P2PTunnelPart<?>[] l = loop(level, loopBase);
        UUID ownerHost = host(level, ins[0]), loopHost = host(level, loopBase);
        for (var o : outs) if (o == null) return "H2 stress: INVALID (output tunnel missing)";
        if (l[0] == null || ownerHost == null || loopHost == null) return "H2 stress: INVALID (loop / hosts missing)";
        // Input handlers resolved on main: off-thread getBlockEntity is null, so plain producers call the
        // tunnel's own input handler directly (the transfer then runs through each output's guard).
        Object[] handlers = new Object[3];
        handlers[0] = level.getBlockEntity(ins[0]).getCapability(ForgeCapabilities.ENERGY, Direction.DOWN).orElse(null);
        handlers[1] = level.getBlockEntity(ins[1]).getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN).orElse(null);
        handlers[2] = level.getBlockEntity(ins[2]).getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.DOWN).orElse(null);
        for (Object h : handlers) if (h == null) return "H2 stress: INVALID (input handler missing)";
        var c = Ae2Compat.COUNTERS;
        long rej0 = c.p2pDepthRejected.sum(), fr0 = c.p2pDepthFalseReject.sum(), uf0 = c.p2pDepthUnderflow.sum();
        java.util.Set<Object> watch = java.util.Set.of(outs[0], outs[1], outs[2]);
        P2PDepth.falseRejectWatch = watch;
        java.util.concurrent.atomic.LongAdder entries = new java.util.concurrent.atomic.LongAdder(), transfers = new java.util.concurrent.atomic.LongAdder(),
                loops = new java.util.concurrent.atomic.LongAdder(), thrown = new java.util.concurrent.atomic.LongAdder(),
                caught = new java.util.concurrent.atomic.LongAdder(), mainTransfers = new java.util.concurrent.atomic.LongAdder(),
                regionTransfers = new java.util.concurrent.atomic.LongAdder();
        List<String> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> leaks = java.util.Collections.synchronizedList(new ArrayList<>());
        ThreadLocal<Boolean> throwNext = new ThreadLocal<>();
        P2PDepth.hookInGet = (tun, d) -> {
            entries.increment();
            if (Boolean.TRUE.equals(throwNext.get()) && watch.contains(tun)) {
                throwNext.remove();
                thrown.increment();
                throw new IllegalStateException("H2 stress deliberate handler failure");
            }
        };
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        java.util.function.Consumer<String> leakCheck = who -> {
            for (var o : outs) {
                if (depth(o) != 0) leaks.add(who + " depth=" + depth(o));
                if (P2PDepth.PROTECT && ((NwCapTunnel) o).nw$hasEntry()) leaks.add(who + " ThreadLocal entry");
            }
        };
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int ti = t;
            threads.add(fork("nw-h2s-producer-" + t, () -> {
                long n = 0;
                while (System.nanoTime() < end && unexpected.size() < 20) {
                    int k = (int) (n % 3);
                    if (ti == 0 && n % 100 == 99) throwNext.set(true);
                    try {
                        switch (k) {                                      // off-thread: guard exercised, target view empty
                            case 0 -> ((IEnergyStorage) handlers[0]).receiveEnergy(amounts[0], false);
                            case 1 -> ((IItemHandler) handlers[1]).insertItem(0, new ItemStack(Items.COBBLESTONE, amounts[1]), false);
                            default -> ((net.minecraftforge.fluids.capability.IFluidHandler) handlers[2]).fill(
                                    new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, amounts[2]),
                                    net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                        }
                        transfers.increment();
                    } catch (IllegalStateException e) {
                        if (e.getMessage() != null && e.getMessage().startsWith("H2 stress deliberate")) {
                            caught.increment();
                            if (depth(outs[k]) != 0) leaks.add("after throw depth=" + depth(outs[k]));
                        } else unexpected.add(Thread.currentThread().getName() + ": " + e);
                    } catch (Throwable e) {
                        unexpected.add(Thread.currentThread().getName() + ": " + e);
                    }
                    n++;
                }
                throwNext.remove();
                leakCheck.accept(Thread.currentThread().getName());
            }));
        }
        Runnable[] regionStep = {null};
        long[] rn = {0};
        regionStep[0] = () -> {                                         // owner RegionThread: real targets + recursion loop
            try {
                for (int k = 0; k < 3; k++) {
                    transfer(level, ins[k], kinds[k], amounts[k]);
                    regionTransfers.increment();
                }
            } catch (Throwable e) {
                unexpected.add("region: " + e);
            }
            if (System.nanoTime() < end) RegionTasks.submit(ownerHost, regionStep[0]);
            else leakCheck.accept(Thread.currentThread().getName());
        };
        Runnable[] loopStep = {null};
        loopStep[0] = () -> {
            try {
                long a = transfer(level, loopBase, Direction.NORTH, "fe", 1000);
                if (a != 0) unexpected.add("recursion loop accepted " + a);
                loops.increment();
            } catch (Throwable e) {
                unexpected.add("loop: " + e);
            }
            if (System.nanoTime() < end) RegionTasks.submit(loopHost, loopStep[0]);
        };
        RegionTasks.submit(ownerHost, regionStep[0]);
        if (loopHost.equals(ownerHost)) return "H2 stress: INVALID (loop host == owner host)";
        RegionTasks.submit(loopHost, loopStep[0]);
        mainProducer = () -> {
            if (System.nanoTime() >= end) {
                mainProducer = null;
                leakCheck.accept("Server thread");
                return;
            }
            try {
                for (int k = 0; k < 3; k++) {
                    transfer(level, ins[k], kinds[k], amounts[k]);
                    mainTransfers.increment();
                }
            } catch (Throwable e) {
                unexpected.add("main: " + e);
            }
        };
        status = "H2 stress: running";
        fork("nw-h2s-coordinator", () -> {
            long left = end - System.nanoTime();
            if (left > 0) {
                try {
                    Thread.sleep(left / 1_000_000 + 2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            boolean ok = join(threads.toArray(new Thread[0]));
            P2PDepth.hookInGet = null;
            P2PDepth.falseRejectWatch = null;
            long rejected = c.p2pDepthRejected.sum() - rej0, falseRej = c.p2pDepthFalseReject.sum() - fr0, underflow = c.p2pDepthUnderflow.sum() - uf0;
            List<String> f = new ArrayList<>(unexpected.subList(0, Math.min(5, unexpected.size())));
            if (!ok) f.add("producers did not finish");
            if (falseRej != 0) f.add("crossThreadFalseRejects=" + falseRej);
            if (underflow != 0) f.add("depthUnderflow=" + underflow);
            if (!leaks.isEmpty()) f.add("depthLeak " + leaks.subList(0, Math.min(3, leaks.size())));
            if (thrown.sum() != caught.sum()) f.add("thrown " + thrown.sum() + " != caught " + caught.sum());
            if (rejected != loops.sum()) f.add("realRecursionRejected " + rejected + " != loops " + loops.sum());
            status = "H2 stress " + seconds + "s [depth=" + (P2PDepth.PROTECT ? "THREAD" : "SHARED") + "]" + (f.isEmpty() ? ": PASS " : ": FAIL " + f + " ")
                    + "entries=" + entries.sum() + " transfers(plain)=" + transfers.sum() + " region=" + regionTransfers.sum()
                    + " main=" + mainTransfers.sum() + " loops=" + loops.sum() + " realRecursionRejected=" + rejected
                    + " crossThreadFalseRejects=" + falseRej + " depthUnderflow=" + underflow + " depthLeak=" + leaks.size()
                    + " thrown=" + thrown.sum() + " caught=" + caught.sum() + " unexpected=" + unexpected.size();
            Ae2Compat.LOG.info("[nestworld-compat-ae2] p2p fixture {}", status);
        });
        return status;
    }

    /** H2-3: the external handler throws (fixture hook inside get, depth 1); the guard's finally must
     *  restore depth; the next transfer on the same thread through the same tunnel must work. */
    public static String h2Throw(ServerLevel level, String kind, BlockPos in, BlockPos out, int amount) {
        P2PTunnelPart<?> o = tunnel(level, out, Direction.UP);
        if (o == null) return "H2-3: INVALID";
        P2PDepth.hookInGet = (tun, d) -> {
            if (tun == o) throw new IllegalStateException("H2-3 deliberate handler failure");
        };
        String thrown = null;
        try {
            transfer(level, in, kind, amount);
        } catch (IllegalStateException e) {
            thrown = e.getMessage();
        } finally {
            P2PDepth.hookInGet = null;
        }
        int after = depth(o);
        boolean entry = ((NwCapTunnel) o).nw$hasEntry();
        long next = transfer(level, in, kind, amount);
        List<String> f = new ArrayList<>();
        if (thrown == null) f.add("the handler failure did not propagate");
        if (after != 0) f.add("depth after the exception = " + after);
        if (P2PDepth.PROTECT && entry) f.add("ThreadLocal entry left after the exception");
        if (next <= 0) f.add("next transfer on the same thread failed (accepted " + next + "): tunnel dead");
        status = "H2-3 " + kind + " [depth=" + (P2PDepth.PROTECT ? "THREAD" : "SHARED") + "]" + (f.isEmpty() ? ": PASS " : ": FAIL " + f + " ")
                + "thrown=" + thrown + " depthAfter=" + after + " nextAccepted=" + next;
        Ae2Compat.LOG.info("[nestworld-compat-ae2] p2p fixture {}", status);
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
