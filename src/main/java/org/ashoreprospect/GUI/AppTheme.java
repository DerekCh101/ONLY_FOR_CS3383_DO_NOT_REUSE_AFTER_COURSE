/** Paints the current ThemePack onto the main scene and popups. */
package org.ashoreprospect.GUI;

import javafx.css.Styleable;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.MenuButton;
import javafx.stage.Window;
import org.ashoreprospect.GUI.theme.BuiltinThemes;
import org.ashoreprospect.GUI.theme.Contrast;
import org.ashoreprospect.GUI.theme.ThemePack;
import org.ashoreprospect.GUI.theme.ThemePalette;
import org.ashoreprospect.net.Endpoints;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Theme registry. Widgets read {@link #palette()} / CSS tokens.
 * Register a new {@link ThemePack} (color, later photo/drawing) — do not hard-code hex in UI.
 */
public final class AppTheme {

    private static final Path FILE = Endpoints.dir().resolve("theme.txt");
    private static final String CSS = "/ashore-theme.css";
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Map<String, ThemePack> packs = new LinkedHashMap<>();

    private static ThemePack current;

    static {
        for (ThemePack p : BuiltinThemes.all()) packs.put(p.id(), p);
        current = load();
    }

    private AppTheme() {}

    public static ThemePack currentPack() {
        return current;
    }

    public static ThemePalette palette() {
        return current.palette();
    }

    public static void register(ThemePack pack) {
        if (pack != null && pack.id() != null) packs.put(pack.id(), pack);
    }

    public static List<ThemePack> packs() {
        return List.copyOf(packs.values());
    }

    public static void setPack(String id) {
        ThemePack next = packs.get(id);
        if (next == null || next == current) return;
        current = next;
        save();
        fire();
    }

    public static boolean isDark() {
        return Contrast.darkSurface(palette().bg());
    }

    /** Flip between the two built-in color packs. Custom packs stay until chosen in UI later. */
    public static void toggle() {
        setPack(isDark() ? BuiltinThemes.LIGHT.id() : BuiltinThemes.DARK.id());
    }

    public static void listen(Runnable r) {
        if (r != null) listeners.add(r);
    }

    public static void clearListeners() {
        listeners.clear();
    }

    public static void apply(Scene scene) {
        if (scene == null) return;
        ensureCss(scene);
        Parent root = scene.getRoot();
        if (root == null) return;
        paint(root);
        hookPopups(root);
        current.decorate(root);
    }

    /**
     * Popup menus are a different scene. Do not put -ashore-* tokens on
     * {@code setStyle} — JavaFX types those as String and ClassCasts them
     * when CSS asks for a Paint. Light/dark colors live in ashore-theme.css.
     */
    public static void applyPopup(ContextMenu menu) {
        if (menu == null) return;
        markPopup(menu);
        menu.setStyle(null);
        Runnable paintScene = () -> {
            Scene scene = menu.getScene();
            if (scene == null) return;
            ensureCss(scene);
            if (scene.getRoot() != null) {
                markPopup(scene.getRoot());
                scene.getRoot().setStyle(null);
            }
        };
        paintScene.run();
        javafx.application.Platform.runLater(paintScene);
    }

    private static void markPopup(Styleable node) {
        if (node == null) return;
        node.getStyleClass().removeAll("theme-light", "theme-dark", "ashore-root");
        if (!node.getStyleClass().contains("ashore-popup")) {
            node.getStyleClass().add("ashore-popup");
        }
        node.getStyleClass().add(isDark() ? "theme-dark" : "theme-light");
    }

    public static void hookPopups(Parent tree) {
        if (tree == null) return;
        walkMenus(tree);
    }

    public static final String FONT = "'Segoe UI', 'Helvetica Neue', Arial, sans-serif";

    public static String font() {
        return "-fx-font-family: " + FONT + "; ";
    }

    public static String paper() {
        return palette().paper();
    }

    public static String past() {
        return palette().past();
    }

    public static String today() {
        return palette().today();
    }

    public static String hover() {
        return palette().hover();
    }

    public static String ink() {
        return palette().ink();
    }

    public static String muted() {
        return palette().muted();
    }

    public static String typewriterBg() {
        return palette().bg();
    }

    public static String typewriterRail() {
        return palette().chrome();
    }

    public static String railOn() {
        ThemePalette p = palette();
        return "-fx-background-color: " + p.line() + "; -fx-text-fill: " + p.ink()
                + "; -fx-background-radius: 8; -fx-padding: 8 16; " + font() + "-fx-font-size: 13px;";
    }

    public static String railOff() {
        return "-fx-background-color: transparent; -fx-text-fill: " + muted()
                + "; -fx-background-radius: 8; -fx-padding: 8 16; " + font() + "-fx-font-size: 13px;";
    }

    public static String tabOn() {
        ThemePalette p = palette();
        return "-fx-background-color: " + p.accent() + "; -fx-text-fill: " + p.accentInk()
                + "; -fx-background-radius: 8; -fx-padding: 6 16; " + font() + "-fx-font-size: 13px;";
    }

    public static String tabOff() {
        return "-fx-background-color: transparent; -fx-text-fill: " + muted()
                + "; -fx-background-radius: 8; -fx-padding: 6 16; "
                + font() + "-fx-font-size: 13px;";
    }

    public static String fieldCss() {
        return "-fx-background-color: " + paper() + "; -fx-text-fill: " + ink()
                + "; -fx-prompt-text-fill: " + muted()
                + "; " + font() + "-fx-font-size: 13px; "
                + "-fx-background-radius: 6; -fx-padding: 8 10;";
    }

    public static String cardPaper() {
        return palette().cardPaper();
    }

    public static String cardInk() {
        return palette().cardInk();
    }

    public static String cardMuted() {
        return palette().cardMuted();
    }

    public static String accent() {
        return palette().accent();
    }

    public static String accentInk() {
        return palette().accentInk();
    }

    public static String line() {
        return palette().line();
    }

    public static String cardBoxCss() {
        return "-fx-background-color: " + cardPaper() + "; -fx-padding: 20; "
                + "-fx-border-color: " + line() + "; -fx-border-width: 1; "
                + "-fx-background-radius: 12; -fx-border-radius: 12; "
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 16, 0.2, 0, 6);";
    }

    public static String cardPrimaryBtnCss() {
        return "-fx-background-color: " + accent() + "; -fx-text-fill: " + accentInk()
                + "; -fx-background-radius: 8; -fx-padding: 8 14; " + font();
    }

    public static String cardGhostBtnCss() {
        return "-fx-background-color: transparent; -fx-text-fill: " + cardInk()
                + "; -fx-border-color: " + line() + "; -fx-border-radius: 8; "
                + "-fx-background-radius: 8; -fx-padding: 8 14; " + font();
    }

    private static void paint(Node node) {
        if (node == null) return;
        node.setStyle(palette().tokenStyle());
        node.getStyleClass().removeAll("theme-light", "theme-dark", "ashore-popup");
        if (!node.getStyleClass().contains("ashore-root")) {
            node.getStyleClass().add("ashore-root");
        }
        node.getStyleClass().add(isDark() ? "theme-dark" : "theme-light");
    }

    private static void walkMenus(Node n) {
        if (n instanceof MenuButton mb) {
            ContextMenu cm = mb.getContextMenu();
            if (cm != null) {
                cm.setOnShowing(e -> applyPopup(cm));
            }
        }
        if (n instanceof Control c && c.getContextMenu() != null && !(n instanceof MenuButton)) {
            ContextMenu cm = c.getContextMenu();
            cm.setOnShowing(e -> applyPopup(cm));
        }
        if (n instanceof Parent p) {
            for (Node ch : p.getChildrenUnmodifiable()) walkMenus(ch);
        }
    }

    private static void ensureCss(Scene scene) {
        String url = cssUrl();
        if (url != null && !scene.getStylesheets().contains(url)) {
            scene.getStylesheets().add(url);
        }
    }

    private static void fire() {
        for (Window w : Window.getWindows()) {
            if (w == null || w.getScene() == null) continue;
            if (w instanceof javafx.stage.PopupWindow) {
                Parent r = w.getScene().getRoot();
                if (r != null) {
                    ensureCss(w.getScene());
                    markPopup(r);
                    r.setStyle(null);
                }
                continue;
            }
            apply(w.getScene());
        }
        for (Runnable r : listeners) r.run();
    }

    private static String cssUrl() {
        var url = AppTheme.class.getResource(CSS);
        return url == null ? null : url.toExternalForm();
    }

    private static ThemePack load() {
        try {
            if (!Files.isRegularFile(FILE)) return BuiltinThemes.LIGHT;
            String raw = Files.readString(FILE, StandardCharsets.UTF_8).trim();
            if (packs.containsKey(raw)) return packs.get(raw);
            if ("dark".equalsIgnoreCase(raw)) return BuiltinThemes.DARK;
            return BuiltinThemes.LIGHT;
        } catch (Exception e) {
            return BuiltinThemes.LIGHT;
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, current.id(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("theme save failed: " + e.getMessage());
        }
    }
}
