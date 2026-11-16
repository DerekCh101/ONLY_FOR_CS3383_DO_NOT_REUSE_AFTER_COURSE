/**
 * The scene-facing half of {@link AppTheme}: {@code apply}, the root painting it performs,
 * {@code hookPopups}, {@code applyPopup}, and the theme-change broadcast through {@code fire}.
 *
 * <p>The existing {@code AppThemeTest} covers the registry and the CSS-string builders but
 * deliberately never calls these methods, because {@code fire()} walks {@code Window.getWindows()}
 * and needs a live toolkit. This class supplies that toolkit via {@link FxTestKit}.
 *
 * <p>Two pieces of state outlive a test: the selected pack (persisted to
 * {@code .scheduler/theme.txt}) and the listener list. Both are snapshotted and restored.
 * {@link org.junit.jupiter.api.parallel.ResourceLock} keeps this class from racing the other
 * theme tests over the same preference file.
 */
package org.ashoreprospect.GUI;

import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.ashoreprospect.FxTestKit;
import org.ashoreprospect.GUI.theme.BuiltinThemes;
import org.ashoreprospect.net.Endpoints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("theme-prefs")
class AppThemeSceneTest {

    private static final Path PREF_FILE = Endpoints.dir().resolve("theme.txt");

    private String savedPrefs;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestKit.ensureStarted();
    }

    @BeforeEach
    void snapshot() throws Exception {
        savedPrefs = Files.isRegularFile(PREF_FILE)
                ? Files.readString(PREF_FILE, StandardCharsets.UTF_8) : null;
        FxTestKit.onFx(() -> {
            AppTheme.clearListeners();
            AppTheme.setPack(BuiltinThemes.LIGHT.id());
        });
    }

    @AfterEach
    void restore() throws Exception {
        FxTestKit.onFx(() -> {
            AppTheme.clearListeners();
            AppTheme.setPack(BuiltinThemes.LIGHT.id());
        });
        if (savedPrefs == null) Files.deleteIfExists(PREF_FILE);
        else {
            Files.createDirectories(PREF_FILE.getParent());
            Files.writeString(PREF_FILE, savedPrefs, StandardCharsets.UTF_8);
        }
    }

    private static Scene sceneWith(StackPane root) {
        return new Scene(root, 400, 300);
    }

    // ==================================================================
    // apply — guards
    // ==================================================================

    @Test
    void applyIgnoresANullScene() throws Exception {
        FxTestKit.onFx(() -> assertDoesNotThrow(() -> AppTheme.apply(null)));
    }

    // ==================================================================
    // apply — what it paints on the root
    // ==================================================================

    @Test
    void applyMarksTheRootWithTheThemeClasses() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane();
            AppTheme.apply(sceneWith(root));

            assertTrue(root.getStyleClass().contains("ashore-root"),
                    "the root marker class is what CSS keys off");
            assertTrue(root.getStyleClass().contains("theme-light"),
                    "light pack selects theme-light: " + root.getStyleClass());
            assertFalse(root.getStyleClass().contains("theme-dark"));
        });
    }

    @Test
    void applyUnderTheDarkPackSelectsTheDarkClass() throws Exception {
        FxTestKit.onFx(() -> {
            AppTheme.setPack(BuiltinThemes.DARK.id());
            StackPane root = new StackPane();
            AppTheme.apply(sceneWith(root));

            assertTrue(root.getStyleClass().contains("theme-dark"));
            assertFalse(root.getStyleClass().contains("theme-light"));
        });
    }

    @Test
    void applyPutsThePaletteTokensOnTheRootStyle() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane();
            AppTheme.apply(sceneWith(root));

            String style = root.getStyle();
            assertNotNull(style, "the root should carry the -ashore-* tokens");
            assertTrue(style.contains("-ashore-"), "expected theme tokens, got: " + style);
        });
    }

    @Test
    void applyAttachesTheStylesheetExactlyOnce() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane();
            Scene scene = sceneWith(root);

            AppTheme.apply(scene);
            int afterFirst = scene.getStylesheets().size();
            AppTheme.apply(scene);

            assertTrue(afterFirst >= 1, "the ashore stylesheet should be attached");
            assertEquals(afterFirst, scene.getStylesheets().size(),
                    "apply must not stack duplicate stylesheets: " + scene.getStylesheets());
        });
    }

    @Test
    void applyingTwiceDoesNotDuplicateRootClasses() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane();
            Scene scene = sceneWith(root);

            AppTheme.apply(scene);
            AppTheme.apply(scene);

            assertEquals(1, count(root.getStyleClass(), "ashore-root"), "ashore-root added once");
            assertEquals(1, count(root.getStyleClass(), "theme-light"), "theme-light added once");
        });
    }

    private static int count(List<String> values, String wanted) {
        int n = 0;
        for (String v : values) if (wanted.equals(v)) n++;
        return n;
    }

    @Test
    void switchingPackThenReapplyingSwapsTheThemeClass() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane();
            Scene scene = sceneWith(root);
            AppTheme.apply(scene);
            assertTrue(root.getStyleClass().contains("theme-light"));

            AppTheme.setPack(BuiltinThemes.DARK.id());
            AppTheme.apply(scene);

            assertTrue(root.getStyleClass().contains("theme-dark"));
            assertFalse(root.getStyleClass().contains("theme-light"),
                    "the stale class must be removed, not just supplemented");
        });
    }

    // ==================================================================
    // hookPopups / walkMenus
    // ==================================================================

    @Test
    void hookPopupsIgnoresNull() throws Exception {
        FxTestKit.onFx(() -> assertDoesNotThrow(() -> AppTheme.hookPopups(null)));
    }

    @Test
    void hookPopupsInstallsAHandlerOnANestedMenuButton() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane();
            VBox column = new VBox();
            HBox row = new HBox();
            MenuButton mb = new MenuButton("Actions");
            mb.getItems().add(new MenuItem("One"));
            ContextMenu menu = new ContextMenu();
            mb.setContextMenu(menu);
            row.getChildren().add(mb);
            column.getChildren().add(row);
            root.getChildren().add(column);

            assertNull(menu.getOnShowing(), "nothing installed yet");
            AppTheme.hookPopups(root);
            assertNotNull(menu.getOnShowing(), "the nested menu should be hooked");
        });
    }

    @Test
    void hookPopupsAlsoHandlesAPlainControlsContextMenu() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane();
            TextField field = new TextField();
            ContextMenu menu = new ContextMenu();
            field.setContextMenu(menu);
            root.getChildren().add(field);

            AppTheme.hookPopups(root);

            assertNotNull(menu.getOnShowing(), "a Control's context menu is hooked too");
        });
    }

    @Test
    void hookPopupsLeavesAChildlessTreeAlone() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane(new Label("nothing to hook"));
            assertDoesNotThrow(() -> AppTheme.hookPopups(root));
        });
    }

    // ==================================================================
    // applyPopup
    // ==================================================================

    @Test
    void applyPopupIgnoresNull() throws Exception {
        FxTestKit.onFx(() -> assertDoesNotThrow(() -> AppTheme.applyPopup(null)));
    }

    @Test
    void applyPopupMarksTheMenuAsAPopupWithTheThemeClass() throws Exception {
        FxTestKit.onFx(() -> {
            ContextMenu menu = new ContextMenu(new MenuItem("x"));

            AppTheme.applyPopup(menu);

            assertTrue(menu.getStyleClass().contains("ashore-popup"),
                    "popups get their own marker: " + menu.getStyleClass());
            assertTrue(menu.getStyleClass().contains("theme-light"));
            assertFalse(menu.getStyleClass().contains("theme-dark"));
        });
    }

    @Test
    void applyPopupUnderDarkUsesTheDarkMarker() throws Exception {
        FxTestKit.onFx(() -> {
            AppTheme.setPack(BuiltinThemes.DARK.id());
            ContextMenu menu = new ContextMenu(new MenuItem("x"));

            AppTheme.applyPopup(menu);

            assertTrue(menu.getStyleClass().contains("theme-dark"));
            assertFalse(menu.getStyleClass().contains("theme-light"));
        });
    }

    @Test
    void applyPopupClearsTheInlineStyleOnTheMenu() throws Exception {
        FxTestKit.onFx(() -> {
            ContextMenu menu = new ContextMenu(new MenuItem("x"));
            menu.setStyle("-fx-background-color: red;");

            AppTheme.applyPopup(menu);

            // JavaFX reports a cleared style as "" rather than null.
            assertFalse(menu.getStyle().contains("red"),
                    "inline styles are dropped in favour of the stylesheet: " + menu.getStyle());
        });
    }

    @Test
    void applyPopupDoesNotStackDuplicateMarkerClasses() throws Exception {
        FxTestKit.onFx(() -> {
            ContextMenu menu = new ContextMenu(new MenuItem("x"));

            AppTheme.applyPopup(menu);
            AppTheme.applyPopup(menu);

            assertEquals(1, count(menu.getStyleClass(), "ashore-popup"));
            assertEquals(1, count(menu.getStyleClass(), "theme-light"));
        });
    }

    // ==================================================================
    // fire — the broadcast behind setPack
    // ==================================================================

    @Test
    void setPackNotifiesEveryRegisteredListener() throws Exception {
        FxTestKit.onFx(() -> {
            AtomicInteger hits = new AtomicInteger();
            AppTheme.listen(hits::incrementAndGet);
            AppTheme.listen(hits::incrementAndGet);

            AppTheme.setPack(BuiltinThemes.DARK.id());

            assertEquals(2, hits.get(), "both listeners should have fired");
        });
    }

    @Test
    void setPackToTheCurrentPackFiresNothing() throws Exception {
        FxTestKit.onFx(() -> {
            AtomicInteger hits = new AtomicInteger();
            AppTheme.listen(hits::incrementAndGet);

            AppTheme.setPack(AppTheme.currentPack().id());
            AppTheme.setPack("no-such-pack");

            assertEquals(0, hits.get(), "a no-op change must not notify");
        });
    }

    @Test
    void toggleFlipsBetweenTheTwoBuiltInsAndNotifies() throws Exception {
        FxTestKit.onFx(() -> {
            AtomicInteger hits = new AtomicInteger();
            AppTheme.listen(hits::incrementAndGet);
            assertSame(BuiltinThemes.LIGHT, AppTheme.currentPack());

            AppTheme.toggle();

            assertSame(BuiltinThemes.DARK, AppTheme.currentPack());
            assertEquals(1, hits.get());

            AppTheme.toggle();

            assertSame(BuiltinThemes.LIGHT, AppTheme.currentPack());
            assertEquals(2, hits.get());
        });
    }

    @Test
    void setPackRepaintsAnAlreadyShownScene() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane();
            Scene scene = sceneWith(root);
            AppTheme.apply(scene);
            assertTrue(root.getStyleClass().contains("theme-light"));

            // No explicit apply() here: fire() should walk the open windows' scenes.
            AppTheme.setPack(BuiltinThemes.DARK.id());

            // The Stage must be showing for Window.getWindows() to include it.
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(scene);
            stage.show();
            AppTheme.setPack(BuiltinThemes.LIGHT.id());

            assertTrue(root.getStyleClass().contains("theme-light"));
            assertEquals(1, count(root.getStyleClass(), "theme-light"),
                    "the repaint replaces the stale class: " + root.getStyleClass());
            stage.hide();
        });
    }

    @Test
    void aShownStageIsRepaintedWhenTheThemeChanges() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane();
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(sceneWith(root));
            stage.show();
            try {
                AppTheme.apply(stage.getScene());
                assertTrue(root.getStyleClass().contains("theme-light"));

                AppTheme.setPack(BuiltinThemes.DARK.id());

                assertTrue(root.getStyleClass().contains("theme-dark"),
                        "an open window repaints itself: " + root.getStyleClass());
                assertFalse(root.getStyleClass().contains("theme-light"));
            } finally {
                stage.hide();
            }
        });
    }

    @Test
    void clearListenersStopsTheBroadcast() throws Exception {
        FxTestKit.onFx(() -> {
            AtomicInteger hits = new AtomicInteger();
            AppTheme.listen(hits::incrementAndGet);
            AppTheme.clearListeners();

            AppTheme.setPack(BuiltinThemes.DARK.id());

            assertEquals(0, hits.get());
        });
    }
}
