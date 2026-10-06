package net.nestworld.region;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.nestworld.modapi.entity.NestWorldEntities;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * PERF-1B TEST-ONLY (docs/contracts/PERF-1B-spec.md §6 rule 4): deterministic proof of the D1
 * accounting. It drives two setVelocity calls through the real public entry
 * ({@link NestWorldEntities#setVelocity}, then the installed backend, where
 * {@link VelocityTelemetry#onAccepted} runs) and forces one APPLIED and one STALE_INTENT_DROPPED
 * outcome with the {@link EntityMutationTestHooks} barrier. The interleaving is the one in
 * {@code Api1A7DeterministicTests.runOrderingTest}:
 * <ol>
 *   <li>V1 is submitted (intent version n) and parks at barrier A after owner resolution;</li>
 *   <li>the target migrates A to B;</li>
 *   <li>V2 is submitted (intent version n+1) and applies on B;</li>
 *   <li>V1 is released, reroutes to B and loses arbitration to V2: STALE_INTENT_DROPPED.</li>
 * </ol>
 * The API-1A fixtures dispatch directly and bypass the backend, so they never count as
 * accepted; this one does. Expected deltas: accepted 2, applied 1, stale 1, other terminals 0,
 * unaccounted 0. Requires {@code -Dnestworld.apiStats=true} and no other setVelocity traffic
 * (the PERF-1B consumer paused). Observes only; changes no production behaviour.
 */
final class Perf1bD1AccountingTest {
    private Perf1bD1AccountingTest() {}

    private static long terminals() {
        return VelocityTelemetry.applied.sum() + VelocityTelemetry.staleDropped.sum()
                + VelocityTelemetry.targetRemoved.sum() + VelocityTelemetry.targetNotFound.sum()
                + VelocityTelemetry.rerouteLimitDropped.sum() + VelocityTelemetry.redeliveryLimitDropped.sum()
                + VelocityTelemetry.otherTerminal.sum();
    }

    private static boolean await(LongSupplier value, long target, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (value.getAsLong() < target) {
            if (System.currentTimeMillis() > end) return false;
            Thread.sleep(20);
        }
        return true;
    }

    static List<String> run(NestworldRegionSystem sys) throws Exception {
        List<String> out = new ArrayList<>();
        out.add("=== PERF-1B D1: deterministic accounting through the public setVelocity entry ===");
        if (!VelocityTelemetry.ENABLED) {
            out.add("RESULT=FAIL reason=apiStats off (start the server with -Dnestworld.apiStats=true)");
            return out;
        }
        ServerLevel level = sys.getOverworld();
        WorldRegion[] regions = Api1A7DeterministicTests.ensureTwoRegions(sys);
        Api1A7DeterministicTests.ensureAnchors(regions, level);
        WorldRegion a = regions[0], b = regions[1];
        Entity entity = Api1A7DeterministicTests.spawnTestEntity(level);
        Api1A7DeterministicTests.assignSole(regions, a, entity.getUUID());

        long acc0 = VelocityTelemetry.accepted.sum(), app0 = VelocityTelemetry.applied.sum();
        long stale0 = VelocityTelemetry.staleDropped.sum(), term0 = terminals();
        long fast0 = VelocityTelemetry.pathOwnerFast.sum(), cross0 = VelocityTelemetry.pathMailboxCross.sum();
        long main0 = VelocityTelemetry.pathMailboxFromMain.sum(), rer0 = VelocityTelemetry.reroutes.sum();

        Vec3 v1 = new Vec3(1.0, 0.0, 0.0), v2 = new Vec3(9.0, 0.0, 0.0);
        EntityMutationTestHooks.disarmAll();
        EntityMutationTestHooks.ENABLED = true;
        EntityMutationTestHooks.armA();
        Thread v1Thread = new Thread(() -> NestWorldEntities.setVelocity(entity, v1), "PERF1B-D1-V1");
        v1Thread.start();
        boolean hitA = EntityMutationTestHooks.awaitHitA(10);
        out.add("V1 submitted first, parked at barrier A (owner " + a.getId() + " resolved): hit=" + hitA);

        Api1A7DeterministicTests.assignSole(regions, b, entity.getUUID());
        out.add("target migrated " + a.getId() + " -> " + b.getId() + " while V1 is parked");

        // Same scoping as runOrderingTest: barrier A is global, so V2 must not hit it.
        EntityMutationTestHooks.ENABLED = false;
        NestWorldEntities.setVelocity(entity, v2);
        EntityMutationTestHooks.ENABLED = true;
        boolean v2Applied = await(VelocityTelemetry.applied::sum, app0 + 1, 5000);
        out.add("V2 submitted second and applied on " + b.getId() + ": " + v2Applied);

        EntityMutationTestHooks.releaseA();
        v1Thread.join(5000);
        EntityMutationTestHooks.ENABLED = false;
        boolean drained = await(Perf1bD1AccountingTest::terminals, term0 + 2, 10000);
        Thread.sleep(500); // a late duplicate terminal would show up here

        long dAcc = VelocityTelemetry.accepted.sum() - acc0;
        long dApp = VelocityTelemetry.applied.sum() - app0;
        long dStale = VelocityTelemetry.staleDropped.sum() - stale0;
        long dTerm = terminals() - term0;
        long dOther = dTerm - dApp - dStale;
        long unaccounted = dAcc - dTerm;
        out.add("deltas accepted=" + dAcc + " applied=" + dApp + " stale_dropped=" + dStale
                + " other_terminals=" + dOther + " unaccounted=" + unaccounted + " drained=" + drained);
        out.add("path deltas owner_fast=" + (VelocityTelemetry.pathOwnerFast.sum() - fast0)
                + " mailbox_cross=" + (VelocityTelemetry.pathMailboxCross.sum() - cross0)
                + " mailbox_from_main=" + (VelocityTelemetry.pathMailboxFromMain.sum() - main0)
                + " reroutes=" + (VelocityTelemetry.reroutes.sum() - rer0));
        boolean pass = hitA && v2Applied && drained
                && dAcc == 2 && dApp == 1 && dStale == 1 && dOther == 0 && unaccounted == 0;
        out.add("RESULT=" + (pass ? "PASS" : "FAIL")
                + " (expected accepted=2 applied=1 stale_dropped=1 other_terminals=0 unaccounted=0)");
        entity.discard();
        return out;
    }
}
