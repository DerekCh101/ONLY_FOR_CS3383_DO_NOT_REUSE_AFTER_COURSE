/** Named color tokens (paper, ink, panel, muted) one pack must fill. */
package org.ashoreprospect.GUI.theme;

/**
 * Semantic colors for one pack. Widgets should read these (or CSS tokens),
 * not hard-coded hex. {@link #contrastSafe()} fixes ink/background clashes.
 */
public record ThemePalette(
        String bg,
        String chrome,
        String panel,
        String ink,
        String muted,
        String line,
        String paper,
        String past,
        String today,
        String hover,
        String accent,
        String accentInk,
        String cardPaper,
        String cardInk,
        String cardMuted
) {
    public ThemePalette contrastSafe() {
        return new ThemePalette(
                bg,
                chrome,
                panel,
                Contrast.inkOn(panel, ink),
                Contrast.mutedOn(panel, muted),
                line,
                paper,
                past,
                today,
                hover,
                accent,
                Contrast.inkOn(accent, accentInk),
                cardPaper,
                Contrast.inkOn(cardPaper, cardInk),
                Contrast.mutedOn(cardPaper, cardMuted)
        );
    }

    /** Looked-up color tokens for a scene / popup root. */
    public String tokenStyle() {
        ThemePalette p = this;
        return "-ashore-bg: " + p.bg() + ";"
                + "-ashore-chrome: " + p.chrome() + ";"
                + "-ashore-panel: " + p.panel() + ";"
                + "-ashore-ink: " + p.ink() + ";"
                + "-ashore-muted: " + p.muted() + ";"
                + "-ashore-line: " + p.line() + ";"
                + "-ashore-paper: " + p.paper() + ";"
                + "-ashore-past: " + p.past() + ";"
                + "-ashore-today: " + p.today() + ";"
                + "-ashore-hover: " + p.hover() + ";"
                + "-ashore-accent: " + p.accent() + ";"
                + "-ashore-accent-ink: " + p.accentInk() + ";"
                + "-ashore-card: " + p.cardPaper() + ";"
                + "-ashore-card-ink: " + p.cardInk() + ";"
                + "-ashore-card-muted: " + p.cardMuted() + ";"
                + "-fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif;";
    }
}
