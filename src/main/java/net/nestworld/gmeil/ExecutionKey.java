package net.nestworld.gmeil;

import java.util.Objects;

/** Per-path policy key. Names identify a callback; they are not hardcoded safety exceptions. */
public record ExecutionKey(String loader, ExecutionSource source, String modId,
                           String ownerClass, String methodName, String descriptor) {
    public ExecutionKey {
        loader = requireText(loader, "loader");
        source = Objects.requireNonNull(source, "source");
        modId = requireText(modId, "modId");
        ownerClass = requireText(ownerClass, "ownerClass");
        methodName = requireText(methodName, "methodName");
        descriptor = requireText(descriptor, "descriptor");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is blank");
        return value;
    }
}
