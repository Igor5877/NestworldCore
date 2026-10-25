package net.nestworld.module;

/** Static metadata carried by the {@code DISCOVERED} state. For external jars the loader reads
 * the equivalent data from {@code META-INF/nestworld/module.json} before loading the entrypoint,
 * then cross-checks this runtime descriptor. */
public record ModuleDescriptor(ModuleId id, ModuleVersion version, String displayName) {
    public ModuleDescriptor {
        if (displayName == null || displayName.isBlank()) {
            displayName = id.value();
        }
    }
}
