package net.nestworld.module;

/** Per-connection, per-tick send budget. All packets and acknowledgements are preserved.
 * The first packet may exceed the byte budget, to guarantee progress. */
public record LoginBudget(int packets, int bytes) {
    public static final LoginBudget VANILLA = new LoginBudget(1, Integer.MAX_VALUE);

    public LoginBudget {
        if (packets < 1 || packets > 256) throw new IllegalArgumentException("packets must be 1..256");
        if (bytes < 1) throw new IllegalArgumentException("bytes must be positive");
    }

    public boolean permits(int sentPackets, long sentBytes, int nextBytes) {
        return sentPackets < packets && (sentPackets == 0 || sentBytes + nextBytes <= bytes);
    }

    public LoginBudget intersect(LoginBudget other) {
        return new LoginBudget(Math.min(packets, other.packets), Math.min(bytes, other.bytes));
    }
}
