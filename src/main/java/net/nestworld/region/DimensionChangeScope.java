package net.nestworld.region;

/**
 * Marks "this thread is currently inside a cross-dimension teleport" (portal, End gateway,
 * deferred region-thread {@code changeDimension}).
 *
 * <p>Why it exists: with {@link NestworldTuning#GETCHUNK_ZERO_WAIT_MAIN_THREAD} the main thread
 * never waits for a chunk in {@code ServerChunkCache.getChunk(FULL, load=true)} — it returns a
 * {@code NestworldProxyLevelChunk} that reads as VOID_AIR and silently DROPS every
 * {@code setBlockState} until the real chunk is generated. That is fine for a mod's bulk scan, but
 * a dimension change reads and writes blocks in a freshly requested chunk immediately: the End
 * obsidian platform, the Nether/Overworld portal frame and the "is there a safe floor" checks all
 * hit the proxy. Result: the player spawns over the void and falls, the destination portal is
 * never built, and the player later ends up inside terrain that generated under their position.
 *
 * <p>While the scope is active {@code getChunk} waits for the real chunk instead (still bounded
 * by {@link NestworldTuning#GETCHUNK_WAIT_TIMEOUT_MS}, ignoring the per-tick burst budget), i.e.
 * exactly vanilla's behaviour for a handful of chunks per teleport.
 *
 * <p>Re-entrant (depth counter, per thread) and always paired enter/exit in try/finally.
 */
public final class DimensionChangeScope {
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private DimensionChangeScope() {}

    public static void enter() {
        DEPTH.get()[0]++;
    }

    public static void exit() {
        int[] d = DEPTH.get();
        if (d[0] > 0) d[0]--;
    }

    public static boolean isActive() {
        return DEPTH.get()[0] > 0;
    }
}
