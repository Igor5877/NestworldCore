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
 * Fires once per {@code RegionThread.runFreeRunningTick()} iteration (and once per
 * blocking-dispatch {@code runOneTick} call, for the non-free-running side), reporting the
 * region's execution mode and the scheduled-tick transient-queue depths immediately before and
 * immediately after that iteration's own work. No behavior change: this only reads queue
 * sizes that already exist for other reasons and writes them into a JFR event buffer; nothing
 * here drains, polls, or blocks anything, and the whole iteration's actual work runs exactly
 * as it would with this event type disabled.
 */
@Name("nestworld.RegionTick")
@Label("NestWorld Region Tick")
@Category({"NestWorld", "Region", "Promote"})
@Description("One RegionThread tick iteration (free-running or dispatched), with before/after scheduled-tick transient-queue depths -- used to see exactly what a region's own thread did on the tick immediately before/after a promote/demote transition.")
// See RegionPromoteEvent's javadoc for why this is explicitly registered rather than relying
// on JFR's automatic class-load-time registration.
@Registered(false)
public class RegionTickEvent extends Event {

    static {
        FlightRecorder.register(RegionTickEvent.class);
    }

    @Label("Region ID")
    public int regionId;

    @Label("Execution Mode")
    public String executionMode;

    @Label("Thread Name")
    public String threadName;

    @Label("Thread Id")
    public long threadId;

    @Label("scheduledTicks depth before")
    public int scheduledTicksDepthBefore;

    @Label("scheduledTicks depth after")
    public int scheduledTicksDepthAfter;

    @Label("carriedWork depth before")
    public int carriedWorkDepthBefore;

    @Label("carriedWork depth after")
    public int carriedWorkDepthAfter;
}
