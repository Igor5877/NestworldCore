package net.nestworld.region.jfr;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import net.minecraftforge.fml.CrashReportCallables;

/**
 * Owner 2026-11-13: crash reports must carry enough evidence to find the culprit (the 2026-11-13 DistanceManager CME report
 * had only the MAIN stack). Registers two sections that Forge appends to EVERY crash report:
 * "NestWorld thread dump" (every live thread with its full stack and held/awaited locks) and "NestWorld state" (regions,
 * execution domains, DistanceManager pending sets). Read-only; costs nothing until a crash report is written.
 */
public final class CrashForensics {
    private CrashForensics() {}

    private static volatile boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        CrashReportCallables.registerCrashCallable("NestWorld thread dump", CrashForensics::threadDump);
        CrashReportCallables.registerCrashCallable("NestWorld state", CrashForensics::state);
    }

    static String threadDump() {
        StringBuilder sb = new StringBuilder("\n");
        try {
            ThreadInfo[] infos = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
            for (ThreadInfo ti : infos) {
                sb.append('"').append(ti.getThreadName()).append("\" id=").append(ti.getThreadId()).append(' ').append(ti.getThreadState());
                if (ti.getLockName() != null) sb.append(" on ").append(ti.getLockName());
                if (ti.getLockOwnerName() != null) sb.append(" owned by \"").append(ti.getLockOwnerName()).append('"');
                sb.append('\n');
                for (StackTraceElement e : ti.getStackTrace()) sb.append("\t\tat ").append(e).append('\n');   // full depth
                for (var l : ti.getLockedSynchronizers()) sb.append("\t\t- locked ").append(l).append('\n');
                sb.append('\n');
            }
        } catch (Throwable t) {
            sb.append("thread dump failed: ").append(t);
        }
        return sb.toString();
    }

    static String state() {
        StringBuilder sb = new StringBuilder("\n");
        try {
            if (net.nestworld.region.NestworldRegionSystem.isInitialised()) {
                sb.append(net.nestworld.region.ExecutionDomains.status()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("domains: ").append(t).append('\n');
        }
        try {
            var srv = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv != null) {
                for (var level : srv.getAllLevels()) {
                    var dm = level.getChunkSource().chunkMap.getDistanceManager();
                    sb.append(level.dimension().location()).append(": promotionPending player=").append(dm.nestworldPromotionPendingPlayer())
                            .append(" bulk=").append(dm.nestworldPromotionPendingBulk()).append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("distance managers: ").append(t).append('\n');
        }
        return sb.toString();
    }
}
