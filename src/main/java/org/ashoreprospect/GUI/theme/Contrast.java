/** Contrast helpers so ink stays readable on a pack’s paper. */
package org.ashoreprospect.GUI.theme;

import javafx.scene.paint.Color;

/** WCAG-style contrast so ink never sits on a similar background. */
public final class Contrast {

    public static final String NEAR_BLACK = "#1c2430";
    public static final String NEAR_WHITE = "#fbf7ef";
    private static final double MIN_RATIO = 4.5;

    private Contrast() {}

    public static Color parse(String hex) {
        if (hex == null || hex.isBlank()) return Color.BLACK;
        try {
            return Color.web(hex.trim());
        } catch (Exception e) {
            return Color.BLACK;
        }
    }

    public static double luminance(Color c) {
        if (c == null) return 0;
        return 0.2126 * lin(c.getRed()) + 0.7152 * lin(c.getGreen()) + 0.0722 * lin(c.getBlue());
    }

    public static double luminance(String hex) {
        return luminance(parse(hex));
    }

    public static double ratio(String a, String b) {
        double l1 = luminance(a);
        double l2 = luminance(b);
        double hi = Math.max(l1, l2);
        double lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** Prefer {@code ink} on {@code bg}; otherwise black or white, whichever contrasts. */
    public static String inkOn(String bg, String ink) {
        if (ratio(bg, ink) >= MIN_RATIO) return ink;
        return luminance(bg) > 0.55 ? NEAR_BLACK : NEAR_WHITE;
    }

    public static String mutedOn(String bg, String muted) {
        if (ratio(bg, muted) >= 3.0) return muted;
        String ink = inkOn(bg, muted);
        Color c = parse(ink);
        Color bgc = parse(bg);
        Color mix = c.interpolate(bgc, 0.35);
        return toHex(mix);
    }

    public static boolean darkSurface(String bg) {
        return luminance(bg) < 0.45;
    }

    public static String toHex(Color c) {
        if (c == null) return NEAR_BLACK;
        int r = (int) Math.round(c.getRed() * 255);
        int g = (int) Math.round(c.getGreen() * 255);
        int b = (int) Math.round(c.getBlue() * 255);
        return String.format("#%02x%02x%02x", r, g, b);
    }

    private static double lin(double srgb) {
        return srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }
}
