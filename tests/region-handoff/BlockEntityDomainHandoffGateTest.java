package net.nestworld.region;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Run with assertions enabled; no Minecraft server, mocks, or timing sleeps required. */
public final class BlockEntityDomainHandoffGateTest {
    public static void main(String[] args) throws Exception {
        BlockEntityDomainHandoffGate gate = new BlockEntityDomainHandoffGate();
        check(gate.enterNative(), "first native enters");
        check(!gate.enterNative(), "same-position native callbacks are exclusive");
        check(!gate.enterOwner(), "handoff refuses while native callback is running");
        gate.leaveNative();
        check(gate.enterOwner(), "source callback enters at quiescence");
        check(!gate.enterNative(), "precollected native callback fenced during owner callback");
        check(!gate.enterOwner(), "concurrent handoff must wait asynchronously");
        check(!gate.idle(), "close must not release a pin from inside owner callback");
        gate.leaveOwner();
        check(gate.idle(), "after-return completion publishes quiescence");
        check(gate.enterNative(), "native resumes after source callback");
        gate.close();
        check(!gate.enterNative() && !gate.enterOwner(), "shutdown refuses new entries");
        check(!gate.enterReleaseOwner(), "shutdown cannot unpin while native work is active");
        gate.leaveNative();
        check(gate.idle(), "in-flight native callback may finish after shutdown");
        check(gate.enterReleaseOwner(), "closed admission permits quiescent cleanup");
        check(!gate.enterNative(), "cleanup fences new native callbacks");
        gate.leaveOwner();

        BlockEntityDomainHandoffToken removed = new BlockEntityDomainHandoffToken();
        check(removed.cancel() && !removed.start(), "unload cancels one pending invocation");
        check(!removed.cancel(), "duplicate unload callback cannot cancel twice");
        BlockEntityDomainHandoffToken rerouted = new BlockEntityDomainHandoffToken();
        check(rerouted.pending() && rerouted.pending(), "rerouting does not consume invocation");
        check(rerouted.start() && !rerouted.start(), "duplicate delivery executes once");
        check(!rerouted.cancel(), "running invocation cannot be retroactively cancelled");
        rerouted.finish();
        check(!rerouted.pending() && !rerouted.start() && !rerouted.cancel(), "completed invocation stays terminal");

        // Both transitions start together. They can never hold native and owner claims simultaneously.
        for (int n = 0; n < 2_000; n++) {
            BlockEntityDomainHandoffGate raced = new BlockEntityDomainHandoffGate();
            CountDownLatch start = new CountDownLatch(1), claimed = new CountDownLatch(2), finish = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger(), errors = new AtomicInteger();
            Thread nativeThread = new Thread(() -> race(start, claimed, finish, raced, false, winners, errors));
            Thread ownerThread = new Thread(() -> race(start, claimed, finish, raced, true, winners, errors));
            nativeThread.start(); ownerThread.start(); start.countDown(); claimed.await();
            int count = winners.get();
            finish.countDown(); nativeThread.join(); ownerThread.join();
            check(count == 1, "native/owner claim race must have exactly one winner");
            check(errors.get() == 0 && raced.idle(), "race claims balanced");
        }
        System.out.println("BlockEntityDomainHandoffGateTest PASS (2000 simultaneous native/owner races)");
    }

    private static void race(CountDownLatch start, CountDownLatch claimed, CountDownLatch finish,
                             BlockEntityDomainHandoffGate gate, boolean owner,
                             AtomicInteger winners, AtomicInteger errors) {
        boolean entered = false;
        try {
            start.await(); entered = owner ? gate.enterOwner() : gate.enterNative();
            if (entered) winners.incrementAndGet();
            claimed.countDown(); finish.await();
            if (entered) { if (owner) gate.leaveOwner(); else gate.leaveNative(); }
        } catch (Throwable e) { errors.incrementAndGet(); claimed.countDown(); }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
