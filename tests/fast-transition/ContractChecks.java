package net.nestworld.module;

/** Dependency-free contract checks. Run with tests/fast-transition/check.sh. */
public final class ContractChecks {
    private static int checks;
    public static void main(String[] args) {
        // A recipe retaining its ID/type but changing its serialized ingredients must invalidate.
        check(!hash("recipes", "same-id", "same-type", "iron->gold")
                .equals(hash("recipes", "same-id", "same-type", "copper->gold")), "changed recipe body");
        check(hash("recipes", "a", "b").equals(hash("recipes", "a", "b")), "stable across instances");
        check(!hash("recipes", "ab", "c").equals(hash("recipes", "a", "bc")), "unambiguous field framing");
        check(!hash("recipes", "a").equals(hash("registries", "a")), "domain separation");
        check(hash("recipes", "a").length() == 64, "full SHA-256");

        LoginBudget batch = new LoginBudget(32, 1024);
        check(batch.permits(0, 0, 2048), "oversized first payload still progresses");
        check(!batch.permits(1, 2048, 1), "oversized packet exhausts byte budget");
        check(batch.permits(1, 512, 512), "exact byte boundary");
        check(!batch.permits(1, 512, 513), "byte budget enforced");
        check(!batch.permits(32, 0, 0), "zero-byte payloads still respect packet limit");
        check(!LoginBudget.VANILLA.permits(1, 0, 1), "default one packet per tick");
        check(batch.intersect(new LoginBudget(8, 4096)).equals(new LoginBudget(8, 1024)), "most restrictive policy wins");
        invalid(() -> new LoginBudget(0, 1));
        invalid(() -> new LoginBudget(257, 1));
        invalid(() -> new LoginBudget(1, 0));
        System.out.println("PASS: " + checks + " Fast Transition contract checks");
    }
    private static String hash(String domain, String... fields) {
        ContentGeneration generation = new ContentGeneration(domain);
        for (String field : fields) generation.field(field);
        return generation.finish();
    }
    private static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("invalid budget accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
}
