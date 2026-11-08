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

    private static Task task(String name, double hours, int state,
                             LocalDate deadlineDate, LocalTime deadlineTime) {
        return new Task(name, hours, state, null, null, deadlineDate, deadlineTime);
    }
}