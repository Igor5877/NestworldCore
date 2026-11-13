package net.nestworld.region;

/** Per-position bookkeeping only. Never holds its monitor across world/mod code. */
final class BlockEntityDomainHandoffGate {
    private int nativeRunning;
    private boolean ownerRunning;
    private boolean closed;

    synchronized boolean enterNative() {
        if (closed || ownerRunning || nativeRunning != 0) return false;
        nativeRunning++;
        return true;
    }

    synchronized void leaveNative() {
        if (nativeRunning <= 0) throw new IllegalStateException("native handoff gate underflow");
        nativeRunning--;
    }

    synchronized boolean enterOwner() {
        if (closed || ownerRunning || nativeRunning != 0) return false;
        ownerRunning = true;
        return true;
    }

    synchronized void leaveOwner() {
        if (!ownerRunning) throw new IllegalStateException("owner handoff gate underflow");
        ownerRunning = false;
    }

    synchronized boolean idle() { return nativeRunning == 0 && !ownerRunning; }
    /** Shutdown closes admission but must still allow a quiescent pin-release transition. */
    synchronized boolean enterReleaseOwner() {
        if (ownerRunning || nativeRunning != 0) return false;
        ownerRunning = true;
        return true;
    }
    synchronized int nativeRunning() { return nativeRunning; }
    synchronized boolean ownerRunning() { return ownerRunning; }
    synchronized boolean closed() { return closed; }
    synchronized void close() { closed = true; }
}
