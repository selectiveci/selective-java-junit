package ci.selective.junit.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class WireIds {
    private WireIds() {}

    public static String wire(String id) {
        byte[] raw = id.getBytes(StandardCharsets.UTF_8);
        if (id.indexOf('\0') < 0 && !Texts.loneSurrogate(id) && raw.length <= 255) {
            return id;
        }
        return prefix(id, 190) + "~" + sha256(raw);
    }

    private static String prefix(String id, int limit) {
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int cp : id.codePoints().toArray()) {
            if (cp == 0 || Texts.lone(cp)) {
                continue;
            }
            int width = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + width > limit) {
                break;
            }
            out.appendCodePoint(cp);
            bytes += width;
        }
        return out.toString();
    }

    private static String sha256(byte[] raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
