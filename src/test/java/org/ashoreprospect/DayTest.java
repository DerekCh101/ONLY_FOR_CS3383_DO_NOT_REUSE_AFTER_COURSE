package org.ashoreprospect;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("app-clock")
class DayTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private Clock originalClock;

    @BeforeEach
    void setUp() {
        originalClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(Clock.fixed(
                Instant.parse("2026-10-12T07:00:00Z"), ZoneId.of("UTC")));
    }

    @AfterEach
    void tearDown() {
        AppTimeReference.setClock(originalClock);
    }

    @Test
    void canPlace_rejectsPastAndOutOfBoundsSlots() {
        Day day = new Day(new BlockedTimePeriod());
        Task task = task("task", 0.5);

        assertFalse(day.canPlace(task, MON, 2, false, false));
        assertFalse(day.canPlace(task, MON, -1, false, false));
        assertFalse(day.canPlace(task, MON, AppTimeReference.SLOTS_PER_DAY, false, false));
        assertTrue(day.canPlace(task, MON, 3, false, false));
    }

    @Test
    void canPlace_allowsSameTaskButRejectsDifferentTaskCollision() {
        Day day = new Day(new BlockedTimePeriod());
        Task placed = task("placed", 1.0);
        Task other = task("other", 0.5);
        day.placeTask(3, placed);

        assertTrue(day.canPlace(placed, MON, 3, false, false));
        assertFalse(day.canPlace(other, MON, 3, false, false));
    }

    @Test
    void placeTask_clipsAtEndAndCountsOnlyNewlyOccupiedSlots() {
        Day day = new Day(new BlockedTimePeriod());
        Task task = task("long", 1.0);

        day.placeTask(AppTimeReference.SLOTS_PER_DAY - 1, task);
        day.placeTask(AppTimeReference.SLOTS_PER_DAY - 1, task);

        assertSame(task, day.returnDayGrid()[AppTimeReference.SLOTS_PER_DAY - 1]);
        assertEquals(AppTimeReference.SLOTS_PER_DAY - 1, day.getAvailableSlots());
    }

    @Test
    void recomputeLoadFromGrid_restoresLoadInvariant() {
        Day day = new Day(new BlockedTimePeriod());
        Task task = task("task", 1.0);
        day.returnDayGrid()[3] = task;
        day.returnDayGrid()[4] = task;

        day.recomputeLoadFromGrid();

        assertEquals(AppTimeReference.SLOTS_PER_DAY - 2, day.getAvailableSlots());
        assertFalse(day.getOverloadFlag());
        day.clearAllSlots();
        assertEquals(AppTimeReference.SLOTS_PER_DAY, day.getAvailableSlots());
    }

    private static Task task(String name, double hours) {
        return new Task(name, hours, 3, null, null, null, null);
    }
}