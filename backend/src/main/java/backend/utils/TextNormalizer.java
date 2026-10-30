package backend.utils;

public final class TextNormalizer {

    private TextNormalizer() {
    }

    /** Collapses whitespace/newlines from raw XML/HTML text nodes into a single clean line. */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("\\s+", " ").trim();
    }
}
