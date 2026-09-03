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
 * Fires around {@code RegionSplitManager#promoteFreeRunning}, once with {@code phase=BEGIN}
 * immediately before the region's execution mode actually flips, once with
 * {@code phase=COMMIT} immediately after. Every field is a read-only snapshot -- taking one
 * never drains, polls, blocks, or otherwise mutates anything it reports on. No scheduling,
 * ordering, or timing behavior is changed by this event existing; when the JFR event type is
 * disabled (the default outside an active recording), emitting it is a no-op check, not a
 * real snapshot -- see the {@code isEnabled()} guard at every call site.
 */
@Name("nestworld.RegionPromote")
@Label("NestWorld Region Promote")
@Category({"NestWorld", "Region", "Promote"})
@Description("A WorldRegion's execution mode transition around promotion to free-running, with a read-only snapshot of every scheduled-tick transient queue depth at that instant.")
// Explicit registration (2026-09-03, per project owner's review): @Registered(false) opts out
// of JFR's automatic class-load-time registration, whose interaction with Forge's own
// class-transforming loader is what this event type's total no-op silence (0 recorded, 0 in
// JFR metadata, despite confirmed-executing bytecode) is suspected to be caused by -- see
// project memory. FlightRecorder.register() below runs it explicitly and deterministically
// instead, the first time this class is touched.
@Registered(false)
public class RegionPromoteEvent extends Event {

    static {
        FlightRecorder.register(RegionPromoteEvent.class);
    }

    @Label("Region ID")
    public int regionId;

    @Label("Phase")
    @Description("BEGIN (captured immediately before the mode flip) or COMMIT (immediately after)")
    public String phase;

    @Label("Old Execution Mode")
    public String oldExecutionMode;

    @Label("New Execution Mode")
    public String newExecutionMode;

    @Label("Thread Name")
    public String threadName;

    @Label("Thread Id")
    public long threadId;

    @Label("WorldRegion#scheduledTicks depth")
    public int scheduledTicksDepth;

    @Label("RegionThread carriedWork depth")
    public int carriedWorkDepth;

    @Label("NestworldRegionSystem#nestworldPendingMainThreadScheduleTicks depth")
    public int pendingMainThreadScheduleTicksDepth;
}
