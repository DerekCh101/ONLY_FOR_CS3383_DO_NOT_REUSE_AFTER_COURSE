/**
 * The three-column day view: yesterday / today / tomorrow cards, their headings, the "No tasks"
 * placeholder, the per-task description lines (including the checkbox-list renderer), and the
 * click-to-select handler.
 *
 * <p>The clock is pinned to a Monday noon so the three columns are deterministic.
 * {@code TaskManagementSystem} is process-wide, so it is reset and its collections cleared around
 * every test.
 */
package org.ashoreprospect.GUI;

import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.BlockedTimePeriod;
import org.ashoreprospect.Day;
import org.ashoreprospect.FxTestKit;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.Workflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("task-system")
class DayGridBuilderTest {

    /** Monday 2026-10-12 at noon — mid-day so both morning and afternoon slots are "future". */
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

    private static Task task(String name, double hours) {
        Task t = new Task(name, hours, 0, null, null, null, null);
        t.setTaskName(name);
        return t;
    }

    private Task placeOn(LocalDate date, int hour, String name, double hours) {
        Task t = task(name, hours);
        t.setPlacedDate(date);
        t.setPlacedTime(LocalTime.of(hour, 0));
        system.getPlacedTasks().add(t);
        Day day = system.getGrid().computeIfAbsent(date, d -> new Day(new BlockedTimePeriod()));
        day.placeTask(AppTimeReference.localTimeToSlot(LocalTime.of(hour, 0)), t);
        return t;
    }

    /** The three column VBoxes, in grid-column order. */
    private static List<VBox> columns(GridPane grid) {
        VBox[] out = new VBox[3];
        for (var node : grid.getChildren()) {
            Integer col = GridPane.getColumnIndex(node);
            if (node instanceof VBox v && col != null && col >= 0 && col < 3) out[col] = v;
        }
        List<VBox> list = new ArrayList<>();
        for (VBox v : out) if (v != null) list.add(v);
        return list;
    }

