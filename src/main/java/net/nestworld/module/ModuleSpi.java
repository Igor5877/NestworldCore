package net.nestworld.module;

import java.util.Map;
import java.util.Set;

/** Versioned public surface implemented by Core and consumed by external module jars. */
public final class ModuleSpi {
    public static final String VERSION = "1.0";
    private static final Map<String, Integer> CAPABILITIES = Map.of(
            "commands", 1,
            "listeners", 1,
            "loginBudget", 1,
            "chunkPreparation", 1,
            "syncData", 1
    );

    private ModuleSpi() {}

    public static int capabilityVersion(String name) {
        return CAPABILITIES.getOrDefault(name, 0);
    }

    static Set<String> capabilityNames() { return CAPABILITIES.keySet(); }
}
