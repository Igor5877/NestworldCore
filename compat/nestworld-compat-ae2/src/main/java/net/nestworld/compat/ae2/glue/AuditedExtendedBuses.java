package net.nestworld.compat.ae2.glue;

/** These exact ExtendedAE parts inherit the AE2 bus operation and scheduling bodies.
 * Their virtual operations-per-tick multiplier and upgrade-slot overrides remain in effect.
 * This is class admission only: all ordinary transit filter, strategy and domain checks must still run.
 */
final class AuditedExtendedBuses {
    private AuditedExtendedBuses() {}

    static boolean manages(Object part) {
        if (part == null) return false;
        Class<?> type = part.getClass();
        return switch (type.getName()) {
            case "com.glodblock.github.extendedae.common.parts.PartExImportBus" -> AuditedBytecode.matches(type,
                    "c3e7f9024b68dff68d024076dc55ad417739296033581dd2e86579db53fc4146");
            case "com.glodblock.github.extendedae.common.parts.PartExExportBus" -> AuditedBytecode.matches(type,
                    "4fb814447b13a35ded10b19bc29282f50bcf206d48180f3e17e8fac62a7fc7d8");
            default -> false;
        };
    }
}
