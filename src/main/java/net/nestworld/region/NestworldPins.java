package net.nestworld.region;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.language.ModFileScanData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registry of entity types pinned to main-thread ticking.
 *
 * <p>Pinning is the compatibility escape hatch (final-goal item 5): an entity
 * whose mod is not thread-safe can be forced to tick on the main thread exactly
 * as vanilla would, trading that type's parallelism for 100% correctness. A
 * pinned type is never assigned to any region (see
 * {@link BoundaryEntityTransfer}); the main thread ticks it alongside players
 * (see {@link NestworldRegionSystem#tickPinnedEntitiesOnMain}).
 *
 * <p>The hot check {@link #isPinned(EntityType)} is an identity-set lookup; the
 * persisted form is the type's registry id, one per line in
 * {@code <world>/data/nestworld-pins.txt} (human-editable). Ids that do not
 * resolve to a registered type (mod absent this boot) are kept so a round-trip
 * does not silently drop them.
 */
public final class NestworldPins {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/Pins");

    /** Fast path: resolved entity types, checked per entity each tick. */
    private final Set<EntityType<?>> pinnedTypes = ConcurrentHashMap.newKeySet();
    /** Source of truth for persistence and listing (includes unresolved ids). */
    private final Set<ResourceLocation> pinnedIds = ConcurrentHashMap.newKeySet();

    /** Pinned block-entity type ids (e.g. {@code minecraft:hopper}). The BE
     *  phase compares against {@code TickingBlockEntity.getType()}, which is the
     *  registry id string, so these are stored as strings directly. */
    private final Set<String> pinnedBeIds = ConcurrentHashMap.newKeySet();
    private Path beFile;

    /**
     * Block-entity type ids the operator has explicitly asserted are
     * "cascade-safe": their {@code serverTick()} never mutates a block
     * position other than its own and never triggers a redstone/piston
     * update or a neighbour-chunk inventory access (the same reach a hopper
     * or piston has). This is a trust boundary the code cannot verify —
     * marking an unsafe type reintroduces exactly the cross-region tick
     * race the region border band exists to prevent.
     *
     * <p>Types in this set bypass the border-band inset check in
     * {@code NestworldRegionSystem#runBlockEntityPhase} (routed to their
     * true owning region even inside a border band) and their block-tick
     * heat is excluded from the split-veto calculation in
     * {@link RegionSplitManager} — letting a dense, otherwise-inseparable
     * cluster of known-safe machines actually split. Default is empty: zero
     * behaviour change until an operator opts a type in. */
    private final Set<String> cascadeSafeBeIds = ConcurrentHashMap.newKeySet();
    private Path cascadeSafeBeFile;

    /** Pinned mod namespaces: every entity AND block-entity from these mods is
     *  pinned to main. The escape hatch for a whole mod that isn't thread-safe. */
    private final Set<String> pinnedMods = ConcurrentHashMap.newKeySet();
    private Path modFile;
    private Path errorCountsFile;

    // Auto-pin: when an entity type repeatedly throws on a region thread, pin it
    // to main so one bad mod can't keep crashing a region. OFF by default — a
    // transient race (e.g. the historical "tick error: null") could otherwise
    // pin a common type and silently serialise it (a TNT-minecart lag machine
    // would land back on main). Opt in with -Dnestworld.autoPin=true.
    private final boolean autoPin;
    private final int autoPinThreshold;
    private final ConcurrentHashMap<EntityType<?>, AtomicInteger> errorCounts =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> beErrorCounts =
            new ConcurrentHashMap<>();

    /** Auto-pin, mod-namespace variant: for a Throwable caught somewhere that ISN'T an
     *  entity's or block-entity's own tick call (e.g. a player-tick catch whose real cause
     *  is a mod's GUI/menu code racing its own background state across threads -- see
     *  "toms_storage TerminalSyncManager ConcurrentModificationException", 2026-09-07 --
     *  neither {@link #noteEntityTickError} nor {@link #noteBlockEntityTickError} ever see
     *  this class of bug, because the throwing frame belongs to neither ticking entity type).
     *  Keyed by mod id resolved from the stack trace via {@link #resolveModId}, threshold-pinned
     *  through the same {@link #pinnedMods} set {@code /nestworld pinmod} uses. */
    private final ConcurrentHashMap<String, AtomicInteger> modCallbackErrorCounts =
            new ConcurrentHashMap<>();

    /** Throttle for {@link #saveErrorCounts()}: a bug firing many times per second (the
     *  toms_storage storm that motivated this whole file was ~1 call/tick in bursts) would
     *  otherwise do a synchronous full-file rewrite on EVERY occurrence, on whatever thread
     *  caught it -- for the main-thread player-tick catch, that's a self-inflicted tick spike
     *  from the diagnostic code meant to survive one. Capped to at most one write/second;
     *  losing up to a second of the most recent increments on a true crash is an acceptable
     *  trade against turning error-storm handling into its own performance incident. */
    private final java.util.concurrent.atomic.AtomicLong lastErrorCountsSaveNanos =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long ERROR_COUNTS_SAVE_INTERVAL_NANOS = 1_000_000_000L;

    /** Lazily-built, cached class-name -> mod-id map, from every loaded mod's ASM scan data.
     *  Built once on first use (nothing here runs unless auto-pin is on), not at construction --
     *  {@code ModList.get()} isn't populated yet this early in some call orders. */
    private static final AtomicReference<Map<String, String>> CLASS_TO_MOD_ID = new AtomicReference<>();

    /**
     * Why a given pin exists: captured once, at the moment the pin actually fires, so an
     * operator running {@code /nestworld pinreason <key>} days later gets the real triggering
     * exception instead of having to go spelunking through old log files (which may have
     * rotated away). Purely informational -- never consulted to decide whether something IS
     * pinned; {@link #pinnedTypes}/{@link #pinnedBeIds}/{@link #pinnedMods} remain sole source
     * of truth for that. Keyed identically to the error-count maps ({@code "kind:key"}, e.g.
     * {@code "mod:toms_storage"}) so the two files line up.
     */
    public record PinReason(String kind, String key, String mechanism, int errorCount,
                             int threshold, String exceptionClass, String exceptionMessage,
                             String topFrame, long timestampMillis) {
        /** Human-readable one-paragraph explanation, for chat/RCON output. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(kind).append(' ').append(key).append(" was pinned after ")
                    .append(errorCount).append('/').append(threshold)
                    .append(" errors (").append(mechanism).append(')');
            if (!exceptionClass.isEmpty()) {
                sb.append(" -- last seen: ").append(exceptionClass);
                if (!exceptionMessage.isEmpty() && !"null".equals(exceptionMessage)) {
                    sb.append(": ").append(exceptionMessage);
                }
                if (!topFrame.isEmpty()) sb.append(" at ").append(topFrame);
            }
            long ageSec = Math.max(0, (System.currentTimeMillis() - timestampMillis) / 1000L);
            sb.append(" (").append(formatAge(ageSec)).append(" ago)");
            return sb.toString();
        }

        private static String formatAge(long sec) {
            if (sec < 60) return sec + "s";
            if (sec < 3600) return (sec / 60) + "m";
            if (sec < 86400) return (sec / 3600) + "h";
            return (sec / 86400) + "d";
        }
    }

    private final ConcurrentHashMap<String, PinReason> pinReasons = new ConcurrentHashMap<>();
    private Path pinReasonsFile;

    /** True when auto-pin is enabled (lets the BE phase skip wrapping tickers). */
    public boolean isAutoPinEnabled() { return autoPin; }

    private Path file;

    public NestworldPins() {
        this(Boolean.parseBoolean(System.getProperty("nestworld.autoPin", "false")),
                Integer.getInteger("nestworld.autoPinThreshold", 20));
    }

    /** Test constructor: explicit auto-pin settings, no system-property read. */
    NestworldPins(boolean autoPin, int autoPinThreshold) {
        this.autoPin = autoPin;
        this.autoPinThreshold = autoPinThreshold;
    }

    /** True when no entity pin is active — lets callers skip the pinned pass. */
    public boolean isEmpty() {
        return pinnedIds.isEmpty() && pinnedMods.isEmpty();
    }

    public boolean isPinned(EntityType<?> type) {
        if (!pinnedTypes.isEmpty() && pinnedTypes.contains(type)) return true;
        if (!pinnedMods.isEmpty()) {
            ResourceLocation k = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            return k != null && pinnedMods.contains(k.getNamespace());
        }
        return false;
    }

    /** True when no block-entity pin is active — lets the BE phase skip the check. */
    public boolean isBeEmpty() {
        return pinnedBeIds.isEmpty() && pinnedMods.isEmpty();
    }

    /** {@code typeId} is {@link TickingBlockEntity#getType()} (a registry id string). */
    public boolean isBePinned(String typeId) {
        if (!pinnedBeIds.isEmpty() && pinnedBeIds.contains(typeId)) return true;
        if (!pinnedMods.isEmpty()) {
            int c = typeId.indexOf(':');
            String ns = c < 0 ? "minecraft" : typeId.substring(0, c);
            return pinnedMods.contains(ns);
        }
        return false;
    }

    /** True when no cascade-safe block-entity type is marked — lets callers skip the check. */
    public boolean isCascadeSafeBeEmpty() {
        return cascadeSafeBeIds.isEmpty();
    }

    /** {@code typeId} is {@link net.minecraft.world.level.block.entity.TickingBlockEntity#getType()}
     *  (a registry id string). True only for types explicitly marked cascade-safe — no mod-level
     *  bulk variant, deliberately: this is a stronger per-type safety assertion than a pin. */
    public boolean isCascadeSafeBe(String typeId) {
        return cascadeSafeBeIds.contains(typeId);
    }

    /** Sorted cascade-safe block-entity ids, for {@code /nestworld} admin commands and the file. */
    public List<String> cascadeSafeBeList() {
        return new ArrayList<>(new TreeSet<>(cascadeSafeBeIds));
    }

    /**
     * Marks {@code idStr} as cascade-safe. Returns null on success, or an error message if the id
     * is malformed or names no registered block-entity type. Does not require the type to be
     * currently loaded (mirrors {@link #pin}'s tolerance for "kept but inactive" ids).
     */
    public String markCascadeSafe(String idStr) {
        ResourceLocation id = ResourceLocation.tryParse(idStr);
        if (id == null) return "Not a valid id: " + idStr;
        if (BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).isEmpty()) {
            return "No such block-entity type: " + id;
        }
        cascadeSafeBeIds.add(id.toString());
        saveCascadeSafeBe();
        return null;
    }

    /** Unmarks a cascade-safe block-entity id; returns true if it was marked. */
    public boolean unmarkCascadeSafe(String idStr) {
        ResourceLocation id = ResourceLocation.tryParse(idStr);
        if (id == null) return false;
        boolean removed = cascadeSafeBeIds.remove(id.toString());
        if (removed) saveCascadeSafeBe();
        return removed;
    }

    /** Sorted pinned mod namespaces, for {@code /nestworld pins} and the file. */
    public List<String> modList() {
        return new ArrayList<>(new TreeSet<>(pinnedMods));
    }

    /** Pins every entity and block entity from {@code namespace}. */
    public void pinMod(String namespace) {
        if (pinnedMods.add(namespace.trim())) saveMods();
    }

    /** Unpins a mod namespace; returns true if it was pinned. */
    public boolean unpinMod(String namespace) {
        boolean removed = pinnedMods.remove(namespace.trim());
        if (removed) saveMods();
        return removed;
    }

    /** Sorted block-entity pin ids, for {@code /nestworld pins} and the file. */
    public List<String> beList() {
        return new ArrayList<>(new TreeSet<>(pinnedBeIds));
    }

    /** Sorted registry ids of all pins, for {@code /nestworld pins} and the file. */
    public List<String> list() {
        Set<String> sorted = new TreeSet<>();
        for (ResourceLocation id : pinnedIds) sorted.add(id.toString());
        return new ArrayList<>(sorted);
    }

    /**
     * Pins the given entity-type id (e.g. {@code minecraft:zombie}). Returns
     * null on success, or an error message if the id is malformed or names no
     * registered entity type.
     */
    public String pin(String idStr) {
        ResourceLocation id = ResourceLocation.tryParse(idStr);
        if (id == null) return "Not a valid id: " + idStr;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type != null) {
            pinnedIds.add(id);
            pinnedTypes.add(type);
            save();
            return null;
        }
        if (BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).isPresent()) {
            pinnedBeIds.add(id.toString());
            saveBe();
            return null;
        }
        return "No such entity or block-entity type: " + id;
    }

    /**
     * Records that an entity of {@code type} threw while ticking on a region
     * thread. When auto-pin is enabled and a type crosses the error threshold,
     * it is pinned to the main thread and {@code true} is returned. Thread-safe
     * (called from region threads). No-op unless {@code -Dnestworld.autoPin=true}.
     *
     * @param t the throwable that triggered this call, for {@link #getPinReason} reporting;
     *          may be {@code null} (report will just omit exception detail).
     */
    public boolean noteEntityTickError(EntityType<?> type, Throwable t) {
        if (!autoPin || type == null || pinnedTypes.contains(type)) return false;
        int count = errorCounts.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
        saveErrorCountsThrottled(); // persist progress -- see saveErrorCounts()/throttle javadoc
        if (count < autoPinThreshold) return false;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (!pinnedTypes.add(type)) return false; // pinned concurrently
        pinnedIds.add(id);
        save();
        recordPinReason("entity", String.valueOf(id), "own-tick error (region thread)", count, t);
        LOGGER.warn("AUTO-PINNED {} to the main thread after {} region-thread tick "
                + "errors — it now ticks on main (vanilla parity) so a region thread "
                + "stops crashing on it. Restore parallelism with /nestworld unpin {} "
                + "once the cause is fixed.", id, count, id);
        return true;
    }

    /**
     * Records that a block entity of {@code typeId} (its
     * {@link net.minecraft.world.level.block.entity.TickingBlockEntity#getType()}
     * registry-id string) threw on a region thread. Pins the BE type to main at
     * the threshold. No-op unless auto-pin is enabled. Thread-safe.
     *
     * @param t the throwable that triggered this call, for {@link #getPinReason} reporting;
     *          may be {@code null} (report will just omit exception detail).
     */
    public boolean noteBlockEntityTickError(String typeId, Throwable t) {
        if (!autoPin || typeId == null || pinnedBeIds.contains(typeId)) return false;
        int count = beErrorCounts.computeIfAbsent(typeId, k -> new AtomicInteger()).incrementAndGet();
        saveErrorCountsThrottled();
        if (count < autoPinThreshold) return false;
        if (!pinnedBeIds.add(typeId)) return false; // pinned concurrently
        saveBe();
        recordPinReason("be", typeId, "own-tick error (region thread)", count, t);
        LOGGER.warn("AUTO-PINNED block entity {} to the main thread after {} region-thread "
                + "tick errors. Restore parallelism with /nestworld unpin {} once fixed.",
                typeId, count, typeId);
        return true;
    }

    /**
     * Records that {@code t} was caught somewhere that isn't an entity/BE's own tick call
     * (currently: the two player-tick catches in {@link NestworldDimensionRegion#tick}) and,
     * if its stack trace resolves to a loaded mod, counts an error against that mod's
     * namespace -- auto-pinning it (via the same {@link #pinMod} escape hatch
     * {@code /nestworld pinmod} uses) once the threshold is crossed. This is what
     * {@link #noteEntityTickError}/{@link #noteBlockEntityTickError} structurally cannot do:
     * those only ever see a throw during the misbehaving type's OWN tick; a mod whose GUI/menu
     * code races its own background state across threads (the toms_storage
     * ConcurrentModificationException this was built for) throws from inside a vanilla
     * player's tick, not its own -- so the type at fault was never the one ticking.
     * No-op unless auto-pin is enabled. Thread-safe.
     */
    public boolean noteCallbackError(Throwable t) {
        if (!autoPin || t == null) return false;
        String modId = resolveModId(t);
        if (modId == null || pinnedMods.contains(modId)) return false;
        int count = modCallbackErrorCounts.computeIfAbsent(modId, k -> new AtomicInteger()).incrementAndGet();
        saveErrorCountsThrottled();
        if (count < autoPinThreshold) return false;
        if (!pinnedMods.add(modId)) return false; // pinned concurrently
        saveMods();
        recordPinReason("mod", modId, "caught-elsewhere (cross-thread callback, not own tick)", count, t);
        LOGGER.warn("AUTO-PINNED mod {} to the main thread after {} errors attributed to its "
                + "code via caught-elsewhere stack traces (not its own entity/BE tick -- a "
                + "cross-thread race in its GUI/menu/background code). Restore parallelism "
                + "with /nestworld unpin {} once fixed.", modId, count, modId);
        return true;
    }

    /**
     * Best-effort: walks {@code t}'s stack trace top-down and returns the mod id owning the
     * first frame that belongs to a loaded mod's classes, or {@code null} if none does (pure
     * vanilla/Forge/JVM exception, or a mod not found in the scan -- e.g. Mixin-injected
     * synthetic frames). Built once, lazily, from every mod's ASM scan data -- cheap per call
     * after that (one HashMap get per frame), and this whole path only runs when auto-pin is
     * explicitly opted into.
     */
    private static String resolveModId(Throwable t) {
        Map<String, String> map = CLASS_TO_MOD_ID.get();
        if (map == null) {
            map = buildClassToModIdMap();
            CLASS_TO_MOD_ID.compareAndSet(null, map);
            map = CLASS_TO_MOD_ID.get();
        }
        for (StackTraceElement frame : t.getStackTrace()) {
            String modId = map.get(frame.getClassName());
            if (modId != null) return modId;
        }
        Throwable cause = t.getCause();
        return cause != null && cause != t ? resolveModId(cause) : null;
    }

    private static Map<String, String> buildClassToModIdMap() {
        Map<String, String> map = new java.util.HashMap<>();
        try {
            for (IModFileInfo fileInfo : ModList.get().getModFiles()) {
                List<IModInfo> mods = fileInfo.getMods();
                if (mods.isEmpty()) continue;
                String modId = mods.get(0).getModId();
                // Skip Minecraft/Forge/library "mods" themselves -- never worth auto-pinning,
                // and would otherwise shadow genuinely offending third-party classes if two
                // mod files happened to declare an overlapping class (jar-in-jar shading).
                if (modId.equals("minecraft") || modId.equals("forge")) continue;
                ModFileScanData scan = fileInfo.getFile().getScanResult();
                if (scan == null) continue;
                for (ModFileScanData.ClassData classData : scan.getClasses()) {
                    map.putIfAbsent(classData.clazz().getClassName(), modId);
                }
            }
        } catch (Throwable ignored) {
            // Best-effort diagnostic aid -- never let a scan-data quirk break tick error
            // logging itself. An empty map just means noteCallbackError never fires.
        }
        return map;
    }

    /** Unpins an id (entity or block-entity); returns true if it was pinned. */
    public boolean unpin(String idStr) {
        ResourceLocation id = ResourceLocation.tryParse(idStr);
        if (id == null) return false;
        boolean removed = pinnedIds.remove(id);
        BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(pinnedTypes::remove);
        if (removed) {
            save();
            return true;
        }
        boolean removedBe = pinnedBeIds.remove(id.toString());
        if (removedBe) saveBe();
        return removedBe;
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    /** Loads pins from {@code file} (creating nothing if it is absent). */
    public void load(Path file) {
        this.file = file;
        pinnedIds.clear();
        pinnedTypes.clear();
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                ResourceLocation id = ResourceLocation.tryParse(trimmed);
                if (id == null) {
                    LOGGER.warn("Ignoring malformed pin id: {}", trimmed);
                    continue;
                }
                pinnedIds.add(id);
                BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresentOrElse(
                        pinnedTypes::add,
                        () -> LOGGER.warn("Pinned id {} matches no loaded entity type "
                                + "(mod absent?) — kept but inactive", id));
            }
            if (!pinnedIds.isEmpty()) {
                LOGGER.info("Loaded {} pinned entity type(s): {}", pinnedIds.size(), list());
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read pins file {}: {}", file, e.toString());
        }
    }

    // -----------------------------------------------------------------------
    // Self-test (NESTWORLD_AUTOPIN_TEST): deterministic check of the threshold
    // path without injecting real entity crashes into a live server.
    // -----------------------------------------------------------------------

    public static void selfTest() {
        NestworldPins p = new NestworldPins(true, 3);
        EntityType<?> type = EntityType.ARMOR_STAND;
        boolean below = p.noteEntityTickError(type, null) | p.noteEntityTickError(type, null); // 1,2 < 3
        boolean atThreshold = p.noteEntityTickError(type, null);                          // 3 -> pin
        boolean isPinnedNow = p.isPinned(type);
        boolean idempotent = !p.noteEntityTickError(type, null);                          // already pinned

        // Same threshold path for block-entity types (id strings).
        NestworldPins pb = new NestworldPins(true, 3);
        String be = "minecraft:hopper";
        boolean beBelow = pb.noteBlockEntityTickError(be, null) | pb.noteBlockEntityTickError(be, null);
        boolean beAt = pb.noteBlockEntityTickError(be, null);
        boolean beIsPinned = pb.isBePinned(be);
        boolean beIdem = !pb.noteBlockEntityTickError(be, null);

        boolean ok = !below && atThreshold && isPinnedNow && idempotent
                && !beBelow && beAt && beIsPinned && beIdem;
        LOGGER.info("[AUTOPIN SELF-TEST] {} (entity: below={} at={} pinned={} idem={}; "
                + "be: below={} at={} pinned={} idem={})", ok ? "PASS" : "FAIL",
                !below, atThreshold, isPinnedNow, idempotent,
                !beBelow, beAt, beIsPinned, beIdem);

        selfTestPersistence();
    }

    /**
     * A crash that repeatedly kills the server before ONE run reaches the auto-pin threshold
     * must still eventually pin, by accumulating the count across restarts (this is what
     * {@link #loadErrorCounts}/{@link #saveErrorCounts} exist for -- see that javadoc; added
     * 2026-09-07 after the user pointed out the in-memory-only counters could never fire for
     * a bug whose failure mode IS crashing the server).
     *
     * <p>One increment per simulated "run" (a fresh {@code NestworldPins} instance, discarded
     * without a clean shutdown, standing in for a crash) -- deliberately not several
     * increments in one run: {@link #saveErrorCountsThrottled()} caps real disk writes to
     * ~1/second, so a tight loop of several calls on the SAME instance would mostly get
     * throttled and this test would flake against wall-clock timing instead of testing the
     * actual guarantee. A real crash only ever gives a process one shot at persisting its
     * last increment anyway, so this is the realistic case, not a simplification.
     */
    private static void selfTestPersistence() {
        Path tmp;
        try {
            tmp = Files.createTempFile("nestworld-autopin-selftest", ".txt");
        } catch (IOException e) {
            LOGGER.warn("[AUTOPIN PERSISTENCE SELF-TEST] SKIPPED (could not create temp file: {})", e.toString());
            return;
        }
        try {
            EntityType<?> type = EntityType.ZOMBIE;

            // Runs 1-4: one increment each (cumulative 1,2,3,4), all below threshold=5 --
            // each is a fresh instance so its own first save is never throttled (a brand-new
            // instance's lastErrorCountsSaveNanos starts at 0), giving a deterministic disk
            // state after each "crash" without depending on wall-clock timing.
            boolean everPrematurelyPinned = false;
            for (int i = 0; i < 4; i++) {
                NestworldPins run = new NestworldPins(true, 5);
                run.loadErrorCounts(tmp);
                run.noteEntityTickError(type, null);
                if (run.isPinned(type)) everPrematurelyPinned = true;
            }

            // Run 5: resumes at 4, this run's own increment crosses the threshold in-process.
            NestworldPins run5 = new NestworldPins(true, 5);
            run5.loadErrorCounts(tmp);
            run5.noteEntityTickError(type, null);
            boolean pinnedOnCumulativeThreshold = run5.isPinned(type);

            // Retroactive-at-load, tested independently of the above (a separate file):
            // simulates a crash that landed the threshold-crossing count on disk but died
            // before the in-process pin logic ran. A fresh load must pin BEFORE any tick call.
            Path tmp2 = Files.createTempFile("nestworld-autopin-selftest-retro", ".txt");
            boolean retroactivelyPinnedAtLoad;
            try {
                Files.write(tmp2, List.of("entity:minecraft:zombie=5"));
                NestworldPins run6 = new NestworldPins(true, 5);
                run6.loadErrorCounts(tmp2);
                retroactivelyPinnedAtLoad = run6.isPinned(type);
            } finally {
                Files.deleteIfExists(tmp2);
            }

            boolean ok = !everPrematurelyPinned && pinnedOnCumulativeThreshold && retroactivelyPinnedAtLoad;
            LOGGER.info("[AUTOPIN PERSISTENCE SELF-TEST] {} (never_premature={} "
                    + "cumulative_pin_at_threshold={} retroactive_pin_at_load={})",
                    ok ? "PASS" : "FAIL", !everPrematurelyPinned,
                    pinnedOnCumulativeThreshold, retroactivelyPinnedAtLoad);
        } catch (IOException e) {
            LOGGER.warn("[AUTOPIN PERSISTENCE SELF-TEST] FAILED with I/O error: {}", e.toString());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) { }
        }
    }

    private void save() {
        if (file == null) return;
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            List<String> lines = new ArrayList<>();
            lines.add("# NestWorld entity-type pins — one registry id per line.");
            lines.add("# These types tick on the main thread (vanilla parity) for mod compatibility.");
            lines.addAll(list());
            Files.write(file, lines);
        } catch (IOException e) {
            LOGGER.warn("Could not write pins file {}: {}", file, e.toString());
        }
    }

    /** Loads block-entity pins (own file; same format as entity pins). */
    public void loadBe(Path beFile) {
        this.beFile = beFile;
        pinnedBeIds.clear();
        if (!Files.isRegularFile(beFile)) return;
        try {
            for (String line : Files.readAllLines(beFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                ResourceLocation id = ResourceLocation.tryParse(trimmed);
                if (id == null) {
                    LOGGER.warn("Ignoring malformed block-entity pin id: {}", trimmed);
                    continue;
                }
                pinnedBeIds.add(id.toString());
                if (BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).isEmpty()) {
                    LOGGER.warn("Pinned block-entity id {} matches no loaded type "
                            + "(mod absent?) — kept but inactive", id);
                }
            }
            if (!pinnedBeIds.isEmpty()) {
                LOGGER.info("Loaded {} pinned block-entity type(s): {}", pinnedBeIds.size(), beList());
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read block-entity pins file {}: {}", beFile, e.toString());
        }
    }

    private void saveBe() {
        if (beFile == null) return;
        try {
            Path parent = beFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            List<String> lines = new ArrayList<>();
            lines.add("# NestWorld block-entity-type pins — one registry id per line.");
            lines.add("# These BE types tick on the main thread (vanilla parity) for mod compatibility.");
            lines.addAll(beList());
            Files.write(beFile, lines);
        } catch (IOException e) {
            LOGGER.warn("Could not write block-entity pins file {}: {}", beFile, e.toString());
        }
    }

    /** Loads cascade-safe block-entity ids (own file; same format as entity/BE pins). */
    public void loadCascadeSafeBe(Path cascadeSafeBeFile) {
        this.cascadeSafeBeFile = cascadeSafeBeFile;
        cascadeSafeBeIds.clear();
        if (!Files.isRegularFile(cascadeSafeBeFile)) return;
        try {
            for (String line : Files.readAllLines(cascadeSafeBeFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                ResourceLocation id = ResourceLocation.tryParse(trimmed);
                if (id == null) {
                    LOGGER.warn("Ignoring malformed cascade-safe block-entity id: {}", trimmed);
                    continue;
                }
                cascadeSafeBeIds.add(id.toString());
                if (BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).isEmpty()) {
                    LOGGER.warn("Cascade-safe block-entity id {} matches no loaded type "
                            + "(mod absent?) — kept but inactive", id);
                }
            }
            if (!cascadeSafeBeIds.isEmpty()) {
                LOGGER.info("Loaded {} cascade-safe block-entity type(s): {}",
                        cascadeSafeBeIds.size(), cascadeSafeBeList());
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read cascade-safe block-entity file {}: {}", cascadeSafeBeFile, e.toString());
        }
    }

    private void saveCascadeSafeBe() {
        if (cascadeSafeBeFile == null) return;
        try {
            Path parent = cascadeSafeBeFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            List<String> lines = new ArrayList<>();
            lines.add("# NestWorld cascade-safe block-entity types — one registry id per line.");
            lines.add("# TRUST BOUNDARY: marking a type here asserts its serverTick() never writes");
            lines.add("# a block outside its own position and never triggers a redstone/piston");
            lines.add("# update or neighbour-chunk inventory access. An unsafe type here can cause");
            lines.add("# cross-region tick races. See NestworldPins.cascadeSafeBeIds javadoc.");
            lines.addAll(cascadeSafeBeList());
            Files.write(cascadeSafeBeFile, lines);
        } catch (IOException e) {
            LOGGER.warn("Could not write cascade-safe block-entity file {}: {}", cascadeSafeBeFile, e.toString());
        }
    }

    /** Loads pinned mod namespaces (one per line). */
    public void loadMods(Path modFile) {
        this.modFile = modFile;
        pinnedMods.clear();
        if (!Files.isRegularFile(modFile)) return;
        try {
            for (String line : Files.readAllLines(modFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                pinnedMods.add(trimmed);
            }
            if (!pinnedMods.isEmpty()) {
                LOGGER.info("Loaded {} pinned mod namespace(s): {}", pinnedMods.size(), modList());
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read pinned-mods file {}: {}", modFile, e.toString());
        }
    }

    private void saveMods() {
        if (modFile == null) return;
        try {
            Path parent = modFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            List<String> lines = new ArrayList<>();
            lines.add("# NestWorld pinned mod namespaces — one per line.");
            lines.add("# Every entity and block entity from these mods ticks on the main thread.");
            lines.addAll(modList());
            Files.write(modFile, lines);
        } catch (IOException e) {
            LOGGER.warn("Could not write pinned-mods file {}: {}", modFile, e.toString());
        }
    }

    /**
     * Loads auto-pin error counts (entity/BE/mod-callback, all three) left over from a
     * PREVIOUS server run.
     *
     * <p><b>Why this exists:</b> the counters {@code errorCounts}/{@code beErrorCounts}/
     * {@code modCallbackErrorCounts} are plain in-memory maps -- fine for a bug that keeps
     * throwing without taking the server down, but useless for a bug whose failure mode IS
     * killing the server (a hard crash, a watchdog kill, an OOM) before {@code
     * autoPinThreshold} is ever reached in a single run. That bug then never gets pinned: it
     * crashes at count 3, the process dies, next boot starts back at 0, crashes at count 4,
     * dies again, forever -- the exact "past crashes" pattern the user flagged
     * 2026-09-07 that this load/save pair exists to break. Persisting the raw counts (not
     * just the final pinned-or-not state) means every crash's partial count is retained, so
     * the cumulative total across restarts eventually crosses the threshold even if no single
     * run does -- and if the very error that crossed it was also the one that killed the
     * process, the check below pins retroactively at the NEXT boot, before the dimension
     * even starts ticking, breaking the loop for good.
     *
     * <p>Called once at startup, after {@link #loadMods} (so a threshold-crossing entry
     * pins immediately without racing a first {@code save}/{@code saveBe}/{@code saveMods}
     * that hasn't run yet). No-op if auto-pin is off -- counts are still loaded (so enabling
     * the flag later inherits prior history) but nothing gets auto-pinned from stale data
     * while the operator hasn't opted in.
     */
    public void loadErrorCounts(Path file) {
        this.errorCountsFile = file;
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int colon = trimmed.indexOf(':');
                int eq = trimmed.lastIndexOf('=');
                if (colon < 0 || eq < colon) continue;
                String kind = trimmed.substring(0, colon);
                String key = trimmed.substring(colon + 1, eq);
                int count;
                try {
                    count = Integer.parseInt(trimmed.substring(eq + 1).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                switch (kind) {
                    case "entity" -> {
                        ResourceLocation id = ResourceLocation.tryParse(key);
                        EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
                        if (type != null) errorCounts.computeIfAbsent(type, k -> new AtomicInteger()).set(count);
                    }
                    case "be" -> beErrorCounts.computeIfAbsent(key, k -> new AtomicInteger()).set(count);
                    case "mod" -> modCallbackErrorCounts.computeIfAbsent(key, k -> new AtomicInteger()).set(count);
                    default -> { }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read error-counts file {}: {}", file, e.toString());
            return;
        }
        if (!autoPin) return;
        // Retroactive pin: an entry already at/above threshold from a previous run (most
        // likely the crash that ended it) gets pinned NOW, before anything ticks.
        errorCounts.forEach((type, counter) -> {
            if (counter.get() >= autoPinThreshold && pinnedTypes.add(type)) {
                ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                pinnedIds.add(id);
                save();
                recordPinReason("entity", String.valueOf(id),
                        "own-tick error, carried over across a restart/crash", counter.get(), null);
                LOGGER.warn("AUTO-PINNED {} to the main thread at startup -- {} region-thread "
                        + "tick errors carried over from a previous run crossed the threshold. "
                        + "Restore parallelism with /nestworld unpin {} once fixed.", id, counter.get(), id);
            }
        });
        beErrorCounts.forEach((typeId, counter) -> {
            if (counter.get() >= autoPinThreshold && pinnedBeIds.add(typeId)) {
                saveBe();
                recordPinReason("be", typeId,
                        "own-tick error, carried over across a restart/crash", counter.get(), null);
                LOGGER.warn("AUTO-PINNED block entity {} to the main thread at startup -- {} "
                        + "region-thread tick errors carried over from a previous run crossed "
                        + "the threshold. Restore parallelism with /nestworld unpin {} once fixed.",
                        typeId, counter.get(), typeId);
            }
        });
        modCallbackErrorCounts.forEach((modId, counter) -> {
            if (counter.get() >= autoPinThreshold && pinnedMods.add(modId)) {
                saveMods();
                recordPinReason("mod", modId,
                        "caught-elsewhere, carried over across a restart/crash "
                                + "(likely what was crashing/kicking players before this boot)",
                        counter.get(), null);
                LOGGER.warn("AUTO-PINNED mod {} to the main thread at startup -- {} errors "
                        + "carried over from a previous run crossed the threshold (this is "
                        + "very likely what was crashing/kicking players before this boot). "
                        + "Restore parallelism with /nestworld unpin {} once fixed.",
                        modId, counter.get(), modId);
            }
        });
    }

    /**
     * Throttled entry point for the three {@code note*Error} methods -- at most one actual
     * {@link #saveErrorCounts()} disk write per {@link #ERROR_COUNTS_SAVE_INTERVAL_NANOS}
     * regardless of how many increments arrive in that window (see {@link
     * #lastErrorCountsSaveNanos}'s javadoc for why: an unthrottled write-per-increment turns a
     * fast-firing bug's error storm into a self-inflicted synchronous-disk-I/O storm on the
     * SAME thread that just caught the original error -- for the main-thread player-tick
     * catch, that's exactly the tick-spike class of problem this whole project exists to
     * avoid). The CAS ensures concurrent callers within one window don't all redundantly
     * write; losing at most ~1 second of the most recent counts on a true crash is the
     * accepted trade.
     */
    private void saveErrorCountsThrottled() {
        long now = System.nanoTime();
        long last = lastErrorCountsSaveNanos.get();
        if (now - last < ERROR_COUNTS_SAVE_INTERVAL_NANOS) return;
        if (lastErrorCountsSaveNanos.compareAndSet(last, now)) {
            saveErrorCounts();
        }
    }

    /**
     * Rewrites the error-counts file from the current in-memory state of all three counters.
     * Called (throttled, via {@link #saveErrorCountsThrottled()}) after increments -- see
     * {@link #loadErrorCounts} javadoc for why partial progress must survive a crash. The
     * combined map is expected to stay small (a handful of distinct offending types/mods at
     * most; a healthy server pins nothing), so a full rewrite per call is cheap by itself --
     * it's the CALL FREQUENCY that needed bounding, not this method's own cost.
     */
    private void saveErrorCounts() {
        if (errorCountsFile == null) return;
        try {
            Path parent = errorCountsFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            List<String> lines = new ArrayList<>();
            lines.add("# NestWorld auto-pin error counts — survives a crash/restart so a bug");
            lines.add("# that crashes the server before one run reaches the threshold still");
            lines.add("# accumulates toward it across runs. Format: kind:key=count");
            errorCounts.forEach((type, counter) -> {
                ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                if (id != null) lines.add("entity:" + id + "=" + counter.get());
            });
            beErrorCounts.forEach((typeId, counter) -> lines.add("be:" + typeId + "=" + counter.get()));
            modCallbackErrorCounts.forEach((modId, counter) -> lines.add("mod:" + modId + "=" + counter.get()));
            Files.write(errorCountsFile, lines);
        } catch (IOException e) {
            LOGGER.warn("Could not write error-counts file {}: {}", errorCountsFile, e.toString());
        }
    }

    // -------------------------------------------------------------------
    // Pin reasons -- "why is this pinned" report, see PinReason javadoc
    // -------------------------------------------------------------------

    /** Records why a pin just fired and persists it immediately (pins are rare enough —
     *  at most one per distinct mod/type/BE — that this doesn't need the throttle
     *  {@link #saveErrorCountsThrottled} uses for the much hotter per-error counters). */
    private void recordPinReason(String kind, String key, String mechanism, int count, Throwable t) {
        String exClass = "";
        String exMsg = "";
        String topFrame = "";
        if (t != null) {
            exClass = t.getClass().getName();
            String msg = t.getMessage();
            exMsg = msg == null ? "" : msg.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
            StackTraceElement[] trace = t.getStackTrace();
            if (trace.length > 0) topFrame = trace[0].toString().replace('|', '/');
        }
        pinReasons.put(kind + ":" + key, new PinReason(kind, key, mechanism, count,
                autoPinThreshold, exClass, exMsg, topFrame, System.currentTimeMillis()));
        savePinReasons();
    }

    /** Reason for one pinned key ({@code "entity:minecraft:zombie"}, {@code "be:..."},
     *  {@code "mod:toms_storage"}), or {@code null} if it was pinned manually (no auto-pin
     *  trigger ever recorded one) or before this feature existed. */
    public PinReason getPinReason(String kind, String key) {
        return pinReasons.get(kind + ":" + key);
    }

    /** All recorded pin reasons, most-recent first. */
    public List<PinReason> allPinReasons() {
        List<PinReason> list = new ArrayList<>(pinReasons.values());
        list.sort((a, b) -> Long.compare(b.timestampMillis(), a.timestampMillis()));
        return list;
    }

    /** Loads persisted pin reasons from a previous run — same file layout {@link
     *  #savePinReasons} writes. Purely informational; never affects whether anything is
     *  actually pinned. Malformed lines are skipped, not fatal (matches every other loader
     *  in this class). */
    public void loadPinReasons(Path file) {
        this.pinReasonsFile = file;
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\\|", 8);
                if (parts.length < 8) continue;
                int colon = parts[0].indexOf(':');
                if (colon < 0) continue;
                String kind = parts[0].substring(0, colon);
                String key = parts[0].substring(colon + 1);
                try {
                    int count = Integer.parseInt(parts[2]);
                    int threshold = Integer.parseInt(parts[3]);
                    long ts = Long.parseLong(parts[7]);
                    pinReasons.put(kind + ":" + key, new PinReason(kind, key, parts[1], count,
                            threshold, parts[4], parts[5], parts[6], ts));
                } catch (NumberFormatException e) {
                    // skip malformed line
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read pin-reasons file {}: {}", file, e.toString());
        }
    }

    private void savePinReasons() {
        if (pinReasonsFile == null) return;
        try {
            Path parent = pinReasonsFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            List<String> lines = new ArrayList<>();
            lines.add("# NestWorld auto-pin reasons — why each pin fired, for /nestworld pinreason.");
            lines.add("# Format: kind:key|mechanism|errorCount|threshold|exceptionClass|exceptionMessage|topFrame|timestampMillis");
            pinReasons.forEach((k, reason) -> lines.add(k + "|" + reason.mechanism() + "|"
                    + reason.errorCount() + "|" + reason.threshold() + "|" + reason.exceptionClass()
                    + "|" + reason.exceptionMessage() + "|" + reason.topFrame() + "|" + reason.timestampMillis()));
            Files.write(pinReasonsFile, lines);
        } catch (IOException e) {
            LOGGER.warn("Could not write pin-reasons file {}: {}", pinReasonsFile, e.toString());
        }
    }
}
