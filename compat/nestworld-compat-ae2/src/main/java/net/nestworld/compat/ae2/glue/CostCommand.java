package net.nestworld.compat.ae2.glue;

/** AE2-COST-001-FAST: read-only {@code /nwcompatae2 cost} counters (+ {@code reset} of the counters only). */
public final class CostCommand {
    private CostCommand() { }

    public static String render() {
        return SophExtractIndex.summary() + "\nnesting: " + CostMixinPlugin.nestingDetail() + "\n" + MachinesMemo.summary();
    }

    public static String reset() {
        SophExtractIndex.reset();
        MachinesMemo.reset();
        return "cost counters reset";
    }
}
