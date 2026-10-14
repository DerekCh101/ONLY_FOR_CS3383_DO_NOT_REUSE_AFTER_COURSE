package org.ashoreprospect.GUI;

import javafx.scene.Parent;
import javafx.scene.Scene;
import org.ashoreprospect.net.Endpoints;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Light/dark chrome. Saved in {@code .scheduler/theme.txt}. */
public final class AppTheme {

    private static final Path FILE = Endpoints.dir().resolve("theme.txt");
    private static final String CSS = "/ashore-theme.css";
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private static boolean dark = load();

    private AppTheme() {}

    public static boolean isDark() {
        return dark;
    }

    public static void toggle() {
        dark = !dark;
        save();
        for (Runnable r : listeners) r.run();
    }

    public static void listen(Runnable r) {
        if (r != null) listeners.add(r);
    }

    public static void clearListeners() {
        listeners.clear();
    }

    public static void apply(Scene scene) {
        if (scene == null) return;
        if (!scene.getStylesheets().contains(cssUrl())) {
            scene.getStylesheets().add(cssUrl());
        }
        Parent root = scene.getRoot();
        if (root == null) return;
        root.getStyleClass().removeAll("theme-light", "theme-dark", "ashore-root");
        root.getStyleClass().add("ashore-root");
        root.getStyleClass().add(dark ? "theme-dark" : "theme-light");
    }

    public static final String FONT = "'Segoe UI', 'Helvetica Neue', Arial, sans-serif";

    public static String font() {
        return "-fx-font-family: " + FONT + "; ";
    }

    public static String paper() {
        return dark ? "#1a222c" : "#ffffff";
    }

    public static String past() {
        return dark ? "#2a3340" : "#bdc8c8";
    }

    public static String today() {
        return dark ? "#1a4a58" : "#70d9f1";
    }

    public static String hover() {
        return dark ? "#4a3040" : "#ffccdd";
    }

    public static String ink() {
        return dark ? "#e8e4dc" : "#1c2430";
    }

    public static String muted() {
        return dark ? "#9aa3ad" : "#333333";
    }

    public static String typewriterBg() {
        return dark ? "#1c2430" : "#f3eee4";
    }

    public static String typewriterRail() {
        return dark ? "#161c24" : "#e7dfd0";
    }

    public static String railOn() {
        return dark
                ? "-fx-background-color: #2a3340; -fx-text-fill: #e8e4dc; -fx-background-radius: 8; "
                + "-fx-padding: 8 16; " + font() + "-fx-font-size: 13px;"
                : "-fx-background-color: #fbf7ef; -fx-text-fill: #1c2430; -fx-background-radius: 8; "
                + "-fx-padding: 8 16; " + font() + "-fx-font-size: 13px;";
    }

    public static String railOff() {
        return dark
                ? "-fx-background-color: transparent; -fx-text-fill: #9aa3ad; -fx-background-radius: 8; "
                + "-fx-padding: 8 16; " + font() + "-fx-font-size: 13px;"
                : "-fx-background-color: transparent; -fx-text-fill: #6a5c4a; -fx-background-radius: 8; "
                + "-fx-padding: 8 16; " + font() + "-fx-font-size: 13px;";
    }

    public static String tabOn() {
        return dark
                ? "-fx-background-color: #e8e4dc; -fx-text-fill: #1c2430; -fx-background-radius: 8; "
                + "-fx-padding: 6 16; " + font() + "-fx-font-size: 13px;"
                : "-fx-background-color: #1c2430; -fx-text-fill: #f3eee4; -fx-background-radius: 8; "
                + "-fx-padding: 6 16; " + font() + "-fx-font-size: 13px;";
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

    private static String cssUrl() {
        return AppTheme.class.getResource(CSS).toExternalForm();
    }

    private static boolean load() {
        try {
            if (!Files.isRegularFile(FILE)) return false;
            return "dark".equalsIgnoreCase(Files.readString(FILE, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return false;
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, dark ? "dark" : "light", StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("theme save failed: " + e.getMessage());
        }
    }
}
