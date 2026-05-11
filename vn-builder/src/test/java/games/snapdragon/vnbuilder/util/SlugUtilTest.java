package games.snapdragon.vnbuilder.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mirrors the rpg-builder SlugUtilTest; same regex, same expectations. */
class SlugUtilTest {

    @Test void acceptsValid() {
        assertTrue(SlugUtil.isValid("my-vn"));
        assertTrue(SlugUtil.isValid("a1"));
    }

    @Test void rejectsBoundaryFailures() {
        assertFalse(SlugUtil.isValid(null));
        assertFalse(SlugUtil.isValid(""));
        assertFalse(SlugUtil.isValid("Bad"));
        assertFalse(SlugUtil.isValid("-leading"));
        assertFalse(SlugUtil.isValid("a".repeat(65)));
    }
}
