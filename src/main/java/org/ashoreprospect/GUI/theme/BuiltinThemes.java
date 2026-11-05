/** Built-in Light and Dark color packs. */
package org.ashoreprospect.GUI.theme;

import java.util.List;

/** Built-in color packs. Later: photo pack (image + ColorThemePack chrome), drawing pack. */
public final class BuiltinThemes {

    public static final ThemePack LIGHT = new ColorThemePack("light", "Light",
            new ThemePalette(
                    "#f3eee4", "#efe8db", "#fbf7ef",
                    "#1c2430", "#5c564c", "#e0d6c6",
                    "#ffffff", "#bdc8c8", "#70d9f1", "#ffccdd",
                    "#1c2430", "#f3eee4",
                    "#ffffff", "#1c2430", "#5c564c"
            ));

    public static final ThemePack DARK = new ColorThemePack("dark", "Dark",
            new ThemePalette(
                    "#141618", "#17191c", "#1c1e22",
                    "#e6e8ea", "#9aa3ad", "#2e3238",
                    "#243044", "#0b0c0e", "#2e3c56", "#3b4c6a",
                    "#8b95a1", "#1c1e22",
                    "#222428", "#e6e8ea", "#9aa3ad"
            ));

    private BuiltinThemes() {}

    public static List<ThemePack> all() {
        return List.of(LIGHT, DARK);
    }
}
