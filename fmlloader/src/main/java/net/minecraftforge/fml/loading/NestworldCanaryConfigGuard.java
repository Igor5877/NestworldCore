package net.minecraftforge.fml.loading;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Explicit Canary exception: its overlapping collection/POI Mixins must be disabled before
 * class transformation. This changes only Canary's own config and never edits a mod JAR.
 */
public final class NestworldCanaryConfigGuard {
    public static final List<String> CONFLICTING_KEYS = List.of(
            "mixin.collections.entity_by_type",
            "mixin.collections.entity_filtering",
            "mixin.chunk.entity_class_groups",
            "mixin.ai.poi");

    private NestworldCanaryConfigGuard() {}

    public static boolean containsCanaryJar(Path modsDirectory) throws IOException {
        if (!Files.isDirectory(modsDirectory)) return false;
        try (Stream<Path> files = Files.list(modsDirectory)) {
            return files.filter(Files::isRegularFile).anyMatch(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".jar") && (name.equals("canary.jar")
                        || name.startsWith("canary-") || name.startsWith("canary_"));
            });
        }
    }

    public static boolean allDisabled(Path config) throws IOException {
        if (!Files.isRegularFile(config)) return false;
        Set<String> disabled = new HashSet<>();
        for (String line : Files.readAllLines(config, StandardCharsets.UTF_8)) {
            String key = optionKey(line);
            if (!CONFLICTING_KEYS.contains(key)) continue;
            if (line.strip().equals(key + "=false")) disabled.add(key);
            else disabled.remove(key);
        }
        return disabled.containsAll(CONFLICTING_KEYS);
    }

    /** Returns true only when the file had to be changed. Rewrites every conflicting key. */
    public static boolean ensureDisabled(Path config) throws IOException {
        if (Files.isSymbolicLink(config)) {
            throw new IOException("Refusing to replace symlinked Canary config: " + config);
        }
        List<String> lines = Files.exists(config)
                ? new ArrayList<>(Files.readAllLines(config, StandardCharsets.UTF_8))
                : new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            String key = optionKey(lines.get(i));
            if (!CONFLICTING_KEYS.contains(key)) continue;
            seen.add(key);
            String disabled = key + "=false";
            if (!lines.get(i).equals(disabled)) {
                lines.set(i, disabled);
                changed = true;
            }
        }
        for (String key : CONFLICTING_KEYS) {
            if (seen.add(key)) {
                lines.add(key + "=false");
                changed = true;
            }
        }
        if (!changed) return false;
        Files.createDirectories(config.getParent());
        Path temporary = Files.createTempFile(config.getParent(), "canary-nestworld-", ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, config, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, config, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return true;
    }

    private static String optionKey(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("#") || trimmed.startsWith("!")) return "";
        int separator = trimmed.indexOf('=');
        return separator < 0 ? "" : trimmed.substring(0, separator).strip();
    }
}
