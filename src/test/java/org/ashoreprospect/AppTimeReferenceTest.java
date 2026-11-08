/** 30-minute slot math, 06:00 column start, and the 00:00–05:30 previous-day rule. */
package org.ashoreprospect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("app-clock")
class AppTimeReferenceTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    @Test
    void slotsPerDay_is48() {
        assertEquals(48, AppTimeReference.SLOTS_PER_DAY);
    }

    @Test
    void gridStartsAtSixAm() {
        assertEquals(6 * 60, AppTimeReference.GRID_START_MINUTES);
        assertEquals(0, AppTimeReference.localTimeToSlot(LocalTime.of(6, 0)));
    }

    @Test
    void timeBeforeSixBelongsToPreviousColumn() {
        assertEquals(MON.minusDays(1), AppTimeReference.columnDateFor(MON, LocalTime.of(5, 30)));
        assertEquals(MON.minusDays(1), AppTimeReference.columnDateFor(MON, LocalTime.MIDNIGHT));
        assertEquals(MON, AppTimeReference.columnDateFor(MON, LocalTime.of(6, 0)));
        assertEquals(MON, AppTimeReference.columnDateFor(MON, LocalTime.NOON));
    }

    @Test
    void columnDateFor_nullSafe() {
        assertNull(AppTimeReference.columnDateFor(null, LocalTime.NOON));
        assertEquals(MON, AppTimeReference.columnDateFor(MON, null));
    }

    @Test
    void timeBeforeSixMapsToLateSlots() {
        assertEquals(36, AppTimeReference.localTimeToSlot(LocalTime.MIDNIGHT));
        assertEquals(47, AppTimeReference.localTimeToSlot(LocalTime.of(5, 30)));
        assertEquals(35, AppTimeReference.localTimeToSlot(LocalTime.of(23, 30)));
    }

    @Test
    void slotToLocalTime_roundTrips() {
        for (int slot = 0; slot < AppTimeReference.SLOTS_PER_DAY; slot++) {
            LocalTime t = AppTimeReference.slotToLocalTime(slot);
            assertEquals(slot, AppTimeReference.localTimeToSlot(t), "slot " + slot);
        }
    }

    @Test
    void slotToLocalTime_clampsOutOfRange() {
        assertEquals(LocalTime.of(6, 0), AppTimeReference.slotToLocalTime(-5));
        assertEquals(LocalTime.of(5, 30), AppTimeReference.slotToLocalTime(999));
    }

    @Test
    void localTimeToSlot_handlesEdges() {
        // Midnight is slot 36 of the previous column, not slot 0.
        assertEquals(36, AppTimeReference.localTimeToSlot(LocalTime.of(0, 0)));
        // 05:59 is inside the last slot (05:30–06:00).
        assertEquals(AppTimeReference.SLOTS_PER_DAY - 1, AppTimeReference.localTimeToSlot(LocalTime.of(5, 59)));
    }

    @Test
    void nextCalendarDayStartsAtSlot36() {
        assertFalse(AppTimeReference.slotIsNextCalendarDay(35));
        assertTrue(AppTimeReference.slotIsNextCalendarDay(36));
        assertTrue(AppTimeReference.slotIsNextCalendarDay(47));
    }

    @Test
    void calendarDateForSlot_rollsForwardAfterMidnight() {
        assertEquals(MON, AppTimeReference.calendarDateForSlot(MON, 0));
        assertEquals(MON, AppTimeReference.calendarDateForSlot(MON, 35));
        assertEquals(MON.plusDays(1), AppTimeReference.calendarDateForSlot(MON, 36));
    }

    @Test
    void dateTimeForSlot_combinesColumnAndClock() {
        assertEquals(java.time.LocalDateTime.of(MON, LocalTime.of(6, 0)),
                AppTimeReference.dateTimeForSlot(MON, 0));
        assertEquals(java.time.LocalDateTime.of(MON.plusDays(1), LocalTime.MIDNIGHT),
                AppTimeReference.dateTimeForSlot(MON, 36));
    }

    @Test
    void hoursToMinutes_roundsAndClamps() {
        assertEquals(30, AppTimeReference.hoursToMinutes(0.5));
        assertEquals(20, AppTimeReference.hoursToMinutes(1.0 / 3.0));
        assertEquals(60, AppTimeReference.hoursToMinutes(1));
        assertEquals(1, AppTimeReference.hoursToMinutes(0));
        assertEquals(AppTimeReference.MINUTES_PER_DAY, AppTimeReference.hoursToMinutes(99));
    }

    @Test
    void clampMinutes_boundsToOneDay() {
        assertEquals(1, AppTimeReference.clampMinutes(0));
        assertEquals(1, AppTimeReference.clampMinutes(-10));
        assertEquals(1440, AppTimeReference.clampMinutes(2000));
        assertEquals(45, AppTimeReference.clampMinutes(45));
    }

    @Test
    void minutesToSlots_ceilsUp() {
        assertEquals(1, AppTimeReference.minutesToSlots(1));
        assertEquals(1, AppTimeReference.minutesToSlots(30));
        assertEquals(2, AppTimeReference.minutesToSlots(31));
        assertEquals(4, AppTimeReference.minutesToSlots(120));
        assertEquals(48, AppTimeReference.minutesToSlots(1440));
    }

    @Test
    void occupancySlots_floorsStartAndCeilsEnd() {
        // 18:36–19:53 → 18:30–20:00 = 3 slots
        assertEquals(3, AppTimeReference.occupancySlots(LocalTime.of(18, 36), 77));
        // 09:00–10:00 = exactly 2 slots
        assertEquals(2, AppTimeReference.occupancySlots(LocalTime.of(9, 0), 60));
        // 09:01–09:02 fits inside the 09:00–09:30 cell, so it covers 1 cell.
        assertEquals(1, AppTimeReference.occupancySlots(LocalTime.of(9, 1), 1));
        // 09:29–09:31 straddles two cells.
        assertEquals(2, AppTimeReference.occupancySlots(LocalTime.of(9, 29), 2));
    }

    @Test
    void occupancySlots_neverBelowOne() {
        assertEquals(1, AppTimeReference.occupancySlots(LocalTime.of(9, 29), 1));
        assertEquals(1, AppTimeReference.occupancySlots(null, 1));
    }

    @Test
    void rangeInside_acceptsFullyContained() {
        assertTrue(AppTimeReference.rangeInside(MON, LocalTime.of(9, 0), 60,
                MON, LocalTime.of(9, 15), 30));
        assertTrue(AppTimeReference.rangeInside(MON, LocalTime.of(9, 0), 60,
                MON, LocalTime.of(9, 0), 60));
    }

    @Test
    void rangeInside_rejectsOverhangPastBounds() {
        assertFalse(AppTimeReference.rangeInside(MON, LocalTime.of(9, 0), 60,
                MON, LocalTime.of(8, 45), 30));
        assertFalse(AppTimeReference.rangeInside(MON, LocalTime.of(9, 0), 60,
                MON, LocalTime.of(9, 45), 30));
        assertFalse(AppTimeReference.rangeInside(MON, LocalTime.of(9, 0), 60,
                MON.plusDays(1), LocalTime.of(9, 0), 30));
    }

    @Test
    void rangeInside_rejectsBadInput() {
        assertFalse(AppTimeReference.rangeInside(null, LocalTime.of(9, 0), 60,
                MON, LocalTime.of(9, 0), 30));
        assertFalse(AppTimeReference.rangeInside(MON, LocalTime.of(9, 0), 60,
                MON, LocalTime.of(9, 0), 0));
        assertFalse(AppTimeReference.rangeInside(MON, LocalTime.of(9, 0), 0,
                MON, LocalTime.of(9, 0), 1));
    }

    @Test
    void getStartOfWeek_isMonday() {
        assertEquals(MON, AppTimeReference.getStartOfWeek(MON));
        assertEquals(MON, AppTimeReference.getStartOfWeek(MON.plusDays(3)));
        assertEquals(MON, AppTimeReference.getStartOfWeek(MON.plusDays(6)));
        assertEquals(MON.plusDays(7), AppTimeReference.getStartOfWeek(MON.plusDays(7)));
    }

    @Test
    void relativeMinutes_isOffsetFromSixAm() {
        assertEquals(0, AppTimeReference.relativeMinutes(LocalTime.of(6, 0)));
        assertEquals(180, AppTimeReference.relativeMinutes(LocalTime.of(9, 0)));
        assertEquals(18 * 60, AppTimeReference.relativeMinutes(LocalTime.MIDNIGHT));
    }

    @Test
    void formatSlotLabel_marksNextCalendarDay() {
        assertEquals("06:00", AppTimeReference.formatSlotLabel(0));
        assertEquals("05:30 +", AppTimeReference.formatSlotLabel(47));
    }

    @Test
    void minutesAndHoursConversions_agree() {
        assertEquals(1.5, AppTimeReference.minutesToHours(90));
        // The double overload is only reached via a double-typed argument.
        double minutes = 90.0;
        assertEquals(1.5, AppTimeReference.minutesToHours(minutes));
        assertEquals(1.0, AppTimeReference.slotsToHours(2));
        assertEquals(1.0, AppTimeReference.normalizeDurationHours(1));
    }

    @Test
    void getCurrentDateTime_followsInjectedClock() {
        Clock saved = Clock.systemDefaultZone();
        try {
            AppTimeReference.setClock(Clock.fixed(
                    Instant.parse("2026-10-12T04:00:00Z"),
                    ZoneId.of("UTC")));
            assertEquals(LocalDate.of(2026, 10, 12), AppTimeReference.getCurrentDate());
            assertEquals(LocalTime.of(4, 0), AppTimeReference.getCurrentTime());
            // 04:00 is before 06:00, so "now" belongs to the previous column.
            assertEquals(LocalDate.of(2026, 10, 11), AppTimeReference.currentColumnDate());
            assertEquals(44, AppTimeReference.currentSlotIndex());
        } finally {
            AppTimeReference.setClock(saved);
        }
    }
}
