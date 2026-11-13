package net.nestworld.inspector;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.List;

/**
 * Read-only reflective view of NestWorld Core (net.nestworld.region.*). No compile dependency on Core. Every handle
 * used here is public in both the current tree and the older live build (checked with javap). Any lookup failure ⇒
 * {@link #available()} false ⇒ the inspector sends nothing. Any invocation failure ⇒ the caller treats the value as
 * unknown. Only cheap, thread-safe queries are used (map/set lookups, region list scan, volatile/atomic reads).
 */
public final class CoreBridge {
    private static final Logger LOG = LogUtils.getLogger();

    private static final boolean AVAILABLE;
    private static final String MISSING;
    private static MethodHandle IS_INIT, GET_SYS, IS_MANAGED, GET_DR, GET_PINS;
    private static MethodHandle IS_BE_PINNED, IS_POS_PINNED, IS_CASCADE_SAFE, MOD_LIST, BE_LIST;
    private static MethodHandle GET_GRID, REGION_FOR_CHUNK, ALL_REGIONS;
    private static MethodHandle REGION_ID, MIN_CX, MIN_CZ, MAX_CX, MAX_CZ, IN_BAND, AVG_MS;
    private static MethodHandle RESOLVE, DOMAIN_REGION;
    /** Optional (absent in older cores). */
    private static MethodHandle GROUP_OF;

