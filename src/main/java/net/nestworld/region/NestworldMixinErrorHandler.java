package net.nestworld.region;

import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Durable, core-side fix for the class of crash documented in project memory
 * as "chunkmap-lambda-ordinal-mixin-fragility" (2026-08-11, zc-server; recurred
 * 2026-08-22, spawn-dev with an identical mod jar): Forgified Fabric API's
 * {@code fabric-lifecycle-events-v1} Mixin targets {@code ChunkMap.scheduleUnload}'s
 * inner lambda by its VANILLA compiler-assigned ordinal ({@code
 * lambda$scheduleUnload$14}). NestWorld's own patches add lambda expressions
 * earlier in {@code ChunkMap.java} than vanilla has, which shifts every later
 * lambda's index (currently {@code $16}, not {@code $14}) -- the Mixin's
 * injection target no longer exists at that ordinal, and since that mod's
 * config has {@code required: true}, Mixin's default behavior is to crash the
 * whole server on world load (see {@code MixinProcessor}'s {@code
 * ErrorAction.ERROR} path).
 *
 * <p>The PREVIOUS fix for this was operational, not structural: manually
 * extract the offending mod jar, patch its {@code .mixins.json} to {@code
 * required: false}, repackage, redeploy -- has to be redone by hand on every
 * server that happens to ship this exact mod, forever, and only after it's
 * already crashed once to reveal the problem. This class replaces that with a
 * genuine core-side fix: registers with Mixin's own sanctioned {@link
 * IMixinErrorHandler} extensibility point (confirmed via live bytecode
 * inspection of {@code MixinProcessor}, not guessed -- {@code
 * getErrorHandlers(phase)} is consulted live at the moment of an apply
 * failure, not cached once at Mixin bootstrap, so registering this early in
 * {@code FMLServiceProvider.initialize()} -- see {@code fmlloader}'s call to
 * {@code Mixins.registerErrorHandlerClass}, well before any game/mod class is
 * loaded -- is comfortably early enough) to downgrade ONLY this specific,
 * already-understood-safe failure from fatal to a warning, automatically, on
 * every server that has this mod installed, with zero jar-editing.
 *
 * <p>Deliberately narrow: matches on the failing mixin's own class name, not
 * a blanket "never crash on any Mixin error" override. Every OTHER Mixin
 * failure (a genuinely broken/incompatible mod, not this specific known-safe
 * ordinal mismatch) still gets Mixin's normal default behavior (usually
 * {@code ERROR}) unchanged -- this must never become a general-purpose crash
 * suppressor.
 *
 * <p>Why downgrading to non-fatal is actually safe here, not just
 * crash-avoidance: {@link NestworldFabricChunkEventsCompat#fireChunkUnload}
 * already fires the exact same {@code ServerChunkEvents.CHUNK_UNLOAD} event
 * this Mixin would have fired, from a direct source-level call at the
 * equivalent point in {@code ChunkMap.scheduleUnload()} -- so no
 * functionality is lost when this one Mixin's injection is skipped, only the
 * (already broken, already redundant) duplicate injection attempt.
 */
public final class NestworldMixinErrorHandler implements IMixinErrorHandler {

    /** Class-name substrings of mixins already known to structurally
     *  conflict with NestWorld's own patches, and already compensated for by
     *  a direct source-level call elsewhere in this project -- see each
     *  entry's own reasoning in this class's javadoc / project memory. */
    private static final String[] KNOWN_SAFE_TO_DOWNGRADE = {
            // fabric-lifecycle-events-v1 (Forgified Fabric API / Sinytra Connector):
            // ChunkMap.scheduleUnload's inner-lambda ordinal shift -- see class javadoc.
            "ThreadedAnvilChunkStorageMixin",
    };

    private static boolean isKnownSafe(IMixinInfo mixin) {
        if (mixin == null) return false;
        String name = mixin.getName();
        if (name == null) return false;
        for (String needle : KNOWN_SAFE_TO_DOWNGRADE) {
            if (name.contains(needle)) return true;
        }
        return false;
    }

    @Override
    public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        if (isKnownSafe(mixin)) {
            log("prepare", mixin, th);
            return ErrorAction.WARN;
        }
        return action;
    }

    @Override
    public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
        if (isKnownSafe(mixin)) {
            log("apply", mixin, th);
            return ErrorAction.WARN;
        }
        return action;
    }

    private static void log(String phase, IMixinInfo mixin, Throwable th) {
        System.out.println("[NestWorld] Known-safe Mixin " + phase + " failure downgraded to non-fatal: "
                + (mixin != null ? mixin.getName() : "?") + " (" + th + ") -- see "
                + "NestworldMixinErrorHandler's javadoc / project memory "
                + "chunkmap-lambda-ordinal-mixin-fragility.md for why this is safe.");
    }
}
