/**
 * Pure helpers on the week grid: slot-fraction math, {@code taskHasEnded}, and the
 * null-tolerant {@code cellState}.
 *
 * <p>Every method here is static and either takes a plain value or *reads* a JavaFX type
 * without constructing one — {@code cellState(null)} short-circuits before touching
 * {@code Pane}, so no toolkit is started.
 */
package org.ashoreprospect.GUI;

import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
import org.ashoreprospect.Workflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grid starts at 06:00 (`AppTimeReference.GRID_START_MINUTES`), so column time is
 * measured from there: 06:00 is 0.0, 23:30 is 0.0 again (top of slot 35), and the
 * 00:00–05:30 tail wraps to 0.0–0.966…
 */
@ResourceLock("app-clock")
class WeekGridBuilderTimeTest {

    private static final double EPS = 1e-9;

    private Clock savedClock;

    @BeforeEach
    void freezeClock() {
        savedClock = Clock.systemDefaultZone();
        // 07:00 read through the JVM's zone, so `taskHasEnded` and the wall clock agree
        // wherever the runner sits (a UTC-pinned instant reads 15:00 in Asia/Hong_Kong).
        AppTimeReference.setClock(Clock.fixed(
                LocalDateTime.of(2026, 10, 12, 7, 0).atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault()));
    }

    @AfterEach
    void restoreClock() {
        AppTimeReference.setClock(savedClock);
    }

    // ------------------------------------------------------------ fractionWithinSlot(t)

    @Test
    void slotStartIsZeroAndSlotMiddleIsHalf() {
        assertEquals(0.0, WeekGridBuilder.fractionWithinSlot(LocalTime.of(6, 0)), EPS);
        assertEquals(0.0, WeekGridBuilder.fractionWithinSlot(LocalTime.of(6, 30)), EPS);
        assertEquals(0.5, WeekGridBuilder.fractionWithinSlot(LocalTime.of(6, 15)), EPS);
        assertEquals(0.5, WeekGridBuilder.fractionWithinSlot(LocalTime.of(9, 45)), EPS);
    }

    @Test
    void minuteWithinSlotIsLinear() {
        assertEquals(1.0 / 30, WeekGridBuilder.fractionWithinSlot(LocalTime.of(6, 1)), EPS);
        assertEquals(29.0 / 30, WeekGridBuilder.fractionWithinSlot(LocalTime.of(6, 29)), EPS);
        assertEquals(10.0 / 30, WeekGridBuilder.fractionWithinSlot(LocalTime.of(23, 40)), EPS);
    }

    @Test
    void tailSlotsAfterMidnightWrapToTheEndOfTheColumn() {
        // 00:00 is slot 36 of the column, i.e. 18 slots in: 18 * 30 = 540 min → 0.0.
        assertEquals(0.0, WeekGridBuilder.fractionWithinSlot(LocalTime.of(0, 0)), EPS);
        // 05:30 is the last slot start → 0.0.
        assertEquals(0.0, WeekGridBuilder.fractionWithinSlot(LocalTime.of(5, 30)), EPS);
        // 05:45 is 15 minutes into the final slot.
        assertEquals(0.5, WeekGridBuilder.fractionWithinSlot(LocalTime.of(5, 45)), EPS);
    }

    @Test
    void timeWithoutSecondsIgnoresTheSecondField() {
        // PINNED: the two-arg form has no LocalTime overload, so seconds are dropped.
        assertEquals(WeekGridBuilder.fractionWithinSlot(LocalTime.of(6, 10)),
                WeekGridBuilder.fractionWithinSlot(LocalTime.of(6, 10, 59)), EPS);
    }

    // ------------------------------------------------------------ fractionWithinSlot(t, true)

    @Test
    void withSecondsIsContinuousAcrossTheSlot() {
        assertEquals(0.0, WeekGridBuilder.fractionWithinSlot(LocalTime.of(6, 0), true), EPS);
        assertEquals(30.0 / (30 * 60),
                WeekGridBuilder.fractionWithinSlot(LocalTime.of(6, 0, 30), true), EPS);
        // 06:29:59 is one second before the next slot start, not 59/1800.
        assertEquals(1799.0 / (30 * 60),
                WeekGridBuilder.fractionWithinSlot(LocalTime.of(6, 29, 59), true), EPS);
        assertEquals(0.0, WeekGridBuilder.fractionWithinSlot(LocalTime.of(6, 30), true), EPS);
    }

    @Test
    void withSecondsWrapsAfterMidnightToo() {
        assertEquals(0.0, WeekGridBuilder.fractionWithinSlot(LocalTime.of(0, 0), true), EPS);
        assertEquals(30.0 / (30 * 60),
                WeekGridBuilder.fractionWithinSlot(LocalTime.of(0, 0, 30), true), EPS);
        assertTrue(WeekGridBuilder.fractionWithinSlot(LocalTime.of(5, 59, 59), true) < 1.0);
    }

