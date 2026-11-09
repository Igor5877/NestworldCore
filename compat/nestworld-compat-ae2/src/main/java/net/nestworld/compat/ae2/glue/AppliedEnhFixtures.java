package net.nestworld.compat.ae2.glue;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.me.storage.ExternalStorageFacade;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

/**
 * APPLIEDENH-PERF-001 equivalence + timing fixtures (fixture-only: -Dnestworld.compatAe2.appliedEnhFixtures=true).
 * {@code trace}: the same deterministic extract/insert script runs on the same seeded inventory with elision OFF and
 * ON (fresh facade each run, so appliedenhancements' per-facade slot index / observed-slot state starts equal); it
 * compares every facade result, every wrapHandlerExtract decision (slot, amount, simulate, first, result) in order,
 * and the final slot contents; then restores the inventory. {@code bench}: a per-tick extract/insert loop for spark.
 */
public final class AppliedEnhFixtures {
    public static final boolean ENABLED = Boolean.getBoolean("nestworld.compatAe2.appliedEnhFixtures");

    private AppliedEnhFixtures() { }

    static IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        // prefer a sided capability: that is what an AE2 storage bus sees (live profile: CachedFailedInsertInventoryHandler)
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            IItemHandler h = be.getCapability(ForgeCapabilities.ITEM_HANDLER, d).orElse(null);
            if (h != null && h.getClass().getName().equals(AppliedEnhRecheckElision.CACHED_FAILED)) return h;
        }
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
    }

    static ItemStack[] snapshot(IItemHandler h) {
        ItemStack[] s = new ItemStack[h.getSlots()];
        for (int i = 0; i < s.length; i++) s[i] = h.getStackInSlot(i).copy();
        return s;
    }

    static void restore(IItemHandlerModifiable h, ItemStack[] s) {
        for (int i = 0; i < s.length && i < h.getSlots(); i++) h.setStackInSlot(i, s[i].copy());
    }

    static ItemStack named(String name) {
        ItemStack st = new ItemStack(Items.DIAMOND_SWORD);
        st.setHoverName(Component.literal(name));
        return st;
    }

    /** deterministic seed: several slots of the same item (multi-slot loops), NBT stacks, a partial stack. */
    static void seed(IItemHandlerModifiable h) {
        for (int i = 0; i < h.getSlots(); i++) h.setStackInSlot(i, ItemStack.EMPTY);
        ItemStack[] in = {new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.DIRT, 64),
                new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.IRON_INGOT, 5), named("nw-a"), named("nw-b"),
                new ItemStack(Items.COBBLESTONE, 10), new ItemStack(Items.DIRT, 30), new ItemStack(Items.REDSTONE, 64)};
        int slot = 0;
        for (ItemStack s : in) {
            if (slot >= h.getSlots()) break;
            h.setStackInSlot(slot++, s);
        }
    }

    static String contents(IItemHandler h) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.getSlots(); i++) {
            ItemStack s = h.getStackInSlot(i);
            if (s.isEmpty()) continue;
            sb.append(i).append('=').append(BuiltInRegistries.ITEM.getKey(s.getItem())).append('x').append(s.getCount())
                    .append(s.hasTag() ? "#" + Objects.hashCode(s.getTag()) : "").append(';');
        }
        return sb.toString();
    }

    record Run(List<Long> results, List<String> decisions, String finalContents, long elided) { }

    static Run runScript(IItemHandlerModifiable h) {
        seed(h);
        AppliedEnhRecheckElision.resetCounters();
        List<String> decisions = new ArrayList<>();
        final long id = System.identityHashCode(h);
        AppliedEnhRecheckElision.setSink(t -> { if (t[0] == id) decisions.add(t[1] + "/" + t[2] + "/" + t[3] + "/" + t[4] + "/" + t[6]); });
        List<Long> results = new ArrayList<>();
        try {
            ExternalStorageFacade f = ExternalStorageFacade.of(h);
            IActionSource src = IActionSource.empty();
            AEItemKey[] keys = {AEItemKey.of(Items.COBBLESTONE), AEItemKey.of(Items.DIRT), AEItemKey.of(Items.IRON_INGOT),
                    AEItemKey.of(named("nw-a")), AEItemKey.of(named("nw-b")), AEItemKey.of(Items.DIAMOND_SWORD),
                    AEItemKey.of(Items.GOLD_INGOT), AEItemKey.of(Items.REDSTONE)};
            long[] amounts = {1, 10, 63, 64, 65, 200, 100_000};
            int op = 0;
            for (int round = 0; round < 3; round++) {
                for (AEItemKey k : keys) {
                    for (long a : amounts) {
                        results.add(f.extract(k, a, Actionable.SIMULATE, src));
                        long got = f.extract(k, a, Actionable.MODULATE, src);
                        results.add(got);
                        if (got > 0) results.add(f.insert(k, (got + 1) / 2 + round, Actionable.MODULATE, src));
                        if (++op % 5 == 0) {
                            KeyCounter kc = new KeyCounter();
                            f.getAvailableStacks(kc);
                            results.add((long) kc.size());
                        }
                    }
                }
            }
        } finally {
            AppliedEnhRecheckElision.setSink(null);
        }
        return new Run(results, decisions, contents(h), AppliedEnhRecheckElision.ELIDED.sum());
    }

    public static String trace(ServerLevel level, BlockPos pos) {
        IItemHandler raw = handlerAt(level, pos);
        if (!(raw instanceof IItemHandlerModifiable h)) return "no modifiable item handler at " + pos;
        ItemStack[] saved = snapshot(h);
        boolean was = AppliedEnhRecheckElision.enabled();
        try {
            AppliedEnhRecheckElision.setEnabled(false);
            Run off = runScript(h);
            AppliedEnhRecheckElision.setEnabled(true);
            Run on = runScript(h);
            AppliedEnhRecheckElision.setEnabled(false);
            Run off2 = runScript(h);
            boolean eqR = off.results.equals(on.results) && off.results.equals(off2.results);
            boolean eqD = off.decisions.equals(on.decisions) && off.decisions.equals(off2.decisions);
            boolean eqC = off.finalContents.equals(on.finalContents) && off.finalContents.equals(off2.finalContents);
            long sumOff = off.results.stream().mapToLong(Long::longValue).sum(), sumOn = on.results.stream().mapToLong(Long::longValue).sum();
            String firstDiff = "";
            for (int i = 0; i < Math.min(off.decisions.size(), on.decisions.size()); i++)
                if (!off.decisions.get(i).equals(on.decisions.get(i))) { firstDiff = " firstDecisionDiff@" + i + " off=" + off.decisions.get(i) + " on=" + on.decisions.get(i); break; }
            boolean eq = eqR && eqD && eqC && off.elided == 0 && off2.elided == 0;
            String verdict = AppliedEnhRecheckElision.CACHE_OFF_PROP ? (eq && on.elided == 0 ? "NEG-CONTROL-OK" : "NEG-CONTROL-FAIL")
                    : (eq && on.elided > 0 ? "PASS" : eq ? "EQUAL-NO-ELISION" : "FAIL");
            String msg = "APPLIEDENH-TRACE " + verdict + " handler=" + h.getClass().getName() + " slots=" + h.getSlots()
                    + " results=" + off.results.size() + " eq=" + eqR + " (sumOff=" + sumOff + " sumOn=" + sumOn + ")"
                    + " decisions=" + off.decisions.size() + "/" + on.decisions.size() + " eq=" + eqD
                    + " contentsEq=" + eqC + " elidedOff=" + off.elided + " elidedOn=" + on.elided + " elidedOff2=" + off2.elided
                    + " decisionHash=" + off.decisions.hashCode() + "/" + on.decisions.hashCode() + " resultHash=" + off.results.hashCode()
                    + "/" + on.results.hashCode() + firstDiff + " final=" + on.finalContents;
            Ae2Compat.LOG.info("[nestworld-compat-ae2] {}", msg);
            return msg;
        } finally {
            AppliedEnhRecheckElision.setEnabled(was);
            restore(h, saved);
        }
    }

    // ---- bench: N iterations per server tick for T ticks (profiled externally with spark) ----
    private static volatile Bench bench;

    static final class Bench {
        final IItemHandlerModifiable h; final ExternalStorageFacade f; final ItemStack[] saved; final String before;
        final int iters; int ticksLeft; long nanos, n, extracted, inserted; final boolean mode;
        Bench(IItemHandlerModifiable h, int ticks, int iters) {
            this.h = h; this.saved = snapshot(h); seed(h); this.before = contents(h);
            this.f = ExternalStorageFacade.of(h); this.ticksLeft = ticks; this.iters = iters; this.mode = AppliedEnhRecheckElision.enabled();
        }
    }

    public static String benchStart(ServerLevel level, BlockPos pos, int ticks, int iters) {
        if (bench != null) return "bench already running";
        IItemHandler raw = handlerAt(level, pos);
        if (!(raw instanceof IItemHandlerModifiable h)) return "no modifiable item handler at " + pos;
        AppliedEnhRecheckElision.resetCounters();
        bench = new Bench(h, ticks, iters);
        return "bench started elision=" + bench.mode + " ticks=" + ticks + " iters/tick=" + iters + " handler=" + h.getClass().getSimpleName();
    }

    private static volatile String lastBench = "none";

    public static String benchStatus() {
        Bench b = bench;
        return b == null ? "idle; last: " + lastBench : "running ticksLeft=" + b.ticksLeft + " n=" + b.n;
    }

    static void onServerTick() {
        Bench b = bench;
        if (b == null) return;
        IActionSource src = IActionSource.empty();
        AEItemKey cobble = AEItemKey.of(Items.COBBLESTONE), dirt = AEItemKey.of(Items.DIRT);
        long t0 = System.nanoTime();
        for (int i = 0; i < b.iters; i++) {
            AEItemKey k = (i & 1) == 0 ? cobble : dirt;
            long s = b.f.extract(k, 64, Actionable.SIMULATE, src);
            long m = b.f.extract(k, s, Actionable.MODULATE, src);
            b.extracted += m;
            b.inserted += b.f.insert(k, m, Actionable.MODULATE, src);
        }
        b.nanos += System.nanoTime() - t0;
        b.n += b.iters;
        if (--b.ticksLeft <= 0) {
            bench = null;
            String after = contents(b.h);
            boolean conserved = b.extracted == b.inserted;
            lastBench = "APPLIEDENH-BENCH elision=" + b.mode + " iters=" + b.n + " nsPerIter=" + (b.n == 0 ? 0 : b.nanos / b.n)
                    + " extracted=" + b.extracted + " inserted=" + b.inserted + " conserved=" + conserved
                    + " layoutUnchanged=" + after.equals(b.before) + " | " + AppliedEnhRecheckElision.status();
            Ae2Compat.LOG.info("[nestworld-compat-ae2] {}", lastBench);
            restore(b.h, b.saved);
        }
    }
}
