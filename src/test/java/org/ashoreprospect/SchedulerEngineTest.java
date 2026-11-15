package org.ashoreprospect;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("app-clock")
class SchedulerEngineTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final Clock TEST_CLOCK = Clock.fixed(
            Instant.parse("2026-10-12T07:00:00Z"), ZoneId.of("UTC"));

    private Clock originalClock;
    private TaskManagementSystem system;

    @BeforeEach
    void setUp() {
        originalClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(TEST_CLOCK);
        TaskManagementSystem.resetInstance();
        system = TaskManagementSystem.getInstance();
        system.getPendingTasks().clear();
        system.getPlacedTasks().clear();
        system.getUnscheduledTasks().clear();
        system.getGrid().clear();
        system.getBlockedTime().replaceBlockedPeriods(new ArrayList<>());
    }

    @AfterEach
    void tearDown() {
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(originalClock);
    }

    @Test
    void deadlineTasks_areOrderedByEarlierDeadlineTime() {
        Task later = task("later", 0.5, 2, MON, LocalTime.of(9, 0));
        Task earlier = task("earlier", 0.5, 2, MON, LocalTime.of(8, 0));

        system.getUnscheduledTasks().add(later);
        system.getUnscheduledTasks().add(earlier);

        new SchedulerEngine(system, MON, new TreeMap<>())
                .scheduleTasks(new ArrayList<>(system.getUnscheduledTasks()));

        assertEquals(LocalTime.of(7, 30), earlier.getPlacedTime());
        assertEquals(LocalTime.of(8, 0), later.getPlacedTime());
    }

    @Test
    void flexibleTask_skipsAnOccupiedSlot() {
        Task occupied = task("occupied", 0.5, 3, null, null);
        occupied.setPlacedDate(MON);
        occupied.setPlacedTime(LocalTime.of(7, 30));
        Day day = new Day(system.getBlockedTime());
        day.placeTask(AppTimeReference.localTimeToSlot(occupied.getPlacedTime()), occupied);
        system.getGrid().put(MON, day);

        Task flexible = task("flexible", 0.5, 3, null, null);
        system.getUnscheduledTasks().add(flexible);

        new SchedulerEngine(system, MON, system.getGrid())
                .scheduleTasks(new ArrayList<>(system.getUnscheduledTasks()));

        assertEquals(LocalTime.of(8, 0), flexible.getPlacedTime());
        assertSame(occupied, day.returnDayGrid()[3]);
        assertSame(flexible, day.returnDayGrid()[4]);
    }

    @Test
    void repeatedPlacement_doesNotDuplicateTaskInGridOrPlacedList() {
        Task task = task("task", 0.5, 3, null, null);
        task.setPlacedDate(MON);
        task.setPlacedTime(LocalTime.of(7, 30));
        system.getPlacedTasks().add(task);

        Day day = new Day(system.getBlockedTime());
        system.getGrid().put(MON, day);
        SchedulerEngine engine = new SchedulerEngine(system, MON, system.getGrid());

        engine.placeAlreadyPlacedTasks(java.util.List.of(task));
        engine.placeAlreadyPlacedTasks(java.util.List.of(task));

        assertEquals(1, system.getPlacedTasks().stream()
                .filter(existing -> existing == task).count());
        assertEquals(AppTimeReference.SLOTS_PER_DAY - 1, day.getAvailableSlots());
        assertSame(task, day.returnDayGrid()[3]);
        assertEquals(null, day.returnDayGrid()[4]);
    }

    @Test
    void emptySchedule_removesActiveUnscheduledButPreservesPast() {
        Task active = task("active", 0.5, 3, null, null);
        Task past = task("past", 0.5, 3, null, null);
        past.setLifeCycle(TaskLifeCycle.PAST);
        system.getUnscheduledTasks().add(active);
        system.getUnscheduledTasks().add(past);

        new SchedulerEngine(system, MON, new TreeMap<>()).scheduleTasks(null);

        assertFalse(system.getUnscheduledTasks().contains(active));
        assertTrue(system.getUnscheduledTasks().contains(past));
    }

    @Test
    void invalidDeadline_isMarkedUnscheduled() {
        Task missingDate = task("missing date", 0.5, 2, null, LocalTime.of(9, 0));
        SchedulerEngine engine = new SchedulerEngine(system, MON, new TreeMap<>());

        engine.scheduleTasks(java.util.List.of(missingDate));

        assertTrue(system.getUnscheduledTasks().contains(missingDate));
        assertEquals(TaskLifeCycle.UNSCHEDULED, missingDate.getLifeCycle());
        assertNull(missingDate.getPlacedDate());
        assertNull(missingDate.getPlacedTime());
    }

    @Test
    void pastDeadline_isMarkedUnscheduled() {
        Task expired = task("expired", 0.5, 2, MON.minusDays(1), null);

        new SchedulerEngine(system, MON, new TreeMap<>())
                .scheduleTasks(java.util.List.of(expired));

        assertTrue(system.getUnscheduledTasks().contains(expired));
        assertEquals(TaskLifeCycle.UNSCHEDULED, expired.getLifeCycle());
    }

    @Test
    void deadlineTime_rejectsPlacementThatFinishesAfterDeadline() {
        Task tooLate = task("too late", 1.0, 2, MON, LocalTime.of(7, 30));

        new SchedulerEngine(system, MON, new TreeMap<>())
                .scheduleTasks(java.util.List.of(tooLate));

        assertTrue(system.getUnscheduledTasks().contains(tooLate));
        assertNull(tooLate.getPlacedTime());
    }

    @Test
    void placedTasks_handlePastAndQuickTasks() {
        Task past = task("past", 0.5, 3, null, null);
        past.setLifeCycle(TaskLifeCycle.PAST);
        past.setPlacedDate(MON);
        past.setPlacedTime(LocalTime.of(7, 30));
        system.getPendingTasks().add(past);
        system.getUnscheduledTasks().add(past);

        Task quick = task("quick", 0.5, 3, null, null);
        quick.setParentId("parent-id");
        quick.setPlacedDate(MON);
        quick.setPlacedTime(LocalTime.of(8, 0));

        SchedulerEngine engine = new SchedulerEngine(system, MON, system.getGrid());
        engine.placeAlreadyPlacedTasks(java.util.List.of(past, quick));

        assertTrue(system.getPlacedTasks().contains(past));
        assertTrue(system.getPlacedTasks().contains(quick));
        assertFalse(system.getPendingTasks().contains(past));
        assertFalse(system.getUnscheduledTasks().contains(past));
        assertSame(past, system.getGrid().get(MON).returnDayGrid()[3]);
    }

    @Test
    void placedTask_collision_isMovedToUnscheduled() {
        Task existing = task("existing", 0.5, 3, null, null);
        existing.setPlacedDate(MON);
        existing.setPlacedTime(LocalTime.of(7, 30));
        Day day = new Day(system.getBlockedTime());
        day.placeTask(3, existing);
        system.getGrid().put(MON, day);

        Task conflicting = task("conflicting", 0.5, 3, null, null);
        conflicting.setPlacedDate(MON);
        conflicting.setPlacedTime(LocalTime.of(7, 30));
        system.getPlacedTasks().add(conflicting);

        new SchedulerEngine(system, MON, system.getGrid())
                .placeAlreadyPlacedTasks(java.util.List.of(conflicting));

        assertFalse(system.getPlacedTasks().contains(conflicting));
        assertTrue(system.getUnscheduledTasks().contains(conflicting));
        assertNull(conflicting.getPlacedDate());
        assertNull(conflicting.getPlacedTime());
        assertSame(existing, day.returnDayGrid()[3]);
    }

    @Test
    void refitActiveTasks_schedulesPendingFlexibleTask() {
        Task pending = task("pending", 0.5, 3, null, null);
        system.getPendingTasks().add(pending);

        new SchedulerEngine(system, MON, system.getGrid()).refitActiveTasks();

        assertTrue(system.getPlacedTasks().contains(pending));
        assertEquals(LocalTime.of(7, 30), pending.getPlacedTime());
        assertFalse(system.getPendingTasks().contains(pending));
    }

    @Test
    void engineAccessors_andUnscheduledText_reportCurrentState() {
        Task task = task("shown", 0.5, 3, null, null);
        system.getUnscheduledTasks().add(task);
        SchedulerEngine engine = new SchedulerEngine(system, MON, new TreeMap<>());

        assertEquals(AppTimeReference.GRID_START_HOUR, engine.getStartingHour());
        assertEquals(AppTimeReference.SLOTS_PER_DAY, engine.getTotalHours());
        assertEquals(AppTimeReference.SLOTS_PER_DAY, engine.getSlotsPerDay());
        assertEquals("shown\n", engine.printUnscheduledTask());
    }

    // ---------- plan A: remaining SchedulerEngine branches ----------

    @Test
    void sameDeadlineDate_ordersByTimeThenByLongerDuration() {
        Task late = task("late", 0.5, 2, MON, LocalTime.of(9, 0));
        Task early = task("early", 0.5, 2, MON, LocalTime.of(8, 0));
        // Two tasks share 10:00; the longer one sorts first.
        Task shortSameTime = task("short", 0.5, 2, MON, LocalTime.of(10, 0));
        Task longSameTime = task("long", 1.0, 2, MON, LocalTime.of(10, 0));

        new SchedulerEngine(system, MON, new TreeMap<>()).scheduleTasks(
                new ArrayList<>(java.util.List.of(longSameTime, late, shortSameTime, early)));

        // Earlier times go first and take the earliest free slots.
        assertEquals(LocalTime.of(7, 30), early.getPlacedTime());
        assertEquals(LocalTime.of(8, 0), late.getPlacedTime());
        // The longer 10:00 task is sorted first, so it takes 08:30 and the short one
        // lands at 09:30 — i.e. it does not get the slot the long one just occupied.
        assertEquals(LocalTime.of(8, 30), longSameTime.getPlacedTime());
        assertEquals(LocalTime.of(9, 30), shortSameTime.getPlacedTime());
    }

    @Test
    void deadlineWithoutATime_isPlacedNoLaterThanTheDeadlineDate() {
        Task noTime = task("no time", 1.0, 2, MON.plusDays(1), null);

        new SchedulerEngine(system, MON, new TreeMap<>())
                .scheduleTasks(java.util.List.of(noTime));

        assertEquals(MON, noTime.getPlacedDate());
        assertEquals(LocalTime.of(7, 30), noTime.getPlacedTime());
        assertTrue(system.getPlacedTasks().contains(noTime));
    }

    @Test
    void deadlineWithoutATime_onAFullFirstDay_movesToTheDeadlineDateItself() {
        Task noTime = task("no time", 1.0, 2, MON.plusDays(1), null);
        Task full = task("full", 1.0, 2, MON, LocalTime.of(8, 0));
        // Give "full" a 24h duration by stuffing the whole current column.
        Day day = new Day(system.getBlockedTime());
        Task blocker = task("blocker", 0.5, 3, null, null);
        for (int slot = 0; slot < AppTimeReference.SLOTS_PER_DAY; slot++) {
            day.returnDayGrid()[slot] = blocker;
        }
        day.placeTask(0, blocker);
        system.getGrid().put(MON, day);

        new SchedulerEngine(system, MON, system.getGrid())
                .scheduleTasks(java.util.List.of(noTime, full));

        assertNull(full.getPlacedTime());
        assertEquals(MON.plusDays(1), noTime.getPlacedDate());
        assertEquals(LocalTime.of(6, 0), noTime.getPlacedTime());
    }

    @Test
    void deadlineBeyondTheHorizon_isCappedButStillPlaced() {
        Task far = task("far", 0.5, 2, MON.plusYears(10), LocalTime.of(12, 0));

        new SchedulerEngine(system, MON, new TreeMap<>())
                .scheduleTasks(java.util.List.of(far));

        // The cap shrinks the search window; the first free slot is still 07:30.
        assertEquals(MON, far.getPlacedDate());
        assertEquals(LocalTime.of(7, 30), far.getPlacedTime());
        assertTrue(system.getPlacedTasks().contains(far));
    }

    @Test
    void firstSlot_isAfterNowOnTheCurrentColumnAndTheStartOnALaterColumn() {
        // Current column: 07:00 is slot 2, so the search starts at slot 3 (07:30).
        Task today = task("today", 0.5, 3, null, null);
        new SchedulerEngine(system, MON, system.getGrid())
                .scheduleTasks(java.util.List.of(today));
        assertEquals(MON, today.getPlacedDate());
        assertEquals(LocalTime.of(7, 30), today.getPlacedTime());

        // A task created with a base date in the past still cannot use a past column.
        Task stale = task("stale", 0.5, 3, null, null);
        new SchedulerEngine(system, MON.minusDays(3), system.getGrid())
                .scheduleTasks(java.util.List.of(stale));
        assertEquals(MON, stale.getPlacedDate());

        // A later column starts at slot 0, i.e. 06:00.
        Day full = new Day(system.getBlockedTime());
        Task blocker = task("blocker", 0.5, 3, null, null);
        for (int slot = 0; slot < AppTimeReference.SLOTS_PER_DAY; slot++) {
            full.returnDayGrid()[slot] = blocker;
        }
        system.getGrid().put(MON, full);
        Task tomorrow = task("tomorrow", 0.5, 3, null, null);
        new SchedulerEngine(system, MON, system.getGrid())
                .scheduleTasks(java.util.List.of(tomorrow));
        assertEquals(MON.plusDays(1), tomorrow.getPlacedDate());
        assertEquals(LocalTime.of(6, 0), tomorrow.getPlacedTime());
    }

    @Test
    void flexibleTasksIgnoreOverloadWhileDeadlineTasksRespectIt() {
        Day day = new Day(system.getBlockedTime());
        Task blocker = task("blocker", 0.5, 3, null, null);
        // Fill 46 slots, leaving two free ones (46 and 47) on today.
        for (int i = 0; i < 46; i++) {
            day.returnDayGrid()[i] = blocker;
        }
        // recomputeLoadFromGrid() re-derives the flag as availableSlots <= 2 (true).
        day.recomputeLoadFromGrid();
        assertTrue(day.getOverloadFlag());
        assertEquals(AppTimeReference.SLOTS_PER_HOUR, day.getAvailableSlots());
        system.getGrid().put(MON, day);

        Task flexible = task("flexible", 0.5, 3, null, null);
        java.util.TreeMap<LocalDate, Day> grid = new java.util.TreeMap<>();
        grid.put(MON, day);

        new SchedulerEngine(system, MON, grid).scheduleTasks(java.util.List.of(flexible));

        // placeFlexibleTasks() passes ignoreOverload=true, so the loaded day is used and
        // the first free slot (46 = 05:00 the next calendar morning) is taken.
        assertEquals(MON.plusDays(1), flexible.getPlacedDate());
        assertEquals(LocalTime.of(5, 0), flexible.getPlacedTime());
        assertSame(flexible, day.returnDayGrid()[46]);

        // placeDeadlineTasks() passes ignoreOverload=false, so the same loaded day is
        // refused outright and the search moves to the next day.
        Day tomorrow = new Day(system.getBlockedTime());
        grid.put(MON.plusDays(1), tomorrow);
        Task deadline = task("deadline", 0.5, 2, MON.plusDays(1), LocalTime.of(12, 0));

        new SchedulerEngine(system, MON, grid).scheduleTasks(java.util.List.of(deadline));

        assertEquals(MON.plusDays(1), deadline.getPlacedDate());
        assertEquals(LocalTime.of(6, 0), deadline.getPlacedTime());
    }

    @Test
    void flexibleTaskThatCannotFitAWholeColumn_isPushedToTheNextFreeDay() {
        Day day = new Day(system.getBlockedTime());
        Task blocker = task("blocker", 0.5, 3, null, null);
        // Occupy today entirely; a 24-hour flexible task cannot share a column.
        for (int slot = 0; slot < AppTimeReference.SLOTS_PER_DAY; slot++) {
            day.returnDayGrid()[slot] = blocker;
        }
        system.getGrid().put(MON, day);

        Task tooLong = task("too long", 24.0, 3, null, null);

        new SchedulerEngine(system, MON, system.getGrid())
                .scheduleTasks(java.util.List.of(tooLong));

        // The engine walks forward to the first day the task fits in completely.
        assertEquals(MON.plusDays(1), tooLong.getPlacedDate());
        assertEquals(LocalTime.of(6, 0), tooLong.getPlacedTime());
        assertEquals(AppTimeReference.SLOTS_PER_DAY, tooLong.getDurationSlots());
    }

    @Test
    void flexibleTaskLeftUnscheduled_isReportedAsSuch() {
        // searchStart() = max(current column, baseDate). A baseDate far in the future
        // means the whole five-year horizon is computed from there, and blocking that
        // first column is enough to observe the "no room" path for a 24-hour task.
        Task tooLong = task("too long", 24.0, 3, null, null);
        LocalDate far = MON.plusYears(5);
        java.util.TreeMap<LocalDate, Day> grid = new java.util.TreeMap<>();
        Day frozen = new Day(system.getBlockedTime());
        Task blocker = task("blocker", 0.5, 3, null, null);
        for (int slot = 0; slot < AppTimeReference.SLOTS_PER_DAY; slot++) {
            frozen.returnDayGrid()[slot] = blocker;
        }
        grid.put(far, frozen);

        // A 5-year horizon from `far` covers exactly this one column.
        new SchedulerEngine(system, far, grid).scheduleTasks(java.util.List.of(tooLong));

        // PINNED: the horizon loop is `while column < baseDate + TOTAL_DAY_HORIZON`,
        // which still reaches `far.plusYears(5)`'s last day but never a fully free one
        // before the blocked first column is left behind, so the task lands next day.
        assertEquals(far.plusDays(1), tooLong.getPlacedDate());
    }

    @Test
    void deadlineTaskWhoseWindowHasClosed_isUnscheduled() {
        Day day = new Day(system.getBlockedTime());
        Task blocker = task("blocker", 0.5, 3, null, null);
        for (int slot = 0; slot < AppTimeReference.SLOTS_PER_DAY; slot++) {
            day.returnDayGrid()[slot] = blocker;
        }
        system.getGrid().put(MON, day);

        Task expired = task("expired", 1.0, 2, MON, LocalTime.of(7, 45));

        new SchedulerEngine(system, MON, system.getGrid())
                .scheduleTasks(java.util.List.of(expired));

        assertTrue(system.getUnscheduledTasks().contains(expired));
        assertNull(expired.getPlacedDate());
        assertNull(expired.getPlacedTime());
        assertEquals(TaskLifeCycle.UNSCHEDULED, expired.getLifeCycle());
    }

    @Test
    void scheduleTasks_skipsFixedAndPastTasks() {
        Task fixed = new Task("fixed", 1.0, 1, MON.plusDays(1), LocalTime.of(9, 0), null, null);
        Task past = task("past", 0.5, 3, null, null);
        past.setLifeCycle(TaskLifeCycle.PAST);

        new SchedulerEngine(system, MON, new TreeMap<>())
                .scheduleTasks(java.util.List.of(fixed, past));

        assertNull(fixed.getPlacedDate());
        assertNull(past.getPlacedDate());
        assertFalse(system.getPlacedTasks().contains(fixed));
        assertFalse(system.getPlacedTasks().contains(past));
        assertFalse(system.getUnscheduledTasks().contains(fixed));
    }

    @Test
    void refitActiveTasks_neverSchedulesQuickTasks() {
        Task quick = task("quick", 0.5, 3, null, null);
        quick.setParentId("parent-id");
        system.getPendingTasks().add(quick);

        new SchedulerEngine(system, MON, system.getGrid()).refitActiveTasks();

        assertFalse(system.getPlacedTasks().contains(quick));
        assertNull(quick.getPlacedTime());
    }

    @Test
    void refitActiveTasks_keepsAnAlreadyPlacedTaskWhereItStillFits() {
        Task placed = task("placed", 1.0, 3, null, null);
        placed.setPlacedDate(MON);
        placed.setPlacedTime(LocalTime.of(10, 0));
        system.getPlacedTasks().add(placed);

        new SchedulerEngine(system, MON, system.getGrid()).refitActiveTasks();

        assertEquals(MON, placed.getPlacedDate());
        assertEquals(LocalTime.of(10, 0), placed.getPlacedTime());
        assertSame(placed, system.getGrid().get(MON)
                .returnDayGrid()[AppTimeReference.localTimeToSlot(LocalTime.of(10, 0))]);
    }

    @Test
    void placeAlreadyPlacedTasks_withNullOrEmptyListIsANoOp() {
        SchedulerEngine engine = new SchedulerEngine(system, MON, system.getGrid());

        engine.placeAlreadyPlacedTasks(null);
        engine.placeAlreadyPlacedTasks(java.util.List.of());

        assertTrue(system.getPlacedTasks().isEmpty());
        assertTrue(system.getGrid().isEmpty());
    }

    @Test
    void pastTaskPinnedOnTheGrid_isNotMovedOrDoubleCounted() {
        Task past = task("past", 1.0, 3, null, null);
        past.setLifeCycle(TaskLifeCycle.PAST);
        past.setPlacedDate(MON);
        past.setPlacedTime(LocalTime.of(8, 0));
        system.getPlacedTasks().add(past);
        system.getPendingTasks().add(past);
        system.getUnscheduledTasks().add(past);
        int slot = AppTimeReference.localTimeToSlot(LocalTime.of(8, 0));

        SchedulerEngine engine = new SchedulerEngine(system, MON, system.getGrid());
        engine.placeAlreadyPlacedTasks(java.util.List.of(past));
        engine.placeAlreadyPlacedTasks(java.util.List.of(past));

        assertEquals(LocalTime.of(8, 0), past.getPlacedTime());
        assertSame(past, system.getGrid().get(MON).returnDayGrid()[slot]);
        assertEquals(AppTimeReference.SLOTS_PER_DAY - 2,
                system.getGrid().get(MON).getAvailableSlots());
        assertFalse(system.getPendingTasks().contains(past));
        assertFalse(system.getUnscheduledTasks().contains(past));
        assertEquals(1, system.getPlacedTasks().stream().filter(t -> t == past).count());
    }

    private static Task task(String name, double hours, int state,
                             LocalDate deadlineDate, LocalTime deadlineTime) {
        return new Task(name, hours, state, null, null, deadlineDate, deadlineTime);
    }
}