package net.nestworld.compat.ae2.glue;

import appeng.api.behaviors.StackImportStrategy;
import appeng.api.behaviors.StackTransferContext;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.me.Grid;
import appeng.parts.automation.StackImportFacade;
import appeng.util.prioritylist.IPartitionList;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.nestworld.compat.ae2.mixin.StackImportFacadeAccessor;

/** Opt-in ordered import continuation. Foreign resources retain AE2's real MAIN context.
 * Builtin inventory extraction retains the accounted transit buffer even after a MAIN handoff.
 */
final class BusImportContinuation {
    private static final boolean FLAG = Boolean.getBoolean("nestworld.compatAe2.busImportCursor");
    private static final Method HANDOFF;
    private static final Constructor<?> CONTEXT;
    static final LongAdder admitted = new LongAdder(), absent = new LongAdder(), handoffs = new LongAdder(),
            resumed = new LongAdder(), aborted = new LongAdder(), failures = new LongAdder(), sourceNotLocal = new LongAdder();
    static {
        Method handoff = null;
        Constructor<?> context = null;
        try {
            ClassLoader cl = BusImportContinuation.class.getClassLoader();
            handoff = Class.forName("net.nestworld.region.BlockEntityDomainHandoff", false, cl).getMethod("request",
                    ServerLevel.class, BlockPos.class, String.class, BooleanSupplier.class, Consumer.class, Consumer.class);
            context = Class.forName("appeng.parts.automation.StackTransferContextImpl", false, cl).getDeclaredConstructor(
                    IStorageService.class, IEnergySource.class, IActionSource.class, int.class, IPartitionList.class);
            context.setAccessible(true);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            handoff = null;
            context = null;
        }
        HANDOFF = handoff;
        CONTEXT = context;
    }

    static boolean available() { return FLAG && HANDOFF != null && CONTEXT != null; }

    static boolean accepts(StackImportStrategy strategy) {
        return available() && ImportStrategyCursor.audited(strategy);
    }

    static String status() {
        return "importCursor{flag=" + FLAG + " available=" + available() + " admitted=" + admitted.sum()
                + " absent=" + absent.sum() + " handoffs=" + handoffs.sum() + " resumed=" + resumed.sum()
                + " aborted=" + aborted.sum() + " failures=" + failures.sum() + " sourceNotLocal=" + sourceNotLocal.sum()
                + " modelASuffix=" + modelASuffixRuns.sum() + " modelANonItemDemoted=" + modelANonItemDemoted.sum() + "}" + coreStatus();
    }

    private static String coreStatus() {
        if (!available()) return "";
        try { return "\n" + HANDOFF.getDeclaringClass().getMethod("stats").invoke(null); }
        catch (ReflectiveOperationException unavailable) { return "\ncoreHandoffStats=UNAVAILABLE"; }
    }

    private final BusTransit.Rec record;
    private final List<StackImportStrategy> strategies;
    private BusTransitContext context;
    private volatile net.minecraft.world.level.block.entity.BlockEntity sourceIdentity;
    private int cursor;
    private volatile boolean waiting, ready, done;
    private Object lease;
    private boolean didWork;

    BusImportContinuation(BusTransit.Rec r) {
        record = r;
        strategies = List.copyOf(((StackImportFacadeAccessor) (StackImportFacade) r.importStrategy).nw$strategies());
        admitted.increment();
    }

    /** Owner domain only; never calls a foreign resource strategy on REGION. */
    void runRegion(BusTransitContext ctx) {
        context = ctx;
        sourceIdentity = record.level.getBlockEntity(record.nPos);
        if (sourceIdentity == null) { abort(); return; }
        while (cursor < strategies.size()) {
            StackImportStrategy strategy = strategies.get(cursor);
            if (ImportStrategyCursor.isItem(strategy)) {
                // Source ownership snapshot BEFORE any region item mutation: a source that is not provably local
                // (linked / controller member / transitional / unknown class) is handed to MAIN at this cursor.
                if (LocalImportSource.local(sourceIdentity)) {
                    cursor++; // A throwing strategy is never replayed after a partial extraction.
                    if (strategy.transfer(ctx)) { done = true; return; }
                    continue;
                }
                sourceNotLocal.increment();
            } else {
                ImportStrategyCursor.Probe probe = ImportStrategyCursor.probe(strategy, ctx);
                if (probe == ImportStrategyCursor.Probe.ABSENT) {
                    absent.increment();
                    cursor++;
                    continue;
                }
                if (probe == ImportStrategyCursor.Probe.UNKNOWN) {
                    abort();
                    throw new IllegalStateException("Audited import probe became unavailable");
                }
            }
            {
                waiting = true;
                handoffs.increment();
                try {
                    BeDomainReady.enqueueTo(record.level, record.hostPos, "ae2:cable_bus", this::requestMain,
                            ignored -> abortAndPublish());
                } catch (Throwable failure) {
                    abort();
                    throw new IllegalStateException("Import owner handoff rejected", failure);
                }
                return;
            }
        }
        done = true;
    }

    private void requestMain() {
        if (done || !validMain()) { abortAndPublish(); return; }
        try {
            HANDOFF.invoke(null, record.level, record.nPos, record.nType,
                    (BooleanSupplier) this::validMain, (Consumer<Object>) this::onReady,
                    (Consumer<Object>) ignored -> abortAndPublish());
        } catch (ReflectiveOperationException failure) {
            failures.increment();
            abortAndPublish();
            Ae2Compat.LOG.error("[nestworld-compat-ae2] import handoff request failed at {}", record.nPos, failure);
        }
    }

