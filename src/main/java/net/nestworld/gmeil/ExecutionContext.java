package net.nestworld.gmeil;

/** Immutable causal metadata; future mailbox envelopes can carry this record across threads. */
public record ExecutionContext(long taskId, long parentTaskId, long tick, String phase,
                               ExecutionKey key, ExecutionPolicy policy, int regionId,
                               String threadName, long tokenId, long leaseId) {
    public static final long NONE = -1L;
    public static final int NO_REGION = -1;
}
