package org.ashoreprospect.GUI;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Day;
import org.ashoreprospect.Task;
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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.ashoreprospect.support.FxTestSupport.call;
import static org.ashoreprospect.support.FxTestSupport.findAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "SCHEDULER_TEST_FX", matches = "true")
@ExtendWith(FxTestSupport.class)
@ResourceLock("app-clock")
@ResourceLock("javafx-toolkit")
@ResourceLock("node-session")
class CalendarGridUiTest {

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
    void dayViewSortsDeduplicatesAndSelectsTasks() throws Exception {
        Task first = placed("first", "Early", LocalTime.of(10, 0));
        first.setDescription("[x] Outline\n[ ] Draft\nReminder");
        Task later = placed("later", "Later", LocalTime.of(11, 0));
        Task quick = placed("quick", "Nested event", LocalTime.of(10, 30));
        quick.setParentId(first.getID());
        Day day = new Day(system.getBlockedTime());
        day.placeTask(8, first);
        system.getGrid().put(TODAY, day);
        system.getPlacedTasks().addAll(List.of(later, quick, first));
        AtomicReference<Task> selected = new AtomicReference<>();

        call(() -> {
            GridPane grid = new GridPane();
            DayGridBuilder builder = new DayGridBuilder(system);
            builder.setOnSelectTask(selected::set);
            builder.build(grid, new Label(), 0);

            VBox yesterday = nodeAt(grid, 0, 0, VBox.class);
            VBox today = nodeAt(grid, 1, 0, VBox.class);
            VBox tomorrow = nodeAt(grid, 2, 0, VBox.class);
            assertEquals("Yesterday", ((Label) yesterday.getChildren().get(0)).getText());
            assertEquals("Today", ((Label) today.getChildren().get(0)).getText());
            assertEquals("Tomorrow", ((Label) tomorrow.getChildren().get(0)).getText());

            VBox cards = (VBox) ((ScrollPane) today.getChildren().get(2)).getContent();
            assertEquals(2, cards.getChildren().size());
            VBox firstCard = (VBox) cards.getChildren().get(0);
            VBox laterCard = (VBox) cards.getChildren().get(1);
            List<String> lines = findAll(firstCard, Label.class).stream().map(Label::getText).toList();
            assertEquals("Early", lines.get(0));
            assertTrue(lines.stream().anyMatch(line -> line.contains("Outline")));
            assertTrue(lines.stream().anyMatch(line -> line.contains("Draft")));
            assertEquals("Later", ((Label) laterCard.getChildren().get(0)).getText());
            firstCard.getOnMouseClicked().handle(click(MouseButton.PRIMARY));
            assertSame(first, selected.get());
            return null;
        });
    }

    @Test
    void monthViewDeduplicatesTasksAndClickSelectsTheTask() throws Exception {
        Task task = placed("month", "Presentation", LocalTime.of(10, 0));
        Day day = new Day(system.getBlockedTime());
        day.placeTask(8, task);
        system.getGrid().put(TODAY, day);
        system.getPlacedTasks().add(task);
        AtomicReference<Task> selected = new AtomicReference<>();

        call(() -> {
            GridPane grid = new GridPane();
            MonthGridBuilder builder = new MonthGridBuilder(system, null);
            builder.setOnSelectTask(selected::set);
            builder.build(grid, new Label(), 0);

            assertEquals("OCTOBER 2026", nodeAt(grid, 0, 0, Label.class).getText());
            VBox today = nodeAt(grid, 0, 4, VBox.class);
            List<String> labels = findAll(today, Label.class).stream().map(Label::getText).toList();
            assertEquals("12", labels.get(0));
            assertEquals(1, labels.stream().filter(text -> text.contains("Presentation")).count());
            today.getOnMouseClicked().handle(click(MouseButton.PRIMARY));
            assertSame(task, selected.get());

            builder.build(grid, new Label(), 1);
            assertEquals("NOVEMBER 2026", nodeAt(grid, 0, 0, Label.class).getText());
            return null;
        });
    }

    @Test
    void weekViewDisplaysTaskAndReusesGridWhenNavigating() throws Exception {
        Task task = placed("week", "Planning", LocalTime.of(10, 0));
        system.getPlacedTasks().add(task);
        AtomicReference<Task> selected = new AtomicReference<>();

        call(() -> {
            GridPane grid = new GridPane();
            WeekGridBuilder builder = new WeekGridBuilder(system,
                    new TaskDragDropSupport(system, null, null), null);
            builder.setOnSelectTask(selected::set);
            builder.build(grid, new Label(), 0);
            assertEquals("MON 10/12", nodeAt(grid, 1, 0, Label.class).getText());
            Pane slot = nodeAt(grid, 1, 9, Pane.class);
            assertTrue(findAll(slot, Label.class).stream()
                    .anyMatch(label -> label.getText().contains("Planning")));
            slot.getOnMouseClicked().handle(click(MouseButton.PRIMARY));
            assertSame(task, selected.get());

            builder.build(grid, new Label(), 1);
            assertEquals("MON 10/19", nodeAt(grid, 1, 0, Label.class).getText());
            assertSame(slot, nodeAt(grid, 1, 9, Pane.class));
            assertFalse(findAll(slot, Label.class).stream()
                    .anyMatch(label -> label.getText().contains("Planning")));
            return null;
        });
    }

    private static Task placed(String id, String name, LocalTime time) {
        return Task.loadTaskFromdb(id, name, 1, 3, null, null,
                null, null, TODAY, time);
    }

    private static <T extends Node> T nodeAt(GridPane grid, int column, int row, Class<T> type) {
        return grid.getChildren().stream()
                .filter(type::isInstance)
                .filter(node -> index(GridPane.getColumnIndex(node)) == column)
                .filter(node -> index(GridPane.getRowIndex(node)) == row)
                .map(type::cast).findFirst().orElseThrow();
    }

    private static int index(Integer index) {
        return index == null ? 0 : index;
    }

    private static MouseEvent click(MouseButton button) {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, button, 1,
                false, false, false, false, false, false, false, false, false, false, null);
    }
}
