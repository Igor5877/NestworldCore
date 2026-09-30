package net.nestworld.module;

import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * MODULE-1: the "deliberately boring" test module the architecture owner asked for -- proves
 * discovery, descriptor identity, generation assignment, context creation, three managed
 * registrations (A=listener, B=command, C=command), lifecycle order, registration rejection
 * after QUIESCING, resource revocation, and (via {@link #tickCount}, exposed through RCON
 * status) that revocation is REAL, not just recorded -- the listener actually stops being
 * invoked after revoke, observable live without reading source code. Not used to justify any
 * further SPI surface -- exactly the 3 registrations the central test needs, nothing more.
 */
public final class ExampleModule implements NestWorldModule {
    private final AtomicInteger tickCount = new AtomicInteger();

    public int tickCount() {
        return tickCount.get();
    }

    @Override
    public ModuleDescriptor descriptor() {
        return new ModuleDescriptor(new ModuleId("example-module"), new ModuleVersion("1.0.0"), "Example Module");
    }

    @Override
    public void onEnable(ModuleContext context) {
        // A: listener -- a real Forge event-bus registration, ticks a counter so live
        // verification can observe it INCREMENTING while RUNNING and STOPPING after revoke.
        context.registerListener(new TickListener());

        // B, C: two top-level commands, siblings of NestWorld's own tree, never touching it.
        context.registerCommand("exmodping", ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal("example-module: pong"), false);
            return 1;
        });
        context.registerCommand("exmodstatus", ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal("example-module: ticks=" + tickCount.get()), false);
            return 1;
        });
    }

    @Override
    public void onDisable() {
        // Nothing beyond ModuleContext-owned resources for this deliberately boring module --
        // the ledger already revoked A/B/C before this runs. Nothing module-owned to clean up.
    }

    final class TickListener {
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                tickCount.incrementAndGet();
            }
        }
    }
}
