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
    /** item driver: every period, insert 1 cobblestone into each neighbour's item handler and extract it again */
    static final RegistryObject<Block> ITEM_DRIVER = BLOCKS.register("itemdriver", ItemDriver::new);
    /** read-only probe (AE2-CROSS-REGION-TOPOLOGY-001 boundary fixture): each tick, on the thread that ticks it,
     *  resolves the AE2 grid node exposed by the block entity at the configured target/side. Never mutates. */
    static final RegistryObject<Block> PROBE = BLOCKS.register("probe", Probe::new);
    @SuppressWarnings("ConstantConditions")
    static final RegistryObject<BlockEntityType<DriverBE>> DRIVER_BE = BES.register("driver",
            () -> BlockEntityType.Builder.of(DriverBE::new, DRIVER.get()).build(null));
    @SuppressWarnings("ConstantConditions")
    static final RegistryObject<BlockEntityType<ItemDriverBE>> ITEM_DRIVER_BE = BES.register("itemdriver",
            () -> BlockEntityType.Builder.of(ItemDriverBE::new, ITEM_DRIVER.get()).build(null));
    @SuppressWarnings("ConstantConditions")
    static final RegistryObject<BlockEntityType<ProbeBE>> PROBE_BE = BES.register("probe",
            () -> BlockEntityType.Builder.of(ProbeBE::new, PROBE.get()).build(null));
    static volatile BlockPos probeTarget;
    static volatile net.minecraft.core.Direction probeSide = net.minecraft.core.Direction.WEST;
    static final LongAdder probeLookups = new LongAdder(), probeBeNull = new LongAdder(), probeNodeNull = new LongAdder(),
            probeNodeLive = new LongAdder();
    static volatile String probeLast = "-";
    static final LongAdder itemOps = new LongAdder();
    static final LongAdder itemInserted = new LongAdder();

    static volatile int period = 1;
    /** item driver: insert/extract cycles per neighbour per driver tick (amplifies the same real path) */
    static volatile int burst = 1;
    /** block toggled above each driver (default: Mekanism energy cube; e.g. minecraft:redstone_block for AE2 IO buses) */
    static volatile String targetBlock = "mekanism:basic_energy_cube";
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
                        .then(net.minecraft.commands.Commands.literal("probetarget")
                                .then(net.minecraft.commands.Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                        .then(net.minecraft.commands.Commands.argument("side", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .executes(c -> {
                                                    probeTarget = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "pos");
                                                    probeSide = net.minecraft.core.Direction.byName(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "side"));
                                                    return say(c.getSource(), "probe target=" + probeTarget + " side=" + probeSide);
                                                }))))
                        .then(net.minecraft.commands.Commands.literal("probe").executes(c -> say(c.getSource(),
                                "region-probe lookups=" + probeLookups.sum() + " beNull=" + probeBeNull.sum() + " nodeNull=" + probeNodeNull.sum()
                                        + " nodeLive=" + probeNodeLive.sum() + " last=" + probeLast)))
                        .then(net.minecraft.commands.Commands.literal("probemain")
                                .then(net.minecraft.commands.Commands.argument("a", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                        .then(net.minecraft.commands.Commands.argument("aside", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .executes(c -> say(c.getSource(), probeMain(c.getSource().getLevel(),
                                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(c, "a"),
                                                        net.minecraft.core.Direction.byName(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "aside"))))))))
                        .then(net.minecraft.commands.Commands.literal("burst")
                                .then(net.minecraft.commands.Commands.argument("n",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 100000))
                                        .executes(c -> {
                                            burst = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "n");
                                            return say(c.getSource(), "burst=" + burst);
                                        })))
                        .then(net.minecraft.commands.Commands.literal("block")
                                .then(net.minecraft.commands.Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(c -> {
                                            targetBlock = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id").trim();
                                            return say(c.getSource(), "block=" + targetBlock);
                                        })))
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
        StringBuilder b = new StringBuilder("churn paused=").append(paused).append(" period=").append(period).append(" block=").append(targetBlock)
                .append(" burst=").append(burst).append(" toggles=").append(toggles.sum()).append(" itemOps=").append(itemOps.sum()).append(" itemInserted=").append(itemInserted.sum()).append(" errors=").append(toggleErrors.sum())
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

    /** AE2 IInWorldGridNodeHost.getGridNode(side) via reflection (no compile dependency on AE2). */
    static Object ae2Node(BlockEntity be, net.minecraft.core.Direction side) throws ReflectiveOperationException {
        Class<?> host = Class.forName("appeng.api.networking.IInWorldGridNodeHost");
        if (be == null || !host.isInstance(be)) return null;
        return host.getMethod("getGridNode", net.minecraft.core.Direction.class).invoke(be, side);
    }

    static String id(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }

    /** main-thread view: live BE/node at the probe target, and whether node A (at a, side aside) is connected to it */
    @SuppressWarnings("unchecked")
    static String probeMain(net.minecraft.server.level.ServerLevel level, BlockPos a, net.minecraft.core.Direction aside) {
        try {
            BlockPos b = probeTarget;
            if (b == null) return "probetarget not set";
            BlockEntity beB = level.getBlockEntity(b), beA = level.getBlockEntity(a);
            Object nodeB = ae2Node(beB, probeSide), nodeA = ae2Node(beA, aside);
            boolean connected = false;
            if (nodeA != null) {
                Class<?> conn = Class.forName("appeng.api.networking.IGridConnection");
                Class<?> nodeItf = Class.forName("appeng.api.networking.IGridNode");
                for (Object c : (java.util.List<Object>) nodeItf.getMethod("getConnections").invoke(nodeA)) {
                    Object other = conn.getMethod("getOtherSide", nodeItf).invoke(c, nodeA);
                    if (other == nodeB && nodeB != null) connected = true;
                }
            }
            return "main thread=" + Thread.currentThread().getName() + " B.be=" + id(beB) + " B.node=" + id(nodeB)
                    + " A.be=" + id(beA) + " A.node=" + id(nodeA) + " A<->B connected=" + connected;
        } catch (Throwable t) {
            return "probemain failed: " + t;
        }
    }

    static final class Probe extends BaseEntityBlock {
        Probe() { super(BlockBehaviour.Properties.of().strength(1f)); }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ProbeBE(pos, state); }
        @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide ? null : createTickerHelper(type, PROBE_BE.get(), ProbeBE::tick);
        }
    }

    static final class ProbeBE extends BlockEntity {
        ProbeBE(BlockPos pos, BlockState state) { super(PROBE_BE.get(), pos, state); }

        static void tick(Level level, BlockPos pos, BlockState state, ProbeBE be) {
            BlockPos t = probeTarget;
            if (paused || t == null) return;
            try {
                BlockEntity target = level.getBlockEntity(t);
                Object node = ae2Node(target, probeSide);
                probeLookups.increment();
                if (target == null) probeBeNull.increment();
                else if (node == null) probeNodeNull.increment();
                else probeNodeLive.increment();
                probeLast = "thread=" + Thread.currentThread().getName() + " be=" + id(target)
                        + " removed=" + (target != null && target.isRemoved()) + " node=" + id(node);
            } catch (Throwable e) {
                toggleErrors.increment();
                if ("-".equals(firstError)) firstError = e + "@" + Thread.currentThread().getName();
            }
        }
    }

    static final class ItemDriver extends BaseEntityBlock {
        ItemDriver() { super(BlockBehaviour.Properties.of().strength(1f)); }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ItemDriverBE(pos, state); }
        @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide ? null : createTickerHelper(type, ITEM_DRIVER_BE.get(), ItemDriverBE::tick);
        }
    }

    static final class ItemDriverBE extends BlockEntity {
        long ticks;
        ItemDriverBE(BlockPos pos, BlockState state) { super(ITEM_DRIVER_BE.get(), pos, state); }

        static void tick(Level level, BlockPos pos, BlockState state, ItemDriverBE be) {
            if (paused || ++be.ticks % period != 0) return;
            try {
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    BlockEntity n = level.getBlockEntity(pos.relative(d));
                    if (n == null) continue;
                    n.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, d.getOpposite()).ifPresent(h -> {
                        for (int k = 0; k < burst; k++)
                        for (int slot = 0; slot < h.getSlots(); slot++) {
                            net.minecraft.world.item.ItemStack rest = h.insertItem(slot,
                                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE), false);
                            if (rest.isEmpty()) {
                                itemInserted.increment();
                                h.extractItem(slot, 1, false);
                                break;
                            }
                        }
                    });
                    itemOps.increment();
                }
                THREADS.computeIfAbsent("item " + Thread.currentThread().getName().replaceAll("[0-9]+", "#"), k -> new LongAdder()).increment();
            } catch (Throwable t) {
                toggleErrors.increment();
                if ("-".equals(firstError)) firstError = t + "@" + Thread.currentThread().getName();
            }
        }
    }

    static final class DriverBE extends BlockEntity {
        long ticks;
        DriverBE(BlockPos pos, BlockState state) { super(DRIVER_BE.get(), pos, state); }

        static void tick(Level level, BlockPos pos, BlockState state, DriverBE be) {
            if (paused || ++be.ticks % period != 0) return;
            Block cube = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(targetBlock));
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
