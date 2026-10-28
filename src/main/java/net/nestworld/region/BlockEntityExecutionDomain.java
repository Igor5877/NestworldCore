package net.nestworld.region;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * BLOCK ENTITY EXECUTION DOMAIN (AE2-COMPAT-CLOSURE-001, AE2-BE-READY-001 R1-a scope 1).
 *
 * <p>Answers "on which thread does block-entity work at (level, pos) belong?" — the SAME rule the block-entity phase
 * uses to tick that position ({@link NestworldDimensionRegion#resolveBlockEntityRegion}, composed of the
 * {@code blockEntityTypePolicy} / {@code blockEntityRegionFor} pair that {@code runBlockEntityPhase} itself buckets
 * with): pinned type → MAIN; cascade-safe type → its grid region; otherwise the region whose interior (beyond the
 * border band) contains the chunk; border band or no region → MAIN. A region bucket the BE phase drops for one tick
 * because its region is backlogged is skipped, not moved: its domain stays that region.
 *
 * <p>This is deliberately NOT {@link NestworldOwnership}: that answers "which logical region owns pos?" (grid region,
 * band-agnostic). A border-band block entity belongs to grid region R but executes on MAIN.
 *
 * <p>Delivery: a region domain receives a {@link RegionMessage.Type#BE_DOMAIN_TASK} mailbox leg (drained by its
 * own thread at the start of its tick, under its write lock — serialized with that region's block-entity round);
 * the MAIN domain of a managed dimension is a per-dimension queue drained by its {@code tick()} on the main thread;
 * an unmanaged level's work is a server task (always the main thread). A leg whose region was retired is handed back
 * here by {@link MailboxRetargeting} and re-resolved like any other move.
 *
 * <p>Internal compat-layer primitive, not part of the public Mod API. Core knows nothing about the work it runs and does
 * NOT promise that a block entity present at enqueue time still exists at execution time: callers re-resolve and
 * revalidate their own state inside {@code op}.
 */
public final class BlockEntityExecutionDomain {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/BeDomain");

    /** The resolved domain: {@code region == null} means the server (main) thread. */
    public record Domain(@Nullable WorldRegion region) {
        public static final Domain MAIN = new Domain(null);

        public boolean isMain() {
            return region == null;
        }

        @Override
        public String toString() {
            return region == null ? "MAIN" : "REGION#" + region.getId();
        }
    }

    /** Why a task was not executed. Delivered to {@code onTerminal} on the server thread; never silent. */
    public enum Terminal {
        /** Re-resolution moved the task {@link NestworldTuning#MAILBOX_MAX_REDELIVERIES} times without running it. */
        REDELIVERY_LIMIT_DROPPED,
        /** The dimension's region system shut down with the task still queued for main. */
        LEVEL_GONE
    }

    /** One unit of block-entity-domain work. {@code redeliveries} bounds re-resolution. */
    public record Task(ServerLevel level, BlockPos pos, @Nullable String beTypeId, Runnable op,
                       @Nullable Consumer<Terminal> onTerminal, long id, int redeliveries) {
        Task moved() {
            return new Task(level, pos, beTypeId, op, onTerminal, id, redeliveries + 1);
        }
    }

    // ---- TASK accounting (logical task, independent of mailbox legs) ----
    // created (accepted) = executed (executedRegion + executedMain) + terminalDropped (terminal) + outstanding
    // (pending()); after quiescence outstanding == 0. A task ends only by executing exactly once or by an explicit
    // terminal drop. Mailbox handedOff / applied close LEGS (MailboxAudit) and never appear here: a leg handed off
    // and then lost in re-routing keeps outstanding > 0 at quiescence, however clean the mailbox balance is.
    // rerouted counts moves, not outcomes.
    public static final AtomicLong accepted = new AtomicLong();
    public static final AtomicLong executedRegion = new AtomicLong();
    public static final AtomicLong executedMain = new AtomicLong();
    public static final AtomicLong rerouted = new AtomicLong();
    public static final AtomicLong terminal = new AtomicLong();
    public static final AtomicLong opFailures = new AtomicLong();
    private static final AtomicLong IDS = new AtomicLong();

    private BlockEntityExecutionDomain() {
    }

    /**
     * The execution domain for block-entity work at {@code pos}. {@code beTypeId} is the block entity type's registry id
     * ({@code BlockEntityType.getKey(type).toString()}) — it selects the pinned / cascade-safe exceptions; {@code null}
     * means "no type exception applies".
     */
    public static Domain resolveBlockEntityExecutionDomain(ServerLevel level, BlockPos pos, @Nullable String beTypeId) {
        NestworldDimensionRegion dr = dimension(level);
        if (dr == null) return Domain.MAIN;
        WorldRegion r = dr.resolveBlockEntityRegion(pos.getX() >> 4, pos.getZ() >> 4, beTypeId);
        return r == null ? Domain.MAIN : new Domain(r);
    }

    /** True if the current thread is the execution domain of block-entity work at {@code pos}. */
    public static boolean isCurrentThreadIn(ServerLevel level, BlockPos pos, @Nullable String beTypeId) {
        Domain d = resolveBlockEntityExecutionDomain(level, pos, beTypeId);
        if (d.isMain()) return level.getServer().isSameThread();
        return d.region().owningThread == Thread.currentThread();
    }

    /**
     * Non-blocking: schedules {@code op} to run in the block-entity execution domain of {@code pos} at that domain's
     * next safe point (a region's mailbox drain at the start of its tick, under its write lock; or the dimension's
     * main-thread pass). The domain is re-resolved immediately before execution; if it changed (split / merge / band
     * or policy change) the task is re-routed, at most {@link NestworldTuning#MAILBOX_MAX_REDELIVERIES} times, after
     * which {@code onTerminal(REDELIVERY_LIMIT_DROPPED)} runs on the server thread. Never runs {@code op} inline.
     */
    public static void enqueueForBlockEntityDomain(ServerLevel level, BlockPos pos, @Nullable String beTypeId, Runnable op,
                               @Nullable Consumer<Terminal> onTerminal) {
        accepted.incrementAndGet();
        route(new Task(level, pos.immutable(), beTypeId, op, onTerminal, IDS.incrementAndGet(), 0));
    }

    /** A task whose previous domain is no longer authoritative (its mailbox destination retired). */
    static void reroute(Task task) {
        rerouted.incrementAndGet();
        route(task.moved());
    }

    /** Routes a task to its currently resolved domain. Never runs it. */
    private static void route(Task task) {
        if (task.redeliveries() >= NestworldTuning.MAILBOX_MAX_REDELIVERIES) {
            terminal(task, Terminal.REDELIVERY_LIMIT_DROPPED);
            return;
        }
        NestworldDimensionRegion dr = dimension(task.level());
        if (dr == null) {
            var server = task.level().getServer();
            server.tell(new TickTask(server.getTickCount(), () -> execute(task, executedMain)));
            return;
        }
        WorldRegion r = dr.resolveBlockEntityRegion(task.pos().getX() >> 4, task.pos().getZ() >> 4, task.beTypeId());
        if (r == null) {
            dr.enqueueMainBlockEntityTask(task);
        } else {
            RegionMessage<Task> msg = new RegionMessage<>(null, r, task.level().getServer().getTickCount(), 0,
                    RegionMessage.Type.BE_DOMAIN_TASK, task, System.nanoTime(), MailboxAudit.nextId());
            r.nestworldPostMessage(msg); // C4 safe enqueue; a retired region orphans it → MailboxRetargeting re-routes
        }
    }

    /** Region-thread consumer (RegionThread mailbox drain): run if still in this region's domain, else re-route. */
    static void runOnRegion(Task task, WorldRegion here) {
        NestworldDimensionRegion dr = dimension(task.level());
        WorldRegion now = dr == null ? null
                : dr.resolveBlockEntityRegion(task.pos().getX() >> 4, task.pos().getZ() >> 4, task.beTypeId());
        if (now != here) {
            reroute(task);
            return;
        }
        execute(task, executedRegion);
    }

    /** Main-thread consumer: run if the domain is still MAIN, else re-route to the region now owning it. */
    static void runOnMain(Task task) {
        NestworldDimensionRegion dr = dimension(task.level());
        WorldRegion now = dr == null ? null
                : dr.resolveBlockEntityRegion(task.pos().getX() >> 4, task.pos().getZ() >> 4, task.beTypeId());
        if (now != null) {
            reroute(task);
            return;
        }
        execute(task, executedMain);
    }

    private static void execute(Task task, AtomicLong counter) {
        counter.incrementAndGet();
        try {
            task.op().run();
        } catch (Throwable t) {
            opFailures.incrementAndGet();
            LOGGER.warn("[BE-DOMAIN] task {} at {} failed: {}", task.id(), task.pos(), t.toString());
        }
    }

    static void terminal(Task task, Terminal kind) {
        terminal.incrementAndGet();
        LOGGER.warn("[BE-DOMAIN] task {} at {} terminal: {}", task.id(), task.pos(), kind);
        Consumer<Terminal> cb = task.onTerminal();
        if (cb == null) return;
        Runnable call = () -> {
            try {
                cb.accept(kind);
            } catch (Throwable t) {
                LOGGER.warn("[BE-DOMAIN] onTerminal of task {} failed: {}", task.id(), t.toString());
            }
        };
        var server = task.level().getServer();
        if (server == null || server.isSameThread()) call.run();
        else server.execute(call);
    }

    public static String stats() {
        return "beDomain accepted=" + accepted.get() + " executedRegion=" + executedRegion.get() + " executedMain="
                + executedMain.get() + " rerouted=" + rerouted.get() + " terminal=" + terminal.get() + " opFailures="
                + opFailures.get() + " pending=" + pending();
    }

    /** accepted − executed − terminal: tasks still queued or in a mailbox leg (exact at quiescence). */
    public static long pending() {
        return accepted.get() - executedRegion.get() - executedMain.get() - terminal.get();
    }

    @Nullable
    private static NestworldDimensionRegion dimension(ServerLevel level) {
        return NestworldRegionSystem.isInitialised() && NestworldRegionSystem.get().isManagedLevel(level)
                ? NestworldRegionSystem.get().getDimensionRegion(level) : null;
    }
}
