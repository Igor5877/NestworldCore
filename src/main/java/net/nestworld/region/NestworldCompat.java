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
 * presence), auto-writes the disable lines into the mod's config, and logs a
 * loud warning. The disable takes effect on the next restart; pass
 * {@code -Dnestworld.strictCompat=true} to refuse to run until then.
 */
public final class NestworldCompat {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/Compat");

    /** Canary/Lithium mixin options that defeat the core's thread-safety. */
    private static final List<String> CANARY_UNSAFE_MIXINS = List.of(
            "mixin.collections.entity_by_type=false",
            "mixin.collections.entity_filtering=false",
            "mixin.chunk.entity_class_groups=false");

    private NestworldCompat() {}

    public static void check() {
        if (!isUnsafeEntityCollectionActive()) {
            return; // mod absent, or its conflicting optimization already disabled
        }

        String mod = ModList.get().isLoaded("canary") ? "Canary"
                   : ModList.get().isLoaded("radium") ? "radium"
                   : ModList.get().isLoaded("lithium") ? "lithium"
                   : "a Lithium-family mod";
        boolean autoFixed = "Canary".equals(mod) && writeCanaryDisable();

        LOGGER.error("==================== NestworldCore COMPAT ====================");
        LOGGER.error("Mod '{}' applies THREAD-UNSAFE entity-collection optimizations.", mod);
        LOGGER.error("It overwrites ClassInstanceMultiMap / EntitySection by-type indexes");
        LOGGER.error("with single-thread-only structures that RACE under the core's parallel");
        LOGGER.error("region ticking and FREEZE the server under a mob crowd targeting a");
        LOGGER.error("player. These functions are ALREADY provided thread-safely by the core.");
        if (autoFixed) {
            LOGGER.error(" -> The core disabled them in config/canary.properties.");
            LOGGER.error(" -> RESTART the server to apply. This run remains at risk until then.");
        } else {
            LOGGER.error(" -> Disable these in the mod's config and restart:");
            for (String s : CANARY_UNSAFE_MIXINS) LOGGER.error("      {}", s);
        }
        LOGGER.error("=============================================================");

        if (Boolean.getBoolean("nestworld.strictCompat")) {
            throw new IllegalStateException("NestworldCore strictCompat: refusing to run with "
                    + "thread-unsafe " + mod + " entity-collection optimizations active "
                    + "(config fixed where possible — restart the server).");
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

    private static boolean writeCanaryDisable() {
        try {
            Path cfg = Path.of("config", "canary.properties");
            String existing = Files.exists(cfg) ? Files.readString(cfg) : "";
            if (existing.contains("mixin.collections.entity_by_type")) {
                return false; // user already configured this line — don't fight them
            }
            StringBuilder sb = new StringBuilder(existing);
            if (!existing.isEmpty() && !existing.endsWith("\n")) sb.append('\n');
            sb.append("\n# Added by NestworldCore: these optimizations are not thread-safe under\n");
            sb.append("# parallel region ticking; the core already provides them thread-safely.\n");
            for (String s : CANARY_UNSAFE_MIXINS) sb.append(s).append('\n');
            Files.createDirectories(cfg.getParent());
            Files.writeString(cfg, sb.toString());
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Could not auto-write config/canary.properties: {}", t.toString());
            return false;
        }
    }
}
