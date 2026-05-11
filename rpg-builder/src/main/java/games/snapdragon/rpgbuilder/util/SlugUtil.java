package games.snapdragon.rpgbuilder.util;

import java.util.regex.Pattern;

public final class SlugUtil {
    /**
     * Same constraint the BFF enforces in {@code GameUploadService}:
     * lower-case alphanumeric, dashes ok, must start with alphanumeric,
     * 1-64 chars. Keeping these rules identical means every slug an
     * editor saves can pass straight through to the upload endpoint
     * without surprise rejection.
     */
    public static final Pattern SLUG_RE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private SlugUtil() {}

    public static boolean isValid(String s) {
        return s != null && SLUG_RE.matcher(s).matches();
    }
}
