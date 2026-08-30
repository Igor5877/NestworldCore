package net.nestworld.compat;

/**
 * NestWorld: detects mods whose OWN mixins target the exact same vanilla methods/bytecode shapes
 * that our own startup-optimization patches change. Discovered live on ATM9 (420-mod real pack,
 * 2026-08-27): ModernFix ships mixins that {@code @Redirect} a specific INVOKE call site inside
 * {@code GameData$BlockCallbacks.onBake()} and {@code @ModifyVariable}/{@code @Inject} into
 * {@code StateDefinition}'s constructor and {@code StateHolder.populateNeighbours} by local-
 * variable slot / method signature -- our optimizations change those exact shapes, which made
 * ModernFix's {@code @Redirect} (a mandatory injector) fail to find its target and crash the whole
 * server at boot ({@code GameData.<clinit>} failure, before any mod even starts loading). See
 * project memory phase2-modernfix-compat (or similarly named) for the full incident.
 *
 * Where this flag is true, the affected call sites fall back to the ORIGINAL vanilla code shape
 * (byte-for-byte equivalent to unpatched Forge) so ModernFix's own mixins keep applying correctly
 * and its own (separately maintained, already-proven) optimizations take over instead of ours.
 */
public final class NestworldModCompat {
    public static final boolean MODERNFIX_PRESENT = detectModernFix();

    private NestworldModCompat() {
    }

    private static boolean detectModernFix() {
        try {
            Class.forName("org.embeddedt.modernfix.blockstate.BlockStateCacheHandler", false,
                    NestworldModCompat.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
