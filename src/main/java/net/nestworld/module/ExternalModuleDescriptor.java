package net.nestworld.module;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record ExternalModuleDescriptor(Path jar, ModuleDescriptor module, String entrypoint, String spi,
                                Map<String, Integer> capabilities, String side, boolean critical,
                                String mappings, List<Dependency> dependencies) {
    record Dependency(ModuleId id, String versionRange, boolean required) {}
}
