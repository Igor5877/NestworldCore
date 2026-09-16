package net.nestworld.racedetect;

import java.util.Locale;

/**
 * Live-adjustable mode for the race detector, following {@code NestworldLiveTuning}'s
 * sibling-class-for-a-cohesive-feature-area precedent rather than bolting an enum tunable onto
 * the already-1492-line {@code NestworldTuning}. This is the first enum-valued tunable anywhere
 * in NestworldCore -- every existing tunable is a boolean/int/long system property.
 *
 * Stage A (the only stage implemented so far) only ever cares whether {@link #MODE} is
 * {@link Mode#OFF} or not -- {@link Mode#DISCOVERY}, {@link Mode#WATCH}, {@link Mode#DEEP}, and
 * {@link Mode#CONFIRMED} all currently trigger the exact same static-scan behavior, since the
 * runtime-instrumentation stages those higher modes are meant to unlock don't exist yet. Every
 * user-facing surface that reports the mode (see NestworldCommand's {@code race mode} handler)
 * must say so explicitly rather than implying more capability than is implemented.
 */
public final class RaceDetectTuning {
    private RaceDetectTuning() {
    }

    public enum Mode {
        OFF, DISCOVERY, WATCH, DEEP, CONFIRMED
    }

    /**
     * Default OFF, not DISCOVERY: this gates a brand-new background workload that opens and
     * ASM-scans every loaded mod jar (potentially 180+ jars on a real modpack) plus NestworldCore's
     * own running jar. Opt-in via {@code -Dnestworld.raceDetectMode=discovery} or the
     * {@code /nestworld race mode} RCON command is the safer default until this stage has proven
     * itself clean against a real modpack at least once -- a deliberate, documented judgment call,
     * not something dictated by any existing convention in this codebase.
     */
    public static volatile Mode MODE = resolveBootMode();

    private static Mode resolveBootMode() {
        String raw = System.getProperty("nestworld.raceDetectMode");
        if (raw == null) return Mode.OFF;
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Mode.OFF;
        }
    }
}
