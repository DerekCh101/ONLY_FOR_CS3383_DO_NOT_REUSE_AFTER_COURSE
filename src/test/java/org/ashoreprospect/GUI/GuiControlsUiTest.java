package org.ashoreprospect.GUI;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.ashoreprospect.GUI.theme.BuiltinThemes;
import org.ashoreprospect.support.FxTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.concurrent.atomic.AtomicInteger;

import static org.ashoreprospect.support.FxTestSupport.call;
import static org.ashoreprospect.support.FxTestSupport.findAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "SCHEDULER_TEST_FX", matches = "true")
@ExtendWith(FxTestSupport.class)
@ResourceLock("javafx-toolkit")
@ResourceLock("os-env")
@ResourceLock("theme-prefs")
class GuiControlsUiTest {

    @AfterEach
    void clearThemeListeners() {
        AppTheme.clearListeners();
    }

    @Test
    void serverPickerShowsRelevantFieldsAndRejectsInvalidInputs() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        call(() -> {
            ServerPickView view = new ServerPickView(completed::incrementAndGet);
            RadioButton host = radio(view, "This computer is the server");
            RadioButton remote = radio(view, "This device is a client from your self-host server (Tailscale or wifi)");
            RadioButton creator = radio(view, "Use the creator's server");
            TextField address = findAll(view, TextField.class).stream()
                    .filter(field -> "http://HOST:3456".equals(field.getPromptText()))
                    .findFirst().orElseThrow();
            PasswordField password = findAll(view, PasswordField.class).get(0);
            Button submit = button(view, "Continue");

            host.setSelected(true);
            assertTrue(password.isVisible());
            assertFalse(address.isVisible());
            password.clear();
            submit.fire();
            assertTrue(hasLabel(view, "Enter postgres_pw."));

            remote.setSelected(true);
            assertTrue(address.isVisible());
            assertFalse(address.isDisabled());
            assertFalse(password.isVisible());
            address.setText("not-a-url");
            submit.fire();
            assertTrue(hasLabel(view, "Enter the host URL, e.g. http://HOST:3456"));

            creator.setSelected(true);
            submit.fire();
            assertTrue(hasLabel(view, "Creator's server is not set up yet."));
            assertEquals(0, completed.get());
            return null;
        });
    }

    @Test
    void helperCentersOverlaysAndPreservesErrorLabelColor() throws Exception {
        call(() -> {
            StackPane parent = new StackPane();
            parent.resize(400, 200);
            Pane child = new Pane();
            child.resize(100, 50);
            GuiHelper.centerInParent(child, parent);
            assertEquals(150, child.getLayoutX());
            assertEquals(75, child.getLayoutY());
            assertFalse(child.isManaged());
            StackPane overlay = GuiHelper.createDimmedOverlay(parent);
            assertEquals(400, overlay.getPrefWidth());
            assertEquals(200, overlay.getPrefHeight());

            Label normal = new Label("Normal");
            Label error = new Label("Error");
            error.setStyle("-fx-text-fill: #8b1e1e;");
            TextField field = new TextField();
            Spinner<Integer> spinner = new Spinner<>(0, 5, 1);
            VBox card = GuiHelper.createWhiteBox();
            card.getChildren().addAll(normal, error, field, spinner);
            GuiHelper.paintInk(card);
            assertTrue(normal.getStyle().contains(AppTheme.cardInk()));
            assertTrue(error.getStyle().contains("#8b1e1e"));
            assertTrue(field.getStyle().contains(AppTheme.cardInk()));
            assertTrue(spinner.getEditor().getStyle().contains("#1c2430"));
            return null;
        });
    }

    @Test
    void themeSwitchTogglesAndCanReturnToItsOriginalTheme() throws Exception {
        String original = AppTheme.currentPack().id();
        try {
            call(() -> {
                AppTheme.setPack(BuiltinThemes.LIGHT.id());
                ThemeSwitch control = new ThemeSwitch();
                control.getOnMouseClicked().handle(new MouseEvent(MouseEvent.MOUSE_CLICKED,
                        0, 0, 0, 0, MouseButton.PRIMARY, 1,
                        false, false, false, false, false, false, false, false, false, false, null));
                assertTrue(AppTheme.isDark());
                control.getOnMouseClicked().handle(new MouseEvent(MouseEvent.MOUSE_CLICKED,
                        0, 0, 0, 0, MouseButton.PRIMARY, 1,
                        false, false, false, false, false, false, false, false, false, false, null));
                assertFalse(AppTheme.isDark());
                return null;
            });
        } finally {
            call(() -> {
                AppTheme.setPack(original);
                return null;
            });
        }
    }

    private static RadioButton radio(ServerPickView view, String text) {
        return findAll(view, RadioButton.class).stream()
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    private static Button button(ServerPickView view, String text) {
        return findAll(view, Button.class).stream()
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    private static boolean hasLabel(ServerPickView view, String text) {
        return findAll(view, Label.class).stream().anyMatch(label -> text.equals(label.getText()));
    }
}
