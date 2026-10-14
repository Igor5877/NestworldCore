package net.nestworld.compat.ae2;

import com.mojang.brigadier.CommandDispatcher;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import net.nestworld.compat.ae2.glue.Ae2Compat;
import net.nestworld.compat.ae2.glue.Ae2CompatMixinPlugin;

/** nestworld-compat-ae2: AE2 energy concurrency domain for NestWorld (docs/compat/AE2-COMPAT-DESIGN-001*.md). */
@Mod(NestworldCompatAe2.MOD_ID)
public final class NestworldCompatAe2 {
    public static final String MOD_ID = "nestworld_compat_ae2";

    public NestworldCompatAe2() {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> register(e.getDispatcher()));
    }

    /** Read-only counters (001 §9: the addon publishes its counters through a read-only command). */
    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("nwcompatae2").requires(s -> s.hasPermission(2))
                .then(Commands.literal("stats").executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal(stats()), false);
                    return 1;
                })));
    }

    public static String stats() {
        StringBuilder sb = new StringBuilder();
        sb.append("active=").append(Ae2CompatMixinPlugin.isActive()).append(" mode=").append(Ae2Compat.KERNEL.mode());
        append(sb, Ae2Compat.KERNEL.metrics);
        append(sb, Ae2Compat.COUNTERS);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object counters) {
        for (Field f : counters.getClass().getFields()) {
            try {
                if (f.get(counters) instanceof LongAdder a) sb.append(' ').append(f.getName()).append('=').append(a.sum());
            } catch (IllegalAccessException ignored) {
                // public fields only
            }
        }
    }
}
