package net.nestworld.compat.ae2.glue;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Resource-bytecode identity for narrow optional-mod adapters; unavailable identity always rejects. */
final class AuditedBytecode {
    private static final ClassValue<String> SHA256 = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                return stream == null ? "" : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
            } catch (Exception unavailable) {
                return "";
            }
        }
    };

    private AuditedBytecode() {}

    static boolean matches(Class<?> type, String expected) {
        return expected.equals(SHA256.get(type));
    }
}
