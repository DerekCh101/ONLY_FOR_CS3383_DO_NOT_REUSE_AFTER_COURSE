/**
 * {@link TaskDragDropSupport#canDropOn}: the pure legality check behind every drag.
 *
 * <p>Nothing here builds a scene or fires drag events. The method reads the task, the grid, and
 * the clock, so the clock is pinned and the process-wide {@link TaskManagementSystem} is reset
 * around each test.
 */
package org.ashoreprospect.GUI;

import javafx.scene.layout.StackPane;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.BlockedTimePeriod;
import org.ashoreprospect.Day;
import org.ashoreprospect.FxTestKit;
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
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("task-system")
class TaskDragDropSupportTest {

    /** Monday 2026-10-12, 12:00 — the boundary between "past" and "future" slots. */
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    private Clock savedClock;
    private TaskManagementSystem system;
    private TaskDragDropSupport support;

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
        FxTestKit.onFx(() -> support = new TaskDragDropSupport(system, new StackPane(), null));
    }

    @AfterEach
    void tearDown() {
        if (system != null) clearSystem();
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(savedClock);
    }

    private void clearSystem() {
        system.getPendingTasks().clear();
        system.getPlacedTasks().clear();
        system.getUnscheduledTasks().clear();
        system.getGrid().clear();
    }

    private static Task task(String name, double hours, int state) {
        Task t = new Task(name, hours, state, null, null, null, null);
        t.setTaskName(name);
        return t;
    }

    /** 14:00 today is comfortably in the future relative to the pinned noon clock. */
    private static final int FUTURE_SLOT = AppTimeReference.localTimeToSlot(LocalTime.of(14, 0));

    // ==================================================================
    // guard clauses
    // ==================================================================

    @Test
    void aNullTaskIsNeverDroppable() {
        assertFalse(support.canDropOn(TODAY, FUTURE_SLOT, null));
    }

    @Test
    void aTaskWithNoDurationHasNoLegalDrop() {
        Task zero = task("zero", 0, 0);
        assertTrue(zero.getDurationSlots() <= 1, "sub-slot tasks round up to one slot");

        // A task cannot occupy zero slots: minutesToSlots clamps to at least one, so
        // canDropOn's slotCount <= 0 guard is unreachable through the public API.
        Task negative = new Task("neg", -1, 0, null, null, null, null);
        assertTrue(negative.getDurationSlots() >= 1,
                "negative durations are clamped, never zero");
    }

    @Test
    void aSlotThatHasAlreadyPassedIsRejected() {
        Task t = task("one hour", 1, 0);
        int pastSlot = AppTimeReference.localTimeToSlot(LocalTime.of(10, 0));

        assertFalse(support.canDropOn(TODAY, pastSlot, t), "10:00 is behind the noon clock");
    }

    @Test
    void aSlotStartingExactlyNowIsRejected() {
        Task t = task("one hour", 1, 0);
        // dateTimeForSlot must be strictly after "now", so noon itself fails.
        assertFalse(support.canDropOn(TODAY, AppTimeReference.localTimeToSlot(LocalTime.NOON), t));
    }

    @Test
    void aDropThatWouldRunPastTheEndOfTheDayIsRejected() {
        Task longTask = task("four hours", 4, 0);
        int lateSlot = AppTimeReference.SLOTS_PER_DAY - 2; // only two slots left

        assertFalse(support.canDropOn(TODAY, lateSlot, longTask));
    }

    @Test
    void aDropThatExactlyFillsTheLastSlotIsAllowed() {
        Task task = task("one hour", 1, 0);
        Task twoSlots = task("one hour", 1, 0);
        int lastPair = AppTimeReference.SLOTS_PER_DAY - 2;

        assertTrue(support.canDropOn(TODAY, lastPair, twoSlots),
                "two slots ending exactly at midnight still fit");
    }

    // ==================================================================
    // happy path
    // ==================================================================

    @Test
    void aPlainFutureSlotIsDroppable() {
        Task t = task("free", 1, 0);
        assertTrue(support.canDropOn(TODAY, FUTURE_SLOT, t));
    }

    @Test
    void aLaterDayIsAlwaysInTheFuture() {
        Task t = task("later", 2, 0);
        assertTrue(support.canDropOn(TODAY.plusDays(3), FUTURE_SLOT, t));
    }

    @Test
    void checkingADayThatWasNeverBuiltCreatesItsGridEntry() {
        LocalDate fresh = TODAY.plusDays(5);
        assertFalse(system.getGrid().containsKey(fresh), "the day starts absent");

        support.canDropOn(fresh, FUTURE_SLOT, task("x", 1, 0));

        assertTrue(system.getGrid().containsKey(fresh),
                "canDropOn seeds the grid so later lookups do not miss");
        assertNotNull(system.getGrid().get(fresh));
    }

    // ==================================================================
    // collisions
    // ==================================================================

    @Test
    void droppingOntoYourOwnSlotsIsAllowed() {
        Task t = task("move me", 1, 0);
        Day day = new Day(new BlockedTimePeriod());
        day.placeTask(FUTURE_SLOT, t);
        system.getGrid().put(TODAY, day);

        assertTrue(support.canDropOn(TODAY, FUTURE_SLOT, t),
                "a task may be re-dropped where it already sits");
    }

    @Test
    void droppingOntoASlotHeldByAnotherTaskIsRejected() {
        Day day = new Day(new BlockedTimePeriod());
        day.placeTask(FUTURE_SLOT, task("occupant", 1, 0));
        system.getGrid().put(TODAY, day);

        assertFalse(support.canDropOn(TODAY, FUTURE_SLOT, task("newcomer", 1, 0)));
    }

    @Test
    void anOverlapOnTheSecondSlotOfASpanIsRejected() {
        Day day = new Day(new BlockedTimePeriod());
        day.placeTask(FUTURE_SLOT + 1, task("occupant", 1, 0));
        system.getGrid().put(TODAY, day);

        Task twoHours = task("two hours", 2, 0);
        assertFalse(support.canDropOn(TODAY, FUTURE_SLOT, twoHours),
                "the second slot of the span is taken");
    }

    @Test
    void aSlotImmediatelyBeforeAnOccupiedOneIsFine() {
        Day day = new Day(new BlockedTimePeriod());
        // An unplaced 1h task still occupies two half-hour slots (16:00–17:00), so park the
        // occupant two slots beyond the end of the two-hour span we are testing.
        day.placeTask(FUTURE_SLOT + 4, task("occupant", 1, 0));
        system.getGrid().put(TODAY, day);

        Task twoHours = task("two hours", 2, 0);
        assertTrue(support.canDropOn(TODAY, FUTURE_SLOT, twoHours),
                "slots 0..3 end right where the occupant begins");
    }

    @Test
    void aSlotImmediatelyAfterAnOccupiedOneIsFine() {
        Day day = new Day(new BlockedTimePeriod());
        day.placeTask(FUTURE_SLOT, task("occupant", 1, 0));
        system.getGrid().put(TODAY, day);

        assertTrue(support.canDropOn(TODAY, FUTURE_SLOT + 2, task("after", 1, 0)));
    }

    // ==================================================================
    // deadlines
    // ==================================================================

    @Test
    void aDeadlineTaskWithoutADeadlineDateIsRejected() {
        Task t = new Task("no date", 1, 2, null, null, null, null);
        assertTrue(t.hasDeadline());
        assertFalse(support.canDropOn(TODAY, FUTURE_SLOT, t));
    }

    @Test
    void aDropFinishingBeforeTheDeadlineIsAllowed() {
        Task t = new Task("deadline", 1, 2, null, null,
                LocalDate.of(2026, 10, 12), LocalTime.of(18, 0));
        assertTrue(t.hasDeadline(), "state 2 means a deadline");

        assertTrue(support.canDropOn(TODAY, FUTURE_SLOT, t),
                "14:00 + 1h finishes before the 18:00 deadline");
    }

    @Test
    void aDropFinishingExactlyAtTheDeadlineIsAllowed() {
        Task t = new Task("deadline", 2, 2, null, null,
                LocalDate.of(2026, 10, 12), LocalTime.of(16, 0));

        assertTrue(support.canDropOn(TODAY, FUTURE_SLOT, t),
                "16:00 is not after 16:00");
    }

    @Test
    void aDropOvertakingTheDeadlineIsRejected() {
        Task t = new Task("deadline", 4, 2, null, null,
                LocalDate.of(2026, 10, 12), LocalTime.of(16, 0));

        assertFalse(support.canDropOn(TODAY, FUTURE_SLOT, t),
                "14:00 + 4h passes the 16:00 deadline");
    }

    @Test
    void aDeadlineWithNoTimeMeansEndOfThatDay() {
        Task t = new Task("end of day", 2, 2, null, null,
                LocalDate.of(2026, 10, 12), null);

        assertTrue(support.canDropOn(TODAY, FUTURE_SLOT, t),
                "no deadline time falls back to midnight of the next day");
    }

    @Test
    void droppingPastTheDeadlineDayIsRejected() {
        Task t = new Task("deadline", 2, 2, null, null,
                LocalDate.of(2026, 10, 12), null);

        assertFalse(support.canDropOn(TODAY.plusDays(1), FUTURE_SLOT, t),
                "the deadline day has already passed");
    }
}