    private boolean validMain() {
        BusTransit.BusState bs = ((NwTransitBus) record.bus).nw$transit();
        return !record.orphan && bs.inWorld && bs.incarnation == record.incarnation
                && BusTransit.liveGrid(record.bus.getGridNode()) != null
                && sourceIdentity != null && !sourceIdentity.isRemoved()
                && record.level.getChunkSource().getChunkNow(record.nPos.getX() >> 4, record.nPos.getZ() >> 4) != null
                && record.level.getBlockEntity(record.nPos) == sourceIdentity;
    }

    private void onReady(Object acquired) {
        if (done || !validMain()) {
            close(acquired);
            abortAndPublish();
            return;
        }
        lease = acquired;
        ready = true;
        if (record.gs.workerOwned) runSuffixOnOwnerModelA();
        BusTransit.publish(record);
    }

    static final LongAdder modelASuffixRuns = new LongAdder(), modelANonItemDemoted = new LongAdder();

    /**
     * AE2-GRID-DOMAIN-001 model A: the grid's commit pass runs on the AE2 worker, which may not touch the source, so the
     * suffix cannot wait for {@link #resume}. Here (MAIN, lease held = the source's owner) the ITEM strategies extract
     * into the record's transit buffer exactly as {@link #resume} would — {@link BusTransitContext} never reaches grid
     * state — and the worker commits the buffer. A present non-item resource needs AE2's native context (real network
     * on this thread): it is not run; the grid goes back to model B and the next operation takes the B path.
     */
    private void runSuffixOnOwnerModelA() {
        if (!BeDomainReady.currentThreadIn(record.level, record.nPos, record.nType)) return;   // resume() aborts as before
        try {
            while (cursor < strategies.size()) {
                StackImportStrategy strategy = strategies.get(cursor);
                if (ImportStrategyCursor.isItem(strategy)) {
                    cursor++;
                    if (strategy.transfer(context)) break;
                    continue;
                }
                if (ImportStrategyCursor.probe(strategy, context) == ImportStrategyCursor.Probe.ABSENT) {
                    absent.increment();
                    cursor++;
                    continue;
                }
                // owner 2026-11-13 (MVP-001): no grid demotion — from now on this bus's device tick is served on MAIN
                // (MainService delegation ⇒ AE2's native path, real network context); this op imports nothing more now
                modelANonItemDemoted.increment();
                MainServiced.requireMain(record.bus);
                break;
            }
            modelASuffixRuns.increment();
            resumed.increment();
        } catch (Throwable failure) {
            failures.increment();
            Ae2Compat.LOG.error("[nestworld-compat-ae2] model A import suffix failed; suffix will not be replayed at {}",
                    record.nPos, failure);
        } finally {
            didWork = context.hasDoneWork();
            done = true;
            waiting = false;
        }
    }

    boolean canProcess(boolean alive) {
        if (!alive && !done) abort();
        return done || !waiting || ready;
    }

    /** MAIN only, AFTER held prefix has been committed. Cursor/order/budget are retained exactly once. */
    boolean resume(Grid grid) {
        if (done || !waiting) return false;
        if (!ready || grid == null || !validMain()
                || !BeDomainReady.currentThreadIn(record.level, record.nPos, record.nType)) {
            abort();
            return false;
        }
        boolean ran = false;
        try {
            StackTransferContext nativeContext = (StackTransferContext) CONTEXT.newInstance(grid.getStorageService(),
                    grid.getEnergyService(), record.source, record.ops, record.filter);
            nativeContext.setInverted(record.inverted);
            while (cursor < strategies.size()) {
                StackImportStrategy strategy = strategies.get(cursor++);
                ran = true;
                boolean stop;
                if (ImportStrategyCursor.isItem(strategy)) {
                    stop = strategy.transfer(context);
                } else {
                    nativeContext.setOperationsRemaining(context.getOperationsRemaining());
                    try {
                        stop = strategy.transfer(nativeContext);
                    } finally {
                        context.setOperationsRemaining(nativeContext.getOperationsRemaining());
                    }
                }
                if (stop) break;
            }
            didWork = context.hasDoneWork();
            resumed.increment();
        } catch (Throwable failure) {
            failures.increment();
            Ae2Compat.LOG.error("[nestworld-compat-ae2] import continuation failed; suffix will not be replayed at {}",
                    record.nPos, failure);
        } finally {
            didWork = context.hasDoneWork();
            done = true;
            waiting = false;
        }
        return ran;
    }

    boolean didWork() { return didWork; }
    boolean pendingMain() { return waiting; }

    private void abort() {
        if (!done) aborted.increment();
        done = true;
        waiting = false;
    }

    private void abortAndPublish() {
        abort();
        BusTransit.publish(record);
    }

    /** MAIN only. Keep lease through ledger returns/recovery; Core guards stale native batches after release. */
    void release() {
        abort();
        Object acquired = lease;
        lease = null;
        if (acquired != null) close(acquired);
    }

    private static void close(Object lease) {
        try { ((AutoCloseable) lease).close(); }
        catch (Exception failure) {
            failures.increment();
            Ae2Compat.LOG.error("[nestworld-compat-ae2] import handoff release failed", failure);
        }
    }
}
