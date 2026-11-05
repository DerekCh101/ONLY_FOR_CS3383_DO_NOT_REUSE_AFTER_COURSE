package com.opwait.ashoreprospect.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

data class BlockedTime(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "blocked",
    var weekday: Int? = null,
    var specificDate: LocalDate? = null,
    var startTime: LocalTime,
    var endTime: LocalTime,
    var lastUpdatedAt: Instant? = Instant.now(),
    var isException: Boolean = false,
) {
    fun isDaily(): Boolean = specificDate == null && weekday == null && !isException

    fun appliesOn(date: LocalDate): Boolean {
        if (isException) return date == specificDate
        if (isDaily()) return true
        if (specificDate != null) return date == specificDate
        val wd = weekday ?: return false
        return date.dayOfWeek == weekdayToDayOfWeek(wd)
    }

    fun touch() {
        lastUpdatedAt = Instant.now()
    }

    /** Dated / exception rows drop after their end plus 5 minutes. Daily and weekday never expire. */
    fun isDatedExpired(
        now: LocalDateTime = AppTimeReference.getCurrentDateTime(),
        graceMinutes: Long = 5,
    ): Boolean {
        val day = specificDate ?: return false
        val end = LocalDateTime.of(day, endTime)
        return !now.isBefore(end.plusMinutes(graceMinutes))
    }

    companion object {
        fun daily(title: String, start: LocalTime, end: LocalTime): BlockedTime =
            BlockedTime(title = title.ifBlank { "blocked" }, startTime = start, endTime = end)

        fun once(date: LocalDate, title: String, start: LocalTime, end: LocalTime): BlockedTime =
            BlockedTime(
                title = title.ifBlank { "blocked" },
                specificDate = date,
                startTime = start,
                endTime = end,
            )

        fun weekly(day: DayOfWeek, title: String, start: LocalTime, end: LocalTime): BlockedTime =
            BlockedTime(
                title = title.ifBlank { "blocked" },
                weekday = weekdayToInt(day),
                startTime = start,
                endTime = end,
            )

        fun dailyRange(
            from: LocalDate,
            to: LocalDate,
            title: String,
            start: LocalTime,
            end: LocalTime,
            exception: Boolean = false,
        ): List<BlockedTime> {
            val out = ArrayList<BlockedTime>()
            var day = from
            while (!day.isAfter(to)) {
                out.add(
                    BlockedTime(
                        title = title.ifBlank { "blocked" },
                        specificDate = day,
                        startTime = start,
                        endTime = end,
                        isException = exception,
                    ),
                )
                day = day.plusDays(1)
            }
            return out
        }

        fun weekdayToInt(d: DayOfWeek): Int = d.value % 7

        fun weekdayToDayOfWeek(weekday: Int): DayOfWeek =
            if (weekday == 0) DayOfWeek.SUNDAY else DayOfWeek.of(weekday)
    }
}

class BlockedTimePeriod(private val blockedPeriod: MutableList<BlockedTime> = ArrayList()) {
    fun all(): List<BlockedTime> = blockedPeriod.toList()

    fun replaceAll(rows: List<BlockedTime>) {
        blockedPeriod.clear()
        blockedPeriod.addAll(rows)
    }

    fun add(row: BlockedTime) {
        blockedPeriod.add(row)
    }

    fun remove(id: String) {
        blockedPeriod.removeAll { it.id == id }
    }

    fun isBlocked(date: LocalDate?, time: LocalTime?): Boolean {
        if (date == null || time == null) return false
        var dailyHit = false
        var specificHit = false
        var exceptionHit = false
        for (b in blockedPeriod) {
            if (time.isBefore(b.startTime) || !time.isBefore(b.endTime)) continue
            if (b.isException) {
                if (date == b.specificDate) exceptionHit = true
            } else if (b.isDaily()) {
                dailyHit = true
            } else if (b.appliesOn(date)) {
                specificHit = true
            }
        }
        if (specificHit) return true
        if (exceptionHit) return false
        return dailyHit
    }

    /** The blocking rule that wins for this clock, or null if free / exception. */
    fun covering(date: LocalDate?, time: LocalTime?): BlockedTime? {
        if (date == null || time == null) return null
        var daily: BlockedTime? = null
        var specific: BlockedTime? = null
        var exception = false
        for (b in blockedPeriod) {
            if (time.isBefore(b.startTime) || !time.isBefore(b.endTime)) continue
            if (b.isException) {
                if (date == b.specificDate) exception = true
            } else if (b.isDaily()) {
                daily = b
            } else if (b.appliesOn(date)) {
                specific = b
            }
        }
        if (specific != null) return specific
        if (exception) return null
        return daily
    }

    fun spans(column: LocalDate, startSlot: Int, durationSlots: Int): Boolean {
        val count = maxOf(1, durationSlots)
        for (i in 0 until count) {
            val slot = startSlot + i
            if (slot < 0 || slot >= AppTimeReference.SLOTS_PER_DAY) continue
            val date = AppTimeReference.calendarDateForSlot(column, slot)
            val time = AppTimeReference.slotToLocalTime(slot)
            if (isBlocked(date, time)) return true
        }
        return false
    }

    fun conflictMessage(candidate: BlockedTime): String? {
        for (existing in blockedPeriod) {
            if (existing.id == candidate.id) continue
            if (sameLayer(existing, candidate) && clockOverlap(existing, candidate)) {
                return "Overlaps existing: ${existing.title}"
            }
        }
        return null
    }

    companion object {
        fun sameLayer(a: BlockedTime, b: BlockedTime): Boolean {
            if (a.isException != b.isException) return false
            if (a.isException) return a.specificDate == b.specificDate
            if (a.isDaily() && b.isDaily()) return true
            if (a.isDaily() || b.isDaily()) return false
            if (a.weekday != null || b.weekday != null) return a.weekday == b.weekday
            return a.specificDate == b.specificDate
        }

        fun clockOverlap(a: BlockedTime, b: BlockedTime): Boolean =
            a.startTime.isBefore(b.endTime) && b.startTime.isBefore(a.endTime)
    }
}
