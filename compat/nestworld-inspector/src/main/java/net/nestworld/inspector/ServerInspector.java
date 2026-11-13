package net.nestworld.inspector;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server half: {@code /nwinspect}, the watcher set and the bounded, read-only collection. Everything runs on the
 * server thread (command execution and ServerTickEvent). Zero work when nobody watches: the tick handler returns
 * after one {@code isEmpty()} check.
 */
public final class ServerInspector {
    private static final Logger LOG = LogUtils.getLogger();
    public static final int DEFAULT_RADIUS = 4, MAX_RADIUS = 8, PERIOD_TICKS = 20, PERMISSION = 2;

    /** Watching operators → radius. */
    private static final Map<UUID, Integer> WATCHERS = new ConcurrentHashMap<>();
    private static int tickCounter;
    private static long collectErrors;

    private ServerInspector() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent e) {
        register(e.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("nwinspect").requires(s -> s.hasPermission(PERMISSION))
                .then(Commands.literal("on")
                        .executes(c -> on(c, DEFAULT_RADIUS))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                                .executes(c -> on(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("off").executes(ServerInspector::off))
                .then(Commands.literal("radius")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                                .executes(c -> radius(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("status").executes(ServerInspector::status))
                .then(Commands.literal("probe")
                        .then(Commands.argument("cx", IntegerArgumentType.integer())
                                .then(Commands.argument("cz", IntegerArgumentType.integer())
                                        .executes(c -> probe(c, DEFAULT_RADIUS))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                                                .executes(c -> probe(c, IntegerArgumentType.getInteger(c, "radius"))))))));
    }

    private static int on(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        if (!CoreBridge.available()) {
            src.sendFailure(Component.literal("[nwinspect] no NestWorld core on this server; nothing to show"));
            return 0;
        }
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("[nwinspect] 'on' needs a player; from console use /nwinspect probe <cx> <cz> [radius]"));
            return 0;
        }
        if (!InspectorNetwork.hasMod(p)) {
            src.sendFailure(Component.literal("[nwinspect] your client does not have nestworld_inspector installed; use /nwinspect probe"));
            return 0;
        }
        WATCHERS.put(p.getUUID(), radius);
        src.sendSuccess(() -> Component.literal("[nwinspect] on, radius " + radius + " chunks (toggle rendering with the key binding)"), false);
        return 1;
    }

    private static int off(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p != null) {
            WATCHERS.remove(p.getUUID());
            InspectorNetwork.sendClear(p);
        }
        c.getSource().sendSuccess(() -> Component.literal("[nwinspect] off"), false);
        return 1;
    }

    private static int radius(CommandContext<CommandSourceStack> c, int radius) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null || !WATCHERS.containsKey(p.getUUID())) {
            c.getSource().sendFailure(Component.literal("[nwinspect] not on; use /nwinspect on " + radius));
            return 0;
        }
        WATCHERS.put(p.getUUID(), radius);
        c.getSource().sendSuccess(() -> Component.literal("[nwinspect] radius " + radius), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        String core = CoreBridge.available() ? (CoreBridge.system() != null ? "present+initialised" : "present, not initialised")
                : "absent";
        String msg = "[nwinspect] core " + core + ", groups " + (CoreBridge.groupsAvailable() ? "yes" : "no")
                + ", position pins " + (CoreBridge.positionPinsAvailable() ? "yes" : "no")
                + ", watchers " + WATCHERS.size() + ", collect errors " + collectErrors;
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /** Console-friendly text summary of the same snapshot (source's level, chunk coords). */
    private static int probe(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        if (!CoreBridge.available()) {
            src.sendFailure(Component.literal("[nwinspect] no NestWorld core on this server"));
            return 0;
        }
        int cx = IntegerArgumentType.getInteger(c, "cx"), cz = IntegerArgumentType.getInteger(c, "cz");
        long t0 = System.nanoTime();
        Snapshot s = collect(src.getLevel(), cx, cz, radius);
        long us = (System.nanoTime() - t0) / 1000;
        if (s == null) {
            src.sendFailure(Component.literal("[nwinspect] Core not initialised"));
            return 0;
        }
        Map<String, Integer> chunkCount = new TreeMap<>();
        for (Snapshot.Chunk ch : s.chunks) {
            String k = (ch.regionId() == Snapshot.NONE ? "none" : "REGION#" + ch.regionId()) + (ch.band() ? "/band" : "")
                    + (ch.loaded() ? "" : "/unloaded");
            chunkCount.merge(k, 1, Integer::sum);
        }
        Map<String, Integer> beCount = new TreeMap<>();
        int grouped = 0;
        for (Snapshot.Be b : s.blockEntities) {
            beCount.merge((b.domain() == Snapshot.NONE ? "MAIN" : "REGION#" + b.domain()) + " <- " + Snapshot.reasonName(b.reason()),
                    1, Integer::sum);
            if (b.groupSize() > 0) grouped++;
        }
        StringBuilder sb = new StringBuilder("[nwinspect] ").append(s.dimension).append(" chunk ").append(cx).append(',').append(cz)
                .append(" r=").append(radius).append(" managed=").append(s.managed)
                .append(String.format(" mspt=%.2f", s.mspt)).append(" collect=").append(us).append("us")
                .append("\n regions: ");
        for (Snapshot.Region r : s.regions) {
            sb.append(String.format("#%d %.2fms [%d..%d,%d..%d] ", r.id(), r.avgTickMs(), r.minCX(), r.maxCX(), r.minCZ(), r.maxCZ()));
        }
        sb.append("\n chunks: ").append(chunkCount)
                .append("\n block entities: ").append(s.blockEntities.size()).append(s.truncated ? " (truncated)" : "")
                .append(", grouped ").append(grouped).append(" ").append(beCount);
        io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer();
        try {
            net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(raw);
            s.encode(buf);
            int bytes = buf.readableBytes();
            Snapshot back = Snapshot.decode(buf);
            boolean ok = back.blockEntities.equals(s.blockEntities) && back.chunks.equals(s.chunks) && back.regions.equals(s.regions)
                    && buf.readableBytes() == 0;
            sb.append("\n packet: ").append(bytes).append(" bytes, roundtrip ").append(ok ? "ok" : "MISMATCH");
        } finally {
            raw.release();
        }
        String out = sb.toString();
        src.sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || WATCHERS.isEmpty()) return;
        if (++tickCounter < PERIOD_TICKS) return;
        tickCounter = 0;
        MinecraftServer server = e.getServer();
        for (Iterator<Map.Entry<UUID, Integer>> it = WATCHERS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> w = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(w.getKey());
            if (p == null || !p.hasPermissions(PERMISSION) || !InspectorNetwork.hasMod(p)) {
                it.remove();
                continue;
            }
            try {
                Snapshot s = collect(p.serverLevel(), p.chunkPosition().x, p.chunkPosition().z, w.getValue());
                if (s != null) InspectorNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new InspectorNetwork.SnapshotMsg(s));
            } catch (Throwable t) {
                if (collectErrors++ < 5) LOG.warn("[nestworld_inspector] collection failed (read-only, ignored)", t);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        WATCHERS.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent e) {
        WATCHERS.clear();
    }

    /** Read-only snapshot around chunk (cx,cz). Null if Core is absent/uninitialised. Server thread. */
    static Snapshot collect(ServerLevel level, int ccx, int ccz, int radius) {
        Object sys = CoreBridge.system();
        if (sys == null) return null;
        radius = Math.max(1, Math.min(MAX_RADIUS, radius));
        Snapshot s = new Snapshot();
        s.dimension = level.dimension().location().toString();
        s.centerCX = ccx;
        s.centerCZ = ccz;
        s.radius = radius;
        s.mspt = level.getServer().getAverageTickTime();
        s.groupsSupported = CoreBridge.groupsAvailable();
        Object dr = CoreBridge.dimensionRegion(sys, level);
        Object grid = dr == null ? null : CoreBridge.grid(dr);
        s.managed = grid != null;
        Object pins = CoreBridge.pins(sys);
        Set<String> beIds = pins == null ? Set.of() : new HashSet<>(CoreBridge.beList(pins));
        Set<String> mods = pins == null ? Set.of() : new HashSet<>(CoreBridge.modList(pins));

        int minCX = ccx - radius, maxCX = ccx + radius, minCZ = ccz - radius, maxCZ = ccz + radius;
        if (grid != null) {
            for (Object r : new ArrayList<>(CoreBridge.allRegions(grid))) {
                int[] b = CoreBridge.bounds(r);
                if (b == null || b[2] < minCX || b[0] > maxCX || b[3] < minCZ || b[1] > maxCZ) continue;
                s.regions.add(new Snapshot.Region(CoreBridge.regionId(r), (float) CoreBridge.avgTickMs(r), b[0], b[1], b[2], b[3]));
            }
        }
        Set<String> typesSeen = new HashSet<>();
        Map<String, Integer> typeFlags = new java.util.HashMap<>(); // per-snapshot cache of type pin / cascade-safe
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                Object region = grid == null ? null : CoreBridge.regionForChunk(grid, cx, cz);
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                byte flags = 0;
                if (region != null && CoreBridge.inBorderBand(region, cx, cz)) flags |= 1;
                if (chunk != null) flags |= 2;
                s.chunks.add(new Snapshot.Chunk(region == null ? Snapshot.NONE : CoreBridge.regionId(region), flags));
                if (chunk == null || s.truncated) continue;
                List<BlockEntity> bes;
                try {
                    Map<BlockPos, BlockEntity> m = chunk.getBlockEntities();
                    synchronized (m) { bes = new ArrayList<>(m.values()); }
                } catch (RuntimeException ex) {
                    continue; // concurrent mutation by a region thread: skip this chunk this round
                }
                for (BlockEntity be : bes) {
                    if (be == null || be.isRemoved()) continue;
                    if (s.blockEntities.size() >= Snapshot.MAX_BLOCK_ENTITIES) { s.truncated = true; break; }
                    ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
                    String type = key == null ? "?" : key.toString();
                    if (!typesSeen.contains(type)) {
                        if (typesSeen.size() >= Snapshot.MAX_TYPES) { s.truncated = true; continue; }
                        typesSeen.add(type);
                    }
                    BlockPos pos = be.getBlockPos();
                    s.blockEntities.add(describe(level, pos, type, region, grid, pins, beIds, mods, typeFlags));
                }
            }
        }
        return s;
    }

    private static Snapshot.Be describe(ServerLevel level, BlockPos pos, String type, Object chunkRegion, Object grid,
                                        Object pins, Set<String> beIds, Set<String> mods,
                                        Map<String, Integer> typeFlags) {
        int group = CoreBridge.groupSize(level, pos);
        if (grid == null) return new Snapshot.Be(pos.asLong(), type, Snapshot.NONE, Snapshot.R_UNMANAGED, group);
        int domain;
        try {
            Object r = CoreBridge.resolveDomainRegion(level, pos, type);
            domain = r == null ? Snapshot.NONE : CoreBridge.regionId(r);
        } catch (Throwable t) {
            return new Snapshot.Be(pos.asLong(), type, Snapshot.NONE, Snapshot.R_ERROR, group);
        }
        // Derive the reason with Core's own precedence (NestworldDimensionRegion.blockEntityPolicy/blockEntityRegionFor),
        // then cross-check it against Core's authoritative answer above.
        byte reason;
        int expected;
        int gridId = chunkRegion == null ? Snapshot.NONE : CoreBridge.regionId(chunkRegion);
        int tp = typeFlags.computeIfAbsent(type, t -> (pins != null && CoreBridge.isBePinned(pins, t) ? 1 : 0)
                | (pins != null && CoreBridge.isCascadeSafe(pins, t) ? 2 : 0));
        boolean typePinned = (tp & 1) != 0;
        if (typePinned) {
            reason = beIds.contains(type) ? Snapshot.R_TYPE_PIN : mods.contains(namespace(type)) ? Snapshot.R_MOD_PIN : Snapshot.R_TYPE_PIN;
            expected = Snapshot.NONE;
        } else if (pins != null && CoreBridge.isPositionPinned(pins, level.dimension(), pos.asLong())) {
            reason = Snapshot.R_POSITION_PIN;
            expected = Snapshot.NONE;
        } else if ((tp & 2) != 0) {
            reason = Snapshot.R_CASCADE_SAFE;
            expected = gridId;
        } else if (chunkRegion == null) {
            reason = Snapshot.R_NO_REGION;
            expected = Snapshot.NONE;
        } else if (CoreBridge.inBorderBand(chunkRegion, pos.getX() >> 4, pos.getZ() >> 4)) {
            reason = Snapshot.R_BORDER_BAND;
            expected = Snapshot.NONE;
        } else {
            reason = Snapshot.R_INTERIOR;
            expected = gridId;
        }
        if (expected != domain) reason = Snapshot.R_UNEXPLAINED;
        return new Snapshot.Be(pos.asLong(), type, domain, reason, group);
    }

    private static String namespace(String type) {
        int c = type.indexOf(':');
        return c < 0 ? "minecraft" : type.substring(0, c);
    }
}
