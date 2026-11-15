/**
 * WCAG-style contrast helpers and the palette normaliser.
 *
 * <p>Only {@code javafx.scene.paint.Color} is used, which needs no toolkit startup, so
 * these tests stay headless.
 */
package org.ashoreprospect.GUI.theme;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContrastTest {

    private static final double EPS = 1e-9;

    // ---------- parse ----------

    @Test
    void parse_fallsBackToBlackForNullBlankAndGarbage() {
        assertEquals(Color.BLACK, Contrast.parse(null));
        assertEquals(Color.BLACK, Contrast.parse(""));
        assertEquals(Color.BLACK, Contrast.parse("   "));
        assertEquals(Color.BLACK, Contrast.parse("not-a-color"));

        assertEquals(Color.web("#336699"), Contrast.parse("  #336699  "));
        assertEquals(Color.web("#336699"), Contrast.parse("#336699"));
    }

    // ---------- luminance / ratio ----------

    @Test
    void luminance_matchesTheWcagReferencePoints() {
        assertEquals(0.0, Contrast.luminance(Color.BLACK), EPS);
        assertEquals(1.0, Contrast.luminance(Color.WHITE), EPS);
        // The sRGB -> linear transfer function is linear below 0.04045.
        assertEquals(0.04045 / 12.92, Contrast.luminance(Color.gray(0.04045)), EPS);
        // A null color is treated as fully dark.
        assertEquals(0.0, Contrast.luminance((Color) null), EPS);
        // The hex overload parses first, so garbage behaves like black.
        assertEquals(0.0, Contrast.luminance("nonsense"), EPS);
        assertEquals(Contrast.luminance("#ffffff"), Contrast.luminance(Color.WHITE), EPS);
    }

    @Test
    void ratio_isSymmetricAndBounded() {
        assertEquals(21.0, Contrast.ratio("#000000", "#ffffff"), EPS);
        assertEquals(21.0, Contrast.ratio("#ffffff", "#000000"), EPS);
        assertEquals(1.0, Contrast.ratio("#123456", "#123456"), EPS);
        assertEquals(Contrast.ratio("#336699", "#fbf7ef"),
                Contrast.ratio("#fbf7ef", "#336699"), EPS);
    }

    @Test
    void nearBlackAndNearWhite_clearTheMinimumRatioOnEachOther() {
        assertTrue(Contrast.ratio(Contrast.NEAR_BLACK, Contrast.NEAR_WHITE) >= 4.5);
        assertEquals("#1c2430", Contrast.NEAR_BLACK);
        assertEquals("#fbf7ef", Contrast.NEAR_WHITE);
    }

    // ---------- inkOn ----------

    @Test
    void inkOn_keepsAnInkThatAlreadyContrasts() {
        assertEquals("#1c2430", Contrast.inkOn("#fbf7ef", "#1c2430"));
        assertEquals("#fbf7ef", Contrast.inkOn("#101418", "#fbf7ef"));
    }

    @Test
    void inkOn_swapsToNearBlackOnLightPaperAndNearWhiteOnDarkPaper() {
        // Near-white text on near-white paper is the classic clash.
        assertEquals(Contrast.NEAR_BLACK, Contrast.inkOn("#fffffe", "#fbf7ef"));

        // Dark text on near-black paper, same idea.
        assertEquals(Contrast.NEAR_WHITE, Contrast.inkOn("#101418", "#1c2430"));
    }

    /** Pinned: the light/dark switch is luminance > 0.55, not the darkSurface() cutoff. */
    @Test
    void inkOn_usesA0point55LuminanceCutoff() {
        // #c4c4c4 has luminance just over 0.55, so it takes dark ink.
        assertTrue(Contrast.luminance("#c4c4c4") > 0.55);
        assertEquals(Contrast.NEAR_BLACK, Contrast.inkOn("#c4c4c4", "#c4c4c4"));

        // #c3c3c3 is just under, so it takes light ink.
        assertTrue(Contrast.luminance("#c3c3c3") < 0.55);
        assertEquals(Contrast.NEAR_WHITE, Contrast.inkOn("#c3c3c3", "#c3c3c3"));
    }

    // ---------- mutedOn ----------

    @Test
    void mutedOn_keepsTheMutedColorWhenItClearsTheLowerThreshold() {
        // 3.0 is the threshold for secondary text.
        assertEquals("#5a6470", Contrast.mutedOn("#fbf7ef", "#5a6470"));
    }

    @Test
    void mutedOn_blendsTowardsTheBackgroundWhenTooClose() {
        String blended = Contrast.mutedOn("#808080", "#808080");

        assertNotEquals("#808080", blended);
        assertTrue(blended.matches("#[0-9a-f]{6}"), blended);
        // The result sits between the two inputs, so it can never be pure black/white.
        assertNotEquals(Contrast.NEAR_BLACK, blended);
        assertNotEquals(Contrast.NEAR_WHITE, blended);
    }

    // ---------- darkSurface / toHex ----------

    @Test
    void darkSurface_usesThe0point45Cutoff() {
        assertTrue(Contrast.darkSurface("#000000"));
        assertTrue(Contrast.darkSurface("#1c2430"));
        assertFalse(Contrast.darkSurface("#ffffff"));
        // #b3b3b3 sits just above 0.45, #b2b2b2 just below.
        assertFalse(Contrast.darkSurface("#b3b3b3"));
        assertTrue(Contrast.darkSurface("#b2b2b2"));
        // A null hex parses to black.
        assertTrue(Contrast.darkSurface(null));
    }

    @Test
    void toHex_roundsChannelsAndFallsBackForNull() {
        assertEquals("#000000", Contrast.toHex(Color.BLACK));
        assertEquals("#ffffff", Contrast.toHex(Color.WHITE));
        assertEquals("#336699", Contrast.toHex(Color.web("#336699")));
        assertEquals(Contrast.NEAR_BLACK, Contrast.toHex(null));
        // Opaque white renders as ff even when the source carries a different alpha.
        assertEquals("#ffffff", Contrast.toHex(Color.web("#ffffff", 0.4)));
    }

    // ---------- ThemePalette.contrastSafe ----------

    @Test
    void contrastSafe_leavesTheStructuralColorsAloneAndFixesOnlyTheText() {
        ThemePalette raw = new ThemePalette(
                "#ffffff", "#eeeeee", "#808080",
                "#777777", "#7a7a7a", "#dddddd", "#fafafa", "#e0e0e0", "#fffbe0",
                "#f0f0f0", "#777777", "#787878", "#808080", "#777777", "#7a7a7a");

        ThemePalette safe = raw.contrastSafe();

        assertEquals(raw.bg(), safe.bg());
        assertEquals(raw.chrome(), safe.chrome());
        assertEquals(raw.panel(), safe.panel());
        assertEquals(raw.line(), safe.line());
        assertEquals(raw.paper(), safe.paper());
        assertEquals(raw.past(), safe.past());
        assertEquals(raw.today(), safe.today());
        assertEquals(raw.hover(), safe.hover());
        assertEquals(raw.accent(), safe.accent());
        assertEquals(raw.cardPaper(), safe.cardPaper());

        assertEquals(Contrast.inkOn(raw.panel(), raw.ink()), safe.ink());
        assertEquals(Contrast.mutedOn(raw.panel(), raw.muted()), safe.muted());
        assertEquals(Contrast.inkOn(raw.accent(), raw.accentInk()), safe.accentInk());
        assertEquals(Contrast.inkOn(raw.cardPaper(), raw.cardInk()), safe.cardInk());
        assertEquals(Contrast.mutedOn(raw.cardPaper(), raw.cardMuted()), safe.cardMuted());

        assertNotEquals(raw.ink(), safe.ink());
    }

    @Test
    void contrastSafe_isIdempotentForACompliantPalette() {
        ThemePalette good = new ThemePalette(
                "#fbf7ef", "#f3ede2", "#fbf7ef",
                "#1c2430", "#5a6470", "#ddd6c8", "#ffffff", "#ece6da", "#fff7d6",
                "#f0ead9", "#22506e", "#fbf7ef", "#ffffff", "#1c2430", "#5a6470");

        ThemePalette once = good.contrastSafe();
        ThemePalette twice = once.contrastSafe();

        assertEquals(once, twice);
        assertEquals(good, once, "an already-compliant palette is returned unchanged");
    }

    @Test
    void tokenStyle_emitsEveryTokenWithTheFxCssNames() {
        ThemePalette palette = new ThemePalette(
                "#fbf7ef", "#f3ede2", "#fbf7ef",
                "#1c2430", "#5a6470", "#ddd6c8", "#ffffff", "#ece6da", "#fff7d6",
                "#f0ead9", "#22506e", "#fbf7ef", "#ffffff", "#1c2430", "#5a6470");

        String style = palette.tokenStyle();

        for (String token : new String[]{
                "-ashore-bg", "-ashore-chrome", "-ashore-panel", "-ashore-ink",
                "-ashore-muted", "-ashore-line", "-ashore-paper", "-ashore-past",
                "-ashore-today", "-ashore-hover", "-ashore-accent", "-ashore-accent-ink",
                "-ashore-card", "-ashore-card-ink", "-ashore-card-muted", "-fx-font-family"}) {
            assertTrue(style.contains(token + ":"), "missing token " + token);
        }
        assertTrue(style.contains("-ashore-ink: #1c2430;"));
        assertTrue(style.endsWith(";"));
    }
}
