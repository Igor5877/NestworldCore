package net.nestworld.region;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2026-08-30, Mod Compatibility / Execution Isolation Layer, Phase 1 -- per the user's
 * explicit direction after the FTB Quests {@code DeferredInventoryDetection} race (proven via
 * {@link ModCallbackAttribution}, see project memory
 * ftbchunks-repro-ladder-ftbquests-race-2026-08-30): vanilla Minecraft's mod-callback API
 * (ContainerListener etc.) assumes every callback runs on a single server thread. NestworldCore's
 * player-region-execution genuinely violates that assumption for ANY third-party mod's callback
 * reachable from a region-executed player action. The fix is NOT a blacklist of one mod -- it's
 * a general classifier that decides, per (callback site, code owner), whether a callback is
 * known-safe to run inline on whatever thread called it, or must be routed to the main thread.
 *
 * <p><b>Deliberately conservative default</b>: only code from our own trusted packages
 * ({@code net.nestworld}, {@code net.minecraft}, {@code net.minecraftforge}, {@code com.mojang})
 * is classified {@link Classification#REGION_SAFE} by default. ANY third-party mod callback is
 * {@link Classification#MAIN_THREAD_ONLY} by default -- unproven mod code is never assumed safe
 * for concurrent execution. A mod can only become {@code REGION_SAFE} via an explicit, deliberate
 * allowlist entry (none shipped yet in Phase 1 -- there is no data yet proving ANY mod callback
 * safe under concurrent access). This is the opposite of a blacklist: the default already covers
 * every mod we haven't looked at, including ones not yet discovered to have a problem.
 */
public final class ModCallbackClassifier {
    public static final boolean ENABLED = Boolean.getBoolean("nestworld.modCallbackIsolation");

    public enum Classification { REGION_SAFE, MAIN_THREAD_ONLY }

    /** key = site + "|" + fully-qualified package prefix. Empty in Phase 1 -- no mod callback
     * has been proven region-safe yet; entries here are exceptions that OVERRIDE the
     * conservative default down to REGION_SAFE once deliberately vetted. */
    private static final Map<String, Classification> OVERRIDES = new ConcurrentHashMap<>();

    private ModCallbackClassifier() {}

    public static Classification classify(String site, Object listener) {
        if (listener == null) return Classification.REGION_SAFE;
        String pkg = listener.getClass().getPackageName();
        if (pkg.startsWith("net.nestworld.") || pkg.startsWith("net.minecraft.")
                || pkg.startsWith("net.minecraftforge.") || pkg.startsWith("com.mojang.")) {
            return Classification.REGION_SAFE;
        }
        Classification override = OVERRIDES.get(site + "|" + pkg);
        if (override != null) return override;
        // conservative default -- see class javadoc
        return Classification.MAIN_THREAD_ONLY;
    }

    /** Deliberate allowlist entry -- not used by any code path yet in Phase 1, exposed for
     * future vetting workflow (e.g. an RCON command that promotes a package to REGION_SAFE
     * after a soak period shows zero exceptions/races for it). */
    public static void markRegionSafe(String site, String packagePrefix) {
        OVERRIDES.put(site + "|" + packagePrefix, Classification.REGION_SAFE);
    }
}
