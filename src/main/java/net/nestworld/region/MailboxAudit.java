package net.nestworld.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stage 5 prerequisite (docs/LOCAL_TICK_STAGE4.md, "Part 5 staging"): debug-only
 * instrumentation that proves the mailbox's "exactly-once, correct destination"
 * delivery claim mechanically instead of assuming it from "the server didn't crash".
 * Gemini's independent review of the Part 5 staging plan flagged this as a MANDATORY
 * PREREQUISITE, not a nice-to-have — silent message loss, duplication, or misrouting
 * is exactly the class of bug a crash-only test suite cannot see, and is the specific
 * "world quietly became wrong while the server keeps running" failure mode Part 5
 * (free-running regions) is riskiest for.
 *
 * <p>Deliberately built and baselined BEFORE any Stage 5 behavior change — validated
 * first against TODAY's barrier engine (where sent/applied are already known-correct
 * by construction) specifically so a LATER divergence under free-running regions can
 * be attributed to the new execution model, not mistaken for a pre-existing bug this
 * audit would already have caught on the old engine.
 *
 * <p>Enabled via {@code NESTWORLD_MAILBOX_AUDIT=1} / {@code -Dnestworld.mailboxAudit=true}.
 * Zero cost when disabled beyond one {@code static final boolean} check per call (ID
 * generation itself is always-on — a plain {@code AtomicLong} sequence number, not a
 * UUID, cheap enough to leave on unconditionally so audit mode can be toggled without
 * touching any message-creation call site).
 */
public final class MailboxAudit {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/MailboxAudit");

    public static final boolean ENABLED =
            System.getenv("NESTWORLD_MAILBOX_AUDIT") != null || Boolean.getBoolean("nestworld.mailboxAudit");

    private static final AtomicLong idCounter = new AtomicLong();
    private static final AtomicLong sentTotal = new AtomicLong();
    private static final AtomicLong appliedTotal = new AtomicLong();
    private static final AtomicLong duplicateTotal = new AtomicLong();
    private static final AtomicLong misroutedTotal = new AtomicLong();
    // CORE-MAILBOX-MERGE-001 (C11): retirement-handoff accounting.
    private static final AtomicLong orphanedTotal = new AtomicLong();
    private static final AtomicLong redirectedTotal = new AtomicLong();
    private static final AtomicLong terminalTotal = new AtomicLong();
    private static final AtomicLong handedOffTotal = new AtomicLong();
    private static final Map<MailboxLifecycle.Terminal, AtomicLong> terminalByKind = new ConcurrentHashMap<>();
    static {
        for (MailboxLifecycle.Terminal t : MailboxLifecycle.Terminal.values()) terminalByKind.put(t, new AtomicLong());
    }

    /** In-flight messages: auditId -> record, removed on successful apply. Bounded
     *  by real mailbox depth (the same messages the mailbox itself is holding at any
     *  moment), not an independently unbounded structure — a healthy run keeps this
     *  small; a growing map IS the "pending/possible loss" signal itself. */
    private static final Map<Long, Entry> inFlight = new ConcurrentHashMap<>();

    private record Entry(int sourceId, int destId, RegionMessage.Type type, long sentNanos) {}

    private MailboxAudit() {}

    /** Always-on (cheap), so audit mode can be toggled without touching call sites. */
    public static long nextId() {
        return idCounter.incrementAndGet();
    }

    /** Called once, at the single common enqueue point ({@link WorldRegion#nestworldPostMessage}),
     *  for every GENUINELY NEW message. Re-queues (a lock-timeout retry, e.g. Part 3's
     *  cascade guard) must NOT call this again for the same auditId — see {@link
     *  WorldRegion#nestworldRequeueMessage} — or a retried-but-not-lost message would be
     *  double-counted as two "sent" events for one real send. */
    public static void recordSent(long auditId, WorldRegion source, WorldRegion destination, RegionMessage.Type type) {
        if (!ENABLED) return;
        sentTotal.incrementAndGet();
        inFlight.put(auditId, new Entry(source == null ? -1 : source.getId(), destination.getId(), type, System.nanoTime()));
    }

