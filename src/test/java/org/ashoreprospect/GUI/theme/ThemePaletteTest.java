/**
 * The semantic colour record: token passthrough, contrast repair, and the CSS emitter.
 *
 * <p>Headless — only {@code javafx.scene.paint.Color} is reached, via {@link Contrast}.
 */
package org.ashoreprospect.GUI.theme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemePaletteTest {

    /** A deliberately illegible palette: ink nearly matches the surface behind it. */
    private static ThemePalette lowContrast() {
        return new ThemePalette(
                "#808080", "#808080", "#808080",
                "#828282", "#828282", "#808080",
                "#808080", "#808080", "#808080", "#808080",
                "#808080", "#828282",
                "#808080", "#828282", "#828282"
        );
    }

    private static ThemePalette legible() {
        return new ThemePalette(
                "#ffffff", "#eeeeee", "#ffffff",
                "#000000", "#333333", "#cccccc",
                "#ffffff", "#dddddd", "#eeeeee", "#f0f0f0",
                "#000000", "#ffffff",
                "#ffffff", "#000000", "#333333"
        );
    }

    // ==================================================================
    // record accessors
    // ==================================================================

    @Test
    void accessorsReturnTheConstructorArguments() {
        ThemePalette p = legible();

        assertEquals("#ffffff", p.bg());
        assertEquals("#eeeeee", p.chrome());
        assertEquals("#ffffff", p.panel());
        assertEquals("#000000", p.ink());
        assertEquals("#333333", p.muted());
        assertEquals("#cccccc", p.line());
        assertEquals("#ffffff", p.paper());
        assertEquals("#dddddd", p.past());
        assertEquals("#eeeeee", p.today());
        assertEquals("#f0f0f0", p.hover());
        assertEquals("#000000", p.accent());
        assertEquals("#ffffff", p.accentInk());
        assertEquals("#ffffff", p.cardPaper());
        assertEquals("#000000", p.cardInk());
        assertEquals("#333333", p.cardMuted());
    }

    @Test
    void recordsWithTheSameValuesAreEqual() {
        assertEquals(legible(), legible());
        assertEquals(legible().hashCode(), legible().hashCode());
    }

    @Test
    void recordsWithDifferentValuesAreNotEqual() {
        assertNotEquals(legible(), lowContrast());
    }

    // ==================================================================
    // contrastSafe
    // ==================================================================

    @Test
    void contrastSafe_leavesAlreadyLegibleInkAlone() {
        ThemePalette p = legible().contrastSafe();

        assertEquals("#000000", p.ink());
        assertEquals("#333333", p.muted());
        assertEquals("#000000", p.cardInk());
    }

    @Test
    void contrastSafe_repairsIllegibleInk() {
        // Mid-tone grey surface: the given ink is indistinguishable from the panel behind it.
        ThemePalette raw = new ThemePalette(
                "#ffffff", "#eeeeee", "#8a8a8a",
                "#8b8b8b", "#8c8c8c", "#cccccc",
                "#ffffff", "#dddddd", "#eeeeee", "#f0f0f0",
                "#000000", "#ffffff",
                "#ffffff", "#8b8b8b", "#8c8c8c"
        );
        ThemePalette fixed = raw.contrastSafe();

        assertNotEquals(raw.ink(), fixed.ink(), "ink should be replaced");
        assertNotEquals(raw.cardInk(), fixed.cardInk(), "card ink should be replaced");
        // The contract: whatever comes back must be one of the two safe extremes,
        // and must contrast better on the panel than the original ink did.
        assertTrue(fixed.ink().equals(Contrast.NEAR_BLACK) || fixed.ink().equals(Contrast.NEAR_WHITE),
                "repaired ink must be the near-black or near-white fallback");
        assertTrue(Contrast.ratio(fixed.ink(), raw.panel()) > Contrast.ratio(raw.ink(), raw.panel()),
                "repaired ink must contrast better than the original");
    }

    @Test
    void contrastSafe_picksDarkInkOnALightPanel() {
        ThemePalette raw = new ThemePalette(
                "#ffffff", "#eeeeee", "#fbf7ef",
                "#fafaf8", "#f9f9f7", "#cccccc",
                "#ffffff", "#dddddd", "#eeeeee", "#f0f0f0",
                "#000000", "#ffffff",
                "#ffffff", "#fafaf8", "#f9f9f7"
        );
        ThemePalette fixed = raw.contrastSafe();

        // Light surface + light ink -> the dark extreme is the readable choice.
        assertEquals(Contrast.NEAR_BLACK, fixed.ink());
        assertTrue(Contrast.ratio(fixed.ink(), raw.panel()) >= 4.5);
    }

    @Test
    void contrastSafe_onlyTouchesForegroundTokens() {
        ThemePalette raw = lowContrast();
        ThemePalette fixed = raw.contrastSafe();

        // Surfaces and accents are the caller's choice; only ink/muted get rewritten.
        assertEquals(raw.bg(), fixed.bg());
        assertEquals(raw.chrome(), fixed.chrome());
        assertEquals(raw.panel(), fixed.panel());
        assertEquals(raw.line(), fixed.line());
        assertEquals(raw.paper(), fixed.paper());
        assertEquals(raw.past(), fixed.past());
        assertEquals(raw.today(), fixed.today());
        assertEquals(raw.hover(), fixed.hover());
        assertEquals(raw.accent(), fixed.accent());
        assertEquals(raw.cardPaper(), fixed.cardPaper());
    }

    @Test
    void contrastSafe_isIdempotent() {
        ThemePalette once = lowContrast().contrastSafe();
        assertEquals(once, once.contrastSafe());
    }

    @Test
    void contrastSafe_returnsANewRecordRatherThanMutating() {
        ThemePalette raw = legible();
        ThemePalette fixed = raw.contrastSafe();

        assertNotSame(raw, fixed);
        assertEquals(legible(), raw);
    }

    // ==================================================================
    // tokenStyle
    // ==================================================================

    @Test
    void tokenStyle_interpolatesEachTokenIntoItsVariable() {
        ThemePalette p = legible();
        String css = p.tokenStyle();

        assertTrue(css.contains("-ashore-bg: #ffffff;"));
        assertTrue(css.contains("-ashore-ink: #000000;"));
        assertTrue(css.contains("-ashore-card-muted: #333333;"));
    }

    @Test
    void tokenStyle_endsWithTheDefaultFontStack() {
        assertTrue(legible().tokenStyle().endsWith(";"));
        assertTrue(legible().tokenStyle().contains("Segoe UI"));
    }

    @Test
    void tokenStyle_containsExactlyFifteenAshoreTokens() {
        String css = legible().tokenStyle();
        int count = css.split("-ashore-", -1).length - 1;

        // 15 looked-up tokens; "-fx-font-family" is the only non--ashore declaration.
        assertEquals(15, count);
    }

    @Test
    void tokenStyle_neverEmitsNull() {
        String css = legible().tokenStyle();
        assertFalse(css.contains("null"));
    }
}
