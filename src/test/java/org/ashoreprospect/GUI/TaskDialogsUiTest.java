package org.ashoreprospect.GUI;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.support.FxTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.ashoreprospect.support.FxTestSupport.call;
import static org.ashoreprospect.support.FxTestSupport.findAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "SCHEDULER_TEST_FX", matches = "true")
@ExtendWith(FxTestSupport.class)
@ResourceLock("app-clock")
@ResourceLock("javafx-toolkit")
@ResourceLock("node-session")
class TaskDialogsUiTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private TaskManagementSystem system;

    @BeforeEach
    void setUp() {
        AppTimeReference.setClock(Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"), ZoneOffset.UTC));
        TaskManagementSystem.resetInstance();
        system = TaskManagementSystem.getInstance();
        system.getGrid().clear();
        system.getPlacedTasks().clear();
        system.getPendingTasks().clear();
        system.getUnscheduledTasks().clear();
        system.getBlockedTime().blockedPeriod.clear();
    }

    @AfterEach
    void tearDown() {
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(Clock.systemDefaultZone());
    }

    @Test
    void addTaskRejectsEmptyNameAndResetsWhenReopened() throws Exception {
        AtomicInteger changed = new AtomicInteger();
        call(() -> {
            StackPane root = new StackPane();
            new Scene(root);
            TaskDialogs dialogs = new TaskDialogs(root, system, changed::incrementAndGet);
            dialogs.prewarmAddTask();
            dialogs.showTaskPopup(true, TODAY, 8);
            dialogs.showTaskPopup(true, TODAY, 9);
            assertTrue(hasLabel(root, "New Task @ 2026-10-12 10:00"));

            TextField name = findAll(root, TextField.class).stream()
                    .filter(field -> "Task name".equals(field.getPromptText()))
                    .findFirst().orElseThrow();
            button(root, "Save Task").fire();
            assertTrue(hasLabel(root, "Task name cannot be empty."));
            button(root, "Okay").fire();
            name.setText("Temporary name");
            button(root, "Cancel").fire();

            dialogs.showTaskPopup(false, TODAY.plusDays(1), -1);
            assertEquals("", name.getText());
            assertTrue(hasLabel(root, "Add New Task"));
            button(root, "Cancel").fire();
            assertEquals(0, changed.get());
            assertTrue(system.getPlacedTasks().isEmpty());
            return null;
        });
    }

    @Test
    void blockedEditorChangesDateFieldsAndRejectsInvalidTimeRange() throws Exception {
        AtomicInteger changed = new AtomicInteger();
        call(() -> {
            StackPane root = new StackPane();
            new Scene(root);
            TaskDialogs dialogs = new TaskDialogs(root, system, changed::incrementAndGet);
            dialogs.showBlockedEditor();

            CheckBox everyDay = checkbox(root, "Every day");
            CheckBox range = checkbox(root, "Range");
            assertTrue(everyDay.isSelected());
            range.fire();
            assertTrue(range.isSelected());
            assertFalse(everyDay.isSelected());
            assertTrue(hasLabel(root, "From"));
            assertTrue(hasLabel(root, "To"));

            findAll(root, ComboBox.class).get(1).getSelectionModel().select(0);
            button(root, "Add").fire();
            assertTrue(hasLabel(root, "End must be after start."));
            button(root, "Okay").fire();
            button(root, "Close").fire();
            assertEquals(0, changed.get());
            assertTrue(system.getBlockedTime().blockedPeriod.isEmpty());
            return null;
        });
    }

    private static Button button(StackPane root, String text) {
        return findAll(root, Button.class).stream()
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    private static CheckBox checkbox(StackPane root, String text) {
        return findAll(root, CheckBox.class).stream()
                .filter(box -> text.equals(box.getText())).findFirst().orElseThrow();
    }

    private static boolean hasLabel(StackPane root, String text) {
        return findAll(root, Label.class).stream().anyMatch(label -> text.equals(label.getText()));
    }
}
