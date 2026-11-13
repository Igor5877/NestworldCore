package net.nestworld.compat.ae2.glue;

import appeng.me.Grid;
import appeng.parts.storagebus.StorageBusPart;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * AE2-GRID-DOMAIN-MVP-001 (diagnostics only, owner 2026-11-12): narrow touch census of EXACTLY the Sophisticated
 * chests mounted by grid #1's storage buses. Every operation on them is counted per (op, thread); one in {@link #SAMPLE}
 * is stack-walked for the first caller outside Sophisticated / Forge / Minecraft / JDK / Mixin. No behaviour change.
 */
public final class ChestTouchCensus {
    private ChestTouchCensus() {}

    static volatile boolean on;
    private static volatile int startTick;
    private static final Map<Object, String> TARGETS = new ConcurrentHashMap<>();   // storage wrapper → "pos type"
    private static final Map<String, LongAdder> OPS = new ConcurrentHashMap<>(), CALLERS = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> NEIGHBOURS = new ConcurrentHashMap<>();
    private static final int SAMPLE = 8;
    private static int counter;   // racy on purpose: sampling only
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final String[] SKIP = { "net.p3pp3rf1y.", "net.minecraftforge.", "net.minecraft.", "java.", "jdk.", "sun.",
            "org.spongepowered.", "com.llamalad7.", "net.nestworld.compat.ae2.ingress.", "net.nestworld.compat.ae2.glue.ChestTouchCensus" };

    public static void touch(Object wrapper, String op) {
        if (!on || wrapper == null || !TARGETS.containsKey(wrapper)) return;
        String thread = thread();
        OPS.computeIfAbsent(op + " @" + thread, k -> new LongAdder()).increment();
        if ((++counter % SAMPLE) != 0) return;
        String caller = WALKER.walk(s -> s.map(f -> f.getClassName() + "#" + f.getMethodName())
                .filter(n -> { for (String p : SKIP) if (n.startsWith(p)) return false; return true; }).findFirst().orElse("?"));
        CALLERS.computeIfAbsent(op + " @" + thread + " by " + caller, k -> new LongAdder()).increment();
    }

    private static final Map<String, LongAdder> FACADE = new ConcurrentHashMap<>();

    /** ExternalStorageFacade insert/extract (all facades, not only the 25 chests): sizing of a MAIN-serviced adapter. */
    public static void facade(String op) {
        if (!on) return;
        FACADE.computeIfAbsent(op + " @" + thread(), k -> new LongAdder()).increment();
    }

    private static String thread() {
        if (GridWorker.isWorkerThread()) return "AE2-worker";
        if (Ae2Compat.onMainThread()) return "MAIN";
        String n = Thread.currentThread().getName();
        return n.startsWith("Region") || n.contains("Region") ? "REGION" : n.replaceAll("\\d+", "#");
    }

    /** MAIN: collect the storage-bus targets of the largest grid and start counting. */
    public static String start(MinecraftServer server) {
        Grid top = null;
        for (Grid g : appeng.hooks.ticking.TickHandler.instance().getGridList()) if (top == null || g.size() > top.size()) top = g;
        if (top == null) return "no grids";
        TARGETS.clear(); OPS.clear(); CALLERS.clear(); NEIGHBOURS.clear(); FACADE.clear();
        startTick = server.getTickCount();
        int buses = 0, other = 0;
        for (var node : top.getMachineNodes(StorageBusPart.class)) {
            if (!(node.getOwner() instanceof StorageBusPart bus) || bus.getHost() == null) continue;
            buses++;
            BlockEntity host = bus.getHost().getBlockEntity();
            BlockEntity be = host.getLevel().getBlockEntity(host.getBlockPos().relative(bus.getSide()));
            if (be == null || !be.getClass().getName().startsWith("net.p3pp3rf1y.sophisticatedstorage.")) { other++; continue; }
            try {
                Object wrapper = be.getClass().getMethod("getStorageWrapper").invoke(be);
                TARGETS.put(wrapper, be.getLevel().dimension().location() + " " + be.getBlockPos().toShortString() + " " + be.getClass().getSimpleName());
                for (var d : net.minecraft.core.Direction.values()) {   // static: who else could reach this chest
                    BlockEntity n = be.getLevel().getBlockEntity(be.getBlockPos().relative(d));
                    if (n == null) continue;
                    var k = net.minecraft.world.level.block.entity.BlockEntityType.getKey(n.getType());
                    NEIGHBOURS.computeIfAbsent(String.valueOf(k), x -> new LongAdder()).increment();
                }
            } catch (ReflectiveOperationException e) { other++; }
        }
        on = true;
        return "chest census on: grid1 nodes=" + top.size() + " storageBuses=" + buses + " sophisticatedTargets=" + TARGETS.size()
                + " otherTargets=" + other;
    }

    public static String report() {
        StringBuilder sb = new StringBuilder("chest census targets=" + TARGETS.size() + " on=" + on + "\n static neighbour BE types of the targets:");
        NEIGHBOURS.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .forEach(e -> sb.append("\n  ").append(e.getValue().sum()).append("  ").append(e.getKey()));
        sb.append("\n ExternalStorageFacade ops (all facades, all grids) since start:");
        FACADE.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .forEach(e -> sb.append("\n  ").append(e.getValue().sum()).append("  ").append(e.getKey()));
        sb.append("\n ops (all counted):");
        var l = new ArrayList<>(OPS.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
        for (var e : l) sb.append("\n  ").append(e.getValue().sum()).append("  ").append(e.getKey());
        sb.append("\n callers (1/" + SAMPLE + " sampled):");
        var c = new ArrayList<>(CALLERS.entrySet());
        c.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
        for (int i = 0; i < Math.min(40, c.size()); i++) sb.append("\n  ").append(c.get(i).getValue().sum()).append("  ").append(c.get(i).getKey());
        return sb.toString();
    }
}