    // ------------------------------------------------------------ cellState

    @Test
    void cellStateOfNullIsNull() {
        assertNull(WeekGridBuilder.cellState(null));
    }

    // ------------------------------------------------------------ taskHasEnded

    private static Task placed(double durationMinutes, LocalDate date, LocalTime time) {
        Task t = new Task("t", durationMinutes / 60.0, 1, date, time, null, null);
        t.setPlacedDate(date);
        t.setPlacedTime(time);
        return t;
    }

    /** Clamps the stored duration to the one-minute floor (`clampMinutes`). */
    private static Task atFloor(LocalDate date, LocalTime time) {
        Task t = placed(1, date, time);
        t.setDurationMinutes(0);
        return t;
    }

    @Test
    void taskWithoutAPlacementHasNotEnded() {
        assertFalse(WeekGridBuilder.taskHasEnded(null));
        assertFalse(WeekGridBuilder.taskHasEnded(new Task("t", 1, 1, null, null, null, null)));

        Task onlyDate = new Task("t", 1, 1, null, null, null, null);
        onlyDate.setPlacedDate(LocalDate.of(2026, 10, 12));
        assertFalse(WeekGridBuilder.taskHasEnded(onlyDate));

        Task onlyTime = new Task("t", 1, 1, null, null, null, null);
        onlyTime.setPlacedTime(LocalTime.of(9, 0));
        assertFalse(WeekGridBuilder.taskHasEnded(onlyTime));
    }

    @Test
    void finishedOrPastAlwaysCountsAsEnded() {
        // Clock is 2026-10-12 07:00 UTC and the task starts an hour later, so neither of
        // these would be ended on time alone.
        Task finished = placed(60, LocalDate.of(2026, 10, 12), LocalTime.of(8, 0));
        finished.setWorkflow(Workflow.FINISHED);
        assertTrue(WeekGridBuilder.taskHasEnded(finished));

        Task past = placed(60, LocalDate.of(2026, 10, 12), LocalTime.of(8, 0));
        past.setLifeCycle(TaskLifeCycle.PAST);
        assertTrue(WeekGridBuilder.taskHasEnded(past));
    }

    @Test
    void aLiveBlockHasNotEndedEvenAfterItsStart() {
        // Starts 30 minutes ago and runs an hour, so it is still live.
        Task live = placed(60, LocalDate.of(2026, 10, 12), LocalTime.of(6, 30));
        assertFalse(WeekGridBuilder.taskHasEnded(live));
    }

    @Test
    void endIsStartPlusDurationAndExactlyAtEndCountsAsEnded() {
        // Ends at exactly 07:00, the frozen "now": the check is `!end.isAfter(now)`.
        Task endingNow = placed(60, LocalDate.of(2026, 10, 12), LocalTime.of(6, 0));
        assertTrue(WeekGridBuilder.taskHasEnded(endingNow));

        // One minute longer is still live.
        Task stillOn = placed(61, LocalDate.of(2026, 10, 12), LocalTime.of(6, 0));
        assertFalse(WeekGridBuilder.taskHasEnded(stillOn));
    }

    @Test
    void aZeroDurationStillGetsTheOneMinuteFloor() {
        // PINNED: `hoursToMinutes` and `clampMinutes` both floor at 1, so a Task can never
        // hold a genuinely zero duration; `taskHasEnded` floors again with `Math.max(1, …)`.
        LocalDate day = LocalDate.of(2026, 10, 12);
        assertEquals(1, atFloor(day, LocalTime.of(9, 0)).getDurationMinutes());

        // PINNED: the end keeps the start's seconds, so 06:59:30 + 1 min = 07:00:30 is
        // still after "now" (07:00) and the task counts as live.
        assertFalse(WeekGridBuilder.taskHasEnded(atFloor(day, LocalTime.of(6, 59, 30))));
        // At whole-minute precision the same rule flips: 06:58 + 1 min = 06:59 is already
        // before "now", so the task counts as ended.
        assertTrue(WeekGridBuilder.taskHasEnded(atFloor(day, LocalTime.of(6, 58))));
        // And 06:59 + 1 min = 07:00 is exactly "now", which also counts as ended.
        assertTrue(WeekGridBuilder.taskHasEnded(atFloor(day, LocalTime.of(6, 59))));
        // A task starting at "now" is still on for its first minute.
        assertFalse(WeekGridBuilder.taskHasEnded(atFloor(day, LocalTime.of(7, 0))));
    }

    @Test
    void yesterdayEndedAndTomorrowHasNot() {
        assertTrue(WeekGridBuilder.taskHasEnded(
                placed(60, LocalDate.of(2026, 10, 11), LocalTime.of(20, 0))));
        assertFalse(WeekGridBuilder.taskHasEnded(
                placed(60, LocalDate.of(2026, 10, 13), LocalTime.of(9, 0))));
    }
}