    /** Called at every message-type's single apply site once the message has actually
     *  taken effect (not on a requeue/timeout — those leave the entry untouched, still
     *  correctly tracked as pending). */
    public static void recordApplied(long auditId, WorldRegion appliedAtRegion, RegionMessage.Type type) {
        MailboxLifecycleTestHooks.onAuditApplied(auditId, appliedAtRegion);
        if (!ENABLED) return;
        Entry e = inFlight.remove(auditId);
        if (e == null) {
            duplicateTotal.incrementAndGet();
            LOGGER.warn("Mailbox audit: message {} (type={}) applied but was already removed from "
                    + "in-flight tracking — duplicate apply", auditId, type);
            return;
        }
        if (e.destId() != appliedAtRegion.getId()) {
            misroutedTotal.incrementAndGet();
            LOGGER.warn("Mailbox audit: message {} (type={}) sent to region {} but applied at region {}",
                    auditId, type, e.destId(), appliedAtRegion.getId());
        }
        appliedTotal.incrementAndGet();
    }

    /** CORE-MAILBOX-MERGE-001: the message entered its dimension's orphan queue (its destination
     *  was retired). Still in flight -- an orphan is transport state, not an outcome. */
    static void recordOrphaned(long auditId) {
        if (!ENABLED) return;
        orphanedTotal.incrementAndGet();
    }

    /** CORE-MAILBOX-MERGE-001: re-resolution moved the message to {@code newDestination}; its
     *  eventual apply there is correct routing, not a misroute. */
    static void recordRedirected(long auditId, WorldRegion newDestination) {
        if (!ENABLED) return;
        redirectedTotal.incrementAndGet();
        inFlight.computeIfPresent(auditId, (k, e) -> new Entry(e.sourceId(), newDestination.getId(), e.type(), e.sentNanos()));
    }

    /** CORE-MAILBOX-MERGE-001: an explicit, contract-defined terminal disposition decided during
     *  re-resolution. Closes the in-flight entry exactly like an apply would. */
    static void recordTerminal(long auditId, MailboxLifecycle.Terminal kind, RegionMessage.Type type) {
        MailboxLifecycleTestHooks.onAuditTerminal(auditId, kind.name());
        if (!ENABLED) return;
        Entry e = inFlight.remove(auditId);
        if (e == null) {
            duplicateTotal.incrementAndGet();
            LOGGER.warn("Mailbox audit: message {} (type={}) terminal {} but already closed -- duplicate outcome",
                    auditId, type, kind);
            return;
        }
        terminalTotal.incrementAndGet();
        terminalByKind.get(kind).incrementAndGet();
    }

    /** AE2-BE-READY-001 R1-a: a {@link RegionMessage.Type#BE_DOMAIN_TASK} leg whose destination was
     *  retired left mailbox transport and was handed back to {@link BlockEntityExecutionDomain}
     *  (which accounts for it from there on). Closes the in-flight entry; neither applied nor terminal. */
    static void recordHandedOff(long auditId, RegionMessage.Type type) {
        if (!ENABLED) return;
        if (inFlight.remove(auditId) == null) {
            duplicateTotal.incrementAndGet();
            LOGGER.warn("Mailbox audit: message {} (type={}) handed off but already closed -- duplicate outcome",
                    auditId, type);
            return;
        }
        handedOffTotal.incrementAndGet();
    }

