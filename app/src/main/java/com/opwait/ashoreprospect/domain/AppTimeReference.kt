package com.opwait.ashoreprospect.domain

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** Port of org.ashoreprospect.AppTimeReference (JavaFX desktop). */
object AppTimeReference {
    const val MINUTES_PER_SLOT = 30
    const val SLOTS_PER_HOUR = 60 / MINUTES_PER_SLOT
    const val GRID_START_HOUR = 6
    const val GRID_START_MINUTE = 0
    val GRID_START_MINUTES = GRID_START_HOUR * 60 + GRID_START_MINUTE
    const val MINUTES_PER_DAY = 24 * 60
    const val SLOTS_PER_DAY = MINUTES_PER_DAY / MINUTES_PER_SLOT
    const val TOTAL_DAY_HORIZON = 365 * 5

    private var clock: Clock = Clock.systemDefaultZone()

    fun setClock(newClock: Clock) {
        clock = newClock
    }

    fun getStartOfWeek(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun getCurrentDate(): LocalDate = LocalDate.now(clock)

    fun getCurrentTime(): LocalTime = LocalTime.now(clock)

    fun getCurrentDateTime(): LocalDateTime = LocalDateTime.now(clock)

    fun slotIsNextCalendarDay(slotIndex: Int): Boolean {
        val total = GRID_START_MINUTES + slotIndex * MINUTES_PER_SLOT
        return total >= MINUTES_PER_DAY
    }

    fun calendarDateForSlot(columnDate: LocalDate?, slotIndex: Int): LocalDate? {
        if (columnDate == null) return null
        return if (slotIsNextCalendarDay(slotIndex)) columnDate.plusDays(1) else columnDate
    }

    fun dateTimeForSlot(columnDate: LocalDate, slotIndex: Int): LocalDateTime =
        LocalDateTime.of(calendarDateForSlot(columnDate, slotIndex), slotToLocalTime(slotIndex))

    fun columnDateFor(placedDate: LocalDate?, placedTime: LocalTime?): LocalDate? {
        if (placedDate == null || placedTime == null) return placedDate
        val mins = placedTime.hour * 60 + placedTime.minute
        return if (mins < GRID_START_MINUTES) placedDate.minusDays(1) else placedDate
    }

    fun slotToLocalTime(slotIndex: Int): LocalTime {
        var idx = slotIndex
        if (idx < 0) idx = 0
        if (idx >= SLOTS_PER_DAY) idx = SLOTS_PER_DAY - 1
        val totalMinutes = GRID_START_MINUTES + idx * MINUTES_PER_SLOT
        val minutesInDay = totalMinutes % MINUTES_PER_DAY
        return LocalTime.of(minutesInDay / 60, minutesInDay % 60)
    }

    fun localTimeToSlot(time: LocalTime): Int {
        val totalMinutes = time.hour * 60 + time.minute
        val relative = if (totalMinutes < GRID_START_MINUTES) {
            (MINUTES_PER_DAY - GRID_START_MINUTES) + totalMinutes
        } else {
            totalMinutes - GRID_START_MINUTES
        }
        var slot = relative / MINUTES_PER_SLOT
        if (slot < 0) slot = 0
        if (slot >= SLOTS_PER_DAY) slot = SLOTS_PER_DAY - 1
        return slot
    }

    fun normalizeDurationHours(durationHours: Double): Double {
        var stepped = kotlin.math.round(durationHours * 2.0) / 2.0
        if (stepped < 0.5) stepped = 0.5
        return stepped
    }

    /** Exact minutes (1–24h). Grid occupancy still rounds to 30-minute cells. */
    fun hoursToMinutes(durationHours: Double): Int {
        var m = kotlin.math.round(durationHours * 60.0).toInt()
        if (m < 1) m = 1
        if (m > MINUTES_PER_DAY) m = MINUTES_PER_DAY
        return m
    }

    fun clampMinutes(durationMinutes: Int): Int = durationMinutes.coerceIn(1, MINUTES_PER_DAY)

    fun relativeMinutes(time: LocalTime): Int {
        val totalMinutes = time.hour * 60 + time.minute
        return if (totalMinutes < GRID_START_MINUTES) {
            (MINUTES_PER_DAY - GRID_START_MINUTES) + totalMinutes
        } else {
            totalMinutes - GRID_START_MINUTES
        }
    }

    fun minutesToSlots(durationMinutes: Int): Int {
        val slots = (clampMinutes(durationMinutes) + MINUTES_PER_SLOT - 1) / MINUTES_PER_SLOT
        return maxOf(1, slots)
    }

    fun minutesToSlots(durationMinutes: Double): Int =
        minutesToSlots(kotlin.math.round(durationMinutes).toInt())

    /** Grid occupancy: floor start to a slot, ceil the end. 12:23+125min → 12:00–14:30. */
    fun occupancySlots(start: LocalTime?, durationMinutes: Int): Int {
        if (start == null) return minutesToSlots(durationMinutes)
        val startRel = relativeMinutes(start)
        val endRel = startRel + clampMinutes(durationMinutes)
        val startSlot = startRel / MINUTES_PER_SLOT
        var endSlot = (endRel + MINUTES_PER_SLOT - 1) / MINUTES_PER_SLOT
        if (endRel % MINUTES_PER_SLOT == 0) endSlot = endRel / MINUTES_PER_SLOT
        return maxOf(1, endSlot - startSlot)
    }

    fun minutesBetween(start: LocalTime, end: LocalTime): Int {
        val a = start.hour * 60 + start.minute
        val b = end.hour * 60 + end.minute
        var d = b - a
        if (d <= 0) d += MINUTES_PER_DAY
        if (d > MINUTES_PER_DAY) d = MINUTES_PER_DAY
        return maxOf(1, d)
    }

    fun minutesToHours(durationMinutes: Int): Double = durationMinutes / 60.0

    fun formatSlotLabel(slotIndex: Int): String {
        val t = slotToLocalTime(slotIndex)
        val hhmm = "%02d:%02d".format(t.hour, t.minute)
        return if (slotIsNextCalendarDay(slotIndex)) "$hhmm +" else hhmm
    }

    fun currentSlotIndex(): Int = localTimeToSlot(getCurrentTime())

    /** True when this 30-minute cell has already ended relative to now. */
    fun isSlotInPast(columnDate: LocalDate, slot: Int): Boolean {
        val nowCol = currentColumnDate()
        val nowSlot = currentSlotIndex()
        return columnDate.isBefore(nowCol) || (columnDate == nowCol && slot < nowSlot)
    }

    fun currentColumnDate(): LocalDate =
        columnDateFor(getCurrentDate(), getCurrentTime())!!

    /** Week that contains the current 06:00 column (00:00–05:30 belong to yesterday). */
    fun currentWeekStart(): LocalDate = getStartOfWeek(currentColumnDate())

    fun currentMonth(): java.time.YearMonth = java.time.YearMonth.from(currentColumnDate())

    /** Calendar day where `time` is still now or later (Add task 09:00 / 17:00 defaults). */
    fun nextOpenDateFor(time: LocalTime): LocalDate {
        val today = getCurrentDate()
        return if (time.isBefore(getCurrentTime())) today.plusDays(1) else today
    }
}
