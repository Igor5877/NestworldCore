package dev.nestworld.g0;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.nestworld.module.*;

/** MODULE-2 G0 probe module. g0.mode: lambda | subscribe-main | subscribe-region. The typed
 *  (lambda) control is registered in every mode at HIGHEST so it runs before the @SubscribeEvent
 *  listener of the same event. The typed SPI does not exist yet, so the control calls
 *  EVENT_BUS.addListener directly -- the path the proposed capability would use. */
public final class G0ProbeModule implements NestWorldModule {
    private static final ModuleDescriptor D =
            new ModuleDescriptor(new ModuleId("g0_probe"), new ModuleVersion("0.0.1"), "G0 probe");
    private long serverTicks;

    @Override
    public ModuleDescriptor descriptor() {
        return D;
    }

    @Override
    public void onEnable(ModuleContext context) {
        String mode = System.getProperty("g0.mode", "lambda");
        Thread t = Thread.currentThread();
        Probe.LOG.info("G0PROBE onEnable mode={} thread={} TCCL={} moduleLoader={} identity={}",
                mode, t.getName(), Probe.cl(t.getContextClassLoader()), Probe.cl(getClass().getClassLoader()), context.identity());

        Object typedOwner = new TypedControl();
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, TickEvent.ServerTickEvent.class, e -> {
            if (e.phase != TickEvent.Phase.END) return;
            Probe.hit("typed", typedOwner, "ServerTickEvent");
            if (++serverTicks % 200 == 0) Probe.LOG.info("G0PROBE COUNTS tick={} {}", serverTicks, Probe.COUNTS);
        });
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, LivingEvent.LivingTickEvent.class,
                e -> Probe.hit("typed", typedOwner, "LivingTickEvent"));

        Object subscriber = switch (mode) {
            case "subscribe-main", "subscribe-main-tccl" -> new SubscribeServerTickListener();
            case "subscribe-region" -> new SubscribeLivingListener();
            default -> null;
        };
        if (subscriber != null) {
            // subscribe-main-tccl: the state spec §6.5 proposes (TCCL = module loader during onEnable)
            ClassLoader prev = Thread.currentThread().getContextClassLoader();
            if (mode.endsWith("-tccl")) Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
            ResourceHandle h;
            try {
                h = context.registerListener(subscriber);   // the CURRENT SPI path
            } finally {
                Thread.currentThread().setContextClassLoader(prev);
            }
            Probe.LOG.info("G0PROBE registerListener(Object) returned {} for {}", h == null ? "null" : h.kind(), subscriber.getClass().getName());
        }
    }

    static final class TypedControl {
    }
}
