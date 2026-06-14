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

    private Path file;

    /** True when nothing is pinned — lets callers skip the pinned pass entirely. */
    public boolean isEmpty() {
        return pinnedIds.isEmpty();
    }

    public boolean isPinned(EntityType<?> type) {
        return !pinnedTypes.isEmpty() && pinnedTypes.contains(type);
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
        if (type == null) return "No such entity type: " + id;
        pinnedIds.add(id);
        pinnedTypes.add(type);
        save();
        return null;
    }

    /** Unpins an id; returns true if it was pinned. */
    public boolean unpin(String idStr) {
        ResourceLocation id = ResourceLocation.tryParse(idStr);
        if (id == null) return false;
        boolean removed = pinnedIds.remove(id);
        BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(pinnedTypes::remove);
        if (removed) save();
        return removed;
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
}
