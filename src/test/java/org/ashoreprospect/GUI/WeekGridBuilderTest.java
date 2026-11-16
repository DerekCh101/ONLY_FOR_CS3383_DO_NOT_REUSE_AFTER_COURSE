/**
 * {@link WeekGridBuilder#build}: the 336-cell skeleton, the week headers, the time gutter, the
 * "now" bar, the blocked-time captions, and the task overlays.
 *
 * <p>The builder never opens a window: it paints into a plain {@link GridPane}, so everything here
 * runs headless apart from needing the JavaFX thread for node creation.
 */
package org.ashoreprospect.GUI;

import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.BlockedTime;
import org.ashoreprospect.Day;
import org.ashoreprospect.BlockedTimePeriod;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("task-system")
class WeekGridBuilderTest {

    /** Monday 2026-10-12, 12:00 — two thirds of the way through the 11:30 row. */
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private static final LocalDate WEEK_START = TODAY;
    private static final int SLOTS = 46; // SLOTS_PER_DAY

    private Clock savedClock;
    private TaskManagementSystem system;
    private TaskDialogs dialogs;
    private WeekGridBuilder builder;
    private GridPane grid;
    private Label sidePanel;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestKit.ensureStarted();
    }

    @BeforeEach
    void setUp() throws Exception {
        savedClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        TaskManagementSystem.resetInstance();
        system = TaskManagementSystem.getInstance();
        clearSystem();
        FxTestKit.onFx(() -> {
            AppTheme.clearListeners();
            AppTheme.setPack(org.ashoreprospect.GUI.theme.BuiltinThemes.LIGHT.id());
            StackPane root = new StackPane();
            dialogs = new TaskDialogs(root, system, null);
            builder = new WeekGridBuilder(system,
                    new TaskDragDropSupport(system, root, null), dialogs);
            grid = new GridPane();
            sidePanel = new Label();
        });
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
        if (system.getBlockedTime() != null) system.getBlockedTime().blockedPeriod.clear();
    }

    private void build(int weekOffset) throws Exception {
        FxTestKit.onFx(() -> builder.build(grid, sidePanel, weekOffset));
    }

    /**
     * Like {@link FxTestKit#onFx} but returns the value computed on the FX thread.
     *
     * <p>The checked exception is rethrown unchecked so that callers can use this directly inside
     * {@code assertTrue(...)} arguments, which Java will not let declare {@code throws}.
     */
    private static <T> T onFxGet(java.util.function.Supplier<T> body) {
        java.util.concurrent.atomic.AtomicReference<T> out = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            FxTestKit.onFx(() -> out.set(body.get()));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.get();
    }

    /** The column header for day {@code col} (0 = Monday) of the visible week. */
    private Label header(int col) {
        return onFxGet(() -> grid.getChildren().stream()
                .filter(n -> n instanceof Label)
                .filter(n -> GridPane.getColumnIndex(n) == col + 1)
                .filter(n -> GridPane.getRowIndex(n) == 0)
                .map(n -> (Label) n)
                .findFirst().orElseThrow());
    }

    /** The time-gutter label for {@code slotIndex}. */
    private Label timeLabel(int slotIndex) {
        return onFxGet(() -> grid.getChildren().stream()
                .filter(n -> n instanceof Label)
                .filter(n -> GridPane.getColumnIndex(n) == 0)
                .filter(n -> GridPane.getRowIndex(n) == slotIndex + 1)
                .map(n -> (Label) n)
                .findFirst().orElseThrow());
    }

    /**
     * The cell at (slot, col) — column 0 is Monday.
     *
     * <p>{@code Label} extends {@code Pane}, so the filter has to key on the cell tag rather than
     * on the node type.
     */
    private Pane cell(int slot, int col) {
        return onFxGet(() -> findCell(slot, col));
    }

    /**
     * Find a cell without hopping to the FX thread.
     *
     * <p>Must only be called from the FX thread — it exists so that tests already inside
     * {@code onFxGet} can reach into the grid without nesting a second thread hop, which would
     * deadlock.
     */
    private Pane findCell(int slot, int col) {
        return grid.getChildren().stream()
                .filter(n -> n instanceof Pane)
                .filter(n -> WeekGridBuilder.cellState((Pane) n) != null)
                .filter(n -> GridPane.getColumnIndex(n) == col + 1)
                .filter(n -> GridPane.getRowIndex(n) == slot + 1)
                .map(n -> (Pane) n)
                .findFirst().orElseThrow();
    }

    /**
     * The caption node of a cell.
     *
     * <p>Overlay bars are inserted at index 0 ({@code add(0, bar)}) so the tint sits under the text,
     * and the start slot then appends a bare {@code Region} tick at the end. The caption is neither:
     * it is a {@code Label} or a {@code VBox}. Note {@code Label extends Region}, so the overlay can
     * only be told apart by its exact class.
     */
    private static javafx.scene.Node caption(Pane cell) {
        return cell.getChildren().stream()
                .filter(n -> n.getClass() != Region.class)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the cell holds a caption: " + cell.getChildren()));
    }

    private long cellCount() {
        return onFxGet(() -> grid.getChildren().stream()
                .filter(n -> n instanceof Pane)
                .filter(n -> WeekGridBuilder.cellState((Pane) n) != null)
                .count());
    }

    private Region nowBar() {
        return onFxGet(() -> grid.getChildren().stream()
                .filter(n -> n instanceof Region)
                .filter(n -> "red".equals(styleBackground(n)))
                .filter(n -> Integer.valueOf(7).equals(GridPane.getColumnSpan(n)))
                .map(n -> (Region) n)
                .findFirst().orElse(null));
    }

    private static String styleBackground(javafx.scene.Node n) {
        // The "now" bar is the only full-width Region carrying -fx-background-color: red.
        if (!(n instanceof Region)) return null;
        String s = ((Region) n).getStyle();
        return s != null && s.contains("red") ? "red" : null;
    }

    private static Task placed(String name, LocalDate date, LocalTime time, double hours) {
        Task t = new Task(name, hours, 0, null, null, null, null);
        t.setTaskName(name);
        t.setPlacedDate(date);
        t.setPlacedTime(time);
        return t;
    }

    // ==================================================================
    // skeleton
    // ==================================================================

    @Test
    void aFreshBuildCreatesOneCellPerSlotPerDay() throws Exception {
        build(0);
        assertEquals(AppTimeReference.SLOTS_PER_DAY * 7L, cellCount(),
                "48 slots x 7 days");
    }

    @Test
    void everyCellCarriesAStateWithItsDateAndSlot() throws Exception {
        build(0);

        Pane mondayNoon = cell(AppTimeReference.localTimeToSlot(LocalTime.NOON), 0);
        WeekGridBuilder.CellState st = WeekGridBuilder.cellState(mondayNoon);
        assertNotNull(st);
        assertEquals(WEEK_START, st.columnDate);
        assertEquals(AppTimeReference.localTimeToSlot(LocalTime.NOON), st.slotIndex);

        Pane sunday = cell(0, 6);
        assertEquals(WEEK_START.plusDays(6), WeekGridBuilder.cellState(sunday).columnDate,
                "column 6 is Sunday of the same week");
    }

    @Test
    void theNextWeekOffsetShiftsEveryHeaderAndCellBySevenDays() throws Exception {
        build(1);

        assertEquals(WEEK_START.plusDays(7), WeekGridBuilder.cellState(cell(0, 0)).columnDate);
        assertEquals(WEEK_START.plusDays(13), WeekGridBuilder.cellState(cell(0, 6)).columnDate);
    }

    @Test
    void buildingTheSameGridTwiceReusesTheSkeleton() throws Exception {
        build(0);
        Object firstCell = cell(0, 0);

        build(0);

        assertSame(firstCell, cell(0, 0), "the skeleton is not rebuilt in place");
        assertEquals(AppTimeReference.SLOTS_PER_DAY * 7L, cellCount(), "and is not duplicated");
    }

    @Test
    void movingToAFreshGridRebuildsTheSkeleton() throws Exception {
        build(0);
        GridPane other = onFxGet(GridPane::new);

        FxTestKit.onFx(() -> builder.build(other, sidePanel, 0));

        long onOther = onFxGet(() -> other.getChildren().stream()
                .filter(n -> n instanceof Pane)
                .filter(n -> WeekGridBuilder.cellState((Pane) n) != null)
                .count());
        assertEquals(AppTimeReference.SLOTS_PER_DAY * 7L, onOther);
    }

    // ==================================================================
    // headers and time gutter
    // ==================================================================

    @Test
    void headersNameEveryDayOfTheVisibleWeek() throws Exception {
        build(0);

        assertEquals("MON 10/12", header(0).getText());
        assertEquals("TUE 10/13", header(1).getText());
        assertEquals("SUN 10/18", header(6).getText());
    }

    @Test
    void headerFormatPadsSingleDigitMonthsAndDays() throws Exception {
        AppTimeReference.setClock(Clock.fixed(Instant.parse("2027-01-05T12:00:00Z"), ZoneOffset.UTC));

        build(0); // the week containing Tue 2027-01-05 starts Mon 2027-01-04

        assertEquals("MON 1/4", header(0).getText());
        assertEquals("FRI 1/8", header(4).getText());
    }

    @Test
    void theTimeGutterLabelsEverySlot() throws Exception {
        build(0);

        assertEquals("06:00", timeLabel(0).getText(), "the grid starts at 06:00");
        assertEquals("12:00", timeLabel(AppTimeReference.localTimeToSlot(LocalTime.NOON)).getText());
        assertEquals("05:30 +", timeLabel(AppTimeReference.SLOTS_PER_DAY - 1).getText(),
                "the last slot tips into the next calendar day");
    }

    @Test
    void onlyTheCurrentSlotIsHighlightedInRed() throws Exception {
        build(0);

        int nowSlot = AppTimeReference.localTimeToSlot(LocalTime.NOON);
        assertTrue(timeLabel(nowSlot).getStyle().contains("red"),
                "the live row is marked: " + timeLabel(nowSlot).getStyle());
        assertFalse(timeLabel(0).getStyle().contains("red"), "06:00 is not now");
    }

    @Test
    void theSidePanelIsStyledWhenPresent() throws Exception {
        build(0);

        assertNotNull(sidePanel.getStyle());
        assertTrue(sidePanel.getStyle().contains("font-weight"),
                "the side panel is bolded: " + sidePanel.getStyle());
    }

    @Test
    void aNullSidePanelIsTolerated() throws Exception {
        FxTestKit.onFx(() -> builder.build(grid, null, 0));

        assertEquals(AppTimeReference.SLOTS_PER_DAY * 7L, cellCount());
    }

    // ==================================================================
    // the now bar
    // ==================================================================

    @Test
    void theNowBarSitsOnTheCurrentRow() throws Exception {
        build(0);

        Region bar = nowBar();
        assertNotNull(bar, "the current week gets a now line");
        assertEquals(AppTimeReference.localTimeToSlot(LocalTime.NOON) + 1,
                onFxGet(() -> GridPane.getRowIndex(bar)));
        assertEquals(Integer.valueOf(1), onFxGet(() -> GridPane.getColumnIndex(bar)));
        assertEquals(Integer.valueOf(7), onFxGet(() -> GridPane.getColumnSpan(bar)));
    }

    @Test
    void theNowBarIsOffsetWithinItsSlotByTheSeconds() throws Exception {
        AppTimeReference.setClock(Clock.fixed(Instant.parse("2026-10-12T12:15:00Z"), ZoneOffset.UTC));

        build(0);

        Region bar = nowBar();
        assertNotNull(bar);
        assertEquals(16.0, bar.getTranslateY(), 1e-6, "half of a 32px row");
    }

    @Test
    void theNowBarDisappearsOnOtherWeeks() throws Exception {
        build(1);

        assertNull(nowBar(), "only the current week shows a now line");
    }

    @Test
    void returningToTheCurrentWeekRestoresTheNowBar() throws Exception {
        build(1);
        build(0);

        assertNotNull(nowBar(), "the bar is reattached, not lost");
    }

    @Test
    void updateNowLinePositionMovesTheBarWithinTheSameRow() throws Exception {
        build(0);
        Region bar = nowBar();
        double before = bar.getTranslateY();

        AppTimeReference.setClock(Clock.fixed(Instant.parse("2026-10-12T12:15:00Z"), ZoneOffset.UTC));
        FxTestKit.onFx(() -> builder.updateNowLinePosition());

        assertTrue(bar.getTranslateY() > before, "the bar followed the clock");
        assertEquals(16.0, bar.getTranslateY(), 1e-6);
    }

    @Test
    void nowLineNeedsRowChangeTracksTheSlotBoundary() throws Exception {
        build(0);

        assertFalse(builder.nowLineNeedsRowChange(), "same slot as the build");

        AppTimeReference.setClock(Clock.fixed(Instant.parse("2026-10-12T12:45:00Z"), ZoneOffset.UTC));
        assertTrue(builder.nowLineNeedsRowChange(), "the clock crossed into the next slot");
    }

    @Test
    void nowLineNeedsRowChangeIsFalseBeforeAnyBuild() throws Exception {
        assertFalse(builder.nowLineNeedsRowChange());
    }

    // ==================================================================
    // tasks
    // ==================================================================

    @Test
    void aPlacedTaskIsCaptionedInItsFirstSlotOnly() throws Exception {
        Task t = placed("standup", TODAY, LocalTime.of(14, 0), 1);
        system.getPlacedTasks().add(t);

        build(0);

        Pane first = cell(AppTimeReference.localTimeToSlot(LocalTime.of(14, 0)), 0);
        Pane second = cell(AppTimeReference.localTimeToSlot(LocalTime.of(14, 30)), 0);

        Label caption = assertInstanceOf(Label.class, onFxGet(() -> caption(first)));
        assertEquals("14:00  standup", caption.getText(), "the first slot carries the clock");

        Label continuation = assertInstanceOf(Label.class, onFxGet(() -> caption(second)));
        assertEquals("", continuation.getText(), "the rest of the span stays blank");
    }

    @Test
    void aTaskOnAnotherDayIsNotCaptioned() throws Exception {
        system.getPlacedTasks().add(placed("sunday", WEEK_START.plusDays(6), LocalTime.of(14, 0), 1));

        build(0);

        assertTrue(onFxGet(
                () -> findCell(AppTimeReference.localTimeToSlot(LocalTime.of(14, 0)), 0).getChildren().isEmpty()),
                "Monday's 14:00 is untouched");
        assertFalse(onFxGet(
                () -> findCell(AppTimeReference.localTimeToSlot(LocalTime.of(14, 0)), 6).getChildren().isEmpty()),
                "Sunday's 14:00 holds the caption");
    }

    @Test
    void aTaskInTheGridWinsOverThePlacedList() throws Exception {
        Task fromGrid = placed("from grid", TODAY, LocalTime.of(14, 0), 1);
        Day day = new Day(new BlockedTimePeriod());
        day.placeTask(AppTimeReference.localTimeToSlot(LocalTime.of(14, 0)), fromGrid);
        system.getGrid().put(TODAY, day);
        system.getPlacedTasks().add(placed("from list", TODAY, LocalTime.of(14, 0), 1));

        build(0);

        Label caption = assertInstanceOf(Label.class, onFxGet(
                () -> caption(findCell(AppTimeReference.localTimeToSlot(LocalTime.of(14, 0)), 0))));
        assertEquals("14:00  from grid", caption.getText());
    }

    @Test
    void aFinishedTaskIsMarkedDone() throws Exception {
        Task t = placed("shipped", TODAY, LocalTime.of(14, 0), 1);
        t.setWorkflow(Workflow.FINISHED);
        system.getPlacedTasks().add(t);

        build(0);

        Label caption = assertInstanceOf(Label.class, onFxGet(
                () -> caption(findCell(AppTimeReference.localTimeToSlot(LocalTime.of(14, 0)), 0))));
        assertTrue(caption.getText().startsWith("done"), "got: " + caption.getText());
        assertTrue(caption.getStyle().contains("italic"), "finished tasks are italicised");
    }

    @Test
    void aNestedQuickTaskGetsItsOwnLineUnderTheParent() throws Exception {
        Task parent = placed("review", TODAY, LocalTime.of(14, 0), 2);
        Task quick = new Task("call", 0.5, 0, null, null, null, null);
        quick.setTaskName("call");
        quick.setParentId(parent.getID());
        quick.setPlacedDate(TODAY);
        quick.setPlacedTime(LocalTime.of(14, 30));
        system.getPlacedTasks().add(parent);
        system.getPlacedTasks().add(quick);

        build(0);

        VBox box = assertInstanceOf(VBox.class, onFxGet(
                () -> caption(findCell(AppTimeReference.localTimeToSlot(LocalTime.of(14, 0)), 0))));
        Label child = assertInstanceOf(Label.class, onFxGet(() -> box.getChildren().get(1)));
        assertEquals("14:30  call  ·  30 min", child.getText());
    }

    @Test
    void aQuickTaskIsNeverCaptionedOnItsOwn() throws Exception {
        Task parent = placed("review", TODAY, LocalTime.of(14, 0), 1);
        Task orphan = new Task("orphan", 0.5, 0, null, null, null, null);
        orphan.setTaskName("orphan");
        orphan.setParentId("no-such-parent");
        orphan.setPlacedDate(TODAY);
        orphan.setPlacedTime(LocalTime.of(16, 0));
        system.getPlacedTasks().add(parent);
        system.getPlacedTasks().add(orphan);

        build(0);

        int slot = AppTimeReference.localTimeToSlot(LocalTime.of(16, 0));
        List<javafx.scene.Node> kids = onFxGet(() -> new ArrayList<>(findCell(slot, 0).getChildren()));
        boolean hasCaption = kids.stream()
                .filter(n -> n instanceof Label)
                .map(n -> ((Label) n).getText())
                .anyMatch(s -> s.contains("orphan"));
        assertFalse(hasCaption, "a quick task with no parent is skipped");
    }

    @Test
    void aPendingTaskThatIsNotPlacedIsIgnored() throws Exception {
        system.getPendingTasks().add(placed("pending", TODAY, LocalTime.of(14, 0), 1));
        // placedDate/placedTime are set, but the task sits in pendingTasks, not placedTasks.

        build(0);

        assertTrue(onFxGet(
                () -> findCell(AppTimeReference.localTimeToSlot(LocalTime.of(14, 0)), 0).getChildren().isEmpty()),
                "the builder paints from the grid and the placed list only");
    }

    // ==================================================================
    // overlays
    // ==================================================================

    @Test
    void aPlacedTaskGetsAProportionalOverlayBarInEverySlotItCovers() throws Exception {
        system.getPlacedTasks().add(placed("deep work", TODAY, LocalTime.of(14, 0), 1));

        build(0);

        int start = AppTimeReference.localTimeToSlot(LocalTime.of(14, 0));
        for (int i = 0; i < 2; i++) {
            int slot = start + i;
            List<javafx.scene.Node> regions = onFxGet(() -> findCell(slot, 0).getChildren().stream()
                    .filter(n -> n.getClass() == Region.class).toList());
            assertFalse(regions.isEmpty(), "slot " + slot + " holds the overlay");
        }
    }

    @Test
    void aFinishedTaskGetsTheFadedOverlay() throws Exception {
        Task done = placed("over", TODAY, LocalTime.of(9, 0), 1);
        done.setWorkflow(Workflow.FINISHED);
        system.getPlacedTasks().add(done);

        build(0);

        int start = AppTimeReference.localTimeToSlot(LocalTime.of(9, 0));
        Region bar = (Region) onFxGet(() -> findCell(start, 0).getChildren().stream()
                .filter(n -> n.getClass() == Region.class).findFirst().orElseThrow());
        assertTrue(bar.getStyle().contains("0.50"),
                "an ended task uses the 50% fill: " + bar.getStyle());
    }

    @Test
    void aLiveTaskGetsTheBrighterOverlay() throws Exception {
        system.getPlacedTasks().add(placed("running", TODAY, LocalTime.of(14, 0), 1));

        build(0);

        int start = AppTimeReference.localTimeToSlot(LocalTime.of(14, 0));
        Region bar = (Region) onFxGet(() -> findCell(start, 0).getChildren().stream()
                .filter(n -> n.getClass() == Region.class).findFirst().orElseThrow());
        assertTrue(bar.getStyle().contains("0.22"),
                "a running task uses the 22% fill: " + bar.getStyle());
    }

    @Test
    void turningOffTheRealtimeOverlaySkipsTheBars() throws Exception {
        FxTestKit.onFx(() -> builder.setRealtimeOverlay(false));
        system.getPlacedTasks().add(placed("deep work", TODAY, LocalTime.of(14, 0), 1));

        build(0);

        assertTrue(onFxGet(
                () -> findCell(AppTimeReference.localTimeToSlot(LocalTime.of(14, 0)), 0)
                        .getChildren().stream()
                        .noneMatch(n -> n.getClass() == Region.class)),
                "no overlay bar or tick is painted");
    }

    @Test
    void anOverlayBeyondTheVisibleWeekIsSkipped() throws Exception {
        system.getPlacedTasks().add(placed("next week", WEEK_START.plusDays(8), LocalTime.of(14, 0), 1));

        build(0);

        assertTrue(onFxGet(
                () -> findCell(AppTimeReference.localTimeToSlot(LocalTime.of(14, 0)), 2).getChildren().isEmpty()),
                "wednesday of this week is empty");
    }

    // ==================================================================
    // blocked time
    // ==================================================================

    @Test
    void aBlockedSegmentGetsItsTitleAndSpan() throws Exception {
        // 14:00, not noon: a slot that starts exactly now counts as past (isPast uses !start.isAfter(now))
        // and past cells are shaded without a caption.
        system.getBlockedTime().blockedPeriod.add(
                BlockedTime.daily("lunch", LocalTime.of(14, 0), LocalTime.of(15, 0)));

        build(0);

        Pane slot = cell(AppTimeReference.localTimeToSlot(LocalTime.of(14, 0)), 0);
        Label why = assertInstanceOf(Label.class, onFxGet(() -> caption(slot)));
        assertEquals("lunch  14:00–15:00", why.getText());
    }

    @Test
    void onlyTheFirstSlotOfABlockIsLabelled() throws Exception {
        system.getBlockedTime().blockedPeriod.add(
                BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0)));

        build(0);

        int second = AppTimeReference.localTimeToSlot(LocalTime.of(12, 30));
        assertTrue(onFxGet(() -> findCell(second, 0).getChildren().isEmpty()),
                "the continuation slot is shaded but not labelled");
    }

    @Test
    void anUntitledBlockIsCalledBlocked() throws Exception {
        system.getBlockedTime().blockedPeriod.add(
                BlockedTime.daily("  ", LocalTime.of(14, 0), LocalTime.of(15, 0)));

        build(0);

        Pane slot = cell(AppTimeReference.localTimeToSlot(LocalTime.of(14, 0)), 0);
        Label why = assertInstanceOf(Label.class, onFxGet(() -> caption(slot)));
        assertTrue(why.getText().startsWith("blocked"), "got: " + why.getText());
    }

    @Test
    void aSpecificDateBlockOnlyAppliesToThatDay() throws Exception {
        system.getBlockedTime().blockedPeriod.add(
                BlockedTime.once("offsite", TODAY.plusDays(2), LocalTime.of(12, 0), LocalTime.of(13, 0)));

        build(0);

        assertFalse(onFxGet(
                () -> findCell(AppTimeReference.localTimeToSlot(LocalTime.NOON), 2).getChildren().isEmpty()),
                "wednesday is blocked");
        assertTrue(onFxGet(
                () -> findCell(AppTimeReference.localTimeToSlot(LocalTime.NOON), 0).getChildren().isEmpty()),
                "monday is not");
    }

    @Test
    void aBlockedCellIsNotHoverable() throws Exception {
        system.getBlockedTime().blockedPeriod.add(
                BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0)));

        build(0);

        Pane noon = cell(AppTimeReference.localTimeToSlot(LocalTime.NOON), 0);
        assertFalse(WeekGridBuilder.cellState(noon).hoverable);
    }

    @Test
    void aFreeFutureCellIsHoverable() throws Exception {
        build(0);

        Pane future = cell(AppTimeReference.localTimeToSlot(LocalTime.of(15, 0)), 0);
        WeekGridBuilder.CellState st = WeekGridBuilder.cellState(future);
        assertTrue(st.hoverable);
        assertNull(st.task);
        assertNull(st.block);
        assertNotNull(st.restBg, "the resting paint is remembered for hover restore");
    }

    @Test
    void aPastCellIsNotHoverableAndHasNoCaption() throws Exception {
        build(0);

        Pane past = cell(AppTimeReference.localTimeToSlot(LocalTime.of(9, 0)), 0);
        WeekGridBuilder.CellState st = WeekGridBuilder.cellState(past);
        assertFalse(st.hoverable);
        assertTrue(onFxGet(() -> past.getChildren().isEmpty()));
    }

    @Test
    void aTaskHidesTheUnderlyingBlockCaption() throws Exception {
        system.getBlockedTime().blockedPeriod.add(
                BlockedTime.daily("lunch", LocalTime.of(14, 0), LocalTime.of(15, 0)));
        system.getPlacedTasks().add(placed("call", TODAY, LocalTime.of(14, 0), 1));

        build(0);

        Pane slot = cell(AppTimeReference.localTimeToSlot(LocalTime.of(14, 0)), 0);
        List<String> texts = onFxGet(() -> slot.getChildren().stream()
                .filter(n -> n instanceof Label)
                .map(n -> ((Label) n).getText()).toList());
        assertTrue(texts.stream().noneMatch(s -> s.contains("lunch")),
                "the task caption replaces the block label");
    }

    @Test
    void aNullEntryInTheBlockedListBreaksTheBuild() throws Exception {
        // KNOWN DEFECT: loadBlocked() hands the live list straight to blockedAt(), whose loop
        // dereferences every entry. quickAt/taskAt/paintActualBars all guard with `t == null`;
        // blockedAt does not, so one null element aborts the whole build.
        system.getBlockedTime().blockedPeriod.add(null);

        assertThrows(NullPointerException.class, () -> build(0),
                "a null block element is not tolerated");
    }

    @Test
    void aBlockWithNoStartOrEndIsSkipped() throws Exception {
        system.getBlockedTime().blockedPeriod.add(
                new BlockedTime("id", "half", null, null, null, LocalTime.of(13, 0), null, null));

        build(0);

        assertEquals(AppTimeReference.SLOTS_PER_DAY * 7L, cellCount(),
                "blocks with a null start or end are skipped");
    }

    // ==================================================================
    // scrolling
    // ==================================================================

    @Test
    void scrollingSuppressesHoverPaint() throws Exception {
        build(0);
        FxTestKit.onFx(() -> builder.setScrolling(true));

        Pane future = cell(AppTimeReference.localTimeToSlot(LocalTime.of(15, 0)), 1);
        javafx.scene.input.MouseEvent entered = new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_ENTERED_TARGET,
                0, 0, 0, 0, javafx.scene.input.MouseButton.NONE, 1,
                false, false, false, false, false, false, false,
                false, false, false, null);
        FxTestKit.onFx(() -> future.fireEvent(entered));

        javafx.scene.layout.Background rest = WeekGridBuilder.cellState(future).restBg;
        assertSame(rest, future.getBackground(), "no hover while scrolling");
    }

    @Test
    void hoveringAFreeCellPaintsTheHoverFill() throws Exception {
        build(0);

        Pane future = cell(AppTimeReference.localTimeToSlot(LocalTime.of(15, 0)), 1);
        javafx.scene.layout.Background rest = future.getBackground();
        javafx.scene.input.MouseEvent entered = new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_ENTERED_TARGET,
                0, 0, 0, 0, javafx.scene.input.MouseButton.NONE, 1,
                false, false, false, false, false, false, false,
                false, false, false, null);
        FxTestKit.onFx(() -> future.fireEvent(entered));

        assertFalse(rest.equals(future.getBackground()), "the hover fill differs from rest");
    }
}
