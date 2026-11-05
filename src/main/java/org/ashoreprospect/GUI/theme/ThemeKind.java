/** Color vs photo vs drawing theme kinds for later packs. */
package org.ashoreprospect.GUI.theme;

/**
 * How a pack paints the chrome. Only {@link #COLOR} is implemented today.
 * Photo / drawing packs can overlay a backdrop later without rewriting widgets.
 */
public enum ThemeKind {
    COLOR,
    PHOTO,
    DRAWING
}
