package perf1b;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.nestworld.modapi.entity.NestWorldEntities;

/**
 * PERF-1B benchmark consumer (docs/contracts/PERF-1B-spec.md): an ORDINARY Forge mod. It uses
 * Minecraft, Forge and the public NestWorld Mod API only -- no Core types, no reflection, no
 * Module SPI. The same jar runs on stock Forge (B0 / B1) and on NestWorld (B2 / B3).
 *
 * <p>Producer: block {@code perf1b:producer}, a ticking block entity that every tick issues K
 * velocity updates on its bound targets (round robin), each with a strictly increasing value
 * per target. On NestWorld it ticks on its region's thread, on Forge on the server thread: the
 * same producers, so only the execution model differs.
 *
 * <p>Targets are {@code minecraft:marker} entities tagged {@code perf1b_c<cell>}. A marker has no
 * physics, so a written deltaMovement stays as written and the final state can be checked
 * against the latest intent (LATEST_INTENT_WINS).
 *
 * <p>Configuration (system properties, recorded by the harness):
 * <ul>
 *   <li>{@code perf1b.mode}: API (default; {@code NestWorldEntities.setVelocity}) or RAW (B0:
 *       {@code Entity.setDeltaMovement}; stock Forge only, where the producer and the target
 *       share the server thread)</li>
 *   <li>{@code perf1b.k}: operations per producer per tick (default 64)</li>
 *   <li>{@code perf1b.statsFile}: appends "epochMillis,submitted" once per second</li>
 * </ul>
 * Commands (main thread): {@code /perf1b bind self|partner}, {@code pause}, {@code resume},
 * {@code check}. The partner of cell k is cell k^1, which is another region on NestWorld.
 */
@Mod(Perf1bMod.MODID)
public final class Perf1bMod {
    public static final String MODID = "perf1b";
    static final boolean RAW = "RAW".equalsIgnoreCase(System.getProperty("perf1b.mode", "API"));
    static final int K = Integer.getInteger("perf1b.k", 64);
    static final LongAdder SUBMITTED = new LongAdder();
    static volatile boolean paused = true;
    static final Set<ProducerBlockEntity> LIVE = ConcurrentHashMap.newKeySet();

