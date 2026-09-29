package net.nestworld.region;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * API-1A.7 TEST-ONLY hooks (2026-09-29). NOT part of any public or Core-internal API surface
 * beyond this verification pass -- to be removed or promoted to a real testing framework after
 * the gate closes, per explicit instruction not to leave this as accidental production surface.
 *
 * <p>Zero cost when disabled: every method below is a single {@code volatile boolean} read
 * (guarded by {@link #ENABLED}, default {@code false}) followed by a null-check on an unarmed
 * latch -- no synchronization primitive is even touched unless a test has explicitly armed the
 * corresponding barrier. Production behavior is byte-for-byte unchanged with {@link #ENABLED}
 * left at its default.
 *
 * <p>Three barrier points, matching {@code EntityMutationDispatcher}'s real control flow:
 * <pre>
 * dispatch():      owner resolved -> [BARRIER A] -> enqueue
 * applyQueued():   [BARRIER B] -> reacquire -> ownership check -> [BARRIER C] -> apply
 * </pre>
 * A barrier pauses the PRODUCTION thread that reaches it until the TEST thread releases it --
 * this is what turns a probabilistic race (the baseline API-1A.7 pass's failed RCON-timed
 * attempts) into a deterministic one: the test controls exactly when the paused thread resumes,
 * and can mutate state (force ownership reassignment, remove the entity, etc.) in between.
 */
final class EntityMutationTestHooks {
    private EntityMutationTestHooks() {}

    /** Master switch. False (default) = every method below is a single boolean-read no-op. */
    static volatile boolean ENABLED = false;

    private static volatile CountDownLatch aHit, aRelease;
    private static volatile CountDownLatch bHit, bRelease;
    private static volatile CountDownLatch cHit, cRelease;

    // --- called by production code at each barrier point ---

    static void barrierA() { await(aHit, aRelease); }
    static void barrierB() { await(bHit, bRelease); }
    static void barrierC() { await(cHit, cRelease); }

    private static void await(CountDownLatch hit, CountDownLatch release) {
        if (!ENABLED || hit == null) return;
        hit.countDown();
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // --- test-side arming API ---

    static void armA() { aHit = new CountDownLatch(1); aRelease = new CountDownLatch(1); }
    static void armB() { bHit = new CountDownLatch(1); bRelease = new CountDownLatch(1); }
    static void armC() { cHit = new CountDownLatch(1); cRelease = new CountDownLatch(1); }

    static boolean awaitHitA(long seconds) throws InterruptedException { return aHit.await(seconds, TimeUnit.SECONDS); }
    static boolean awaitHitB(long seconds) throws InterruptedException { return bHit.await(seconds, TimeUnit.SECONDS); }
    static boolean awaitHitC(long seconds) throws InterruptedException { return cHit.await(seconds, TimeUnit.SECONDS); }

    static void releaseA() { aRelease.countDown(); }
    static void releaseB() { bRelease.countDown(); }
    static void releaseC() { cRelease.countDown(); }

    static void disarmAll() {
        aHit = aRelease = bHit = bRelease = cHit = cRelease = null;
    }
}
