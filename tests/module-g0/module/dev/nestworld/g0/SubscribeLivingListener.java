package dev.nestworld.g0;

import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class SubscribeLivingListener {
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingTick(LivingEvent.LivingTickEvent e) {
        Probe.hit("subscribe", this, "LivingTickEvent");
    }
}
