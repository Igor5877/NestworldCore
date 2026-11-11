package net.nestworld.compat.ae2.glue;

import net.minecraftforge.common.capabilities.ForgeCapabilities;

/** Narrow proof that the exact source cannot expose FE/soul capabilities to an unfiltered import facade.
 * Call only in the source's execution domain, after normal lifecycle/domain validation. This does not
 * certify a controller/port ownership relationship, change the import filter, or authorize other source types.
 */
final class CertifiedItemImportSources {
    private static final String MM_BE = "io.ticticboom.mods.mm.port.item.register.ItemPortBlockEntity";
    private static final String MM_STORAGE = "io.ticticboom.mods.mm.port.item.ItemPortStorage";

    private CertifiedItemImportSources() {}

    static boolean mmItemPort(Object source) {
        if (source == null || !MM_BE.equals(source.getClass().getName())) return false;
        Class<?> type = source.getClass();
        if (!AuditedBytecode.matches(type, "f43085cbe1b7fdbd61086ded208d6da2ad8c555b1d2df6ac10b3f08db9248573")) return false;
        try {
            Object storage = type.getMethod("getStorage").invoke(source);
            if (storage == null || !MM_STORAGE.equals(storage.getClass().getName())
                    || !AuditedBytecode.matches(storage.getClass(), "31958024f5b6113f52bde67a421faee4577a83887caea481f7e1e8de40fd87b7")) return false;
            ClassLoader loader = type.getClassLoader();
            Class<?> aeStorage = Class.forName("io.ticticboom.mods.mm.compat.ae2.Ae2ItemPortStorage", false, loader);
            if (!AuditedBytecode.matches(aeStorage, "139519998892fb399709387d38d7684c70de73c1c62b347fb79cf4ef17572cda")) return false;
            Class<?> caps = Class.forName("io.ticticboom.mods.mm.cap.MMCapabilities", false, loader);
            return AuditedBytecode.matches(caps, "b893b517c9fd6f4eb1f0aecb93fffedda06fe96c00660178b129df5965a11a99")
                    && caps.getField("ITEM").get(null) == ForgeCapabilities.ITEM_HANDLER;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }
}
