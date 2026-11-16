/**
 * The solid-colour theme pack: identity fields, kind, and the constructor's contrast normalising.
 *
 * <p>Headless — no toolkit startup is required.
 */
package org.ashoreprospect.GUI.theme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorThemePackTest {

    private static ThemePalette palette(String ink, String muted, String cardInk) {
        return new ThemePalette(
                "#ffffff", "#eeeeee", "#ffffff",
                ink, muted, "#cccccc",
                "#ffffff", "#dddddd", "#eeeeee", "#f0f0f0",
                "#000000", "#ffffff",
                "#ffffff", cardInk, muted
        );
    }

    // ==================================================================
    // identity
    // ==================================================================

    @Test
    void exposesIdAndDisplayName() {
        ColorThemePack pack = new ColorThemePack("ocean", "Ocean Blue", palette("#000000", "#333333", "#000000"));

        assertEquals("ocean", pack.id());
        assertEquals("Ocean Blue", pack.displayName());
    }

    @Test
    void kindIsAlwaysColor() {
        ColorThemePack pack = new ColorThemePack("x", "X", palette("#000000", "#333333", "#000000"));
        assertEquals(ThemeKind.COLOR, pack.kind());
    }

    @Test
    void idAndDisplayNameAreNotSwapped() {
        ColorThemePack pack = new ColorThemePack("the-id", "The Name", palette("#000000", "#333333", "#000000"));

        assertNotEquals(pack.id(), pack.displayName());
        assertEquals("the-id", pack.id());
        assertEquals("The Name", pack.displayName());
    }

    @Test
    void acceptsBlankIdentityWithoutThrowing() {
        ColorThemePack pack = new ColorThemePack("", "", palette("#000000", "#333333", "#000000"));

        assertEquals("", pack.id());
        assertEquals("", pack.displayName());
    }

    @Test
    void isAssignableToThemePack() {
        ThemePack pack = new ColorThemePack("x", "X", palette("#000000", "#333333", "#000000"));

        assertNotNull(pack);
        assertEquals("x", pack.id());
    }

    // ==================================================================
    // construction normalises contrast
    // ==================================================================

    @Test
    void constructorAppliesContrastSafety() {
        ThemePalette raw = palette("#feffff", "#fefefe", "#fdfdfd"); // almost invisible on white
        ColorThemePack pack = new ColorThemePack("bad", "Bad", raw);

        ThemePalette got = pack.palette();
        assertNotEquals(raw.ink(), got.ink(), "low-contrast ink must be repaired");
        assertTrue(Contrast.ratio(got.ink(), got.panel()) >= 4.5);
    }

    @Test
    void constructorLeavesAGoodPaletteIntact() {
        ThemePalette raw = palette("#000000", "#333333", "#000000");
        ColorThemePack pack = new ColorThemePack("good", "Good", raw);

        assertEquals(raw.ink(), pack.palette().ink());
        assertEquals(raw.muted(), pack.palette().muted());
    }

    @Test
    void constructorStoresANormalisedCopyNotTheCallerInstance() {
        ThemePalette raw = palette("#000000", "#333333", "#000000");
        ColorThemePack pack = new ColorThemePack("good", "Good", raw);

        assertNotSame(raw, pack.palette());
        assertEquals(raw, pack.palette());
    }

    @Test
    void paletteIsStableAcrossReads() {
        ColorThemePack pack = new ColorThemePack("x", "X", palette("#000000", "#333333", "#000000"));

        assertEquals(pack.palette(), pack.palette());
        // Records are immutable, so the same instance can be handed out repeatedly.
        assertTrue(pack.palette() == pack.palette());
    }

    @Test
    void twoPacksWithTheSameIdRemainDistinctObjects() {
        ColorThemePack a = new ColorThemePack("dup", "One", palette("#000000", "#333333", "#000000"));
        ColorThemePack b = new ColorThemePack("dup", "Two", palette("#000000", "#333333", "#000000"));

        assertEquals(a.id(), b.id());
        assertNotEquals(a.displayName(), b.displayName());
        assertNotSame(a, b);
    }
}
