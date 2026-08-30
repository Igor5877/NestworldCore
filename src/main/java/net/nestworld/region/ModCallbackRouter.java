package net.nestworld.region;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * 2026-08-30, Mod Compatibility / Execution Isolation Layer, Phase 1. Companion to
 * {@link ModCallbackClassifier}: routes a mod callback invocation to the main thread when the
 * classifier says {@code MAIN_THREAD_ONLY} and the caller is currently on a region thread,
 * instead of invoking it inline (which is what let the FTB Quests race happen -- see project
 * memory ftbchunks-repro-ladder-ftbquests-race-2026-08-30). Callbacks classified
 * {@code REGION_SAFE} (or already running on the main thread) still run inline, synchronously,
 * exactly as vanilla always did -- this is NOT a general "defer everything" queue, only the
 * specific narrow case region-execution actually introduced.
 *
 * <p>Same "never lost, next-tick-at-worst" discipline as
 * {@link NestworldRegionSystem#nestworldEnqueueScheduleTick}: a routed callback runs on the
 * main thread's next drain point (end of this tick, after the region-thread barrier closes),
 * not the instant it was requested -- the arguments captured in the deferred {@code Runnable}
 * must already be immutable/copied by the time they're enqueued (true for
 * {@code ContainerListener.slotChanged}, which is always called with an already-copied
 * {@code ItemStack}).
 */
public final class ModCallbackRouter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ConcurrentLinkedQueue<Runnable> PENDING = new ConcurrentLinkedQueue<>();
    private static final AtomicLong ROUTED_TO_MAIN = new AtomicLong();
    private static final AtomicLong RAN_INLINE = new AtomicLong();
    private static final AtomicLong DRAIN_EXCEPTIONS = new AtomicLong();
    private static volatile int maxObservedQueueSize = 0;

    private ModCallbackRouter() {}

    /** Call this INSTEAD OF invoking the ContainerListener callback directly. `invoke` must
     * close over nothing but already-copied/immutable values (see class javadoc). */
    public static void dispatchContainerListener(String site,
            net.minecraft.world.inventory.ContainerListener listener, Runnable invoke) {
        if (!ModCallbackClassifier.ENABLED) {
            // Isolation off -- Phase 0 behavior: still measure (ModCallbackAttribution), still
            // run inline exactly like vanilla always did.
            ModCallbackAttribution.record(site, listener);
            invoke.run();
            return;
        }
        ModCallbackClassifier.Classification c = ModCallbackClassifier.classify(site, listener);
        boolean isRegionThread = NestworldRegionSystem.nestworldIsRegionThread();
        if (c == ModCallbackClassifier.Classification.MAIN_THREAD_ONLY && isRegionThread) {
            ROUTED_TO_MAIN.incrementAndGet();
            PENDING.add(() -> {
                ModCallbackAttribution.record(site, listener);
                invoke.run();
            });
            int size = PENDING.size();
            if (size > maxObservedQueueSize) maxObservedQueueSize = size;
        } else {
            RAN_INLINE.incrementAndGet();
            ModCallbackAttribution.record(site, listener);
            invoke.run();
        }
    }

    /** Main-thread-only, called once per tick after the region-thread barrier closes (same
     * timing contract as PairingOutboundQueue.drainBudget() etc.). No per-tick budget cap --
     * these are single-listener-callback invocations, not bulk work; if this ever needs a cap,
     * the queue-size high-water mark (visible in report()) is the signal to add one. */
    public static void drain() {
        Runnable r;
        while ((r = PENDING.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                DRAIN_EXCEPTIONS.incrementAndGet();
                LOGGER.warn("ModCallbackRouter: routed callback threw, skipped: {}", t.toString());
            }
        }
    }

    public static String report() {
        return "NW mod-callback-router: enabled=" + ModCallbackClassifier.ENABLED
                + " routedToMain=" + ROUTED_TO_MAIN.get() + " ranInline=" + RAN_INLINE.get()
                + " pendingNow=" + PENDING.size() + " maxObservedQueueSize=" + maxObservedQueueSize
                + " drainExceptions=" + DRAIN_EXCEPTIONS.get();
    }

    public static void reset() {
        ROUTED_TO_MAIN.set(0);
        RAN_INLINE.set(0);
        DRAIN_EXCEPTIONS.set(0);
        maxObservedQueueSize = PENDING.size();
    }
}
