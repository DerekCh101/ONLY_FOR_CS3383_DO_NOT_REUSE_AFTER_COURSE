package org.ashoreprospect.knownDefects;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
import org.ashoreprospect.TaskManagementSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the deadline-comparator NullPointerException.
 *
 * <p>See {@code KNOWN_DEFECTS.md} in this package. When the sort is made null-safe, move the
 * assertions into {@code SchedulerEngineTest} (asserting a successful re-place instead) and
 * delete this file.
 */
@ResourceLock("app-clock")
class SchedulerEngineDeadlineOrderTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    private Clock originalClock;
    private TaskManagementSystem system;

    @BeforeEach
    void setUp() {
        originalClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(Clock.fixed(
                Instant.parse("2026-10-12T07:00:00Z"), ZoneId.of("UTC")));
        TaskManagementSystem.resetInstance();
        system = TaskManagementSystem.getInstance();
        system.getPendingTasks().clear();
        system.getPlacedTasks().clear();
        system.getUnscheduledTasks().clear();
        system.getGrid().clear();
        system.getBlockedTime().replaceBlockedPeriods(new java.util.ArrayList<>());
    }

    @AfterEach
    void tearDown() {
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(originalClock);
    }

    @Test
    void schedulerEngine_deadlineComparatorNpe() {
        // DEFECT: the deadline sort (SchedulerEngine, deadlineTasks.sort) dereferences
        // t1.getDeadLineDate() with no null check, while placeDeadlineTasks() a few lines
        // later explicitly handles `getDeadLineDate() == null`. Any state-2 task with a null
        // deadline date therefore crashes updateSchedule() with a NullPointerException.
        //
        // This is reachable through the public API: a deadline task with a real date (so the
        // comparator has a non-null side to compare against) is enough to trigger it.
        Task planted = deadlineTask("no-date", 0.5);
        planted.setState(2);
        planted.setPlacedDate(MON);
        planted.setPlacedTime(LocalTime.of(9, 0));
        system.getPlacedTasks().add(planted);
        system.getGrid().computeIfAbsent(MON, d -> new org.ashoreprospect.Day(system.getBlockedTime()))
                .placeTask(AppTimeReference.localTimeToSlot(LocalTime.of(9, 0)), planted);
        Task untimed = deadlineTask("untimed", 0.5);
        untimed.setState(2);
        untimed.setDeadLineDate(MON.plusDays(1));
        Task timed = deadlineTask("timed", 0.5);
        timed.setDeadLineDate(MON.plusDays(1));
        timed.setDeadLineTime(LocalTime.of(9, 0));
        system.getUnscheduledTasks().add(untimed);
        system.getUnscheduledTasks().add(timed);

        // PINNED: the crash is recorded exactly as it happens. No production change.
        NullPointerException thrown = assertThrows(
                NullPointerException.class, () -> system.updateSchedule());
        // The JVM reports the null receiver of LocalDate.compareTo() as "other is null".
        assertTrue(thrown.getMessage().contains("other") && thrown.getMessage().contains("null"),
                "PINNED: the NPE comes from the deadline comparator, not anywhere else: "
                        + thrown.getMessage());
    }

    @Test
    void loneDateLessDeadline_taskIsSafeButOnlyBecauseOfTheSort() {
        // CONTEXT: placeDeadlineTasks() guards `getDeadLineDate() == null`, but the sort runs
        // first and crashes when there is anything to compare against. A lone date-less task
        // has nothing to compare against, so it survives and takes the guarded path. Recorded
        // here so the register above explains why a single bad row "works" and two do not.
        Task noDate = deadlineTask("no-date-only", 0.5);
        noDate.setState(2);
        system.getUnscheduledTasks().add(noDate);

        new org.ashoreprospect.SchedulerEngine(system, MON, system.getGrid())
                .scheduleTasks(new java.util.ArrayList<>(system.getUnscheduledTasks()));

        assertTrue(system.getUnscheduledTasks().contains(noDate),
                "the lone task takes the guarded path");
        assertFalse(system.getPlacedTasks().contains(noDate));
    }

    @Test
    void nullEntryInTheScheduleList_crashesBeforeTheNullGuard() {
        // DEFECT: scheduleTasks() dereferences every list entry (task.hasDeadline()) before
        // its own `task == null` guard can run, so a null element throws instead of being
        // skipped. placeAlreadyPlacedTasks() has the same shape at task.getPlacedDate().
        java.util.ArrayList<Task> supplied = new java.util.ArrayList<>();
        supplied.add(null);
        supplied.add(deadlineTask("real", 0.5));

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new org.ashoreprospect.SchedulerEngine(system, MON, system.getGrid())
                        .scheduleTasks(supplied));
        assertTrue(thrown.getMessage().contains("hasDeadline"),
                "PINNED: the crash is at the hasDeadline() dereference: " + thrown.getMessage());

        java.util.ArrayList<Task> placed = new java.util.ArrayList<>();
        placed.add(null);
        NullPointerException second = assertThrows(NullPointerException.class,
                () -> new org.ashoreprospect.SchedulerEngine(system, MON, system.getGrid())
                        .placeAlreadyPlacedTasks(placed));
        assertTrue(second.getMessage().contains("getPlacedDate"),
                "PINNED: the crash is at the getPlacedDate() dereference: "
                        + second.getMessage());
    }

    @Test
    void scheduleTasks_givesAQuickTaskAClockItCanNeverOccupy() {
        // DEFECT: applyPlacement() writes placedDate/placedTime even when Day.placeTask()
        // silently refused the task because it is quick. The nested event ends up with a
        // placed clock on a slot it does not own, while a real task takes the same slot.
        Task quick = deadlineTask("quick", 0.5);
        quick.setParentId("some-parent");
        Task real = deadlineTask("real", 0.5);

        java.util.ArrayList<Task> supplied = new java.util.ArrayList<>();
        supplied.add(quick);
        supplied.add(real);

        new org.ashoreprospect.SchedulerEngine(system, MON, system.getGrid())
                .scheduleTasks(supplied);

        assertTrue(quick.isQuick());
        assertEquals(LocalTime.of(7, 30), quick.getPlacedTime(),
                "PINNED: the clock was written despite the refused grid placement");
        assertEquals(LocalTime.of(7, 30), real.getPlacedTime(),
                "the quick task did not actually consume the slot");
        org.ashoreprospect.Day day = system.getGrid().get(MON);
        assertTrue(java.util.Arrays.stream(day.returnDayGrid()).noneMatch(t -> t == quick),
                "quick tasks never occupy grid slots");
    }

    @Test
    void pinOnGrid_boundaryGuardIsUnreachableBecauseSlotsAreClamped() {
        // DEAD CODE: `slot + count > SLOTS_PER_DAY` inside pinOnGrid can never be true.
        // count comes from getDurationSlots(), which clamps to one day and rounds up to at
        // most 48 slots, and slot is one of 0..47. Documented rather than "covered" with an
        // artificial input.
        Task full = deadlineTask("full", 24.0);
        full.setPlacedDate(MON);
        full.setPlacedTime(LocalTime.of(6, 0));
        assertEquals(AppTimeReference.SLOTS_PER_DAY, full.getDurationSlots());
        assertEquals(0, AppTimeReference.localTimeToSlot(LocalTime.of(6, 0)));

        Task longer = deadlineTask("longer", 48.0);
        longer.setPlacedDate(MON);
        longer.setPlacedTime(LocalTime.of(6, 0));
        assertEquals(AppTimeReference.SLOTS_PER_DAY, longer.getDurationSlots(),
                "PINNED: a longer-than-a-day task is clamped, never overflowing");
    }

    @Test
    void markUnscheduled_neverReparksAPastTask() {
        // DEFECT (or at least an undocumented asymmetry): markUnscheduled() returns early for
        // a PAST task, so a past task whose slot is occupied by another task is left claiming
        // a slot it does not own instead of being parked at UNSCHEDULED.
        Task past = deadlineTask("past-flex", 0.5);
        past.setPlacedDate(MON);
        past.setPlacedTime(LocalTime.of(7, 30));
        past.setLifeCycle(TaskLifeCycle.PAST);
        org.ashoreprospect.Day day = new org.ashoreprospect.Day(system.getBlockedTime());
        day.placeTask(AppTimeReference.localTimeToSlot(LocalTime.of(7, 30)),
                deadlineTask("squatter", 0.5));
        system.getGrid().put(MON, day);

        new org.ashoreprospect.SchedulerEngine(system, MON, system.getGrid())
                .placeAlreadyPlacedTasks(new java.util.ArrayList<>(java.util.List.of(past)));

        assertFalse(system.getUnscheduledTasks().contains(past),
                "PINNED: a PAST task is never re-parked as unscheduled");
        assertTrue(system.getPlacedTasks().contains(past),
                "PINNED: and it stays registered as placed even though its slot is occupied "
                        + "by another task, so the PAST task and the squatter both claim 07:30");
    }

    private static Task deadlineTask(String name, double hours) {
        return new Task(name, hours, 3, null, null, null, null);
    }
}
