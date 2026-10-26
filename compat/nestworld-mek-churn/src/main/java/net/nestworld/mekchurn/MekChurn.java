package net.nestworld.mekchurn;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * TEST-ONLY workload. A driver block whose block-entity ticker (which NestWorld runs on the owning region
 * thread) alternately places and removes a Mekanism energy cube directly above itself. The driver must sit
 * next to a Mekanism cable, so every toggle exercises the cable's neighbour-change / acceptor path and the
 * cube's own join/separate path from the region thread, while Mekanism's registry is consumed on main.
 * Default period: every tick; set with /nwmekchurn period <ticks>; /nwmekchurn pause|resume|status.
 */
@Mod("nestworld_mekchurn")
public final class MekChurn {
    static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, "nestworld_mekchurn");
    static final DeferredRegister<BlockEntityType<?>> BES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "nestworld_mekchurn");
    static final RegistryObject<Block> DRIVER = BLOCKS.register("driver", Driver::new);
    @SuppressWarnings("ConstantConditions")
    static final RegistryObject<BlockEntityType<DriverBE>> DRIVER_BE = BES.register("driver",
            () -> BlockEntityType.Builder.of(DriverBE::new, DRIVER.get()).build(null));

    static volatile int period = 1;
    static volatile boolean paused;
    static final LongAdder toggles = new LongAdder();
    static final LongAdder toggleErrors = new LongAdder();
    static final Map<String, LongAdder> THREADS = new ConcurrentHashMap<>();
    static volatile String firstError = "-";

    public MekChurn() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        BES.register(bus);
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(
                net.minecraft.commands.Commands.literal("nwmekchurn").requires(s -> s.hasPermission(2))
                        .then(net.minecraft.commands.Commands.literal("status").executes(c -> say(c.getSource(), status())))
                        .then(net.minecraft.commands.Commands.literal("pause").executes(c -> { paused = true; return say(c.getSource(), "paused"); }))
                        .then(net.minecraft.commands.Commands.literal("resume").executes(c -> { paused = false; return say(c.getSource(), "resumed"); }))
                        .then(net.minecraft.commands.Commands.literal("period")
                                .then(net.minecraft.commands.Commands.argument("ticks",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 1200))
                                        .executes(c -> {
                                            period = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "ticks");
                                            return say(c.getSource(), "period=" + period);
                                        })))));
    }

    static int say(net.minecraft.commands.CommandSourceStack s, String t) {
        s.sendSuccess(() -> Component.literal(t), false);
        return 1;
    }

    static String status() {
        StringBuilder b = new StringBuilder("churn paused=").append(paused).append(" period=").append(period)
                .append(" toggles=").append(toggles.sum()).append(" errors=").append(toggleErrors.sum())
                .append(" firstError=").append(firstError);
        THREADS.forEach((k, v) -> b.append("\n ").append(k).append(" = ").append(v.sum()));
        try {
            b.append("\n ").append(Class.forName("net.nestworld.compat.NestworldCoremodSupport").getMethod("mekAuditSummary").invoke(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            b.append("\n coreAudit=n/a");
        }
        try {
            Class<?> cache = Class.forName("mekanism.common.lib.transmitter.acceptor.NetworkAcceptorCache");
            boolean patched = false;
            for (var m : cache.getDeclaredMethods()) patched |= m.getName().equals("nestworld$markChanged");
            b.append("\n NetworkAcceptorCache patched=").append(patched);
        } catch (ReflectiveOperationException | LinkageError e) {
            b.append("\n NetworkAcceptorCache lookup failed: ").append(e);
        }
        return b.toString();
    }

    static final class Driver extends BaseEntityBlock {
        Driver() { super(BlockBehaviour.Properties.of().strength(1f)); }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new DriverBE(pos, state); }
        @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide ? null : createTickerHelper(type, DRIVER_BE.get(), DriverBE::tick);
        }
    }

    static final class DriverBE extends BlockEntity {
        long ticks;
        DriverBE(BlockPos pos, BlockState state) { super(DRIVER_BE.get(), pos, state); }

        static void tick(Level level, BlockPos pos, BlockState state, DriverBE be) {
            if (paused || ++be.ticks % period != 0) return;
            Block cube = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("mekanism", "basic_energy_cube"));
            if (cube == null || cube == Blocks.AIR) return;
            BlockPos target = pos.above();
            try {
                BlockState now = level.getBlockState(target);
                if (now.is(cube)) level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
                else if (now.isAir()) level.setBlock(target, cube.defaultBlockState(), 3);
                else return;
                toggles.increment();
                THREADS.computeIfAbsent(Thread.currentThread().getName().replaceAll("[0-9]+", "#"), k -> new LongAdder()).increment();
            } catch (Throwable t) {
                toggleErrors.increment();
                if ("-".equals(firstError)) firstError = t + "@" + Thread.currentThread().getName();
            }
        }

    }
}
