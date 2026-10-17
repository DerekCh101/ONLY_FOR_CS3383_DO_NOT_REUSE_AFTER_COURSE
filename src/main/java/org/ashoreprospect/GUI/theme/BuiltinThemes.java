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
                    "#121820", "#161c24", "#1a222c",
                    "#e8e4dc", "#9aa3ad", "#2a3340",
                    "#1a222c", "#2a3340", "#1a4a58", "#4a3040",
                    "#e8e4dc", "#1c2430",
                    "#ffffff", "#1c2430", "#5c564c"
            ));

    private BuiltinThemes() {}

    public static List<ThemePack> all() {
        return List.of(LIGHT, DARK);
    }
}
