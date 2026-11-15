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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        // lands at 09:30, i.e. it does not get the slot the long one just occupied.
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

    // ---------------------------------------------------------------- stage 3: branch hardening

    @Test
    void updateSchedule_crashesWhenAPlacedDeadlineTaskHasNoDeadlineDate() throws Exception {
        // BUG (found in stage 3): the deadline sort at SchedulerEngine:139-151 dereferences
        // t.getDeadLineDate() without a null check, while placeDeadlineTasks() a few lines
        // later explicitly handles `getDeadLineDate() == null`. Any state-2 task with a null
        // deadline date therefore crashes updateSchedule() with a NullPointerException.
        //
        // A second copy of this pin lives in knownDefects.SchedulerEngineDeadlineOrderTest,
        // the defect register the project treats as the canonical record. This copy stays so
        // the fix has a nearby regression test to promote.
        //
        // Reachable through the public API: a deadline task with a real date (so the sort
        // has a non-null side to compare against) is enough to trigger it.
        Task planted = task("no-date", 0.5, 3, null, null);
        planted.setState(2);
        planted.setPlacedDate(MON);
        planted.setPlacedTime(LocalTime.of(9, 0));
        system.getPlacedTasks().add(planted);
        system.getGrid().computeIfAbsent(MON, d -> new Day(system.getBlockedTime()))
                .placeTask(AppTimeReference.localTimeToSlot(LocalTime.of(9, 0)), planted);
        system.getUnscheduledTasks().add(task("untimed", 0.5, 2, MON.plusDays(1), null));
        system.getUnscheduledTasks().add(task("timed", 0.5, 2, MON.plusDays(1), LocalTime.of(9, 0)));

        // PINNED: no production change - the crash is recorded exactly as it happens.
        NullPointerException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> system.updateSchedule());
        // The JVM reports the null receiver of LocalDate.compareTo() as "other is null".
        assertTrue(thrown.getMessage().contains("other") && thrown.getMessage().contains("null"),
                "PINNED: the NPE comes from the deadline comparator, not anywhere else: "
                + thrown.getMessage());
    }

    @Test
    void deadlineTasks_mixedNullAndPresentDeadlineTimes_stillSort() throws Exception {
        // L144-L146: with at least one task that has a deadline *time*, the comparator
        // never compares two LocalDate nulls, so the sort survives and the untimed task is
        // ordered after the timed one.
        Task untimed = task("untimed", 0.5, 2, MON.plusDays(1), null);
        Task timed = task("timed", 0.5, 2, MON.plusDays(1), LocalTime.of(9, 0));

        system.getUnscheduledTasks().add(untimed);
        system.getUnscheduledTasks().add(timed);

        new SchedulerEngine(system, MON, new java.util.TreeMap<>())
                .scheduleTasks(new ArrayList<>(system.getUnscheduledTasks()));

        assertEquals(LocalTime.of(7, 30), timed.getPlacedTime(), "timed task is placed first");
        assertEquals(LocalTime.of(8, 0), untimed.getPlacedTime(), "untimed task goes next");
    }

    @Test
    void deadlineTasks_nullTimeSortsAfterATimedDeadlineOnTheSameDate() throws Exception {
        // L144-L146: the null-aware arms of the deadline comparator. On one date, a task with
        // no deadline time sorts *after* one with a time, regardless of duration.
        Task untimed = task("untimed", 2.0, 2, MON.plusDays(1), null);
        Task timed = task("timed", 0.5, 2, MON.plusDays(1), LocalTime.of(9, 0));

        system.getUnscheduledTasks().add(untimed);
        system.getUnscheduledTasks().add(timed);

        new SchedulerEngine(system, MON, system.getGrid())
                .scheduleTasks(new ArrayList<>(system.getUnscheduledTasks()));

        assertEquals(LocalTime.of(7, 30), timed.getPlacedTime(),
                "the timed deadline is placed first despite being shorter");
        assertEquals(LocalTime.of(8, 0), untimed.getPlacedTime());
        assertTrue(system.getPlacedTasks().contains(timed));
        assertTrue(system.getPlacedTasks().contains(untimed));
    }

    @Test
    void deadlineTasks_bothUntimedSortByLongerDurationFirst() throws Exception {
        // L146's else-path: when both deadline times are null, the tie-break is duration,
        // longest first. An untimed deadline on the same date has no time pressure, so this
        // only exercises the comparator.
        Task longTask = task("long", 2.0, 2, MON.plusDays(1), null);
        Task shortTask = task("short", 0.5, 2, MON.plusDays(1), null);

        system.getUnscheduledTasks().add(shortTask);
        system.getUnscheduledTasks().add(longTask);

        new SchedulerEngine(system, MON, system.getGrid())
                .scheduleTasks(new ArrayList<>(system.getUnscheduledTasks()));

        assertEquals(LocalTime.of(7, 30), longTask.getPlacedTime(),
                "the longer task is placed first");
        assertEquals(LocalTime.of(9, 30), shortTask.getPlacedTime());
    }

    @Test
    void addIfActive_ignoresAnUnscheduledListThatIsNull() throws Exception {
        // L40: the null-source guard. Only reachable by handing scheduleTasks() a null list;
        // the public refitActiveTasks() always passes real lists.
        SchedulerEngine engine = new SchedulerEngine(system, MON, system.getGrid());
        engine.scheduleTasks(null);
        assertTrue(system.getPlacedTasks().isEmpty());
        assertTrue(system.getUnscheduledTasks().isEmpty());
    }

    @Test
    void scheduleTasks_rejectsANullEntryInTheMiddleOfTheList() throws Exception {
        // PINNED: L42's `task == null` arm is dead. scheduleTasks() dereferences every entry
        // at L132 (`task.hasDeadline()`) before addIfActive() ever sees it, so a null in the
        // list throws instead of being skipped. Only the isQuick() arm of L42 is live.
        // Kept here because it documents a reachability decision about addIfActive(); the
        // matching placeAlreadyPlacedTasks() case is in knownDefects.SchedulerEngineDeadlineOrderTest.
        ArrayList<Task> supplied = new ArrayList<>();
        supplied.add(null);
        supplied.add(task("real", 0.5, 3, null, null));

        NullPointerException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> new SchedulerEngine(system, MON, system.getGrid()).scheduleTasks(supplied));
        assertTrue(thrown.getMessage().contains("hasDeadline"),
                "PINNED: the crash is at the hasDeadline() dereference: " + thrown.getMessage());
    }

    @Test
    void scheduleTasks_quickTasksGetAClockButNoGridSlot() throws Exception {
        // L42: `task == null || task.isQuick()` - the isQuick() arm never fires here. A quick
        // task handed straight to scheduleTasks() is classified as flexible (no deadline) and
        // gets a placed clock, but Day.placeTask() refuses it because it is quick, so it never
        // occupies a grid slot. The real task then takes the same 07:30 slot.
        Task quick = task("quick", 0.5, 3, null, null);
        quick.setParentId("some-parent");
        Task real = task("real", 0.5, 3, null, null);

        ArrayList<Task> supplied = new ArrayList<>();
        supplied.add(quick);
        supplied.add(real);

        new SchedulerEngine(system, MON, system.getGrid()).scheduleTasks(supplied);

        assertTrue(quick.isQuick());
        // PINNED: the stuck clock is an artefact of applyPlacement() writing the clock even
        // when Day.placeTask() silently refused the task.
        assertEquals(LocalTime.of(7, 30), quick.getPlacedTime());
        assertEquals(LocalTime.of(7, 30), real.getPlacedTime(),
                "the quick task did not actually consume the slot");
        Day day = system.getGrid().get(MON);
        assertSame(real, day.returnDayGrid()[3]);
        assertTrue(java.util.Arrays.stream(day.returnDayGrid()).noneMatch(t -> t == quick),
                "quick tasks never occupy grid slots");
    }

    @Test
    void addIfActive_skipsPastTasksAndDuplicates() throws Exception {
        // L43: `!isPast(task) && !out.contains(task)`. A PAST task in the pending list is
        // left alone, and a task present in both pending and unscheduled is scheduled once.
        Task past = task("past", 0.5, 3, null, null);
        past.setPlacedDate(MON);
        past.setPlacedTime(LocalTime.of(7, 30));
        past.setLifeCycle(TaskLifeCycle.PAST);
        Task dup = task("dup", 0.5, 3, null, null);
        system.getPendingTasks().add(past);
        system.getPendingTasks().add(dup);
        system.getUnscheduledTasks().add(dup);

        new SchedulerEngine(system, MON, system.getGrid()).refitActiveTasks();

        assertEquals(LocalTime.of(7, 30), past.getPlacedTime(), "PAST task keeps its slot");
        assertEquals(1, system.getPlacedTasks().stream().filter(t -> t == dup).count(),
                "duplicate entry is scheduled exactly once");
    }

    @Test
    void placeAlreadyPlacedTasks_skipsATaskWithAPlacedDateButNoTime() throws Exception {
        // L65: `date == null || time == null` - the mirror case of the existing null-date
        // test. The task is neither pinned nor made unscheduled.
        Task half = task("half-placed", 0.5, 3, null, null);
        half.setPlacedDate(MON);
        half.setPlacedTime(null);

        new SchedulerEngine(system, MON, system.getGrid())
                .placeAlreadyPlacedTasks(new ArrayList<>(java.util.List.of(half)));

        assertNull(half.getPlacedTime());
        assertFalse(system.getPlacedTasks().contains(half));
        assertFalse(system.getUnscheduledTasks().contains(half));
    }

    @Test
    void placeAlreadyPlacedTasks_registersAQuickTaskOnce() throws Exception {
        // L67: a quick task with a placed clock is only registered, never put on the grid,
        // and a second pass must not duplicate it in placedTasks.
        Task quick = task("quick-placed", 0.5, 3, null, null);
        quick.setParentId("parent-x");
        quick.setPlacedDate(MON);
        quick.setPlacedTime(LocalTime.of(9, 0));

        SchedulerEngine engine = new SchedulerEngine(system, MON, system.getGrid());
        engine.placeAlreadyPlacedTasks(new ArrayList<>(java.util.List.of(quick)));
        engine.placeAlreadyPlacedTasks(new ArrayList<>(java.util.List.of(quick)));

        assertEquals(1, system.getPlacedTasks().stream().filter(t -> t == quick).count());
        Day day = system.getGrid().get(MON);
        assertTrue(day == null || java.util.Arrays.stream(day.returnDayGrid()).noneMatch(t -> t == quick),
                "quick tasks never occupy grid slots");
    }

    @Test
    void placeAlreadyPlacedTasks_movesADeadlineTaskPastItsDeadlineToUnscheduled()
            throws Exception {
        // L89: the deadline half of the combined guard. The slot is free, so only
        // respectsDeadlineTime() can reject it - the task would finish after its deadline.
        Task late = task("late", 2.0, 2, MON, LocalTime.of(7, 45));
        late.setPlacedDate(MON);
        late.setPlacedTime(LocalTime.of(7, 30));

        new SchedulerEngine(system, MON, system.getGrid())
                .placeAlreadyPlacedTasks(new ArrayList<>(java.util.List.of(late)));

        assertTrue(system.getUnscheduledTasks().contains(late));
        assertFalse(system.getPlacedTasks().contains(late));
    }

    @Test
    void pinOnGrid_acceptsAFullDayTaskExactlyFillingTheColumn() throws Exception {
        // L105: 24 h at 06:00 is slot 0 + 48 slots, which is *exactly* SLOTS_PER_DAY, so the
        // strict `>` guard lets it through and the whole column is claimed.
        Task pastFull = task("past-full", 24.0, 3, null, null);
        pastFull.setPlacedDate(MON);
        pastFull.setPlacedTime(LocalTime.of(6, 0));
        pastFull.setLifeCycle(TaskLifeCycle.PAST);

        new SchedulerEngine(system, MON, system.getGrid())
                .placeAlreadyPlacedTasks(new ArrayList<>(java.util.List.of(pastFull)));

        assertTrue(system.getPlacedTasks().contains(pastFull));
        Day day = system.getGrid().get(MON);
        assertNotNull(day, "pinning creates the column");
        assertTrue(java.util.Arrays.stream(day.returnDayGrid()).allMatch(t -> t == pastFull),
                "a 24 h task fills every one of the 48 slots");
    }

    @Test
    void pinOnGrid_guardIsUnreachableBecauseSlotsAreClampedToADay() throws Exception {
        // PINNED: `slot + count > SLOTS_PER_DAY` at L105 can never be true. count comes from
        // getDurationSlots(), which clamps the duration to one day and rounds up to at most
        // 48 slots, and slot is one of 0..47. A 06:00 start is therefore the only way to
        // reach the boundary, and it lands exactly on 48 rather than past it.
        Task full = task("full", 24.0, 3, null, null);
        full.setPlacedDate(MON);
        full.setPlacedTime(LocalTime.of(6, 0));
        assertEquals(AppTimeReference.SLOTS_PER_DAY, full.getDurationSlots());
        assertEquals(0, AppTimeReference.localTimeToSlot(LocalTime.of(6, 0)));

        // A longer-than-a-day task is clamped rather than overflowing.
        Task longer = task("longer", 48.0, 3, null, null);
        longer.setPlacedDate(MON);
        longer.setPlacedTime(LocalTime.of(6, 0));
        assertEquals(AppTimeReference.SLOTS_PER_DAY, longer.getDurationSlots());
    }

    @Test
    void markUnscheduled_ignoresTasksThatMayNotGoUnscheduled() throws Exception {
        // L258: a PAST fixed-time task with a placed clock takes the pinOnGrid() branch and
        // is never marked unscheduled (canGoUnscheduled() is false for state 1).
        Task pastFixed = task("past-fixed", 1.0, 1, null, null);
        pastFixed.setPlacedDate(MON);
        pastFixed.setPlacedTime(LocalTime.of(6, 0));
        pastFixed.setLifeCycle(TaskLifeCycle.PAST);

        new SchedulerEngine(system, MON, system.getGrid())
                .placeAlreadyPlacedTasks(new ArrayList<>(java.util.List.of(pastFixed)));

        assertTrue(system.getPlacedTasks().contains(pastFixed));
        assertFalse(system.getUnscheduledTasks().contains(pastFixed));
    }

    @Test
    void markUnscheduled_skipsAPastFlexibleTask() throws Exception {
        // L259: isPast(task) -> return. A PAST flexible task that cannot be placed on the
        // grid is left where it is instead of being pushed into the unscheduled list.
        Task past = task("past-flex", 0.5, 3, null, null);
        past.setPlacedDate(MON);
        past.setPlacedTime(LocalTime.of(7, 30));
        past.setLifeCycle(TaskLifeCycle.PAST);
        // Occupy the very slot it claims, so canPlace() fails on collision.
        Day day = new Day(system.getBlockedTime());
        day.placeTask(AppTimeReference.localTimeToSlot(LocalTime.of(7, 30)),
                task("squatter", 0.5, 3, null, null));
        system.getGrid().put(MON, day);

        new SchedulerEngine(system, MON, system.getGrid())
                .placeAlreadyPlacedTasks(new ArrayList<>(java.util.List.of(past)));

        assertFalse(system.getUnscheduledTasks().contains(past),
                "PINNED: a PAST task is never re-parked as unscheduled");
    }

    @Test
    void placeAlreadyPlacedTasks_rejectsANullEntry() throws Exception {
        // PINNED: L65's combined guard does not protect against a null *entry*. The loop
        // dereferences task at L63 (`task.getPlacedDate()`) before the date/time check, so
        // the documented "null-or-empty list is a no-op" only covers a null list, not a
        // null element. Only the `date == null` and `time == null` arms are live.
        ArrayList<Task> withNull = new ArrayList<>();
        withNull.add(null);
        SchedulerEngine engine = new SchedulerEngine(system, MON, system.getGrid());

        NullPointerException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> engine.placeAlreadyPlacedTasks(withNull));
        assertTrue(thrown.getMessage().contains("getPlacedDate"),
                "PINNED: the crash is at the getPlacedDate() dereference: " + thrown.getMessage());
    }

    @Test
    void scheduleTasks_dropsActivePendingTasksButKeepsPastOnes() throws Exception {
        // L156: `removeIf(t -> !isPast(t))` runs after the placement passes and strips every
        // non-PAST pending task, including ones that were never scheduled.
        Task active = task("active-pending", 0.5, 3, null, null);
        active.setLifeCycle(TaskLifeCycle.UNSCHEDULED);
        Task past = task("past-pending", 0.5, 3, null, null);
        past.setPlacedDate(MON);
        past.setPlacedTime(LocalTime.of(7, 30));
        past.setLifeCycle(TaskLifeCycle.PAST);
        system.getPendingTasks().add(active);
        system.getPendingTasks().add(past);

        // scheduleTasks() returns early for an empty list (L120), so L156 only runs when at
        // least one task is supplied.
        Task trigger = task("trigger", 0.5, 3, null, null);
        new SchedulerEngine(system, MON, system.getGrid())
                .scheduleTasks(new ArrayList<>(java.util.List.of(trigger)));

        assertFalse(system.getPendingTasks().contains(active));
        assertTrue(system.getPendingTasks().contains(past));
    }

    @Test
    void deadlineTaskWithANullDateIsUnreachableThroughTheSortAlone() throws Exception {
        // L174: placeDeadlineTasks() guards `getDeadLineDate() == null`, but the sort that
        // runs before it (SchedulerEngine:139) dereferences the date first. So this guard is
        // only ever safe for a *single* date-less task - two of them crash in the comparator
        // (see updateSchedule_crashesWhenAPlacedDeadlineTaskHasNoDeadlineDate above).
        Task noDate = task("no-date-only", 0.5, 3, null, null);
        noDate.setState(2);
        system.getUnscheduledTasks().add(noDate);

        // A lone task has nothing to compare against, so the sort leaves it alone and
        // placeDeadlineTasks() marks it unscheduled instead of crashing.
        new SchedulerEngine(system, MON, system.getGrid())
                .scheduleTasks(new ArrayList<>(system.getUnscheduledTasks()));

        assertTrue(system.getUnscheduledTasks().contains(noDate),
                "the lone task takes the guarded path at L174");
        assertFalse(system.getPlacedTasks().contains(noDate));
    }

    @Test
    void deadlineTaskWithoutADate_pinsTheUnhandledSecondPath() throws Exception {
        // PINNED: placeDeadlineTasks() also guards `lastDay < 0` (L174), i.e. a deadline
        // more than a day before baseDate. That arm is unreachable for a *live* task, but a
        // lone date-less task reaches the L174 guard above. Both are recorded here so the
        // dead-code review does not need to re-derive it.
        Task noDate = task("orphan-deadline", 0.5, 3, null, null);
        noDate.setState(2);
        assertTrue(noDate.hasDeadline());
        assertNull(noDate.getDeadLineDate());
    }

    private static Task task(String name, double hours, int state,
                             LocalDate deadlineDate, LocalTime deadlineTime) {
        return new Task(name, hours, state, null, null, deadlineDate, deadlineTime);
    }
}
