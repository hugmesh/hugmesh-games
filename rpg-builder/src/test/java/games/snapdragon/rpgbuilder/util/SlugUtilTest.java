package games.snapdragon.rpgbuilder.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlugUtilTest {

    @Test void acceptsLowercaseAlphanumericWithDashes() {
        assertTrue(SlugUtil.isValid("my-rpg"));
        assertTrue(SlugUtil.isValid("a"));
        assertTrue(SlugUtil.isValid("a1"));
        assertTrue(SlugUtil.isValid("0game"));
        assertTrue(SlugUtil.isValid("rpg-with-many-dashes"));
    }

    @Test void rejectsUppercase() {
        assertFalse(SlugUtil.isValid("MyRpg"));
        assertFalse(SlugUtil.isValid("MY-RPG"));
    }

    @Test void rejectsLeadingDashOrUnderscore() {
        assertFalse(SlugUtil.isValid("-foo"));
        assertFalse(SlugUtil.isValid("_foo"));
    }

    @Test void rejectsEmptyAndNull() {
        assertFalse(SlugUtil.isValid(""));
        assertFalse(SlugUtil.isValid(null));
    }

    @Test void rejectsOver64Chars() {
        assertFalse(SlugUtil.isValid("a".repeat(65)));
    }

    @Test void rejectsSpaceOrPunctuation() {
        assertFalse(SlugUtil.isValid("hello world"));
        assertFalse(SlugUtil.isValid("dot.slug"));
        assertFalse(SlugUtil.isValid("ünicode"));
    }
}
