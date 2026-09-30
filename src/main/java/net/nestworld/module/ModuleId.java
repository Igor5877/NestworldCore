package net.nestworld.module;

/** MODULE-1: stable identity across reloads (e.g. {@code "example-module"}). Never changes for
 *  the same module across generations -- see {@link ModuleGeneration} for what DOES change. */
public record ModuleId(String value) {
    public ModuleId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ModuleId must not be blank");
        }
    }
}
