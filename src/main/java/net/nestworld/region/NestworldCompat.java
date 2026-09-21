package net.nestworld.region;

import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Startup guard against mods whose optimizations are thread-UNSAFE under the
 * core's parallel region ticking.
 *
 * <p>NestworldCore parallelizes entity/block ticking across region threads and
 * already provides thread-safe versions of vanilla's entity-collection
 * structures (e.g. a {@code ConcurrentHashMap}/{@code CopyOnWriteArrayList}
 * backed {@link net.minecraft.util.ClassInstanceMultiMap}). Mods in the
 * "Lithium family" (Canary, radium, lithium) overwrite those same structures
 * with faster but <em>single-thread-only</em> versions (fastutil
 * {@code Reference2ReferenceOpenHashMap}). When their replacement is active it
 * races under parallel ticking and freezes the server (observed:
 * {@code ArrayIndexOutOfBoundsException} in {@code rehash} when a mob crowd runs
 * {@code NearestAttackableTargetGoal} -> {@code getEntitiesOfClass} against the
 * same section from several region threads).
 *
 * <p>This guard detects the <em>active-unsafe</em> state (not merely the mod's
 * presence) and logs the conflicting options without editing the mod's config.
 * Pass {@code -Dnestworld.strictCompat=true} to refuse to run in that state.
 */
public final class NestworldCompat {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/Compat");

    /**
     * Canary/Lithium mixin options that defeat the core's thread-safety by
     * replacing shared per-level structures with single-thread-only ones:
     * <ul>
     *   <li>{@code collections.*} / {@code chunk.entity_class_groups} — overwrite
     *       the thread-safe ClassInstanceMultiMap with a racy fastutil map
     *       (freeze under a mob crowd targeting a player).</li>
     *   <li>{@code ai.poi} — its PoiManager rewrite races the shared
     *       PoiManager DistanceTracker queue under parallel ticking (AIOOBE in
     *       LongLinkedOpenHashSet.removeFirstLong, "Exception ticking world").</li>
     * </ul>
     * Both reproduced and confirmed by stress test: disabling these stops the
     * crash; without the mod the core handles the same load thread-safely.
     */
    private static final List<String> CANARY_UNSAFE_MIXINS = List.of(
            "mixin.collections.entity_by_type=false",
            "mixin.collections.entity_filtering=false",
            "mixin.chunk.entity_class_groups=false",
            "mixin.ai.poi=false");

    private NestworldCompat() {}

    public static void check() {
        if (!isUnsafeEntityCollectionActive()) {
            return; // mod absent, or its conflicting optimization already disabled
        }

        String mod = ModList.get().isLoaded("canary") ? "Canary"
                   : ModList.get().isLoaded("radium") ? "radium"
                   : ModList.get().isLoaded("lithium") ? "lithium"
                   : "a Lithium-family mod";
        LOGGER.error("==================== NestworldCore COMPAT ====================");
        LOGGER.error("Mod '{}' applies THREAD-UNSAFE entity-collection optimizations.", mod);
        LOGGER.error("It overwrites ClassInstanceMultiMap / EntitySection by-type indexes");
        LOGGER.error("with single-thread-only structures that RACE under the core's parallel");
        LOGGER.error("region ticking and FREEZE the server under a mob crowd targeting a");
        LOGGER.error("player. These functions are ALREADY provided thread-safely by the core.");
        LOGGER.error(" -> Review these options in the mod's config before restarting:");
        for (String s : CANARY_UNSAFE_MIXINS) LOGGER.error("      {}", s);
        LOGGER.error("=============================================================");

        if (Boolean.getBoolean("nestworld.strictCompat")) {
            throw new IllegalStateException("NestworldCore strictCompat: refusing to run with "
                    + "thread-unsafe " + mod + " entity-collection optimizations active "
                    + "(NestworldCore did not alter the mod's config).");
        }
    }

    /**
     * Is a Lithium-family mod present with its thread-unsafe entity-collection
     * optimization still enabled? Detected by mod presence plus config rather
     * than by reflecting on the mixin-merged class (whose method signatures
     * reference the mod's own types and can make {@code getDeclaredMethods}
     * throw): if Canary is loaded and its conflicting mixins are not disabled in
     * its config, the unsafe optimization is live.
     */
    private static boolean isUnsafeEntityCollectionActive() {
        boolean lithiumFamily = ModList.get().isLoaded("canary")
                || ModList.get().isLoaded("radium")
                || ModList.get().isLoaded("lithium");
        if (!lithiumFamily) return false;
        // Canary we can verify precisely from its config; if already disabled, safe.
        if (ModList.get().isLoaded("canary") && canaryConflictDisabled()) return false;
        return true;
    }

    private static boolean canaryConflictDisabled() {
        try {
            Path cfg = Path.of("config", "canary.properties");
            return Files.exists(cfg)
                    && Files.readString(cfg).contains("mixin.collections.entity_by_type=false");
        } catch (Throwable t) {
            return false;
        }
    }

}
