package net.nestworld.module;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 with length-framed fields and a versioned domain separator. Caller supplies stable order. */
public final class ContentGeneration {
    private final MessageDigest digest;

    public ContentGeneration(String domain) {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        field("nestworld-sync-v1/" + domain);
    }

    public void field(String value) { field(value.getBytes(StandardCharsets.UTF_8)); }

    public void field(byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    public String finish() { return HexFormat.of().formatHex(digest.digest()); }
}
