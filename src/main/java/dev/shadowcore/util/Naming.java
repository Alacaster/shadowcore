package dev.shadowcore.util;

import java.util.Locale;

public final class Naming {

    /** Minecraft's hard limit on player/profile names. */
    public static final int MAX_NAME_LENGTH = 16;

    /** Maximum suffix length. */
    public static final int MAX_SUFFIX_LENGTH = 10;

    public static final String SUFFIX_PATTERN = "[a-z0-9_-]{1," + MAX_SUFFIX_LENGTH + "}";

    private Naming() {}

    public static String normalizeSuffix(final String suffix) {
        return suffix.toLowerCase(Locale.ROOT);
    }

    public static String normalizeKey(final String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    public static boolean isValidSuffix(final String suffix) {
        return suffix != null && suffix.matches(SUFFIX_PATTERN);
    }

    /**
     * Builds the full local identity name within the 16-character limit.
     * <p>
     * Strategy: try {@code base + "_" + suffix} first. If it exceeds 16 characters,
     * we build a 16-character string where the suffix is placed at the end and eats
     * into the base name. This ensures even players with 16-character names can have
     * a suffix — they just lose some base name characters.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code ("Alice", "pvp")} → {@code "Alice_pvp"} (9 chars, fits)</li>
     *   <li>{@code ("abcdefghijklmnop", "27")} → {@code "abcdefghijklm_27"} (16 chars)</li>
     *   <li>{@code ("abcdefghijklmnop", "pvpmaster")} → {@code "abcdef_pvpmaster"} (16 chars)</li>
     * </ul>
     */
    public static String fullLocalName(final String ownerBaseName, final String suffix) {
        final String candidate = ownerBaseName + "_" + suffix;
        if (candidate.length() <= MAX_NAME_LENGTH) {
            return candidate;
        }
        // Build backwards: suffix occupies the tail, underscore before it, base fills the rest
        final int suffixWithSep = 1 + suffix.length(); // "_" + suffix
        final int baseSpace = MAX_NAME_LENGTH - suffixWithSep;
        if (baseSpace <= 0) {
            // Suffix alone fills 16 chars — just truncate the whole thing
            return candidate.substring(0, MAX_NAME_LENGTH);
        }
        return ownerBaseName.substring(0, baseSpace) + "_" + suffix;
    }
}
