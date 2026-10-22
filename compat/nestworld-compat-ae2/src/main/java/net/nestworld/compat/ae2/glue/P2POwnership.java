package net.nestworld.compat.ae2.glue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * P2P-FORWARDING-FAIL-CLOSED-001 (AE2-COMPAT-P2P-001 H3 safety gate, owner-approved 2026-10-15).
 * A P2P capability tunnel forwards a synchronous capability call to the block next to the OTHER end
 * of the tunnel, which can be owned by another region or dimension. Before AE2 looks that block up,
 * the current thread must own its position; otherwise the forward is refused with AE2's own empty
 * handler (0 energy / unchanged stack / 0 fluid), without touching the ghost copy or the live foreign
 * block entity. This is NOT cross-region P2P support: cross-region P2P is unsupported, and
 * unsupported means refused, never corrupted, lost or foreign-mutated.
 *
 * <p>Ownership comes from NestWorld Core's narrow read-only contract
 * {@code net.nestworld.region.NestworldOwnership.isCurrentThreadOwner(Level, BlockPos)}, looked up by
 * name (the addon has no Core compile dependency). Core present without the contract: locality cannot
 * be established, so EVERY P2P capability forward is refused. No NestWorld Core: vanilla, allowed.
 */
public final class P2POwnership {
    /** Negative-control switch: rig/negp2p_noforwardguard.py flips it to false (= unguarded AE2). */
    public static final boolean PROTECT = true;

    public enum Mode { CONTRACT, NO_CONTRACT_REFUSE_ALL, NO_NESTWORLD }

    public static final Mode MODE;
    private static final MethodHandle OWNER;

    static {
        ClassLoader cl = P2POwnership.class.getClassLoader();
        MethodHandle h = null;
        Mode m;
        try {
            Class<?> own = Class.forName("net.nestworld.region.NestworldOwnership", true, cl);
            int contract = own.getField("CONTRACT").getInt(null);
            h = MethodHandles.publicLookup().findStatic(own, "isCurrentThreadOwner",
                    MethodType.methodType(boolean.class, Level.class, BlockPos.class));
            m = contract >= 1 ? Mode.CONTRACT : Mode.NO_CONTRACT_REFUSE_ALL;
        } catch (ReflectiveOperationException | LinkageError e) {
            m = classPresent("net.nestworld.region.NestworldRegionSystem", cl) ? Mode.NO_CONTRACT_REFUSE_ALL : Mode.NO_NESTWORLD;
        }
        MODE = m;
        OWNER = m == Mode.CONTRACT ? h : null;
        Ae2Compat.LOG.info("[nestworld-compat-ae2] P2P forwarding ownership: mode={} ({})", MODE,
                MODE == Mode.CONTRACT ? "cross-owner P2P capability forwards are refused"
                        : MODE == Mode.NO_CONTRACT_REFUSE_ALL ? "Core lacks NestworldOwnership: EVERY P2P capability forward is refused"
                        : "no NestWorld Core: vanilla behaviour");
    }

    private static boolean classPresent(String name, ClassLoader cl) {
        try {
            Class.forName(name, false, cl);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static volatile boolean warned;

    /** May the current thread synchronously reach the block at {@code pos} in {@code level}? */
    public static boolean mayForward(Level level, BlockPos pos) {
        if (!PROTECT || MODE == Mode.NO_NESTWORLD) {
            Ae2Compat.COUNTERS.p2pForwardLocalAllowed.increment();
            return true;
        }
        boolean ok = false;
        if (MODE == Mode.CONTRACT) {
            try {
                ok = (boolean) OWNER.invokeExact(level, pos);
            } catch (Throwable t) {
                ok = false;                                   // fail closed
            }
        }
        if (ok) {
            Ae2Compat.COUNTERS.p2pForwardLocalAllowed.increment();
            return true;
        }
        Ae2Compat.COUNTERS.p2pForwardForeignRejected.increment();
        if (Ae2Compat.onMainThread()) Ae2Compat.COUNTERS.p2pForwardRejectedMainCaller.increment();
        if (!warned) {
            warned = true;
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] P2P capability forward refused (target {} not owned by {}); cross-region P2P "
                    + "is unsupported and fails closed. Further refusals are only counted (p2pForwardForeignRejected).",
                    pos.toShortString(), Thread.currentThread().getName());
        }
        return false;
    }

    private P2POwnership() {
    }
}
