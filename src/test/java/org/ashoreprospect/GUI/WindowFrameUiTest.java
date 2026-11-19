package org.ashoreprospect.GUI;

import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.ashoreprospect.support.FxTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.ashoreprospect.support.FxTestSupport.call;
import static org.ashoreprospect.support.FxTestSupport.findAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "SCHEDULER_TEST_FX", matches = "true")
@ExtendWith(FxTestSupport.class)
@ResourceLock("javafx-toolkit")
@ResourceLock("theme-prefs")
class WindowFrameUiTest {

    private final Map<Field, Object> previousState = new LinkedHashMap<>();
    private Stage stage;

    @AfterEach
    void restoreWindowState() throws Exception {
        call(() -> {
            if (stage != null) stage.close();
            for (Map.Entry<Field, Object> entry : previousState.entrySet()) {
                entry.getKey().set(null, entry.getValue());
            }
            return null;
        });
    }

    @Test
    void frameShowsTitleAndTogglesBetweenWorkAreaAndRestoredSize() throws Exception {
        call(() -> {
            for (Field field : WindowFrame.class.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) continue;
                field.setAccessible(true);
                previousState.put(field, field.get(null));
            }
            Field existingStage = WindowFrame.class.getDeclaredField("stage");
            existingStage.setAccessible(true);
            assertNull(existingStage.get(null), "window frame was already installed");

            stage = new Stage();
            stage.setX(85);
            stage.setY(65);
            stage.setWidth(900);
            stage.setHeight(600);
            WindowFrame.install(stage);
            VBox content = new VBox(new Label("Content"));
            WindowFrame.setContent(content);
            stage.setTitle("Test scheduler");
            assertSame(WindowFrame.frame(), stage.getScene().getRoot());
            assertTrue(findAll(WindowFrame.frame(), Label.class).stream()
                    .anyMatch(label -> "Test scheduler".equals(label.getText())));

            Button maximize = findAll(WindowFrame.frame(), Button.class).stream()
                    .filter(button -> button.getStyleClass().contains("win-max"))
                    .findFirst().orElseThrow();
            Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
            maximize.fire();
            assertEquals(bounds.getWidth(), stage.getWidth(), 0.1);
            assertEquals(bounds.getHeight(), stage.getHeight(), 0.1);
            maximize.fire();
            assertEquals(900, stage.getWidth(), 0.1);
            assertEquals(600, stage.getHeight(), 0.1);
            assertEquals(85, stage.getX(), 0.1);
            assertEquals(65, stage.getY(), 0.1);
            assertSame(content, ((javafx.scene.layout.BorderPane)
                    WindowFrame.frame().getChildren().get(0)).getCenter());
            return null;
        });
    }
}
