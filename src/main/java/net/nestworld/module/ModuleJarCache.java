package net.nestworld.module;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Classloaders are reused for the JVM lifetime; changing an already-loaded jar requires restart. */
final class ModuleJarCache {
    private record Entry(long size, long modified, String sha256, ModuleClassLoader loader) {}
    private static final Map<Path, Entry> CACHE = new HashMap<>();

    private ModuleJarCache() {}

    static synchronized ModuleClassLoader obtain(Path jar, ModuleId id) throws Exception {
        Path canonical = jar.toRealPath();
        long size = Files.size(canonical);
        long modified = Files.getLastModifiedTime(canonical).toMillis();
        String hash = sha256(canonical);
        Entry old = CACHE.get(canonical);
        if (old != null) {
            if (old.size != size || old.modified != modified || !old.sha256.equals(hash)) {
                throw new IllegalStateException("jar changed since this JVM loaded it; restart the process: " + canonical);
            }
            return old.loader;
        }
        URL url = canonical.toUri().toURL();
        ModuleClassLoader loader = new ModuleClassLoader(id.value(), url, NestWorldModule.class.getClassLoader());
        CACHE.put(canonical, new Entry(size, modified, hash, loader));
        return loader;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) >= 0;) if (n > 0) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
