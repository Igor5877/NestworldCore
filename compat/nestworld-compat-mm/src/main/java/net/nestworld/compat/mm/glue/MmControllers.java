package net.nestworld.compat.mm.glue;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MM-GROUP-DOMAIN-001. A MasterfulMachinery controller reads and writes its ports' unsynchronized handlers from its own
 * tick, so the controller and every port must execute on ONE thread.
 *
 * <p>0.2.0 (owner goal: reduce MAIN load). Each formed multiblock is registered as a group in Core
 * ({@code BlockEntityGroups}, reflective). The group stays where its geometry puts it (normally one REGION) and is
 * position-pinned to MAIN (CORE-PINZONE-001) only when it would otherwise be split:
 * <ul>
 *   <li>its members' geometric domains differ (region border, band, split), or</li>
 *   <li>someone else holds a position pin on a member (an automation host reaching it from MAIN) while the group's
 *       geometric domain is a region.</li>
 * </ul>
 * Release back to the region needs {@link #RELEASE_SCANS} consecutive clean scans (no flapping). Pin the new member
 * set before releasing the old one. Without Core's group registry the addon behaves as 0.1.0 (split ⇒ MAIN, kept).
 *
 * <p>No compile dependency on Core or any other addon. Exact-version gated on MasterfulMachinery.
 */
public final class MmControllers {
    public static final Logger LOG = LogManager.getLogger("NestWorld/CompatMM");
    static final String VERSION = "0.2.0";
    static final String MM_VERSION = "3-1.20.1-0.1.35.10";
    static final String CONTROLLER_CLASS = "io/ticticboom/mods/mm/controller/machine/register/MachineControllerBlockEntity.class";
    static final String CONTROLLER_SHA256 = "7b4354e5c83f5ce09b0ea159c898b0ea47b8b4165d0c1e98ae39f4efddcddd57";
    static final int INTERVAL = Math.max(1, Integer.getInteger("nestworld.compatMm.scanInterval", 20));
    static final int RELEASE_SCANS = Math.max(1, Integer.getInteger("nestworld.compatMm.releaseScans", 3));

    private static final MethodHandle RESOLVE, PIN, UNPIN, REGISTER, UNREGISTER, GEOMETRIC, PIN_REFS, REGISTER_SAFE;
    /** Explicit adapter policy (TZ AE2-GRID-DOMAIN-001 §3″.1 class 1′): a complete MM multiblock may be owned whole by a
     *  dedicated domain. Off by default; enabled only for the validation fixture until it passes. */
    static final boolean WHOLE_GROUP_CLAIM_SAFE = Boolean.getBoolean("nestworld.compatMm.wholeGroupClaimSafe");
    private static final Object MAIN_DOMAIN;
    static {
        MethodHandle r = null, p = null, u = null, reg = null, unreg = null, geo = null, refs = null, regSafe = null;
        Object main = null;
        ClassLoader cl = MmControllers.class.getClassLoader();
        var lookup = MethodHandles.publicLookup();
        try {
            Class<?> d = Class.forName("net.nestworld.region.BlockEntityExecutionDomain", true, cl);
            Class<?> domain = Class.forName("net.nestworld.region.BlockEntityExecutionDomain$Domain", true, cl);
            r = lookup.findStatic(d, "resolveBlockEntityExecutionDomain",
                    MethodType.methodType(domain, ServerLevel.class, BlockPos.class, String.class))
                    .asType(MethodType.methodType(Object.class, ServerLevel.class, BlockPos.class, String.class));
            MethodType t = MethodType.methodType(void.class, ServerLevel.class, BlockPos.class);
            p = lookup.findStatic(d, "pinPositionToMain", t);
            u = lookup.findStatic(d, "unpinPositionFromMain", t);
            main = domain.getField("MAIN").get(null);
            try {
                Class<?> g = Class.forName("net.nestworld.region.BlockEntityGroups", true, cl);
                MethodType gt = MethodType.methodType(void.class, ServerLevel.class, long[].class);
                reg = lookup.findStatic(g, "registerGroup", gt);
                unreg = lookup.findStatic(g, "unregisterGroup", gt);
                geo = lookup.findStatic(g, "geometricDomain", MethodType.methodType(domain, ServerLevel.class, BlockPos.class, String.class))
                        .asType(MethodType.methodType(Object.class, ServerLevel.class, BlockPos.class, String.class));
                refs = lookup.findStatic(g, "positionPinRefs", MethodType.methodType(int.class, ServerLevel.class, BlockPos.class));
                try {
                    regSafe = lookup.findStatic(g, "registerGroup", MethodType.methodType(void.class, ServerLevel.class, long[].class, boolean.class));
                } catch (Throwable older) {
                    regSafe = null;
                }
            } catch (Throwable noGroups) {
                reg = unreg = geo = refs = null;
            }
        } catch (Throwable unavailable) {
            r = p = u = null;
        }
        RESOLVE = r; PIN = p; UNPIN = u; REGISTER = reg; UNREGISTER = unreg; GEOMETRIC = geo; PIN_REFS = refs; REGISTER_SAFE = regSafe;
        MAIN_DOMAIN = main;
    }

    static boolean groupsAvailable() { return REGISTER != null && UNREGISTER != null && GEOMETRIC != null && PIN_REFS != null; }

    static volatile String mode = "NOT_EVALUATED";
    private static volatile boolean active;
    private static final Set<BlockEntity> TRACKED = ConcurrentHashMap.newKeySet();
    /** MAIN only. */
    private static final Map<BlockEntity, Ctl> CTL = new IdentityHashMap<>();
    static final AtomicLong tracked = new AtomicLong(), groupsPinned = new AtomicLong(), groupsReleased = new AtomicLong(),
            regroups = new AtomicLong(), scanErrors = new AtomicLong(), pinErrors = new AtomicLong(),
            pinGeometry = new AtomicLong(), pinExternal = new AtomicLong();
    private static int tick;

    /** Per controller, MAIN only. */
    private static final class Ctl {
        ServerLevel level;
        long[] members;      // sorted
        boolean registered, pinned;
        int clean;
        String reason = "";
    }

    private MmControllers() {}

    /** Any thread (controller tick). */
    public static void track(BlockEntity controller) {
        if (active && TRACKED.add(controller)) tracked.incrementAndGet();
    }

    /** Common setup: exact-version gate; fail-closed. */
    static void evaluateGate() {
        if (Boolean.getBoolean("nestworld.compatMm.disable")) { mode = "DISABLED"; return; }
        if (!ModList.get().isLoaded("mm")) { mode = "NO_MM"; return; }
        if (RESOLVE == null || PIN == null || UNPIN == null) { mode = "NO_CORE"; return; }
        String version = ModList.get().getModContainerById("mm").map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        String sha;
        try (InputStream in = Objects.requireNonNull(MmControllers.class.getClassLoader().getResourceAsStream(CONTROLLER_CLASS))) {
            sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (Throwable t) {
            sha = "unreadable: " + t;
        }
        if (!MM_VERSION.equals(version) || !CONTROLLER_SHA256.equals(sha)) {
            mode = "INELIGIBLE mm=" + version + " controllerSha=" + sha;
            LOG.error("[nestworld-compat-mm] MasterfulMachinery build not audited (expected {} / {}); fix OFF, MM runs "
                    + "unmodified (MM-GROUP-DOMAIN-001 NOT mitigated): {}", MM_VERSION, CONTROLLER_SHA256, mode);
            return;
        }
        active = true;
        mode = groupsAvailable() ? "ACTIVE" : "ACTIVE_NO_CORE_GROUPS";
        LOG.info("[nestworld-compat-mm] {} {} for MasterfulMachinery {}: {}", mode, VERSION, version, groupsAvailable()
                ? "multiblocks registered as Core groups; MAIN only when split"
                : "Core without BlockEntityGroups: split multiblocks are kept together on MAIN (0.1.0 behaviour)");
    }

    /** Server thread, end of every server tick. */
    static void onServerTick() {
        if (!active || ++tick % INTERVAL != 0) return;
        for (BlockEntity c : TRACKED) {
            try {
                scan(c);
            } catch (Throwable t) {
                scanErrors.incrementAndGet();
                if (scanErrors.get() <= 5) LOG.warn("[nestworld-compat-mm] scan failed at {}", c.getBlockPos(), t);
            }
        }
    }

    private static void scan(BlockEntity c) {
        if (c.isRemoved() || !(c.getLevel() instanceof ServerLevel level)
                || level.getChunkSource().getChunkNow(c.getBlockPos().getX() >> 4, c.getBlockPos().getZ() >> 4) == null
                || level.getBlockEntity(c.getBlockPos()) != c) {
            forget(c);
            return;
        }
        List<BlockEntity> ports = ((NwMmController) c).nw$ports();
        if (ports.isEmpty() && !CTL.containsKey(c)) return;
        BlockEntity[] bes = new BlockEntity[ports.size() + 1];
        bes[0] = c;
        for (int i = 0; i < ports.size(); i++) bes[i + 1] = ports.get(i);
        long[] sorted = new long[bes.length];
        for (int i = 0; i < bes.length; i++) sorted[i] = bes[i].getBlockPos().asLong();
        Arrays.sort(sorted);

        Ctl ctl = CTL.computeIfAbsent(c, k -> new Ctl());
        if (ctl.members == null || !Arrays.equals(ctl.members, sorted)) {
            long[] old = ctl.members;
            ServerLevel oldLevel = ctl.level;
            if (groupsAvailable()) {
                if (WHOLE_GROUP_CLAIM_SAFE && REGISTER_SAFE != null) {
                    try { REGISTER_SAFE.invokeExact(level, sorted, true); } catch (Throwable t) { pinErrors.incrementAndGet(); }
                } else {
                    call(REGISTER, level, sorted);
                }
                if (ctl.registered && old != null) call(UNREGISTER, oldLevel, old);
                ctl.registered = true;
            }
            if (ctl.pinned) {               // new set first, then the old one: never a window apart
                pin(level, sorted);
                unpin(oldLevel, old);
            }
            if (old != null) regroups.incrementAndGet();
            ctl.members = sorted;
            ctl.level = level;
        }

        if (!groupsAvailable()) {           // 0.1.0 behaviour: split ⇒ MAIN, kept while loaded
            if (!ctl.pinned && !uniform(level, bes, RESOLVE)) pinGroup(ctl, "split");
            return;
        }
        String need = needMain(level, bes, ctl.pinned);
        if (need != null) {
            ctl.clean = 0;
            if (!ctl.pinned) pinGroup(ctl, need);
        } else if (ctl.pinned && ++ctl.clean >= RELEASE_SCANS) {
            unpin(ctl.level, ctl.members);
            ctl.pinned = false;
            ctl.clean = 0;
            ctl.reason = "";
            groupsReleased.incrementAndGet();
        }
    }

    /** Null = the group can stay in its geometric domain; otherwise the reason it must run on MAIN. */
    private static String needMain(ServerLevel level, BlockEntity[] bes, boolean ownPins) {
        Object first = null;
        for (int i = 0; i < bes.length; i++) {
            Object d = domain(GEOMETRIC, level, bes[i]);
            if (i == 0) first = d;
            else if (!Objects.equals(first, d)) { pinGeometry.incrementAndGet(); return "geometry"; }
        }
        if (Objects.equals(first, MAIN_DOMAIN)) return null;     // all on MAIN anyway: one thread already
        int own = ownPins ? 1 : 0;
        for (BlockEntity be : bes) {
            if (refs(level, be.getBlockPos()) > own) { pinExternal.incrementAndGet(); return "external-pin"; }
        }
        return null;
    }

    private static boolean uniform(ServerLevel level, BlockEntity[] bes, MethodHandle resolver) {
        Object first = null;
        for (int i = 0; i < bes.length; i++) {
            Object d = domain(resolver, level, bes[i]);
            if (i == 0) first = d;
            else if (!Objects.equals(first, d)) return false;
        }
        return true;
    }

    private static void pinGroup(Ctl ctl, String reason) {
        pin(ctl.level, ctl.members);
        ctl.pinned = true;
        ctl.reason = reason;
        groupsPinned.incrementAndGet();
    }

    private static void forget(BlockEntity c) {
        TRACKED.remove(c);
        ((NwMmController) c).nw$untracked();
        Ctl ctl = CTL.remove(c);
        if (ctl == null || ctl.members == null) return;
        if (ctl.pinned) { unpin(ctl.level, ctl.members); groupsReleased.incrementAndGet(); }
        if (ctl.registered) call(UNREGISTER, ctl.level, ctl.members);
    }

    /** Server stopping: Core pins/groups are runtime state of this server; drop ours. */
    static void onServerStopping() {
        for (BlockEntity c : List.copyOf(CTL.keySet())) forget(c);
        TRACKED.clear();
    }

    private static Object domain(MethodHandle resolver, ServerLevel level, BlockEntity be) {
        ResourceLocation key = BlockEntityType.getKey(be.getType());
        try {
            String type = key == null ? null : key.toString();   // a ternary argument would erase to Object for invokeExact
            return resolver.invokeExact(level, be.getBlockPos(), type);
        } catch (Throwable t) {
            throw new IllegalStateException("Core domain resolution failed", t);
        }
    }

    private static int refs(ServerLevel level, BlockPos pos) {
        try { return (int) PIN_REFS.invokeExact(level, pos); }
        catch (Throwable t) { return Integer.MAX_VALUE; }   // unknown ⇒ treat as pinned by someone (fail-closed)
    }

    private static void call(MethodHandle h, ServerLevel level, long[] members) {
        try { h.invokeExact(level, members); } catch (Throwable t) { pinErrors.incrementAndGet(); }
    }

    private static void pin(ServerLevel level, long[] positions) {
        for (long p : positions) {
            try { PIN.invokeExact(level, BlockPos.of(p)); } catch (Throwable t) { pinErrors.incrementAndGet(); }
        }
    }

    private static void unpin(ServerLevel level, long[] positions) {
        if (positions == null) return;
        for (long p : positions) {
            try { UNPIN.invokeExact(level, BlockPos.of(p)); } catch (Throwable t) { pinErrors.incrementAndGet(); }
        }
    }

    static String status() {
        int pinnedGroups = 0, positions = 0, registered = 0;
        for (Ctl c : CTL.values()) {
            if (c.registered) registered++;
            if (c.pinned) { pinnedGroups++; positions += c.members.length; }
        }
        return "nestworld-compat-mm " + VERSION + " mode=" + mode + " interval=" + INTERVAL + " releaseScans=" + RELEASE_SCANS
                + " tracked=" + TRACKED.size() + " (total " + tracked.get() + ") registeredGroups=" + registered
                + " pinnedGroups=" + pinnedGroups + " pinnedPositions=" + positions + " groupsPinned=" + groupsPinned.get()
                + " groupsReleased=" + groupsReleased.get() + " regroups=" + regroups.get() + " pinGeometry=" + pinGeometry.get()
                + " pinExternal=" + pinExternal.get() + " scanErrors=" + scanErrors.get() + " pinErrors=" + pinErrors.get()
                + "\n" + coreGroupStats();
    }

    /** Verification: every group must currently resolve to ONE domain (position pins included). */
    static String groups() {
        int split = 0, onMain = 0, onRegion = 0, shown = 0;
        StringBuilder sb = new StringBuilder();
        for (var e : CTL.entrySet()) {
            Ctl c = e.getValue();
            if (c.members == null) continue;
            Object first = null;
            boolean uniform = true;
            for (int i = 0; i < c.members.length; i++) {
                BlockPos pos = BlockPos.of(c.members[i]);
                BlockEntity be = c.level.getBlockEntity(pos);
                ResourceLocation key = be == null ? null : BlockEntityType.getKey(be.getType());
                Object d;
                String type = key == null ? null : key.toString();
                try { d = RESOLVE.invokeExact(c.level, pos, type); }
                catch (Throwable t) { d = "ERROR"; }
                if (i == 0) first = d;
                else if (!Objects.equals(first, d)) uniform = false;
            }
            if (!uniform) split++;
            else if (Objects.equals(first, MAIN_DOMAIN)) onMain++;
            else onRegion++;
            if ((!uniform || c.pinned) && shown++ < 15) sb.append("\n  ").append(BlockEntityType.getKey(e.getKey().getType()))
                    .append(" @ ").append(e.getKey().getBlockPos().toShortString()).append(" members=").append(c.members.length)
                    .append(" domain=").append(first).append(c.pinned ? " pinned(" + c.reason + ")" : "").append(uniform ? "" : " SPLIT");
        }
        return "groups=" + CTL.size() + " stillSplit=" + split + " onMain=" + onMain + " onRegion=" + onRegion + sb;
    }

    private static String coreGroupStats() {
        try {
            return String.valueOf(Class.forName("net.nestworld.region.BlockEntityGroups", true, MmControllers.class.getClassLoader())
                    .getMethod("stats").invoke(null));
        } catch (Throwable t) {
            return "coreGroups=UNAVAILABLE";
        }
    }
}
