package org.example;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;

public final class AppTimeReference {

    private static Clock clock = Clock.systemDefaultZone();

    public static final int MINUTES_PER_SLOT = 30;
    public static final int SLOTS_PER_HOUR = 60 / MINUTES_PER_SLOT;

    public static final int GRID_START_HOUR = 6;
    public static final int GRID_START_MINUTE = 0;
    public static final int GRID_START_MINUTES =
            GRID_START_HOUR * 60 + GRID_START_MINUTE;

    public static final int MINUTES_PER_DAY = 24 * 60;

    /**
     * One column = 06:00 → next calendar morning 05:30 inclusive.
     * 24 hours / 30 min = 48 slots.
     * Slots 0..35  = 06:00..23:30 same calendar date as the column.
     * Slots 36..47 = 00:00..05:30 next calendar date.
     */
    public static final int SLOTS_PER_DAY = MINUTES_PER_DAY / MINUTES_PER_SLOT;

    public static final int TOTAL_DAY_HORIZON = 365 * 5;

    private AppTimeReference() {}

    public static LocalDate getStartOfWeek(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static LocalDate getCurrentDate() {
        return LocalDate.now(clock);
    }

    public static LocalTime getCurrentTime() {
        return LocalTime.now(clock);
    }

    public static LocalDateTime getCurrentDateTime() {
        return LocalDateTime.now(clock);
    }

    public static void setClock(Clock newClock) {
        clock = newClock;
    }

    public static boolean slotIsNextCalendarDay(int slotIndex) {
        int total = GRID_START_MINUTES + slotIndex * MINUTES_PER_SLOT;
        return total >= MINUTES_PER_DAY;
    }

    /** Calendar date of the clock time sitting in this column slot. */
    public static LocalDate calendarDateForSlot(LocalDate columnDate, int slotIndex) {
        if (columnDate == null) return null;
        return slotIsNextCalendarDay(slotIndex) ? columnDate.plusDays(1) : columnDate;
    }

    public static LocalDateTime dateTimeForSlot(LocalDate columnDate, int slotIndex) {
        return LocalDateTime.of(calendarDateForSlot(columnDate, slotIndex), slotToLocalTime(slotIndex));
    }

    /**
     * Which week-column a stored placedDate+placedTime belongs on.
     * 00:00–05:30 lives on the previous day's column.
     */
    public static LocalDate columnDateFor(LocalDate placedDate, LocalTime placedTime) {
        if (placedDate == null || placedTime == null) return placedDate;
        int mins = placedTime.getHour() * 60 + placedTime.getMinute();
        if (mins < GRID_START_MINUTES) return placedDate.minusDays(1);
        return placedDate;
    }

    public static LocalTime slotToLocalTime(int slotIndex) {
        if (slotIndex < 0) slotIndex = 0;
        if (slotIndex >= SLOTS_PER_DAY) slotIndex = SLOTS_PER_DAY - 1;
        int totalMinutes = GRID_START_MINUTES + slotIndex * MINUTES_PER_SLOT;
        int minutesInDay = totalMinutes % MINUTES_PER_DAY;
        return LocalTime.of(minutesInDay / 60, minutesInDay % 60);
    }

    public static int localTimeToSlot(LocalTime time) {
        int totalMinutes = time.getHour() * 60 + time.getMinute();
        int relative;
        if (totalMinutes < GRID_START_MINUTES) {
            relative = (MINUTES_PER_DAY - GRID_START_MINUTES) + totalMinutes;
        } else {
            relative = totalMinutes - GRID_START_MINUTES;
        }
        int slot = relative / MINUTES_PER_SLOT;
        if (slot < 0) return 0;
        if (slot >= SLOTS_PER_DAY) return SLOTS_PER_DAY - 1;
        return slot;
    }

    public static double normalizeDurationHours(double durationHours) {
        double stepped = Math.round(durationHours * 2.0) / 2.0;
        if (stepped < 0.5) stepped = 0.5;
        return stepped;
    }

    public static int hoursToMinutes(double durationHours) {
        return (int) Math.round(normalizeDurationHours(durationHours) * 60.0);
    }

    public static int minutesToSlots(int durationMinutes) {
        int slots = durationMinutes / MINUTES_PER_SLOT;
        return Math.max(1, slots);
    }

    public static int minutesToSlots(double durationMinutes) {
        return minutesToSlots((int) Math.round(durationMinutes));
    }

    public static int hoursToSlots(double durationHours) {
        return minutesToSlots(hoursToMinutes(durationHours));
    }

    public static double minutesToHours(int durationMinutes) {
        return durationMinutes / 60.0;
    }

    public static double minutesToHours(double durationMinutes) {
        return durationMinutes / 60.0;
    }

    public static double slotsToHours(int slots) {
        return slots * (MINUTES_PER_SLOT / 60.0);
    }

    public static String formatSlotLabel(int slotIndex) {
        LocalTime t = slotToLocalTime(slotIndex);
        String hhmm = String.format("%02d:%02d", t.getHour(), t.getMinute());
        return slotIsNextCalendarDay(slotIndex) ? hhmm + " +" : hhmm;
    }

    public static int currentSlotIndex() {
        return localTimeToSlot(getCurrentTime());
    }

    /** Column that contains "now" (before 06:00 that is yesterday's column). */
    public static LocalDate currentColumnDate() {
        return columnDateFor(getCurrentDate(), getCurrentTime());
    }
}