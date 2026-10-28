package net.nestworld.ae2detect.mixin;

import appeng.util.FakePlayer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.nestworld.ae2detect.StaticDetect;
import net.nestworld.ae2detect.StaticDetect.Site;
import org.spongepowered.asm.mixin.Mixin;

/** Observation only: FakePlayer.FAKE_PLAYERS (static WeakHashMap) is read/written in get(). */
@Mixin(value = FakePlayer.class, remap = false)
public abstract class FakePlayerDetectMixin {
    @WrapMethod(method = "get")
    private static Player nwd$get(ServerLevel level, GameProfile profile, Operation<Player> op) {
        StaticDetect.enter(Site.FAKE_PLAYERS);
        try { return op.call(level, profile); } finally { StaticDetect.exit(Site.FAKE_PLAYERS); }
    }
}
