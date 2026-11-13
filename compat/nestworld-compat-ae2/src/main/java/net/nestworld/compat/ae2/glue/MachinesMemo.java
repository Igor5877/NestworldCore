package net.nestworld.compat.ae2.glue;

import java.util.concurrent.atomic.LongAdder;

/** AE2-COST-001-FAST candidate 2 counters; flag {@code -Dnestworld.compatAe2.machinesMemo=true} (default off). */
public final class MachinesMemo {
    public static final boolean ON = Boolean.getBoolean("nestworld.compatAe2.machinesMemo");
    public static final int VERIFY = Integer.getInteger("nestworld.compatAe2.machinesMemoVerify", 0);
    public static final LongAdder HIT = new LongAdder(), MISS = new LongAdder(), VERIFY_OK = new LongAdder(), MISMATCH = new LongAdder();
    public static volatile boolean disabled;

    public static void mismatch(Class<?> c) {
        MISMATCH.increment();
        disabled = true;
        Ae2Compat.LOG.error("[nestworld-compat-ae2] MACHINES-MEMO verify mismatch for {} — memo disabled for the run", c.getName());
    }

    private MachinesMemo() { }

    public static String summary() {
        return "machinesMemo " + (ON ? "ON" : "OFF (flag)") + " hit=" + HIT.sum() + " miss=" + MISS.sum() + " verify ok=" + VERIFY_OK.sum() + " mismatch(must be 0)=" + MISMATCH.sum() + (disabled ? " [DISABLED]" : "");
    }

    public static void reset() { HIT.reset(); MISS.reset(); VERIFY_OK.reset(); MISMATCH.reset(); }
}