    static {
        String missing = null;
        try {
            ClassLoader cl = CoreBridge.class.getClassLoader();
            MethodHandles.Lookup lk = MethodHandles.publicLookup();
            Class<?> sys = Class.forName("net.nestworld.region.NestworldRegionSystem", false, cl);
            Class<?> pins = Class.forName("net.nestworld.region.NestworldPins", false, cl);
            Class<?> dr = Class.forName("net.nestworld.region.NestworldDimensionRegion", false, cl);
            Class<?> grid = Class.forName("net.nestworld.region.WorldGrid", false, cl);
            Class<?> wr = Class.forName("net.nestworld.region.WorldRegion", false, cl);
            Class<?> bed = Class.forName("net.nestworld.region.BlockEntityExecutionDomain", false, cl);
            Class<?> dom = Class.forName("net.nestworld.region.BlockEntityExecutionDomain$Domain", false, cl);
            IS_INIT = lk.findStatic(sys, "isInitialised", MethodType.methodType(boolean.class));
            GET_SYS = lk.findStatic(sys, "get", MethodType.methodType(sys));
            IS_MANAGED = lk.findVirtual(sys, "isManagedLevel", MethodType.methodType(boolean.class, Level.class));
            GET_DR = lk.findVirtual(sys, "getDimensionRegion", MethodType.methodType(dr, Level.class));
            GET_PINS = lk.findVirtual(sys, "getPins", MethodType.methodType(pins));
            IS_BE_PINNED = lk.findVirtual(pins, "isBePinned", MethodType.methodType(boolean.class, String.class));
            try { // CORE-PINZONE-001: absent in older cores (e.g. 47.4.401)
                IS_POS_PINNED = lk.findVirtual(pins, "isBePositionPinned",
                        MethodType.methodType(boolean.class, ResourceKey.class, long.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                IS_POS_PINNED = null;
            }
            IS_CASCADE_SAFE = lk.findVirtual(pins, "isCascadeSafeBe", MethodType.methodType(boolean.class, String.class));
            MOD_LIST = lk.findVirtual(pins, "modList", MethodType.methodType(List.class));
            BE_LIST = lk.findVirtual(pins, "beList", MethodType.methodType(List.class));
            GET_GRID = lk.findVirtual(dr, "getGrid", MethodType.methodType(grid));
            REGION_FOR_CHUNK = lk.findVirtual(grid, "getRegionForChunk", MethodType.methodType(wr, int.class, int.class));
            ALL_REGIONS = lk.findVirtual(grid, "getAllRegions", MethodType.methodType(Collection.class));
            REGION_ID = lk.findVirtual(wr, "getId", MethodType.methodType(int.class));
            MIN_CX = lk.findVirtual(wr, "getMinChunkX", MethodType.methodType(int.class));
            MIN_CZ = lk.findVirtual(wr, "getMinChunkZ", MethodType.methodType(int.class));
            MAX_CX = lk.findVirtual(wr, "getMaxChunkX", MethodType.methodType(int.class));
            MAX_CZ = lk.findVirtual(wr, "getMaxChunkZ", MethodType.methodType(int.class));
            IN_BAND = lk.findVirtual(wr, "isInBorderBand", MethodType.methodType(boolean.class, int.class, int.class));
            AVG_MS = lk.findVirtual(wr, "getAvgTickMs", MethodType.methodType(double.class));
            RESOLVE = lk.findStatic(bed, "resolveBlockEntityExecutionDomain",
                    MethodType.methodType(dom, ServerLevel.class, BlockPos.class, String.class));
            DOMAIN_REGION = lk.findVirtual(dom, "region", MethodType.methodType(wr));
            try {
                Class<?> groups = Class.forName("net.nestworld.region.BlockEntityGroups", false, cl);
                GROUP_OF = lk.findStatic(groups, "groupOf", MethodType.methodType(long[].class, ServerLevel.class, BlockPos.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                GROUP_OF = null;
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            missing = e.toString();
        }
        MISSING = missing;
        AVAILABLE = missing == null;
        if (AVAILABLE) {
            LOG.info("[nestworld_inspector] NestWorld Core found (BlockEntityGroups {}, position pins {})",
                    GROUP_OF != null ? "present" : "absent", IS_POS_PINNED != null ? "present" : "absent");
        } else {
            LOG.info("[nestworld_inspector] no NestWorld Core ({}); inspector inactive", MISSING);
        }
    }

    private CoreBridge() {}

    /** Hot per-block-entity handles as static finals (constant-folded by the JIT); initialised on first use, after
     *  the outer lookups, and only reached when {@link #AVAILABLE}. */
    private static final class F {
        static final MethodHandle RESOLVE = CoreBridge.RESOLVE, DOMAIN_REGION = CoreBridge.DOMAIN_REGION,
                REGION_ID = CoreBridge.REGION_ID, IN_BAND = CoreBridge.IN_BAND, REGION_FOR_CHUNK = CoreBridge.REGION_FOR_CHUNK,
                IS_POS_PINNED = CoreBridge.IS_POS_PINNED, GROUP_OF = CoreBridge.GROUP_OF;
    }

    public static boolean available() { return AVAILABLE; }

    public static boolean groupsAvailable() { return GROUP_OF != null; }

    public static boolean positionPinsAvailable() { return IS_POS_PINNED != null; }

    /** The NestworldRegionSystem instance if Core is present and initialised, else null. */
    public static Object system() {
        if (!AVAILABLE) return null;
        try {
            if (!(boolean) IS_INIT.invoke()) return null;
            return GET_SYS.invoke();
        } catch (Throwable t) {
            return null;
        }
    }

    /** NestworldDimensionRegion for a managed level, or null. */
    public static Object dimensionRegion(Object sys, ServerLevel level) {
        try {
            if (!(boolean) IS_MANAGED.invoke(sys, (Level) level)) return null;
            return GET_DR.invoke(sys, (Level) level);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object pins(Object sys) {
        try { return GET_PINS.invoke(sys); } catch (Throwable t) { return null; }
    }

    public static Object grid(Object dimRegion) {
        try { return GET_GRID.invoke(dimRegion); } catch (Throwable t) { return null; }
    }

    public static Object regionForChunk(Object grid, int cx, int cz) {
        try { return F.REGION_FOR_CHUNK.invoke(grid, cx, cz); } catch (Throwable t) { return null; }
    }

    @SuppressWarnings("unchecked")
    public static Collection<Object> allRegions(Object grid) {
        try { return (Collection<Object>) ALL_REGIONS.invoke(grid); } catch (Throwable t) { return List.of(); }
    }

    public static int regionId(Object region) {
        try { return (int) F.REGION_ID.invoke(region); } catch (Throwable t) { return Integer.MIN_VALUE; }
    }

    /** {minCX, minCZ, maxCX, maxCZ} or null. */
    public static int[] bounds(Object region) {
        try {
            return new int[]{(int) MIN_CX.invoke(region), (int) MIN_CZ.invoke(region),
                    (int) MAX_CX.invoke(region), (int) MAX_CZ.invoke(region)};
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean inBorderBand(Object region, int cx, int cz) {
        try { return (boolean) F.IN_BAND.invoke(region, cx, cz); } catch (Throwable t) { return false; }
    }

    public static double avgTickMs(Object region) {
        try { return (double) AVG_MS.invoke(region); } catch (Throwable t) { return -1; }
    }

    /** Core's authoritative execution-domain region for a block entity: null = MAIN; throws on failure. */
    public static Object resolveDomainRegion(ServerLevel level, BlockPos pos, String typeId) throws Throwable {
        Object d = F.RESOLVE.invoke(level, pos, typeId);
        return d == null ? null : F.DOMAIN_REGION.invoke(d);
    }

    public static boolean isBePinned(Object pins, String typeId) {
        try { return (boolean) IS_BE_PINNED.invoke(pins, typeId); } catch (Throwable t) { return false; }
    }

    public static boolean isPositionPinned(Object pins, ResourceKey<Level> dim, long pos) {
        if (IS_POS_PINNED == null) return false;
        try { return (boolean) F.IS_POS_PINNED.invoke(pins, dim, pos); } catch (Throwable t) { return false; }
    }

    public static boolean isCascadeSafe(Object pins, String typeId) {
        try { return (boolean) IS_CASCADE_SAFE.invoke(pins, typeId); } catch (Throwable t) { return false; }
    }

    @SuppressWarnings("unchecked")
    public static List<String> modList(Object pins) {
        try { return (List<String>) MOD_LIST.invoke(pins); } catch (Throwable t) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    public static List<String> beList(Object pins) {
        try { return (List<String>) BE_LIST.invoke(pins); } catch (Throwable t) { return List.of(); }
    }

    /** Group member count containing pos, 0 if none or groups unsupported. */
    public static int groupSize(ServerLevel level, BlockPos pos) {
        if (GROUP_OF == null) return 0;
        try {
            long[] g = (long[]) F.GROUP_OF.invoke(level, pos);
            return g == null ? 0 : g.length;
        } catch (Throwable t) {
            return 0;
        }
    }
}
