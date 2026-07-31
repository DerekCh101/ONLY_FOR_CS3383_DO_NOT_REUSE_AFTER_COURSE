package org.example;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;

public final class AppTimeReference {

    private static Clock clock = Clock.systemDefaultZone();

    public static final int MINUTES_PER_SLOT = 30;
    public static final int SLOTS_PER_HOUR = 60 / MINUTES_PER_SLOT;

    public static final int GRID_START_HOUR = 6;
    public static final int GRID_START_MINUTE = 0;
    public static final int GRID_START_MINUTES =
            GRID_START_HOUR * 60 + GRID_START_MINUTE; // 360

    public static final int GRID_END_MINUTES = 24 * 60; // midnight cut

    /** 06:00 → 23:30 inclusive → 36 slots */
    public static final int SLOTS_PER_DAY =
            (GRID_END_MINUTES - GRID_START_MINUTES) / MINUTES_PER_SLOT; // 36

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

    public static void setClock(Clock newClock) {
        clock = newClock;
    }

    public static LocalTime slotToLocalTime(int slotIndex) {
        if (slotIndex < 0) slotIndex = 0;
        if (slotIndex >= SLOTS_PER_DAY) slotIndex = SLOTS_PER_DAY - 1;
        int totalMinutes = GRID_START_MINUTES + slotIndex * MINUTES_PER_SLOT;
        return LocalTime.of(totalMinutes / 60, totalMinutes % 60);
    }

    public static int localTimeToSlot(LocalTime time) {
        int totalMinutes = time.getHour() * 60 + time.getMinute();
        int relative = totalMinutes - GRID_START_MINUTES;
        if (relative < 0) return 0;
        int slot = relative / MINUTES_PER_SLOT;
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
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    public static int currentSlotIndex() {
        return localTimeToSlot(getCurrentTime());
    }
}