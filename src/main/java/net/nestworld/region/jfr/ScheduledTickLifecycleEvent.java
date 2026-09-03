package net.nestworld.region.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Registered;

/**
 * Diagnostic-only (2026-09-03, "PROMOTE transition" investigation -- see project memory).
 * One lifecycle stage of a scheduled block/fluid tick's journey through NestworldCore's
 * transient handoffs. {@code eventType} is one of:
 *
 * <ul>
 *   <li>{@code POST} -- {@code WorldRegion#nestworldPostScheduledTicks}, main thread appends a
 *       batch to {@code scheduledTicks}.</li>
 *   <li>{@code POLL} -- {@code WorldRegion#nestworldPollScheduledTicks}, the region's own
 *       thread drains {@code scheduledTicks} (queueDepth is what was there BEFORE the drain,
 *       i.e. what's about to move to execution).</li>
 *   <li>{@code EXECUTE} -- {@code RegionThread#runWorkBudgeted}, immediately before running one
 *       drained entry's actual {@code Runnable}.</li>
 *   <li>{@code HANDOFF} -- {@code NestworldRegionSystem#nestworldEnqueueScheduleTick}, a region
 *       thread's own {@code scheduleTick()} call being redirected into
 *       {@code nestworldPendingMainThreadScheduleTicks} instead of going straight to vanilla's
 *       {@code LevelTicks} (queueDepth is that queue's size AFTER this enqueue).</li>
 * </ul>
 *
 * {@code position}/{@code tickType} are populated only where the call site can read them
 * without changing any existing method's signature or the scheduler's own data shapes (e.g.
 * {@link net.nestworld.region.ScheduledTickHandoffMetrics.Tagged}'s already-public {@code pos}/
 * {@code kind} fields) -- per the investigation's explicit instruction not to introduce a
 * correlation ID if doing so would require a scheduler semantics change, HANDOFF's queueDepth
 * alone (no position) is the fallback where that's not cheaply available.
 */
@Name("nestworld.ScheduledTickLifecycle")
@Label("NestWorld Scheduled Tick Lifecycle")
@Category({"NestWorld", "Region", "Promote"})
@Description("One stage (POST/POLL/EXECUTE/HANDOFF) of a scheduled block/fluid tick's path through NestworldCore's transient scheduling queues.")
// See RegionPromoteEvent's javadoc for why this is explicitly registered rather than relying
// on JFR's automatic class-load-time registration.
@Registered(false)
public class ScheduledTickLifecycleEvent extends Event {

    static {
        FlightRecorder.register(ScheduledTickLifecycleEvent.class);
    }

    @Label("Region ID")
    public int regionId;

    @Label("Event Type")
    @Description("POST, POLL, EXECUTE, or HANDOFF")
    public String eventType;

    @Label("Queue Depth")
    public int queueDepth;

    @Label("Thread Name")
    public String threadName;

    @Label("Thread Id")
    public long threadId;

    @Label("Position")
    public String position;

    @Label("Tick Type")
    public String tickType;
}
