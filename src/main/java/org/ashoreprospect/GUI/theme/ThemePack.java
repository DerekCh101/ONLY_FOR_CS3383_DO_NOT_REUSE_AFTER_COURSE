/** One installable look: palette plus how to paint a scene. */
package org.ashoreprospect.GUI.theme;

import javafx.scene.Parent;
import javafx.scene.image.Image;

import java.util.Optional;

/**
 * One installable look. Add a pack (color, photo, drawing) and register it
 * on {@link org.ashoreprospect.GUI.AppTheme} — do not fork widget code.
 */
public interface ThemePack {

    String id();

    String displayName();

    ThemeKind kind();

    ThemePalette palette();

    /** Optional full-window backdrop (photo / drawing packs). */
    default Optional<Image> backdrop() {
        return Optional.empty();
    }

    /** Extra paint after CSS tokens (photo overlay, custom canvas). */
    default void decorate(Parent root) {}
}
