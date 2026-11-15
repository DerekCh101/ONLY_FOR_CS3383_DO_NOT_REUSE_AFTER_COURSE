/** Lifecycle clock: UNSCHEDULED when unplaced, PAST when the end is at or before now. */
package org.ashoreprospect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("app-clock")
class RefreshLifeCycleTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final LocalTime NOW = LocalTime.of(9, 0);

    private Clock saved;

    @BeforeEach
    void freezeClock() {
        saved = Clock.systemDefaultZone();
        AppTimeReference.setClock(Clock.fixed(MON.atTime(NOW).atZone(ZONE).toInstant(), ZONE));
    }

    @AfterEach
    void restoreClock() {
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(saved);
    }

    @Test
    void unplacedTask_becomesUnscheduled() {
        Task task = task("loose", 1.0, null, null);
        task.setLifeCycle(TaskLifeCycle.ONGOING);

        RefreshLifeCycle.refreshLifeCycle(task);

        assertEquals(TaskLifeCycle.UNSCHEDULED, task.getLifeCycle());
    }

    @Test
    void taskEndingExactlyNow_isPast() {
        Task task = task("ends now", 1.0, MON, NOW.minusHours(1));

        RefreshLifeCycle.refreshLifeCycle(task);

        assertEquals(TaskLifeCycle.PAST, task.getLifeCycle());
    }

    @Test
    void taskEndingOneMinuteLater_isOngoing() {
        Task task = task("still running", 1.0, MON, NOW.minusMinutes(59));

        RefreshLifeCycle.refreshLifeCycle(task);

        assertEquals(TaskLifeCycle.ONGOING, task.getLifeCycle());
    }

    @Test
    void multiSlotDuration_usesRoundedSlotsForEndTime() {
        // 61 raw minutes placed at 07:30 would end at 08:31: still ONGOING at 08:30
        // and PAST at 08:31. The lifecycle instead uses durationSlots * 30, and the
        // placement is floored to the 07:30 half-hour cell and rounded up to 3 slots,
        // so the task stays ONGOING at 08:31 and first turns PAST at 09:00.
        Task task = task("ninety", 61.0 / 60.0, MON, LocalTime.of(7, 30));
        assertEquals(3, task.getDurationSlots());

        AppTimeReference.setClock(Clock.fixed(
                MON.atTime(8, 31).atZone(ZONE).toInstant(), ZONE));
        RefreshLifeCycle.refreshLifeCycle(task);
        assertEquals(TaskLifeCycle.ONGOING, task.getLifeCycle());

        AppTimeReference.setClock(Clock.fixed(
                MON.atTime(NOW).atZone(ZONE).toInstant(), ZONE));
        RefreshLifeCycle.refreshLifeCycle(task);
        assertEquals(TaskLifeCycle.PAST, task.getLifeCycle());
    }

    @Test
    void refreshAll_reportsOnlyActualChanges() {
        TaskManagementSystem system = TaskManagementSystem.getInstance();
        system.getPlacedTasks().clear();
        system.getPendingTasks().clear();
        system.getUnscheduledTasks().clear();
        system.getGrid().clear();

        Task upcoming = task("upcoming", 1.0, MON, LocalTime.of(10, 0));
        system.getPlacedTasks().add(upcoming);

        // First pass moves UNSCHEDULED -> ONGOING, so this counts as a change.
        assertTrue(RefreshLifeCycle.refreshAll(system));
        assertEquals(TaskLifeCycle.ONGOING, upcoming.getLifeCycle());

        // Second pass finds nothing new to do.
        assertFalse(RefreshLifeCycle.refreshAll(system));
    }

    @Test
    void nullInputs_areSafe() {
        RefreshLifeCycle.refreshLifeCycle(null);
        assertFalse(RefreshLifeCycle.refreshAll(null));

        AppTimeReference.setClock(Clock.fixed(
                MON.atTime(NOW).atZone(ZONE).toInstant(), ZONE));
        Task future = task("future", 1.0, MON.plusDays(1), LocalTime.of(10, 0));
        RefreshLifeCycle.refreshLifeCycle(future);
        assertEquals(TaskLifeCycle.ONGOING, future.getLifeCycle());
        assertEquals("", future.getParentId());
    }

    private static Task task(String name, double hours, LocalDate placedDate, LocalTime placedTime) {
        return Task.loadTaskFromdb(name + "-id", name, hours, 3,
                null, null, null, null, placedDate, placedTime);
    }
}
