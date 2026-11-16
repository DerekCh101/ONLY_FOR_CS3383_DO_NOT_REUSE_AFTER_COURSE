/**
 * Built-in theme packs, the palette normaliser, and the CSS token emitter.
 *
 * <p>No toolkit is needed: {@code Contrast} only touches {@code javafx.scene.paint.Color}, which
 * is plain data. This is the same headless trick {@code ContrastTest} relies on.
 */
package org.ashoreprospect.GUI.theme;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinThemesTest {

    private static final String HEX = "#[0-9a-fA-F]{6}";

    // ==================================================================
    // all() / identity
    // ==================================================================

    @Test
    void all_returnsLightThenDark() {
        List<ThemePack> packs = BuiltinThemes.all();

        assertEquals(2, packs.size());
        assertSame(BuiltinThemes.LIGHT, packs.get(0));
        assertSame(BuiltinThemes.DARK, packs.get(1));
    }

    @Test
    void all_isStableAcrossCalls() {
        assertSame(BuiltinThemes.LIGHT, BuiltinThemes.all().get(0));
        assertEquals(BuiltinThemes.all(), BuiltinThemes.all());
    }

    @Test
    void idsAndNamesMatchTheMenuLabels() {
        assertEquals("light", BuiltinThemes.LIGHT.id());
        assertEquals("Light", BuiltinThemes.LIGHT.displayName());
        assertEquals("dark", BuiltinThemes.DARK.id());
        assertEquals("Dark", BuiltinThemes.DARK.displayName());
    }

    @Test
    void idsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (ThemePack p : BuiltinThemes.all()) ids.add(p.id());
        assertEquals(2, ids.size());
    }

    @Test
    void kindIsColorForBothPacks() {
        assertEquals(ThemeKind.COLOR, BuiltinThemes.LIGHT.kind());
        assertEquals(ThemeKind.COLOR, BuiltinThemes.DARK.kind());
    }

    // ==================================================================
    // Palette integrity
    // ==================================================================

    @Test
    void everyPaletteTokenIsAHexColor() {
        for (ThemePack pack : BuiltinThemes.all()) {
            ThemePalette p = pack.palette();
            for (String token : new String[]{
                    p.bg(), p.chrome(), p.panel(), p.ink(), p.muted(), p.line(),
                    p.paper(), p.past(), p.today(), p.hover(), p.accent(), p.accentInk(),
                    p.cardPaper(), p.cardInk(), p.cardMuted()}) {
                assertNotNull(token, pack.id() + " has a null token");
                assertTrue(token.matches(HEX), pack.id() + " token not hex: " + token);
            }
        }
    }

    @Test
    void palettesSurviveContrastSafetyUntouchedWhenAlreadyLegible() {
        // ColorThemePack runs contrastSafe() in its constructor, so a second pass must be a no-op.
        for (ThemePack pack : BuiltinThemes.all()) {
            ThemePalette once = pack.palette();
            ThemePalette twice = once.contrastSafe();
            assertEquals(once, twice, pack.id() + " palette is not contrast-stable");
        }
    }

    @Test
    void lightIsLightAndDarkIsDark() {
        assertFalse(Contrast.darkSurface(BuiltinThemes.LIGHT.palette().bg()));
        assertTrue(Contrast.darkSurface(BuiltinThemes.DARK.palette().bg()));
    }

    @Test
    void lightMatchesTheDocumentedWeekTileColors() {
        ThemePalette light = BuiltinThemes.LIGHT.palette();
        // ADVANCE_BEHAVIOUR 8a: light theme keeps cream paper / today / past.
        assertEquals("#ffffff", light.paper());
        assertEquals("#bdc8c8", light.past());
        assertEquals("#70d9f1", light.today());
    }

    @Test
    void darkMatchesTheDocumentedWeekTileColors() {
        ThemePalette dark = BuiltinThemes.DARK.palette();
        // ADVANCE_BEHAVIOUR 8a: dark paper #243044, today #2e3c56, past #0b0c0e, hover #3b4c6a.
        assertEquals("#243044", dark.paper());
        assertEquals("#2e3c56", dark.today());
        assertEquals("#0b0c0e", dark.past());
        assertEquals("#3b4c6a", dark.hover());
    }

    // ==================================================================
    // tokenStyle
    // ==================================================================

    @Test
    void tokenStyle_emitsEveryLookedUpToken() {
        String css = BuiltinThemes.LIGHT.palette().tokenStyle();

        for (String key : new String[]{
                "-ashore-bg", "-ashore-chrome", "-ashore-panel", "-ashore-ink", "-ashore-muted",
                "-ashore-line", "-ashore-paper", "-ashore-past", "-ashore-today", "-ashore-hover",
                "-ashore-accent", "-ashore-accent-ink", "-ashore-card", "-ashore-card-ink",
                "-ashore-card-muted"}) {
            assertTrue(css.contains(key + ":"), "missing token " + key);
        }
    }

    @Test
    void tokenStyle_setsTheFontFamily() {
        assertTrue(BuiltinThemes.DARK.palette().tokenStyle().contains("-fx-font-family:"));
    }

    @Test
    void tokenStyle_isWellFormed() {
        String css = BuiltinThemes.DARK.palette().tokenStyle();

        assertTrue(css.endsWith(";"), "style must end with a semicolon");
        assertFalse(css.contains(";;"), "no empty declarations");
        assertFalse(css.contains("null"), "no null leaked into the stylesheet");
        // Balanced braces would break the inline style; these tokens are flat declarations.
        assertFalse(css.contains("{") || css.contains("}"));
    }

    @Test
    void tokenStyle_isDifferentPerTheme() {
        String light = BuiltinThemes.LIGHT.palette().tokenStyle();
        String dark = BuiltinThemes.DARK.palette().tokenStyle();

        assertFalse(light.equals(dark));
        assertTrue(light.contains(BuiltinThemes.LIGHT.palette().bg()));
        assertTrue(dark.contains(BuiltinThemes.DARK.palette().bg()));
    }
}
