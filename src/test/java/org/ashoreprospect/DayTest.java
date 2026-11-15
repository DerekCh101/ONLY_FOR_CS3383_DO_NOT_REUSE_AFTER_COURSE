package org.ashoreprospect;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

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

    // ---------- plan J: remaining Day branches ----------

    @Test
    void placeTask_ignoresNullTaskAndOutOfRangeSlots() {
        Day day = new Day(new BlockedTimePeriod());
        Task task = task("task", 0.5);

        day.placeTask(3, null);
        day.placeTask(-1, task);
        day.placeTask(AppTimeReference.SLOTS_PER_DAY, task);

        for (Task slot : day.returnDayGrid()) {
            assertNull(slot);
        }
        assertEquals(AppTimeReference.SLOTS_PER_DAY, day.getAvailableSlots());
    }

    @Test
    void placeTask_neverPaintsAQuickTaskOntoTheGrid() {
        Day day = new Day(new BlockedTimePeriod());
        Task quick = task("quick", 0.5);
        quick.setParentId("parent-id");
        assertTrue(quick.isQuick());

        day.placeTask(3, quick);

        assertNull(day.returnDayGrid()[3]);
        assertEquals(AppTimeReference.SLOTS_PER_DAY, day.getAvailableSlots());
    }

    @Test
    void overloadFlag_turnsOnAtOneHourLeftAndBlocksUntilIgnored() {
        Day day = new Day(new BlockedTimePeriod());
        // 46 slots of 1-hour tasks leave exactly SLOTS_PER_HOUR (2) free.
        for (int i = 0; i < 23; i++) {
            day.placeTask(i * 2, task("filler-" + i, 1.0));
        }
        assertTrue(day.getOverloadFlag());
        assertEquals(AppTimeReference.SLOTS_PER_HOUR, day.getAvailableSlots());

        Task late = task("late", 0.5);
        assertFalse(day.canPlace(late, MON, 46, false, false));
        assertTrue(day.canPlace(late, MON, 46, false, true));
    }

    @Test
    void canPlace_allowsTheSameTaskIdInItsOwnSlotAndRejectsAnotherId() {
        Day day = new Day(new BlockedTimePeriod());
        Task placed = task("placed", 1.0);
        day.placeTask(4, placed);

        // Same id, same slot: the identical task is always allowed.
        assertTrue(day.canPlace(placed, MON, 4, true, true));

        Task twin = Task.loadTaskFromdb(placed.getID(), "twin", 1.0, 3,
                null, null, null, null, null, null);
        // Same id but a different object still passes the id check.
        assertTrue(day.canPlace(twin, MON, 4, true, true));

        Task stranger = task("stranger", 1.0);
        assertFalse(day.canPlace(stranger, MON, 4, true, true));
    }

    @Test
    void canPlace_rejectsTheSlotThatStartsExactlyNow() {
        Day day = new Day(new BlockedTimePeriod());
        Task task = task("now", 0.5);

        // The clock is fixed at 07:00, which is slot 2 (06:00 + 2 * 30 min).
        assertEquals(2, AppTimeReference.currentSlotIndex());
        assertFalse(day.canPlace(task, MON, 2, true, true));
        assertTrue(day.canPlace(task, MON, 3, true, true));
    }

    @Test
    void constructorCoversEverySlotAfterRealMidnightAndTheScreenIsPaintable() {
        Day day = new Day(new BlockedTimePeriod());
        for (int slot = 0; slot < AppTimeReference.SLOTS_PER_DAY; slot++) {
            assertNotNull(AppTimeReference.dateTimeForSlot(MON, slot));
        }
        // Slot 47 is 05:30 on the *next* calendar day; canPlace must still accept it
        // for a 30-minute task because 05:30 + 30 min ends the column exactly.
        Task tail = task("tail", 0.5);
        assertTrue(day.canPlace(tail, MON, 47, true, true));
        assertTrue(AppTimeReference.slotIsNextCalendarDay(47));
        // A 1-hour task cannot start in the last slot.
        assertFalse(day.canPlace(task("hour", 1.0), MON, 47, true, true));
    }

    @Test
    void recomputeLoadFromGrid_subtractsBlockedBudgetAsWellAsUsedSlots() {
        BlockedTimePeriod blocked = new BlockedTimePeriod();
        blocked.replaceBlockedPeriods(java.util.List.of(
                BlockedTime.daily("lunch", java.time.LocalTime.of(12, 0),
                        java.time.LocalTime.of(13, 0))));
        Day day = new Day(blocked);
        assertEquals(AppTimeReference.SLOTS_PER_DAY - 2, day.getAvailableSlots());

        day.returnDayGrid()[3] = task("one", 0.5);
        day.recomputeLoadFromGrid();

        assertEquals(AppTimeReference.SLOTS_PER_DAY - 2 - 1, day.getAvailableSlots());
        assertFalse(day.getOverloadFlag());
    }

    private static Task task(String name, double hours) {
        return new Task(name, hours, 3, null, null, null, null);
    }
}