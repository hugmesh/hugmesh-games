package games.hugmesh.vnbuilder.util;

import java.util.regex.Pattern;

public final class SlugUtil {
    public static final Pattern SLUG_RE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private SlugUtil() {}
    public static boolean isValid(String s) {
        return s != null && SLUG_RE.matcher(s).matches();
    }
}