    /** Every Label text inside a subtree, depth-first. */
    private static List<String> texts(javafx.scene.Node root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(javafx.scene.Node node, List<String> out) {
        if (node instanceof Label l) out.add(l.getText());
        // A ScrollPane keeps its content off the children list, so follow it explicitly.
        if (node instanceof ScrollPane s && s.getContent() != null) {
            collect(s.getContent(), out);
        }
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node child : p.getChildrenUnmodifiable()) collect(child, out);
        }
    }

    private static ScrollPane scrollOf(VBox column) {
        for (var node : column.getChildren()) {
            if (node instanceof ScrollPane s) return s;
        }
        return null;
    }

    // ==================================================================
    // grid chrome
    // ==================================================================

    @Test
    void buildClearsPreviousContent() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            grid.add(new Label("stale"), 0, 0);

            new DayGridBuilder(system).build(grid, null, 0);

            assertFalse(texts(grid).contains("stale"), "old children must be gone");
        });
    }

    @Test
    void buildSetsTheTenPixelGapAndEightPixelPadding() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            assertEquals(10, grid.getHgap(), 0.001);
            assertEquals(8, grid.getVgap(), 0.001);
            assertEquals(8, grid.getPadding().getTop(), 0.001);
        });
    }

    @Test
    void buildCreatesThreeEqualColumnsAndOneRow() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            assertEquals(3, grid.getColumnConstraints().size());
            assertEquals(1, grid.getRowConstraints().size());
            assertEquals(100.0 / 3.0, grid.getColumnConstraints().get(0).getPercentWidth(), 0.001);
            assertEquals(3, columns(grid).size(), "three day columns");
        });
    }

    @Test
    void theMiddleColumnIsToday() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            List<VBox> cols = columns(grid);
            assertEquals("Today", headingOf(cols.get(1)), "the centre column is today");
        });
    }

    @Test
    void theLeftAndRightColumnsAreYesterdayAndTomorrow() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            List<VBox> cols = columns(grid);
            assertEquals("Yesterday", headingOf(cols.get(0)));
            assertEquals("Tomorrow", headingOf(cols.get(2)));
        });
    }

    @Test
    void dayOffsetSlidesTheWindowSoAllThreeHeadingsBecomeWeekdays() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            // +5 days from Monday: Sat-in-the-middle, so Tue/Sat/Sun are the three columns.
            new DayGridBuilder(system).build(grid, null, 5);

            List<VBox> cols = columns(grid);
            assertEquals("Saturday", headingOf(cols.get(1)), "the centre becomes Saturday");
            assertEquals("Friday", headingOf(cols.get(0)));
            assertEquals("Sunday", headingOf(cols.get(2)));
        });
    }

    @Test
    void aNegativeOffsetLooksBackwards() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            // -2 days from Monday: Saturday in the middle.
            new DayGridBuilder(system).build(grid, null, -2);

            List<VBox> cols = columns(grid);
            assertEquals("Saturday", headingOf(cols.get(1)));
        });
    }

    private static String headingOf(VBox column) {
        for (var node : column.getChildren()) {
            if (node instanceof Label l) return l.getText();
        }
        return null;
    }

    @Test
    void theSubheadingCarriesTheWeekdayNameAndIsoDate() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            VBox today = columns(grid).get(1);
            List<String> labels = new ArrayList<>();
            for (var node : today.getChildren()) {
                if (node instanceof Label l) labels.add(l.getText());
            }
            assertTrue(labels.size() >= 2, "title plus subheading: " + labels);
            assertEquals("Monday  2026-10-12", labels.get(1), "weekday + ISO date");
        });
    }

    @Test
    void buildStylesTheSidePanelWhenGiven() throws Exception {
        FxTestKit.onFx(() -> {
            Label panel = new Label();
            new DayGridBuilder(system).build(new GridPane(), panel, 0);

            assertNotNull(panel.getStyle());
            assertTrue(panel.getStyle().contains(AppTheme.ink()), panel.getStyle());
        });
    }

    @Test
    void buildToleratesANullSidePanel() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);
            assertEquals(3, columns(grid).size());
        });
    }

    // ==================================================================
    // column styling
    // ==================================================================

    @Test
    void todayColumnIsFilledWithTheTodayColourAndATwoPixelBorder() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            VBox today = columns(grid).get(1);
            assertTrue(today.getStyle().contains(AppTheme.today()), today.getStyle());
            assertTrue(today.getStyle().contains("-fx-border-width: 2"), today.getStyle());
        });
    }

    @Test
    void theOtherColumnsUsePaperAndAOnePixelBorder() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            for (int i : new int[]{0, 2}) {
                VBox col = columns(grid).get(i);
                assertTrue(col.getStyle().contains(AppTheme.paper()), "column " + i + ": " + col.getStyle());
                assertTrue(col.getStyle().contains("-fx-border-width: 1"), "column " + i);
            }
        });
    }

    @Test
    void eachColumnWrapsItsCardsInAScrollPane() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            for (VBox col : columns(grid)) {
                ScrollPane scroll = scrollOf(col);
                assertNotNull(scroll, "every day column scrolls");
                assertTrue(scroll.isFitToWidth(), "cards stretch to the column width");
                assertEquals(ScrollPane.ScrollBarPolicy.NEVER, scroll.getHbarPolicy());
            }
        });
    }

    // ==================================================================
    // empty and populated columns
    // ==================================================================

    @Test
    void anEmptyDayShowsTheNoTasksPlaceholder() throws Exception {
        FxTestKit.onFx(() -> {
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            for (VBox col : columns(grid)) {
                assertTrue(texts(col).contains("No tasks"), "empty column: " + texts(col));
            }
        });
    }

    @Test
    void aDayWithATaskListsItInsteadOfThePlaceholder() throws Exception {
        FxTestKit.onFx(() -> {
            placeOn(TODAY, 14, "Standup", 0.5);
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            List<String> today = texts(columns(grid).get(1));
            assertTrue(today.contains("Standup"), "the task name: " + today);
            assertFalse(today.contains("No tasks"), "the placeholder is replaced: " + today);
        });
    }

    @Test
    void aTaskOnAnAdjacentDayLandsInItsOwnColumn() throws Exception {
        FxTestKit.onFx(() -> {
            placeOn(TODAY.plusDays(1), 9, "Tomorrow task", 1);
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            List<VBox> cols = columns(grid);
            assertTrue(texts(cols.get(2)).contains("Tomorrow task"), "right column: " + texts(cols.get(2)));
            assertFalse(texts(cols.get(1)).contains("Tomorrow task"), "not the centre column");
        });
    }

    @Test
    void tasksAreOrderedByTheirStartTime() throws Exception {
        FxTestKit.onFx(() -> {
            placeOn(TODAY, 16, "Afternoon", 1);
            placeOn(TODAY, 13, "Early", 1);
            placeOn(TODAY, 15, "Midday", 1);

            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            List<String> labels = texts(columns(grid).get(1));
            int early = labels.indexOf("Early");
            int midday = labels.indexOf("Midday");
            int afternoon = labels.indexOf("Afternoon");
            assertTrue(early >= 0 && midday >= 0 && afternoon >= 0, "all three present: " + labels);
            assertTrue(early < midday && midday < afternoon,
                    "chips follow the clock: " + labels);
        });
    }

    @Test
    void quickTasksAreLeftOut() throws Exception {
        FxTestKit.onFx(() -> {
            Task parent = task("Parent", 1);
            Task quick = task("Quick bit", 0.5);
            quick.setParentId(parent.getID());
            quick.setPlacedDate(TODAY);
            quick.setPlacedTime(LocalTime.of(14, 0));
            system.getPlacedTasks().add(quick);

            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            assertFalse(texts(columns(grid).get(1)).contains("Quick bit"),
                    "quicks are filtered out of the day view");
        });
    }

    // ==================================================================
    // card metadata
    // ==================================================================

    @Test
    void aCardShowsTheStartTimeAndDuration() throws Exception {
        FxTestKit.onFx(() -> {
            placeOn(TODAY, 14, "Timed", 1.5);
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            List<String> labels = texts(columns(grid).get(1));
            assertTrue(labels.contains("14:00  ·  1.5 h"),
                    "time and hours: " + labels);
        });
    }

    @Test
    void aFinishedTaskIsMarkedDone() throws Exception {
        FxTestKit.onFx(() -> {
            Task t = placeOn(TODAY, 14, "Finished one", 1);
            t.setWorkflow(Workflow.FINISHED);

            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            List<String> labels = texts(columns(grid).get(1));
            assertTrue(labels.stream().anyMatch(s -> s.endsWith("done")),
                    "a finished task reads as done: " + labels);
        });
    }

    @Test
    void aCardWithoutADescriptionSaysSo() throws Exception {
        FxTestKit.onFx(() -> {
            placeOn(TODAY, 14, "No desc", 1);
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            assertTrue(texts(columns(grid).get(1)).contains("No description"));
        });
    }

    @Test
    void aBlankDescriptionAlsoReadsAsNoDescription() throws Exception {
        FxTestKit.onFx(() -> {
            Task t = placeOn(TODAY, 14, "Blank desc", 1);
            t.setDescription("   ");
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            assertTrue(texts(columns(grid).get(1)).contains("No description"));
        });
    }

    @Test
    void aPlainDescriptionIsShownAsOneLine() throws Exception {
        FxTestKit.onFx(() -> {
            Task t = placeOn(TODAY, 14, "With desc", 1);
            t.setDescription("Just one sentence.");
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            assertTrue(texts(columns(grid).get(1)).contains("Just one sentence."));
        });
    }

    @Test
    void aCheckboxDescriptionRendersTickAndBoxGlyphs() throws Exception {
        FxTestKit.onFx(() -> {
            Task t = placeOn(TODAY, 14, "Checklist", 1);
            t.setDescription("[x] done item\n[ ] open item");
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            List<String> labels = texts(columns(grid).get(1));
            assertTrue(labels.contains("☑  done item"), "ticked item: " + labels);
            assertTrue(labels.contains("☐  open item"), "open item: " + labels);
        });
    }

    @Test
    void anUppercaseXIsAlsoTreatedAsDone() throws Exception {
        FxTestKit.onFx(() -> {
            Task t = placeOn(TODAY, 14, "Upper", 1);
            t.setDescription("[X] upper done");
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            assertTrue(texts(columns(grid).get(1)).contains("☑  upper done"));
        });
    }

    @Test
    void aMixedListKeepsItsFreeTextLines() throws Exception {
        FxTestKit.onFx(() -> {
            Task t = placeOn(TODAY, 14, "Mixed", 1);
            t.setDescription("heading\n[x] item one\n[ ] item two");
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            List<String> labels = texts(columns(grid).get(1));
            assertTrue(labels.contains("heading"), "free text survives: " + labels);
            assertTrue(labels.contains("☑  item one"));
            assertTrue(labels.contains("☐  item two"));
        });
    }

    @Test
    void blankLinesInAListAreSkipped() throws Exception {
        FxTestKit.onFx(() -> {
            Task t = placeOn(TODAY, 14, "Gappy", 1);
            t.setDescription("[x] one\n\n[ ] two\n");
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            long glyphs = texts(columns(grid).get(1)).stream()
                    .filter(s -> s.startsWith("☑") || s.startsWith("☐")).count();
            assertEquals(2, glyphs, "exactly two glyph lines, blanks dropped");
        });
    }

    @Test
    void aDescriptionWithoutMarksIsShownVerbatim() throws Exception {
        FxTestKit.onFx(() -> {
            Task t = placeOn(TODAY, 14, "Verbatim", 1);
            // No leading [x]/[ ] anywhere, so the raw text is used untouched.
            t.setDescription("line one\nline two");
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            assertTrue(texts(columns(grid).get(1)).contains("line one\nline two"),
                    "unmarked text keeps its newline verbatim");
        });
    }

    // ==================================================================
    // click to select
    // ==================================================================

    @Test
    void clickingACardSelectsItsTask() throws Exception {
        FxTestKit.onFx(() -> {
            Task placed = placeOn(TODAY, 14, "Pick me", 1);
            AtomicReference<Task> selected = new AtomicReference<>();
            DayGridBuilder builder = new DayGridBuilder(system);
            builder.setOnSelectTask(selected::set);

            GridPane grid = new GridPane();
            builder.build(grid, null, 0);

            VBox card = cardFor(columns(grid).get(1), "Pick me");
            assertNotNull(card, "the task card exists");
            card.fireEvent(new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                    javafx.scene.input.MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, true, null));

            assertEquals(placed, selected.get());
        });
    }

    @Test
    void clickingACardWithoutAHandlerIsHarmless() throws Exception {
        FxTestKit.onFx(() -> {
            placeOn(TODAY, 14, "No handler", 1);
            GridPane grid = new GridPane();
            new DayGridBuilder(system).build(grid, null, 0);

            VBox card = cardFor(columns(grid).get(1), "No handler");
            assertNotNull(card);
            card.fireEvent(new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                    javafx.scene.input.MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, true, null));
            assertTrue(true, "no handler means no exception");
        });
    }

    /** The innermost card VBox whose labels include {@code name}. */
    private static VBox cardFor(VBox column, String name) {
        VBox found = null;
        for (var node : allNodes(column)) {
            if (node instanceof VBox v && texts(v).contains(name)) {
                // Prefer the deepest match.
                found = v;
            }
        }
        return found;
    }

    private static List<javafx.scene.Node> allNodes(javafx.scene.Node root) {
        List<javafx.scene.Node> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(javafx.scene.Node node, List<javafx.scene.Node> out) {
        out.add(node);
        if (node instanceof ScrollPane s && s.getContent() != null) {
            walk(s.getContent(), out);
        }
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node child : p.getChildrenUnmodifiable()) walk(child, out);
        }
    }
}
