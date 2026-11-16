/**
 * The pure helpers behind the week grid: sub-slot fractions, the live/ended test, the cell-state
 * lookup, the drop-hover rim, and the day-style painter.
 *
 * <p>These are the parts of {@code WeekGridBuilder} that do not need the 336-cell skeleton.
 * The clock is pinned and the process-wide {@link TaskManagementSystem} is reset around each test
 * so {@code styleSlot} sees a predictable grid.
 */
package org.ashoreprospect.GUI;

import javafx.scene.layout.Pane;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.BlockedTimePeriod;
import org.ashoreprospect.Day;
import org.ashoreprospect.FxTestKit;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("task-system")
class WeekGridBuilderStaticsTest {

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
        system = TaskManagementSystem.getInstance();
        clearSystem();
        FxTestKit.onFx(() -> {
            AppTheme.clearListeners();
            AppTheme.setPack(org.ashoreprospect.GUI.theme.BuiltinThemes.LIGHT.id());
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
    }

    private static Task placed(String name, LocalDate date, LocalTime time, double hours) {
        Task t = new Task(name, hours, 0, null, null, null, null);
        t.setTaskName(name);
        t.setPlacedDate(date);
        t.setPlacedTime(time);
        return t;
    }

    // ==================================================================
    // fractionWithinSlot
    // ==================================================================

    @Test
    void aSlotBoundaryStartsAtZero() {
        // The grid starts at 06:00, so every whole half-hour lands exactly on 0.
        assertEquals(0.0, WeekGridBuilder.fractionWithinSlot(LocalTime.of(6, 0)), 1e-9);
        assertEquals(0.0, WeekGridBuilder.fractionWithinSlot(LocalTime.of(14, 0)), 1e-9);
    }

    @Test
    void theMiddleOfASlotIsAHalf() {
        assertEquals(0.5, WeekGridBuilder.fractionWithinSlot(LocalTime.of(14, 15)), 1e-9);
    }

    @Test
    void theLastMinuteOfASlotApproachesOne() {
        assertEquals(29 / 30.0, WeekGridBuilder.fractionWithinSlot(LocalTime.of(14, 29)), 1e-9);
    }

    @Test
    void timesBeforeTheGridStartWrapToTheEndOfTheDay() {
        // 05:59 is the last minute of the final slot (24 h after 05:59 the grid has wrapped).
        double f = WeekGridBuilder.fractionWithinSlot(LocalTime.of(5, 59));
        assertEquals(29 / 30.0, f, 1e-9, "05:59 behaves like the tail of the last slot");
    }

    @Test
    void midnightIsAlsoWTailOfTheDay() {
        assertEquals(0.0, WeekGridBuilder.fractionWithinSlot(LocalTime.MIDNIGHT), 1e-9,
                "midnight starts its own half-hour slot after the wrap");
    }

    @Test
    void theSecondsVariantIsSmoothWithinTheSlot() {
        // 14:00:30 is half a minute of the 30-minute slot.
        assertEquals(30 / (30.0 * 60),
                WeekGridBuilder.fractionWithinSlot(LocalTime.of(14, 0, 30), true), 1e-9);
    }

    @Test
    void theSecondsVariantAtASlotBoundaryIsZero() {
        assertEquals(0.0,
                WeekGridBuilder.fractionWithinSlot(LocalTime.of(14, 0, 0), true), 1e-9);
    }

    @Test
    void theSecondsVariantIsFinerThanTheMinuteVariant() {
        double coarse = WeekGridBuilder.fractionWithinSlot(LocalTime.of(14, 10, 45), false);
        double fine = WeekGridBuilder.fractionWithinSlot(LocalTime.of(14, 10, 45), true);

        assertEquals(10 / 30.0, coarse, 1e-9, "minutes only");
        assertTrue(fine > coarse, "the seconds push it further into the slot");
    }

    // ==================================================================
    // cellState
    // ==================================================================

    @Test
    void aNullCellHasNoState() {
        assertNull(WeekGridBuilder.cellState(null));
    }

    @Test
    void aCellThatWasNeverTaggedHasNoState() throws Exception {
        FxTestKit.onFx(() -> {
            assertNull(WeekGridBuilder.cellState(new Pane()));
        });
    }

    @Test
    void aTaggedCellReturnsItsState() throws Exception {
        FxTestKit.onFx(() -> {
            Pane cell = new Pane();
            WeekGridBuilder.CellState st = new WeekGridBuilder.CellState();
            st.slotIndex = 7;
            cell.getProperties().put(WeekGridBuilder.CELL_KEY, st);

            WeekGridBuilder.CellState found = WeekGridBuilder.cellState(cell);
            assertNotNull(found, "the tagged cell reports its state");
            assertEquals(7, found.slotIndex);
        });
    }

    @Test
    void aPropertyOfTheWrongTypeYieldsNoState() throws Exception {
        FxTestKit.onFx(() -> {
            Pane cell = new Pane();
            cell.getProperties().put(WeekGridBuilder.CELL_KEY, "not-a-state");
            assertNull(WeekGridBuilder.cellState(cell));
        });
    }

    // ==================================================================
    // taskHasEnded
    // ==================================================================

    @Test
    void aNullTaskHasNotEnded() {
        assertFalse(WeekGridBuilder.taskHasEnded(null));
    }

    @Test
    void anUnplacedTaskHasNotEnded() {
        Task t = new Task("loose", 1, 0, null, null, null, null);
        assertFalse(WeekGridBuilder.taskHasEnded(t), "no placed date or time");
    }

    @Test
    void aTaskStillRunningHasNotEnded() {
        // 11:00 + 2h = 13:00, and the clock is noon.
        Task t = placed("live", TODAY, LocalTime.of(11, 0), 2);
        assertFalse(WeekGridBuilder.taskHasEnded(t));
    }

    @Test
    void aTaskWhoseEndHasPassedIsEnded() {
        // 09:00 + 1h = 10:00, before the noon clock.
        Task t = placed("done", TODAY, LocalTime.of(9, 0), 1);
        assertTrue(WeekGridBuilder.taskHasEnded(t));
    }

    @Test
    void aTaskEndingExactlyNowCountsAsEnded() {
        // 11:00 + 1h = 12:00, equal to the clock: not after it, so ended.
        Task t = placed("just", TODAY, LocalTime.of(11, 0), 1);
        assertTrue(WeekGridBuilder.taskHasEnded(t), "the end must be strictly after now");
    }

    @Test
    void aFinishedTaskIsEndedEvenWhenItsSlotIsInTheFuture() {
        Task t = placed("finished", TODAY, LocalTime.of(15, 0), 1);
        t.setWorkflow(Workflow.FINISHED);
        assertTrue(WeekGridBuilder.taskHasEnded(t));
    }

    @Test
    void aTaskMarkedPastIsEnded() {
        Task t = placed("past", TODAY, LocalTime.of(15, 0), 1);
        t.setLifeCycle(TaskLifeCycle.PAST);
        assertTrue(WeekGridBuilder.taskHasEnded(t));
    }

    @Test
    void aZeroDurationTaskEndsImmediately() {
        Task t = placed("instant", TODAY, LocalTime.of(9, 0), 0);
        assertTrue(WeekGridBuilder.taskHasEnded(t),
                "a task with no length is over as soon as it starts, if that start is past");
    }

    @Test
    void tomorrowNeverHasEnded() {
        Task t = placed("future", TODAY.plusDays(1), LocalTime.of(9, 0), 1);
        assertFalse(WeekGridBuilder.taskHasEnded(t));
    }

    // ==================================================================
    // styleSlot
    // ==================================================================

    @Test
    void styleSlotIgnoresANullCell() {
        WeekGridBuilder.styleSlot(null, system, TODAY, 0);
    }

    @Test
    void styleSlotIgnoresANullSystem() throws Exception {
        FxTestKit.onFx(() -> {
            Pane cell = new Pane();
            WeekGridBuilder.styleSlot(cell, null, TODAY, 0);
            assertNull(cell.getBackground(), "nothing painted without a system");
        });
    }

    @Test
    void styleSlotIgnoresANullDate() throws Exception {
        FxTestKit.onFx(() -> {
            Pane cell = new Pane();
            WeekGridBuilder.styleSlot(cell, system, null, 0);
            assertNull(cell.getBackground(), "nothing painted without a date");
        });
    }

    @Test
    void styleSlotPaintsAnEmptyFutureCell() throws Exception {
        FxTestKit.onFx(() -> {
            Pane cell = new Pane();
            WeekGridBuilder.styleSlot(cell, system, TODAY.plusDays(1), 20);

            assertTrue(cell.getStyle() != null || cell.getBackground() != null,
                    "an empty future slot gets a paint");
        });
    }

    @Test
    void styleSlotPaintsAPastCell() throws Exception {
        FxTestKit.onFx(() -> {
            Pane cell = new Pane();
            WeekGridBuilder.styleSlot(cell, system, TODAY.minusDays(1), 20);

            assertTrue(cell.getStyle() != null || cell.getBackground() != null,
                    "a past slot is shaded too");
        });
    }

    @Test
    void styleSlotReflectsATaskSittingInTheSlot() throws Exception {
        FxTestKit.onFx(() -> {
            Task t = placed("in slot", TODAY.plusDays(1), LocalTime.of(14, 0), 1);
            int slot = AppTimeReference.localTimeToSlot(LocalTime.of(14, 0));
            Day day = new Day(new BlockedTimePeriod());
            day.placeTask(slot, t);
            system.getGrid().put(TODAY.plusDays(1), day);

            Pane cell = new Pane();
            WeekGridBuilder.styleSlot(cell, system, TODAY.plusDays(1), slot);

            assertTrue(cell.getStyle() != null || cell.getBackground() != null
                            || cell.getBorder() != null,
                    "an occupied slot is visually distinct");
        });
    }

    @Test
    void styleSlotToleratesAnOutOfRangeSlotIndex() throws Exception {
        FxTestKit.onFx(() -> {
            Pane cell = new Pane();
            WeekGridBuilder.styleSlot(cell, system, TODAY.plusDays(1), -1);
            assertTrue(cell.getStyle() != null || cell.getBackground() != null,
                    "a negative index is simply out of range");
        });
    }

    // ==================================================================
    // paintDropHover
    // ==================================================================

    @Test
    void paintDropHoverIgnoresANullCellOrState() throws Exception {
        FxTestKit.onFx(() -> {
            WeekGridBuilder.CellState st = new WeekGridBuilder.CellState();
            st.columnDate = TODAY.plusDays(1);
            WeekGridBuilder.paintDropHover(null, st, true);
            WeekGridBuilder.paintDropHover(new Pane(), null, true);
        });
    }

    @Test
    void paintDropHoverIgnoresAStateWithNoDate() throws Exception {
        FxTestKit.onFx(() -> {
            Pane cell = new Pane();
            WeekGridBuilder.paintDropHover(cell, new WeekGridBuilder.CellState(), true);
            assertNull(cell.getBackground(), "no date means nothing to paint");
        });
    }

    @Test
    void paintDropHoverFillsAnEmptyFutureCell() throws Exception {
        FxTestKit.onFx(() -> {
            Pane cell = new Pane();
            WeekGridBuilder.CellState st = cellStateFor(TODAY.plusDays(1), 20);

            WeekGridBuilder.paintDropHover(cell, st, true);

            assertNotNull(cell.getBackground(), "an empty future cell takes the drop fill");
        });
    }

    @Test
    void paintDropHoverOnlyRimsACellThatHoldsATask() throws Exception {
        FxTestKit.onFx(() -> {
            Pane cell = new Pane();
            WeekGridBuilder.CellState st = cellStateFor(TODAY.plusDays(1), 20);
            st.task = placed("holding", TODAY.plusDays(1), LocalTime.of(14, 0), 1);

            WeekGridBuilder.paintDropHover(cell, st, true);

            assertNull(cell.getBackground(), "a cell with a task keeps its paint, only the rim changes");
            assertNotNull(cell.getBorder(), "but it does get a rim");
        });
    }

    @Test
    void paintDropHoverOnlyRimsAPastCell() throws Exception {
        FxTestKit.onFx(() -> {
            Pane cell = new Pane();
            WeekGridBuilder.CellState st = cellStateFor(TODAY.minusDays(1), 20);

            WeekGridBuilder.paintDropHover(cell, st, true);

            assertNull(cell.getBackground(), "past cells are not painted by a hover");
            assertNotNull(cell.getBorder());
        });
    }

    @Test
    void paintDropHoverRejectsWithTheOtherEdge() throws Exception {
        FxTestKit.onFx(() -> {
            Pane okCell = new Pane();
            Pane noCell = new Pane();
            WeekGridBuilder.paintDropHover(okCell, cellStateFor(TODAY.plusDays(1), 20), true);
            WeekGridBuilder.paintDropHover(noCell, cellStateFor(TODAY.plusDays(1), 20), false);

            assertNotNull(okCell.getBackground());
            assertNotNull(noCell.getBackground());
            assertFalse(okCell.getBackground().equals(noCell.getBackground()),
                    "the accept and reject fills differ");
        });
    }

    @Test
    void paintDropHoverTreatsAQuickTaskAsEmpty() throws Exception {
        FxTestKit.onFx(() -> {
            Task parent = placed("parent", TODAY.plusDays(1), LocalTime.of(14, 0), 1);
            Task quick = placed("quick", TODAY.plusDays(1), LocalTime.of(14, 0), 0.5);
            quick.setParentId(parent.getID());

            Pane cell = new Pane();
            WeekGridBuilder.CellState st = cellStateFor(TODAY.plusDays(1), 20);
            st.task = quick;

            WeekGridBuilder.paintDropHover(cell, st, true);

            assertNotNull(cell.getBackground(),
                    "a quick task does not block the fill, so the cell is painted");
        });
    }

    private static WeekGridBuilder.CellState cellStateFor(LocalDate date, int slot) {
        WeekGridBuilder.CellState st = new WeekGridBuilder.CellState();
        st.columnDate = date;
        st.slotIndex = slot;
        return st;
    }
}
