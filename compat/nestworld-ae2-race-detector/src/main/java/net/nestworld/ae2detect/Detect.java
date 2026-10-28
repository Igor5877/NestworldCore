package net.nestworld.ae2detect;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Observation-only detector for AE2 EnergyService cross-thread overlaps (test copies of real islands).
 * It never changes AE2 behaviour: wrappers call the original exactly once, HEAD injects only count.
 */
@Mod("nestworld_ae2detect")
public final class Detect {
    /** service -> threads currently inside injectProviderPower / extractProviderPower */
    public static final Map<Object, Set<Thread>> INJECTING = new ConcurrentHashMap<>();
    public static final Map<Object, Set<Thread>> EXTRACTING = new ConcurrentHashMap<>();

    public static final LongAdder injectCalls = new LongAdder(), extractCalls = new LongAdder();
    /** extract on thread T while another thread injects into the same service */
    public static final LongAdder extractWhileForeignInject = new LongAdder();
    /** two different threads inside injectProviderPower of the same service at once */
    public static final LongAdder injectWhileForeignInject = new LongAdder();
    /** inject on T while another thread extracts from the same service */
    public static final LongAdder injectWhileForeignExtract = new LongAdder();
    /** add/removeRequester while ongoingInjectOperation was set by ANOTHER thread (the airis ISE shape) */
    public static final LongAdder requesterModifyForeignInject = new LongAdder();
    /** same, but the flag was set by the CURRENT thread (AE2 single-thread re-entrancy) */
    public static final LongAdder requesterModifySameThread = new LongAdder();
    /** add/removeProvider while ongoingExtractOperation was set by ANOTHER thread */
    public static final LongAdder providerModifyForeignExtract = new LongAdder();
    public static final LongAdder providerModifySameThread = new LongAdder();
    public static final AtomicLong firstForeignModifyMillis = new AtomicLong();
    public static volatile String firstForeignModify = "-";

    public Detect() {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(
                net.minecraft.commands.Commands.literal("nwae2detect").requires(s -> s.hasPermission(2)).executes(c -> {
                    c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(summary()), false);
                    return 1;
                })));
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(
                net.minecraft.commands.Commands.literal("nwae2static").requires(s -> s.hasPermission(2))
                        .executes(c -> say(c.getSource(), StaticDetect.summary()))
                        .then(net.minecraft.commands.Commands.literal("threads").executes(c -> say(c.getSource(), StaticDetect.threads())))
                        .then(net.minecraft.commands.Commands.literal("stacks").executes(c -> say(c.getSource(), StaticDetect.stacks())))));
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(
                net.minecraft.commands.Commands.literal("nwae2tick").requires(s -> s.hasPermission(2))
                        .executes(c -> say(c.getSource(), TickDetect.summary()))
                        .then(net.minecraft.commands.Commands.literal("threads").executes(c -> say(c.getSource(), TickDetect.threads())))
                        .then(net.minecraft.commands.Commands.literal("stacks").executes(c -> say(c.getSource(), TickDetect.stacks())))
                        .then(net.minecraft.commands.Commands.literal("verify").executes(c -> say(c.getSource(), TickDetect.verify())))));
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(
                net.minecraft.commands.Commands.literal("nwmekdetect").requires(s -> s.hasPermission(2))
                        .executes(c -> say(c.getSource(), MekDetect.summary()))
                        .then(net.minecraft.commands.Commands.literal("threads").executes(c -> say(c.getSource(), MekDetect.threads())))
                        .then(net.minecraft.commands.Commands.literal("stacks").executes(c -> say(c.getSource(), MekDetect.stacks())))));
    }

    private static int say(net.minecraft.commands.CommandSourceStack s, String text) {
        s.sendSuccess(() -> net.minecraft.network.chat.Component.literal(text), false);
        return 1;
    }

    public static String summary() {
        return "injectCalls=" + injectCalls.sum() + " extractCalls=" + extractCalls.sum()
                + " extractWhileForeignInject=" + extractWhileForeignInject.sum()
                + " injectWhileForeignInject=" + injectWhileForeignInject.sum()
                + " injectWhileForeignExtract=" + injectWhileForeignExtract.sum()
                + " requesterModifyForeignInject=" + requesterModifyForeignInject.sum()
                + " requesterModifySameThread=" + requesterModifySameThread.sum()
                + " providerModifyForeignExtract=" + providerModifyForeignExtract.sum()
                + " providerModifySameThread=" + providerModifySameThread.sum()
                + " firstForeignModify=" + firstForeignModify;
    }

    public static boolean foreign(Map<Object, Set<Thread>> m, Object svc) {
        Set<Thread> s = m.get(svc);
        if (s == null) return false;
        Thread me = Thread.currentThread();
        for (Thread t : s) if (t != me) return true;
        return false;
    }

    public static void enter(Map<Object, Set<Thread>> m, Object svc) {
        m.computeIfAbsent(svc, k -> ConcurrentHashMap.newKeySet()).add(Thread.currentThread());
    }

    public static void exit(Map<Object, Set<Thread>> m, Object svc) {
        Set<Thread> s = m.get(svc);
        if (s != null) s.remove(Thread.currentThread());
    }

    public static void noteFirst(String what) {
        if (firstForeignModifyMillis.compareAndSet(0, System.currentTimeMillis())) {
            firstForeignModify = what + "@" + Thread.currentThread().getName() + "@" + new java.util.Date();
        }
    }
}
