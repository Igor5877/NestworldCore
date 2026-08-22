package net.nestworld.region;

/**
 * P0.5 (docs/P0_3_AUTONOMOUS_BASELINE_SPEC.md follow-up ТЗ): live-settable override for
 * {@link NestworldTuning#CHUNK_GEN_BUDGET}, so a chunkGenBudget=2/4/8 sweep can run on ONE warm
 * JVM without a restart between data points -- same rationale as {@code ChunkMap
 * .nestworldSetGenConcurrency}'s existing live concurrency toggle (a restart-per-data-point sweep
 * was found to introduce a real JIT-compilation-storm confound, see project memory
 * layer13-concurrency-sweep-partial-and-jit-storm). {@code -1} means "use
 * NestworldTuning.CHUNK_GEN_BUDGET's boot-time value" (the default, no behavior change unless
 * explicitly overridden via {@code /nestworld setgenbudget}).
 */
public final class NestworldLiveTuning {
    private NestworldLiveTuning() {}

    public static volatile int chunkGenBudgetOverride = -1;

    /** Effective Tier2 (bulk) per-tick promotion budget right now. */
    public static int effectiveChunkGenBudget() {
        return chunkGenBudgetOverride >= 0 ? chunkGenBudgetOverride : NestworldTuning.CHUNK_GEN_BUDGET;
    }

    /**
     * Live-settable multiplier for vanilla's "moved too quickly" movement-speed check
     * (see {@code ServerGamePacketListenerImpl.handleMovePlayer}'s patched {@code f2}
     * computation). {@code 1.0} = unchanged vanilla thresholds (100 blocks²/tick normal,
     * 300 blocks²/tick while fall-flying). Raised via {@code /nestworld setmovethreshold}
     * to tolerate mod-added fast flight (jetpacks, rocket boots) that vanilla's elytra-only
     * {@code disableElytraMovementCheck} gamerule doesn't cover (it only skips the check
     * when {@code isFallFlying()} is true, i.e. real elytra gliding — not jetpack-style
     * custom flight, which stays subject to the same 100 blocks²/tick vanilla ever allowed
     * for grounded/swimming movement and rubber-bands the player back when exceeded).
     * {@code -1} means "use NestworldTuning.MOVE_TOO_QUICKLY_MULTIPLIER's boot-time value
     * from server.properties" (the default, no behavior change unless overridden).
     */
    public static volatile double moveTooQuicklyMultiplierOverride = -1;

    /** Effective moved-too-quickly threshold multiplier right now. */
    public static double effectiveMoveTooQuicklyMultiplier() {
        return moveTooQuicklyMultiplierOverride >= 0
                ? moveTooQuicklyMultiplierOverride
                : NestworldTuning.MOVE_TOO_QUICKLY_MULTIPLIER;
    }

    /**
     * Live-settable cap on the movement-packet-burst allowance — see
     * {@link NestworldTuning#MAX_MOVEMENT_PACKETS_PER_TICK}'s javadoc. {@code -1} means "use
     * the boot-time server.properties value" (the default).
     */
    public static volatile int maxMovementPacketsPerTickOverride = -1;

    /** Effective movement-packet-burst cap right now. */
    public static int effectiveMaxMovementPacketsPerTick() {
        return maxMovementPacketsPerTickOverride >= 0
                ? maxMovementPacketsPerTickOverride
                : NestworldTuning.MAX_MOVEMENT_PACKETS_PER_TICK;
    }

    /**
     * Live-settable override for the chunk-promotion pump-budget deadline clamp — see
     * {@link NestworldTuning#CHUNK_PROMOTION_PUMP_BUDGET_MS}'s javadoc. {@code -1} means
     * "use the boot-time server.properties/system-property value" (the default).
     */
    public static volatile long chunkPromotionPumpBudgetMsOverride = -1;

    /** Effective chunk-promotion pump-budget deadline (ms) right now. */
    public static long effectiveChunkPromotionPumpBudgetMs() {
        return chunkPromotionPumpBudgetMsOverride >= 0
                ? chunkPromotionPumpBudgetMsOverride
                : NestworldTuning.CHUNK_PROMOTION_PUMP_BUDGET_MS;
    }

    /**
     * Live-settable override for vanilla's "floating too long" anti-flyhack kick — see
     * {@link NestworldTuning#FLOATING_KICK_ENABLED}'s javadoc. Tri-state: {@code 0} =
     * "use the boot-time server.properties value" (the default), {@code 1} = force
     * enabled, {@code -1} = force disabled — a plain {@code volatile boolean} can't
     * represent "unset", hence this int encoding (same idiom as this class's other
     * {@code -1 = unset} overrides, just shifted since 0/false is a valid real value here).
     */
    public static volatile int floatingKickEnabledOverride = 0;

    /** Effective floating-kick-enabled state right now. */
    public static boolean effectiveFloatingKickEnabled() {
        if (floatingKickEnabledOverride > 0) return true;
        if (floatingKickEnabledOverride < 0) return false;
        return NestworldTuning.FLOATING_KICK_ENABLED;
    }
}
