package net.nestworld.ae2detect.mixin;

import appeng.hooks.ticking.TickHandler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.nestworld.ae2detect.B0Detect;
import org.spongepowered.asm.mixin.Mixin;

/** B0 (observation only): AE2 TickHandler server / level tick and BE readiness time. */
@Mixin(value = TickHandler.class, remap = false)
public abstract class B0TickHandlerMixin {
    @WrapMethod(method = "onServerTick")
    private void nwd$server(TickEvent.ServerTickEvent ev, Operation<Void> op) {
        long t = B0Detect.begin();
        try { op.call(ev); } finally { B0Detect.end("tick.server", t); }
    }

    @WrapMethod(method = "onLevelTick")
    private void nwd$level(TickEvent.LevelTickEvent ev, Operation<Void> op) {
        long t = B0Detect.begin();
        try { op.call(ev); } finally { B0Detect.end("tick.level", t); }
    }

    @WrapMethod(method = "readyBlockEntities")
    private void nwd$ready(ServerLevel level, Operation<Void> op) {
        long t = B0Detect.begin();
        try { op.call(level); } finally { B0Detect.end("tick.ready", t); }
    }
}
