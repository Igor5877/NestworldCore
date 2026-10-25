package net.nestworld.module;

/** MODULE-1: stable identity across reloads (e.g. {@code "example-module"}). Never changes for
 *  the same module across generations -- see {@link ModuleGeneration} for what DOES change. */
public record ModuleId(String value) {
    public ModuleId {
        if (value == null || !value.matches("^[a-z0-9]+(?:[._-][a-z0-9]+)*$") || value.length() < 2 || value.length() > 64) {
            throw new IllegalArgumentException("ModuleId must be 2..64 canonical lowercase characters");
        }
    }
}
