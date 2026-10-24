package ci.selective.junit.runtime;

public final class Texts {
    private Texts() {}

    static boolean lone(int cp) {
        return cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE;
    }

    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> out.appendCodePoint(lone(cp) ? 0xFFFD : cp));
        return out.toString();
    }

    public static boolean loneSurrogate(String value) {
        return value.codePoints().anyMatch(Texts::lone);
    }

    public static boolean on(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }
}