    static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);
    static final RegistryObject<Block> PRODUCER = BLOCKS.register("producer",
            () -> new ProducerBlock(BlockBehaviour.Properties.of().strength(-1.0F, 3600000.0F).noLootTable()));
    @SuppressWarnings("ConstantConditions")
    static final RegistryObject<BlockEntityType<ProducerBlockEntity>> PRODUCER_BE = BLOCK_ENTITIES.register("producer",
            () -> BlockEntityType.Builder.of(ProducerBlockEntity::new, PRODUCER.get()).build(null));

    public Perf1bMod() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MinecraftForge.EVENT_BUS.register(this);
        String file = System.getProperty("perf1b.statsFile");
        if (file != null) {
            Thread t = new Thread(() -> writeLoop(Path.of(file)), "perf1b-stats-writer");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void writeLoop(Path out) {
        while (true) {
            try {
                Thread.sleep(1000L);
                Files.writeString(out, System.currentTimeMillis() + "," + SUBMITTED.sum() + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (InterruptedException e) {
                return;
            } catch (IOException ignored) {
                // gaps are visible to the harness
            }
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("perf1b").requires(s -> s.hasPermission(2))
                .then(Commands.literal("bind").then(Commands.argument("target", StringArgumentType.word())
                        .executes(c -> bind(c.getSource(), StringArgumentType.getString(c, "target")))))
                .then(Commands.literal("pause").executes(c -> { paused = true; return reply(c.getSource(), "paused"); }))
                .then(Commands.literal("resume").executes(c -> { paused = false; return reply(c.getSource(), "resumed"); }))
                .then(Commands.literal("check").executes(c -> check(c.getSource()))));
    }

    private static int reply(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal("perf1b " + msg), false);
        return 1;
    }

    /** Binds every live producer to the markers of its own cell (self) or of cell^1 (partner). */
    private static int bind(CommandSourceStack src, String target) {
        boolean partner = "partner".equals(target);
        ServerLevel level = src.getServer().overworld();
        List<Entity> all = new ArrayList<>();
        level.getAllEntities().forEach(all::add);
        int producers = 0, bound = 0;
        for (ProducerBlockEntity be : LIVE) {
            int cell = partner ? be.cell ^ 1 : be.cell;
            String tag = "perf1b_c" + cell;
            List<Entity> mine = new ArrayList<>();
            for (Entity e : all) if (e.getTags().contains(tag)) mine.add(e);
            mine.sort(Comparator.comparing(Entity::getUUID));
            be.bind(mine.toArray(new Entity[0]));
            producers++;
            bound += mine.size();
        }
        int p = producers, b = bound;
        src.sendSuccess(() -> Component.literal("perf1b bound producers=" + p + " targets=" + b
                + " mode=" + (RAW ? "RAW" : "API") + " k=" + K + " target=" + target), false);
        return producers;
    }

    /** LATEST_INTENT_WINS check after quiescence: each target's velocity equals its last intent. */
    private static int check(CommandSourceStack src) {
        long match = 0, mismatch = 0, removed = 0, intents = 0;
        for (ProducerBlockEntity be : LIVE) {
            if (be.targets == null) continue;
            for (int i = 0; i < be.targets.length; i++) {
                Entity e = be.targets[i];
                intents += be.last[i];
                if (e.isRemoved()) removed++;
                else if (be.last[i] == 0 || e.getDeltaMovement().x == (double) be.last[i]) match++;
                else mismatch++;
            }
        }
        long m = match, x = mismatch, r = removed, n = intents, s = SUBMITTED.sum();
        src.sendSuccess(() -> Component.literal("perf1b check paused=" + paused + " match=" + m
                + " mismatch=" + x + " removed=" + r + " intents=" + n + " submitted=" + s), false);
        return (int) x;
    }

    static final class ProducerBlock extends BaseEntityBlock {
        ProducerBlock(BlockBehaviour.Properties props) { super(props); }

        @Override
        public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ProducerBlockEntity(pos, state); }

        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide ? null : createTickerHelper(type, PRODUCER_BE.get(), ProducerBlockEntity::serverTick);
        }
    }

    static final class ProducerBlockEntity extends BlockEntity {
        int cell;
        volatile Entity[] targets;
        long[] last;
        int cursor;

        ProducerBlockEntity(BlockPos pos, BlockState state) { super(PRODUCER_BE.get(), pos, state); }

        void bind(Entity[] t) {
            last = new long[t.length];
            cursor = 0;
            targets = t;
        }

        @Override
        public void onLoad() { super.onLoad(); if (level != null && !level.isClientSide) LIVE.add(this); }

        @Override
        public void setRemoved() { super.setRemoved(); LIVE.remove(this); }

        @Override
        public void load(CompoundTag tag) { super.load(tag); cell = tag.getInt("cell"); }

        @Override
        protected void saveAdditional(CompoundTag tag) { super.saveAdditional(tag); tag.putInt("cell", cell); }

        static void serverTick(Level level, BlockPos pos, BlockState state, ProducerBlockEntity be) {
            Entity[] t = be.targets;
            if (paused || t == null || t.length == 0) return;
            for (int i = 0; i < K; i++) {
                int idx = be.cursor;
                be.cursor = idx + 1 == t.length ? 0 : idx + 1;
                long v = ++be.last[idx];
                Vec3 velocity = new Vec3((double) v, 0.0D, 0.0D);
                if (RAW) t[idx].setDeltaMovement(velocity);
                else NestWorldEntities.setVelocity(t[idx], velocity);
                SUBMITTED.increment();
            }
        }
    }
}
