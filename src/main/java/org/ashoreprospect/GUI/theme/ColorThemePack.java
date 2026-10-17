/** ThemePack backed only by colors (no photo/drawing yet). */
package org.ashoreprospect.GUI.theme;

/** Solid-color pack. Photo/drawing packs can wrap one of these for chrome colors. */
public final class ColorThemePack implements ThemePack {

    private final String id;
    private final String name;
    private final ThemePalette palette;

    public ColorThemePack(String id, String name, ThemePalette raw) {
        this.id = id;
        this.name = name;
        this.palette = raw.contrastSafe();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return name;
    }

    @Override
    public ThemeKind kind() {
        return ThemeKind.COLOR;
    }

    @Override
    public ThemePalette palette() {
        return palette;
    }
}
