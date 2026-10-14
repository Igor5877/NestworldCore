package net.nestworld.compat.ae2.kernel;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock of the AE2 concurrency domain (AE2-COMPAT-DESIGN-001.1 §3): one per AE2 grid, attached to
 * its {@code EnergyService}. A domain is held when every member grid's lock is held, acquired in
 * ascending {@link #serial()} order. The serial comes from this class's own atomic counter, not from
 * AE2's non-atomic {@code Grid.nextSerial}.
 */
public final class DomainLock implements Comparable<DomainLock> {
    private static final AtomicLong SERIALS = new AtomicLong();

    private final long serial = SERIALS.incrementAndGet();
    final ReentrantLock lock = new ReentrantLock();

    public long serial() {
        return serial;
    }

    public boolean isHeldByCurrentThread() {
        return lock.isHeldByCurrentThread();
    }

    @Override
    public int compareTo(DomainLock o) {
        return Long.compare(serial, o.serial);
    }

    @Override
    public String toString() {
        return "D" + serial;
    }
}
