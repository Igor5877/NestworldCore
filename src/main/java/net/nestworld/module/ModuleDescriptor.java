package net.nestworld.module;

/** MODULE-1: static metadata known before a module is ever loaded -- the {@code DISCOVERED}
 *  state's payload (`docs/MODULE0_AUDIT.md` §C). Startup-only discovery for MODULE-1: no jar
 *  scanning, no classpath discovery mechanism exists yet -- a {@link NestWorldModule} is
 *  discovered by being handed to {@link ModuleRegistry#discover} before server start. */
public record ModuleDescriptor(ModuleId id, ModuleVersion version, String displayName) {
    public ModuleDescriptor {
        if (displayName == null || displayName.isBlank()) {
            displayName = id.value();
        }
    }
}
