package net.nestworld.module;

/** MODULE-1: the module's code revision -- changes when its jar/artifact changes. Distinct from
 *  {@link ModuleGeneration}, which changes on every LOAD even when the version doesn't. */
public record ModuleVersion(String value) {
    public ModuleVersion {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ModuleVersion must not be blank");
        }
    }
}
