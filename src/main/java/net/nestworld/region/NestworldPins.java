package net.nestworld.region;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
     */
    public boolean noteEntityTickError(EntityType<?> type) {
        if (!autoPin || type == null || pinnedTypes.contains(type)) return false;
        int count = errorCounts.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
        if (count < autoPinThreshold) return false;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (!pinnedTypes.add(type)) return false; // pinned concurrently
        pinnedIds.add(id);
        save();
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
     */
    public boolean noteBlockEntityTickError(String typeId) {
        if (!autoPin || typeId == null || pinnedBeIds.contains(typeId)) return false;
        int count = beErrorCounts.computeIfAbsent(typeId, k -> new AtomicInteger()).incrementAndGet();
        if (count < autoPinThreshold) return false;
        if (!pinnedBeIds.add(typeId)) return false; // pinned concurrently
        saveBe();
        LOGGER.warn("AUTO-PINNED block entity {} to the main thread after {} region-thread "
                + "tick errors. Restore parallelism with /nestworld unpin {} once fixed.",
                typeId, count, typeId);
        return true;
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
        boolean below = p.noteEntityTickError(type) | p.noteEntityTickError(type); // 1,2 < 3
        boolean atThreshold = p.noteEntityTickError(type);                          // 3 -> pin
        boolean isPinnedNow = p.isPinned(type);
        boolean idempotent = !p.noteEntityTickError(type);                          // already pinned

        // Same threshold path for block-entity types (id strings).
        NestworldPins pb = new NestworldPins(true, 3);
        String be = "minecraft:hopper";
        boolean beBelow = pb.noteBlockEntityTickError(be) | pb.noteBlockEntityTickError(be);
        boolean beAt = pb.noteBlockEntityTickError(be);
        boolean beIsPinned = pb.isBePinned(be);
        boolean beIdem = !pb.noteBlockEntityTickError(be);

        boolean ok = !below && atThreshold && isPinnedNow && idempotent
                && !beBelow && beAt && beIsPinned && beIdem;
        LOGGER.info("[AUTOPIN SELF-TEST] {} (entity: below={} at={} pinned={} idem={}; "
                + "be: below={} at={} pinned={} idem={})", ok ? "PASS" : "FAIL",
                !below, atThreshold, isPinnedNow, idempotent,
                !beBelow, beAt, beIsPinned, beIdem);
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
}
