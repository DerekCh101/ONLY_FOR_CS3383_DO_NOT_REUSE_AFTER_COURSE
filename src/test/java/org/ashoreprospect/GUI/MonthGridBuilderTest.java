/**
 * The month calendar: its 7×6 cell layout, the day-cell shading ladder, holiday labels, the
 * "four chips then +N more" rule, and the click handlers.
 *
 * <p>The clock is pinned so {@code currentColumnDate()} is a fixed Monday, making every cell's
 * position predictable. {@code TaskManagementSystem} is the process-wide singleton, so it is reset
 * around each test and its collections are cleared before the next one starts.
 *
 * <p>Layouts only need {@code GridPane}; nothing here starts a {@code Stage} except the click
 * tests, which fire synthetic mouse events rather than opening real popups.
 */
package org.ashoreprospect.GUI;

import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.Scene;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Day;
import org.ashoreprospect.FxTestKit;
import org.ashoreprospect.BlockedTimePeriod;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("task-system")
class MonthGridBuilderTest {

    /** Monday 2026-10-12 at noon. */
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    private Clock savedClock;
    private TaskManagementSystem system;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestKit.ensureStarted();
    }

    @BeforeEach
    void setUp() throws Exception {
        savedClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        TaskManagementSystem.resetInstance();
        FxTestKit.onFx(() -> {
            AppTheme.clearListeners();
            AppTheme.setPack(org.ashoreprospect.GUI.theme.BuiltinThemes.LIGHT.id());
        });
        system = TaskManagementSystem.getInstance();
        clearSystem();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (system != null) clearSystem();
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(savedClock);
        FxTestKit.onFx(AppTheme::clearListeners);
    }

    private void clearSystem() {
        system.getPendingTasks().clear();
        system.getPlacedTasks().clear();
        system.getUnscheduledTasks().clear();
        system.getGrid().clear();
    }

    private static Task task(String name) {
        Task t = new Task(name, 1.0, 0, null, null, null, null);
        t.setTaskName(name);
        return t;
    }

    /** Place a task on {@code date} at {@code hour} by writing both the grid and the placed list. */
    private Task placeOn(LocalDate date, int hour, String name) {
        Task t = task(name);
        t.setPlacedDate(date);
        t.setPlacedTime(LocalTime.of(hour, 0));
        system.getPlacedTasks().add(t);
        Day day = system.getGrid().computeIfAbsent(date, d -> new Day(new BlockedTimePeriod()));
        day.placeTask(AppTimeReference.localTimeToSlot(LocalTime.of(hour, 0)), t);
        return t;
    }

    /** The seven weekday-header labels, identified by their fixed text. */
    private static List<String> headerTexts(GridPane grid) {
        List<String> out = new ArrayList<>();
        for (var node : grid.getChildren()) {
            if (node instanceof Label l && List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                    .contains(l.getText())) {
                out.add(l.getText());
            }
        }
        return out;
    }

    private static VBox cellAt(GridPane grid, int row, int col) {
        for (var node : grid.getChildren()) {
            Integer r = GridPane.getRowIndex(node);
            Integer c = GridPane.getColumnIndex(node);
            if (r != null && c != null && r == row + 2 && c == col && node instanceof VBox v) {
                return v;
            }
        }
        return null;
    }

    // ==================================================================
    // grid chrome
    // ==================================================================

    @Test
    void buildClearsAnyPreviousContent() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            grid.add(new Label("stale"), 0, 0);
            grid.getColumnConstraints().add(new javafx.scene.layout.ColumnConstraints());

            new MonthGridBuilder(system, null).build(grid, null, 0);

            for (var node : grid.getChildren()) {
                if (node instanceof Label l) {
                    assertFalse("stale".equals(l.getText()), "old children must be gone");
                }
            }
            assertEquals(0, grid.getColumnConstraints().size(), "stale column constraints cleared");
            assertEquals(0, grid.getRowConstraints().size(), "stale row constraints cleared");
        });
    }

    @Test
    void buildAppliesTheTwoPixelGapAndFourPixelPadding() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            assertEquals(2, grid.getHgap(), 0.001);
            assertEquals(2, grid.getVgap(), 0.001);
            assertEquals(4, grid.getPadding().getTop(), 0.001);
            assertEquals(4, grid.getPadding().getLeft(), 0.001);
        });
    }

    @Test
    void buildLaysOutSevenWeekdayHeaders() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            List<String> headers = headerTexts(grid);
            assertEquals(7, headers.size(), "seven weekday headers: " + headers);
            assertTrue(headers.containsAll(
                    List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")));
        });
    }

    @Test
    void buildPutsTheMonthTitleInTheFirstSpanningRow() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            Label title = null;
            for (var node : grid.getChildren()) {
                if (node instanceof Label l && "OCTOBER 2026".equals(l.getText())) title = l;
            }
            assertNotNull(title, "expected the current month title");
            assertEquals(0, GridPane.getRowIndex(title));
            assertEquals(0, GridPane.getColumnIndex(title));
            assertEquals(7, GridPane.getColumnSpan(title));
        });
    }

    @Test
    void buildMakesFortyTwoDayCells() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            int cells = 0;
            for (var node : grid.getChildren()) {
                Integer r = GridPane.getRowIndex(node);
                if (node instanceof VBox && r != null && r >= 2) cells++;
            }
            assertEquals(42, cells, "six weeks of seven days");
        });
    }

    @Test
    void theGridStartsOnTheMondayOfOrBeforeTheFirstOfTheMonth() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            // October 2026 starts on a Thursday, so the grid opens on Monday 28 September.
            VBox first = cellAt(grid, 0, 0);
            assertNotNull(first, "the first cell exists");
            assertEquals("28", firstDayNumber(first), "the grid starts on 28 September");
        });
    }

    private static String firstDayNumber(VBox cell) {
        for (var node : cell.getChildren()) {
            if (node instanceof Label l) return l.getText();
        }
        return null;
    }

    @Test
    void monthOffsetMovesTheTitleBackwardsAndForwards() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane back = new GridPane();
            new MonthGridBuilder(system, null).build(back, null, -1);
            assertTrue(hasLabel(back, "SEPTEMBER 2026"), "one month back");

            GridPane forward = new GridPane();
            new MonthGridBuilder(system, null).build(forward, null, 1);
            assertTrue(hasLabel(forward, "NOVEMBER 2026"), "one month forward");
        });
    }

    @Test
    void monthOffsetCrossesTheYearBoundary() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            // October + 4 = February 2027.
            new MonthGridBuilder(system, null).build(grid, null, 4);
            assertTrue(hasLabel(grid, "FEBRUARY 2027"), "offsets roll over the year");
        });
    }

    private static boolean hasLabel(GridPane grid, String text) {
        for (var node : grid.getChildren()) {
            if (node instanceof Label l && text.equals(l.getText())) return true;
        }
        return false;
    }

    @Test
    void buildStylesTheSidePanelWhenGiven() throws Exception {
        FxTestKit.onFx(() -> {
            Label panel = new Label();
            new MonthGridBuilder(system, null).build(new GridPane(), panel, 0);

            assertNotNull(panel.getStyle());
            assertTrue(panel.getStyle().contains(AppTheme.ink()), panel.getStyle());
        });
    }

    @Test
    void buildToleratesANullSidePanel() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);
            assertEquals(42 + 7 + 1, countPlaced(grid), "title, headers, and 42 cells");
        });
    }

    private static int countPlaced(GridPane grid) {
        int n = 0;
        for (var node : grid.getChildren()) {
            if (GridPane.getRowIndex(node) != null) n++;
        }
        return n;
    }

    // ==================================================================
    // day-cell shading
    // ==================================================================

    @Test
    void todaysCellUsesTheTodayColour() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            VBox todayCell = cellFor(grid, TODAY);
            assertNotNull(todayCell, "today (12 October) is on the grid");
            assertTrue(todayCell.getStyle().contains(AppTheme.today()),
                    "today's cell: " + todayCell.getStyle());
        });
    }

    @Test
    void aPastDayInsideTheMonthUsesThePastColour() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            VBox past = cellFor(grid, TODAY.minusDays(5));
            assertNotNull(past);
            assertTrue(past.getStyle().contains(AppTheme.past()), past.getStyle());
        });
    }

    @Test
    void aFutureDayInsideTheMonthUsesThePaperColour() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            VBox future = cellFor(grid, TODAY.plusDays(5));
            assertNotNull(future);
            assertTrue(future.getStyle().contains(AppTheme.paper()), future.getStyle());
        });
    }

    @Test
    void aDayFromAnAdjacentMonthUsesTheDimmedColour() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            // 28 September leads the October grid and is not in the visible month.
            VBox lead = cellAt(grid, 0, 0);
            assertNotNull(lead);
            assertTrue(lead.getStyle().contains("#e8e8e8"),
                    "an out-of-month cell in the light pack: " + lead.getStyle());
        });
    }

    @Test
    void aHolidayNameIsRenderedAndTurnsTheCellAmber() throws Exception {
        FxTestKit.onFx(() -> {
            // Pin the clock so the visible month contains Christmas Day.
            AppTimeReference.setClock(Clock.fixed(Instant.parse("2026-12-10T12:00:00Z"), ZoneOffset.UTC));
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            VBox xmas = cellFor(grid, LocalDate.of(2026, 12, 25));
            assertNotNull(xmas, "25 December 2026 is on the December grid");
            assertTrue(xmas.getStyle().contains("#ffe0b2"),
                    "a holiday in the light pack is amber: " + xmas.getStyle());
            assertTrue(cellTexts(xmas).contains("Christmas Day"),
                    "the holiday name is shown: " + cellTexts(xmas));
        });
    }

    @Test
    void aHolidayInTheDarkPackUsesTheDarkAmber() throws Exception {
        FxTestKit.onFx(() -> {
            AppTheme.setPack(org.ashoreprospect.GUI.theme.BuiltinThemes.DARK.id());
            AppTimeReference.setClock(Clock.fixed(Instant.parse("2026-12-10T12:00:00Z"), ZoneOffset.UTC));
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            VBox xmas = cellFor(grid, LocalDate.of(2026, 12, 25));
            assertNotNull(xmas);
            assertTrue(xmas.getStyle().contains("#5a4320"), "dark amber: " + xmas.getStyle());
        });
    }

    private static VBox cellFor(GridPane grid, LocalDate date) {
        String day = String.valueOf(date.getDayOfMonth());
        for (var node : grid.getChildren()) {
            if (node instanceof VBox v) {
                List<String> texts = cellTexts(v);
                if (!texts.isEmpty() && texts.get(0).equals(day)) return v;
            }
        }
        return null;
    }

    private static List<String> cellTexts(VBox cell) {
        List<String> out = new ArrayList<>();
        for (var node : cell.getChildren()) {
            if (node instanceof Label l) out.add(l.getText());
        }
        return out;
    }

    // ==================================================================
    // task chips
    // ==================================================================

    @Test
    void aCellListsTheTasksPlacedThatDay() throws Exception {
        FxTestKit.onFx(() -> {
            placeOn(TODAY.plusDays(1), 9, "Write report");
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            VBox cell = cellFor(grid, TODAY.plusDays(1));
            assertNotNull(cell);
            assertTrue(cellTexts(cell).contains("• Write report"),
                    "expected the chip text: " + cellTexts(cell));
        });
    }

    @Test
    void quickTasksAreNotListed() throws Exception {
        FxTestKit.onFx(() -> {
            Task quick = task("quick one");
            quick.setPlacedDate(TODAY.plusDays(1));
            quick.setPlacedTime(LocalTime.of(9, 0));
            // A quick task carries a parent id; Day.placeTask refuses those too.
            Task parent = task("parent");
            quick.setParentId(parent.getID());
            system.getPlacedTasks().add(quick);

            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            VBox cell = cellFor(grid, TODAY.plusDays(1));
            assertNotNull(cell);
            assertFalse(cellTexts(cell).stream().anyMatch(t -> t.contains("quick one")),
                    "quick tasks are filtered: " + cellTexts(cell));
        });
    }

    @Test
    void aCellShowsAtMostFourChipsThenAMoreCount() throws Exception {
        FxTestKit.onFx(() -> {
            LocalDate day = TODAY.plusDays(1);
            for (int i = 0; i < 6; i++) placeOn(day, 7 + i, "Task " + i);

            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            VBox cell = cellFor(grid, day);
            assertNotNull(cell);
            List<String> texts = cellTexts(cell);
            long chips = texts.stream().filter(t -> t.startsWith("• ")).count();
            assertEquals(4, chips, "only four chips fit: " + texts);
            assertTrue(texts.contains("+2 more"), "the overflow count: " + texts);
        });
    }

    @Test
    void exactlyFourChipsShowsNoMoreLabel() throws Exception {
        FxTestKit.onFx(() -> {
            LocalDate day = TODAY.plusDays(1);
            for (int i = 0; i < 4; i++) placeOn(day, 7 + i, "Task " + i);

            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            List<String> texts = cellTexts(cellFor(grid, day));
            assertEquals(4, texts.stream().filter(t -> t.startsWith("• ")).count());
            assertFalse(texts.stream().anyMatch(t -> t.contains("more")),
                    "no overflow at exactly four: " + texts);
        });
    }

    @Test
    void dayCellsCanBeToldApartWhenTheSameDayNumberRepeats() throws Exception {
        FxTestKit.onFx(() -> {
            // The grid shows 28 Sep and 28 Oct; both read "28". Ordering sorts by column run,
            // so the September one comes first.
            GridPane grid = new GridPane();
            new MonthGridBuilder(system, null).build(grid, null, 0);

            List<VBox> twenties = new ArrayList<>();
            for (var node : grid.getChildren()) {
                if (node instanceof VBox v && !cellTexts(v).isEmpty()
                        && "28".equals(cellTexts(v).get(0))) {
                    twenties.add(v);
                }
            }
            assertEquals(2, twenties.size(), "28 Sep and 28 Oct both appear");
            assertFalse(twenties.get(0).getStyle().equals(twenties.get(1).getStyle()),
                    "the out-of-month 28 and the in-month 28 are shaded differently");
        });
    }

    // ==================================================================
    // click handling
    // ==================================================================

    @Test
    void clickingACellWithTasksSelectsTheFirstOne() throws Exception {
        FxTestKit.onFx(() -> {
            Task placed = placeOn(TODAY.plusDays(1), 9, "Clickable");
            AtomicReference<Task> selected = new AtomicReference<>();
            MonthGridBuilder builder = new MonthGridBuilder(system, null);
            builder.setOnSelectTask(selected::set);

            GridPane grid = new GridPane();
            builder.build(grid, null, 0);

            VBox cell = cellFor(grid, TODAY.plusDays(1));
            assertNotNull(cell);
            cell.fireEvent(click(cell, MouseButton.PRIMARY));

            assertEquals(placed, selected.get(), "the first task on the day is selected");
        });
    }

    @Test
    void clickingAnEmptyFutureCellDoesNotSelectAnything() throws Exception {
        FxTestKit.onFx(() -> {
            AtomicReference<Task> selected = new AtomicReference<>();
            MonthGridBuilder builder = new MonthGridBuilder(system, null);
            builder.setOnSelectTask(selected::set);

            GridPane grid = new GridPane();
            builder.build(grid, null, 0);

            VBox cell = cellFor(grid, TODAY.plusDays(3));
            assertNotNull(cell, "the cell exists");
            assertTrue(cellTexts(cell).size() == 1, "only the day number: " + cellTexts(cell));
            cell.fireEvent(click(cell, MouseButton.PRIMARY));

            assertEquals(null, selected.get(), "nothing to select in an empty cell");
        });
    }

    @Test
    void aNullDialogAndNoTasksLeavesAFutureClickHarmless() throws Exception {
        FxTestKit.onFx(() -> {
            MonthGridBuilder builder = new MonthGridBuilder(system, null);
            GridPane grid = new GridPane();
            builder.build(grid, null, 0);

            VBox cell = cellFor(grid, TODAY.plusDays(2));
            assertNotNull(cell);
            // dialogs == null guards the showTaskPopup branch, so this must not throw.
            cell.fireEvent(click(cell, MouseButton.PRIMARY));
            assertTrue(true, "no exception was raised");
        });
    }

    @Test
    void clickingAnEmptyFutureCellWithDialogsOpensTheAddTaskPopup() throws Exception {
        // The popup is shown by flipping visibility, not by showAndWait, so this returns.
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane();
            new Scene(root, 800, 600);
            TaskDialogs dialogs = new TaskDialogs(root, system, () -> { });

            MonthGridBuilder builder = new MonthGridBuilder(system, dialogs);
            GridPane grid = new GridPane();
            builder.build(grid, null, 0);

            VBox cell = cellFor(grid, TODAY.plusDays(2));
            assertNotNull(cell);
            int before = root.getChildren().size();
            cell.fireEvent(click(cell, MouseButton.PRIMARY));

            assertTrue(root.getChildren().size() > before,
                    "the add-task overlay was mounted on the root");
        });
    }

    @Test
    void rightClickingAnEmptyFutureCellWithDialogsAlsoOpensThePopup() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane();
            new Scene(root, 800, 600);
            TaskDialogs dialogs = new TaskDialogs(root, system, () -> { });

            MonthGridBuilder builder = new MonthGridBuilder(system, dialogs);
            GridPane grid = new GridPane();
            builder.build(grid, null, 0);

            VBox cell = cellFor(grid, TODAY.plusDays(2));
            assertNotNull(cell);
            int before = root.getChildren().size();
            cell.fireEvent(click(cell, MouseButton.SECONDARY));

            assertTrue(root.getChildren().size() > before,
                    "secondary click takes the same popup path");
        });
    }

    @Test
    void rightClickingACellWithTasksStillOpensThePopupRatherThanSelecting() throws Exception {
        FxTestKit.onFx(() -> {
            placeOn(TODAY.plusDays(1), 9, "Right click me");
            AtomicReference<Task> selected = new AtomicReference<>();
            StackPane root = new StackPane();
            new Scene(root, 800, 600);
            TaskDialogs dialogs = new TaskDialogs(root, system, () -> { });

            MonthGridBuilder builder = new MonthGridBuilder(system, dialogs);
            builder.setOnSelectTask(selected::set);
            GridPane grid = new GridPane();
            builder.build(grid, null, 0);

            VBox cell = cellFor(grid, TODAY.plusDays(1));
            assertNotNull(cell);
            int before = root.getChildren().size();
            cell.fireEvent(click(cell, MouseButton.SECONDARY));

            assertEquals(null, selected.get(),
                    "a secondary click is a menu, not a selection");
            assertTrue(root.getChildren().size() > before, "the popup still opened");
        });
    }

    @Test
    void aPastEmptyCellWithDialogsDoesNotOpenThePopup() throws Exception {
        FxTestKit.onFx(() -> {
            StackPane root = new StackPane();
            new Scene(root, 800, 600);
            TaskDialogs dialogs = new TaskDialogs(root, system, () -> { });

            MonthGridBuilder builder = new MonthGridBuilder(system, dialogs);
            GridPane grid = new GridPane();
            builder.build(grid, null, 0);

            VBox cell = cellFor(grid, TODAY.minusDays(3));
            assertNotNull(cell, "an earlier day in the same month is on the grid");
            int before = root.getChildren().size();
            cell.fireEvent(click(cell, MouseButton.PRIMARY));

            assertEquals(before, root.getChildren().size(),
                    "past days are read-only, so nothing is mounted");
        });
    }

    private static MouseEvent click(javafx.scene.Node target, MouseButton button) {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, button, 1,
                false, false, false, false,
                button == MouseButton.PRIMARY, false, false, false, false, true,
                null);
    }
}