    /**
     * C11 quiescence report. {@code reachable} = every audit id currently present in live
     * transport (live regions' mailboxes and pending queues, plus orphan queues). Then
     * <pre>
     *   accepted = applied + terminal + inFlight
     *   unaccounted = |inFlight \ reachable|   -- accepted, no outcome, and nowhere in transport
     * </pre>
     * At quiescence the closure property requires unaccounted == 0 (and normally inFlight == 0).
     */
    static String accountingReport(java.util.Set<Long> reachable) {
        if (!ENABLED) return "ACCOUNTING disabled (-Dnestworld.mailboxAudit=true required)";
        long unaccounted = 0;
        StringBuilder sample = new StringBuilder();
        for (Map.Entry<Long, Entry> e : inFlight.entrySet()) {
            if (reachable.contains(e.getKey())) continue;
            unaccounted++;
            if (sample.length() < 300) {
                sample.append(' ').append(e.getKey()).append(':').append(e.getValue().type())
                        .append("->r").append(e.getValue().destId());
            }
        }
        StringBuilder kinds = new StringBuilder();
        for (Map.Entry<MailboxLifecycle.Terminal, AtomicLong> k : terminalByKind.entrySet()) {
            if (k.getValue().get() != 0) kinds.append(' ').append(k.getKey()).append('=').append(k.getValue().get());
        }
        long accepted = sentTotal.get(), applied = appliedTotal.get(), terminal = terminalTotal.get(), live = inFlight.size();
        long handedOff = handedOffTotal.get();
        return "ACCOUNTING accepted=" + accepted + " applied=" + applied + " explicitTerminal=" + terminal
                + " handedOff=" + handedOff
                + " stillInFlight=" + live + " reachableInTransport=" + (live - unaccounted)
                + " unaccounted=" + unaccounted + " balance=" + (accepted - applied - terminal - handedOff - live)
                + " duplicates=" + duplicateTotal.get() + " misrouted=" + misroutedTotal.get()
                + " orphaned=" + orphanedTotal.get() + " redirected=" + redirectedTotal.get()
                + " terminals{" + (kinds.length() == 0 ? "" : kinds.substring(1)) + "}"
                + (unaccounted > 0 ? " UNACCOUNTED_SAMPLE[" + sample.toString().trim() + "]" : "");
    }

    static long unaccounted(java.util.Set<Long> reachable) {
        long n = 0;
        for (Long id : inFlight.keySet()) if (!reachable.contains(id)) n++;
        return n;
    }

    static boolean inFlightContains(long auditId) {
        return inFlight.containsKey(auditId);
    }

    static boolean isAppliedOrClosed(long auditId) {
        return ENABLED && !inFlight.containsKey(auditId);
    }

    /** One-line summary for end-of-soak reporting (e.g. via an RCON debug command). */
    public static String summary() {
        return String.format("sent=%d applied=%d pending=%d duplicates=%d misrouted=%d",
                sentTotal.get(), appliedTotal.get(), inFlight.size(), duplicateTotal.get(), misroutedTotal.get());
    }

    /** Logs every entry pending longer than {@code staleThresholdNanos} — a message sent
     *  but never applied within a generous window is the mechanical definition of "lost"
     *  this audit can detect (as opposed to duplicate/misrouted, which are detected at
     *  apply time). Call periodically from a diagnostic command, not a hot path. */
    public static int logStaleEntries(long staleThresholdNanos) {
        if (!ENABLED) return 0;
        long now = System.nanoTime();
        int stale = 0;
        for (Map.Entry<Long, Entry> e : inFlight.entrySet()) {
            if (now - e.getValue().sentNanos() > staleThresholdNanos) {
                stale++;
                LOGGER.warn("Mailbox audit: message {} (type={}, region {} -> {}) pending {}ms — possible loss",
                        e.getKey(), e.getValue().type(), e.getValue().sourceId(), e.getValue().destId(),
                        (now - e.getValue().sentNanos()) / 1_000_000);
            }
        }
        return stale;
    }

    /** Resets all counters and in-flight tracking — call between independent test runs
     *  (e.g. barrier-engine baseline vs. a later free-running run) so results don't mix. */
    public static void reset() {
        sentTotal.set(0);
        appliedTotal.set(0);
        duplicateTotal.set(0);
        misroutedTotal.set(0);
        orphanedTotal.set(0);
        redirectedTotal.set(0);
        terminalTotal.set(0);
        handedOffTotal.set(0);
        terminalByKind.values().forEach(a -> a.set(0));
        inFlight.clear();
    }
}
