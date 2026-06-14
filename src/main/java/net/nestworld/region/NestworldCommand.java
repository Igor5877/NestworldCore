package net.nestworld.region;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Admin command for inspecting and driving the region sharding system.
 *
 * <pre>
 *   /nestworld status            — list active regions with TPS and entity counts
 *   /nestworld split &lt;id&gt;        — force-split a region (testing / manual tuning)
 *   /nestworld merge &lt;a&gt; &lt;b&gt;     — force-merge two sibling regions
 *   /nestworld pins              — list entity types pinned to main-thread ticking
 *   /nestworld pin &lt;type&gt;        — pin an entity or block-entity type to main
 *   /nestworld unpin &lt;type&gt;      — resume region-thread ticking for a type
 *   /nestworld pinmod &lt;ns&gt;       — pin every entity/BE from a mod namespace
 *   /nestworld unpinmod &lt;ns&gt;     — unpin a mod namespace
 * </pre>
 */
public final class NestworldCommand {

    private NestworldCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("nestworld")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("borders").executes(ctx -> toggleBorders(ctx.getSource())))
                .then(Commands.literal("split")
                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .executes(ctx -> split(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
                .then(Commands.literal("merge")
                        .then(Commands.argument("a", IntegerArgumentType.integer(0))
                                .then(Commands.argument("b", IntegerArgumentType.integer(0))
                                        .executes(ctx -> merge(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "a"),
                                                IntegerArgumentType.getInteger(ctx, "b"))))))
                .then(Commands.literal("pins").executes(ctx -> listPins(ctx.getSource())))
                .then(Commands.literal("pin")
                        .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> pin(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "type")))))
                .then(Commands.literal("unpin")
                        .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(ctx -> unpin(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "type")))))
                .then(Commands.literal("pinmod")
                        .then(Commands.argument("namespace", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(ctx -> pinMod(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "namespace")))))
                .then(Commands.literal("unpinmod")
                        .then(Commands.argument("namespace", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(ctx -> unpinMod(ctx.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "namespace"))))));

        // /spark — in-game access to the spark standalone agent (see SparkBridge).
        // Only when the real spark mod is absent (dev runtime can't load it);
        // in production the mod registers its own /spark and the bridge would
        // shadow it.
        if (!net.minecraftforge.fml.ModList.get().isLoaded("spark")) {
            dispatcher.register(Commands.literal("spark")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> SparkBridge.run(ctx.getSource(), ""))
                    .then(Commands.argument("args", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                            .executes(ctx -> SparkBridge.run(ctx.getSource(),
                                    com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "args")))));
        }
    }

    private static int toggleBorders(CommandSourceStack src) {
        NestworldRegionSystem.showBorders = !NestworldRegionSystem.showBorders;
        boolean on = NestworldRegionSystem.showBorders;
        src.sendSuccess(() -> Component.literal(
                on ? "Region borders: VISIBLE (END_ROD particles, range 96)"
                   : "Region borders: hidden"), true);
        return on ? 1 : 0;
    }

    private static int status(CommandSourceStack src) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldRegionSystem sys = NestworldRegionSystem.get();
        var regions = sys.getTree().getActiveRegions();
        // Server-wide MSPT so the per-region costs can be read against the
        // actual tick health (region cost alone says nothing about redstone
        // and other main-thread work).
        double serverMspt = src.getServer().getAverageTickTime();
        src.sendSuccess(() -> Component.literal(String.format(
                "NestWorld: %d active region(s), server %.1f ms/tick (%.1f TPS)",
                regions.size(), serverMspt, Math.min(20.0, 1000.0 / Math.max(serverMspt, 0.001)))), false);
        BlockTickHeat heat = sys.getBlockTickHeat();
        for (WorldRegion r : regions) {
            RegionThread thread = r.owningThread;
            int deferred = thread != null ? thread.getLastDeferredCount() : 0;
            int workDeferred = thread != null ? thread.getLastWorkDeferred() : 0;
            double regionHeat = heat.totalInRegion(r);
            src.sendSuccess(() -> Component.literal(String.format(
                    "  #%d chunks(%d,%d)-(%d,%d) cost=%.1fms entities=%d%s%s%s",
                    r.getId(), r.getMinChunkX(), r.getMinChunkZ(),
                    r.getMaxChunkX(), r.getMaxChunkZ(),
                    r.getAvgTickMs(), r.getOwnedEntityIds().size(),
                    deferred > 0 ? " deferred=" + deferred : "",
                    workDeferred > 0 ? " workDeferred=" + workDeferred : "",
                    regionHeat >= 1.0
                            ? String.format(" heat=%.0f[%s]", regionHeat, heat.hotspotSummary(r, 3))
                            : "")), false);
        }
        return regions.size();
    }

    private static int split(CommandSourceStack src, int id) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldRegionSystem sys = NestworldRegionSystem.get();
        WorldRegion region = findRegion(sys, id);
        if (region == null) {
            src.sendFailure(Component.literal("No active region with id " + id));
            return 0;
        }
        WorldRegion[] children = sys.getSplitManager().doSplit(region);
        if (children == null) {
            src.sendFailure(Component.literal("Region " + id + " cannot be split (already 1x1?)"));
            return 0;
        }
        // Manual splits are pinned so the idle-TPS hysteresis doesn't
        // immediately merge them back while someone is inspecting them.
        children[0].pinned = true;
        children[1].pinned = true;
        src.sendSuccess(() -> Component.literal(
                "Split #" + id + " -> #" + children[0].getId() + " + #" + children[1].getId()
                + " (pinned — won't auto-merge; use /nestworld merge)"), true);
        return 1;
    }

    private static int merge(CommandSourceStack src, int idA, int idB) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldRegionSystem sys = NestworldRegionSystem.get();
        WorldRegion a = findRegion(sys, idA);
        WorldRegion b = findRegion(sys, idB);
        if (a == null || b == null) {
            src.sendFailure(Component.literal("Region not found: " + (a == null ? idA : idB)));
            return 0;
        }
        WorldRegion merged = sys.getSplitManager().doMerge(a, b);
        if (merged == null) {
            src.sendFailure(Component.literal(
                    "Regions " + idA + " and " + idB + " are not siblings in the BSP tree"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "Merged #" + idA + " + #" + idB + " -> #" + merged.getId()), true);
        return 1;
    }

    private static int listPins(CommandSourceStack src) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldPins p = NestworldRegionSystem.get().getPins();
        var entityPins = p.list();
        var bePins = p.beList();
        var modPinsEarly = p.modList();
        if (entityPins.isEmpty() && bePins.isEmpty() && modPinsEarly.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "No pins (all entities and block entities tick on region threads)."), false);
            return 0;
        }
        if (!entityPins.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    entityPins.size() + " pinned entity type(s) — tick on main thread:"), false);
            for (String id : entityPins) src.sendSuccess(() -> Component.literal("  " + id), false);
        }
        if (!bePins.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    bePins.size() + " pinned block-entity type(s) — tick on main thread:"), false);
            for (String id : bePins) src.sendSuccess(() -> Component.literal("  " + id), false);
        }
        if (!modPinsEarly.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    modPinsEarly.size() + " pinned mod(s) — all their entities/BEs tick on main:"), false);
            for (String ns : modPinsEarly) src.sendSuccess(() -> Component.literal("  " + ns), false);
        }
        return entityPins.size() + bePins.size() + modPinsEarly.size();
    }

    private static int pinMod(CommandSourceStack src, String ns) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        NestworldRegionSystem.get().getPins().pinMod(ns.trim());
        src.sendSuccess(() -> Component.literal(
                "Pinned mod '" + ns.trim() + "' — all its entities and block entities tick "
                + "on main (takes effect next tick)."), true);
        return 1;
    }

    private static int unpinMod(CommandSourceStack src, String ns) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        boolean removed = NestworldRegionSystem.get().getPins().unpinMod(ns.trim());
        if (!removed) {
            src.sendFailure(Component.literal("Mod '" + ns.trim() + "' was not pinned"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "Unpinned mod '" + ns.trim() + "' (resumes region-thread ticking)."), true);
        return 1;
    }

    private static int pin(CommandSourceStack src, String type) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        String err = NestworldRegionSystem.get().getPins().pin(type.trim());
        if (err != null) {
            src.sendFailure(Component.literal(err));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "Pinned " + type.trim() + " to main-thread ticking (takes effect next tick)."), true);
        return 1;
    }

    private static int unpin(CommandSourceStack src, String type) {
        if (!NestworldRegionSystem.isInitialised()) {
            src.sendFailure(Component.literal("NestWorld region system is not active"));
            return 0;
        }
        boolean removed = NestworldRegionSystem.get().getPins().unpin(type.trim());
        if (!removed) {
            src.sendFailure(Component.literal(type.trim() + " was not pinned"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "Unpinned " + type.trim() + " (resumes region-thread ticking next boundary scan)."), true);
        return 1;
    }

    private static WorldRegion findRegion(NestworldRegionSystem sys, int id) {
        for (WorldRegion r : sys.getTree().getActiveRegions()) {
            if (r.getId() == id) return r;
        }
        return null;
    }
}
