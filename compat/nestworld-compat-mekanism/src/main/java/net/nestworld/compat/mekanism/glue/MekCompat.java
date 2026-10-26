package net.nestworld.compat.mekanism.glue;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Addon state and MEKANISM-REGISTRY-RACE-001 accounting (accounting off by default). */
public final class MekCompat {
    public static final Logger LOG = LoggerFactory.getLogger("nestworld-compat-mekanism");

    /** -Dnestworld.compatMekanism.audit=true: produced / published / dropped / mergedDuplicate / generations. */
    public static final boolean AUDIT = Boolean.getBoolean("nestworld.compatMekanism.audit");
    /** distinct (transmitter, side) insertions into a pending changedAcceptors generation */
    public static final LongAdder PRODUCED = new LongAdder();
    /** (transmitter, side) entries handed to commit() as a detached generation */
    public static final LongAdder PUBLISHED = new LongAdder();
    /** entries discarded by deregister() (Mekanism's own clear() semantics) */
    public static final LongAdder DROPPED = new LongAdder();
    /** entries that already existed in the adopting cache */
    public static final LongAdder MERGED_DUPLICATE = new LongAdder();
    /** non-empty detached generations (acceptor caches and the changed-network set) */
    public static final LongAdder GENERATIONS = new LongAdder();

    static volatile String mode = "NOT_EVALUATED";
    static volatile List<String> problems = List.of();
    static volatile long gateMillis;

    private MekCompat() {
    }

    public static String stats() {
        return "mode=" + mode + " problems=" + problems.size() + " audit=" + AUDIT + " produced=" + PRODUCED.sum()
                + " published=" + PUBLISHED.sum() + " dropped=" + DROPPED.sum() + " mergedDuplicate=" + MERGED_DUPLICATE.sum()
                + " generations=" + GENERATIONS.sum() + " gateMs=" + gateMillis;
    }

    public static List<String> problems() {
        return problems;
    }
}
