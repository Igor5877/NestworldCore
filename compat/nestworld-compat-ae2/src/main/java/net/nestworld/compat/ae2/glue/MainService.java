package net.nestworld.compat.ae2.glue;

import appeng.me.Grid;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * AE2-GRID-DOMAIN-MVP-001 BARRIER_MAIN_SERVICE (owner 2026-11-12): narrow host-local SYNCHRONOUS MAIN service.
 * The AE2 worker hands ONE specific operation (a MAIN-serviced device tick, a crafting-provider call, a crafted-items
 * insert) to MAIN and waits for the result; MAIN runs only that operation at a top-level Core service point.
 * <ul>
 * <li>never while the worker is inside a kernel section (T0/topology): fail closed, counted;</li>
 * <li>while MAIN runs it, MAIN acts for the parked worker of grid {@code G} ({@link #delegatedFor}): ingress into G
 *     passes without P(G) — the worker holds P(G) and is parked, so the two never run together;</li>
 * <li>MAIN waiting for any P(G) services pending calls ({@link #lockOnMain}) — otherwise worker⇄MAIN deadlock;</li>
 * <li>a serviced call that needs P(G) of its own delegated grid any other way (topology, a phase) = nested barrier ⇒
 *     fail closed.</li>
 * </ul>
 */
public final class MainService {
    private MainService() {}

    private static final MethodHandle CALL, SERVICE, DELEGATION, IN_SERVICE;
    static {
        MethodHandle c = null, s = null, d = null, i = null;
        try {
            Class<?> ed = Class.forName("net.nestworld.region.ExecutionDomains", true, MainService.class.getClassLoader());
            var l = MethodHandles.publicLookup();
            c = l.findStatic(ed, "callMain", MethodType.methodType(Object.class, String.class, Object.class, Supplier.class));
            s = l.findStatic(ed, "serviceMainCalls", MethodType.methodType(void.class));
            d = l.findStatic(ed, "currentDelegation", MethodType.methodType(Object.class));
            i = l.findStatic(ed, "inMainService", MethodType.methodType(boolean.class));
        } catch (Throwable unavailable) {
            c = s = d = i = null;
        }
        CALL = c; SERVICE = s; DELEGATION = d; IN_SERVICE = i;
    }

    /** The grid whose phase the current AE2 worker thread is running (worker only). */
    static final ThreadLocal<Grid> CURRENT = new ThreadLocal<>();
    static final LongAdder kernelRefused = new LongAdder(), noGridRefused = new LongAdder(), delegatedIngress = new LongAdder(),
            mainLockServiced = new LongAdder(), nestedLockRefused = new LongAdder();

    public static boolean available() { return CALL != null; }

    public static void enter(Grid g) { CURRENT.set(g); }
    public static void exit() { CURRENT.remove(); }

    /** Thrown when the operation cannot be serviced safely; callers map it to AE2's "no work / not accepted" answer. */
    public static final class Refused extends RuntimeException {
        Refused(String why) { super(why, null, false, false); }
    }

    /** Worker: run {@code body} on MAIN and return its result. Not a worker thread: runs directly. */
    @SuppressWarnings("unchecked")
    public static <T> T call(String key, Supplier<T> body) {
        if (!GridWorker.isWorkerThread() || CALL == null) return body.get();
        if (Ae2Compat.KERNEL.inSection()) { kernelRefused.increment(); throw new Refused("kernel section held"); }
        Grid g = CURRENT.get();
        if (g == null) { noGridRefused.increment(); throw new Refused("no current grid"); }
        try {
            return (T) CALL.invokeExact(key, (Object) g, (Supplier<?>) body);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** MAIN: is MAIN currently running a serviced call on behalf of grid {@code g}'s parked worker? */
    public static boolean delegatedFor(Grid g) {
        if (DELEGATION == null || g == null) return false;
        try { return (Object) DELEGATION.invokeExact() == g; } catch (Throwable t) { return false; }
    }

    static boolean inService() {
        if (IN_SERVICE == null) return false;
        try { return (boolean) IN_SERVICE.invokeExact(); } catch (Throwable t) { return false; }
    }

    /** MAIN: take P(G), servicing synchronous calls while waiting (the holder may be parked on one). */
    public static void lockOnMain(Grid g, ReentrantLock p) {
        if (SERVICE == null || !Ae2Compat.onMainThread()) { p.lock(); return; }
        if (inService() && delegatedFor(g) && !p.isHeldByCurrentThread()) {
            nestedLockRefused.increment();
            throw new IllegalStateException("[nestworld-compat-ae2] P(G) wanted inside a serviced call for the same grid (nested barrier)");
        }
        try {
            while (!p.tryLock(100, TimeUnit.MICROSECONDS)) {
                mainLockServiced.increment();
                SERVICE.invokeExact();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.lock();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static String status() {
        return "mainService{available=" + available() + " kernelRefused(must be 0)=" + kernelRefused.sum() + " noGridRefused="
                + noGridRefused.sum() + " delegatedIngress=" + delegatedIngress.sum() + " mainLockServiced=" + mainLockServiced.sum()
                + " nestedLockRefused(must be 0)=" + nestedLockRefused.sum() + " devices=" + MainServiced.devices.sum()
                + " crafting=" + MainServiced.crafting.sum() + " refusedOps=" + MainServiced.refused.sum()
                + " stickyMainBuses=" + MainServiced.stickyAdded.sum() + "}";
    }
}
