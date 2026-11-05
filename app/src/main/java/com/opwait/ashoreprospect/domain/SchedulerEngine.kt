package com.opwait.ashoreprospect.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Port of org.ashoreprospect.SchedulerEngine */
class SchedulerEngine(
    private val session: SchedulerSession,
    private val daysGrid: MutableMap<LocalDate, DayGrid>,
) {
    private val baseDate: LocalDate get() = session.baseDate
    private val unscheduledTasks get() = session.unscheduledTasks

    fun scheduleTasks(tasks: List<Task>?) {
        if (tasks.isNullOrEmpty()) {
            dropActiveUnscheduledOnly()
            return
        }
        dropActiveUnscheduledOnly()
        val deadlineTasks = ArrayList<Task>()
        val flexibleTasks = ArrayList<Task>()
        for (task in tasks) {
            if (task.isQuick() || isPast(task)) continue
            when {
                task.hasDeadline() -> deadlineTasks.add(task)
                task.isFlexible() -> flexibleTasks.add(task)
            }
        }
        deadlineTasks.sortWith { t1, t2 ->
            val dateCompare = t1.deadlineDate!!.compareTo(t2.deadlineDate!!)
            if (dateCompare != 0) dateCompare else t2.durationSlots.compareTo(t1.durationSlots)
        }
        placeDeadlineTasks(deadlineTasks)
        placeFlexibleTasks(flexibleTasks)
        session.pendingTasks.removeAll { !isPast(it) }
        session.unscheduledTasks.removeAll(session.placedTasks)
    }

    fun placeAlreadyPlacedTasks(tasks: List<Task>) {
        if (tasks.isEmpty()) return
        for (task in tasks) {
            if (task.isQuick()) continue
            val date = task.placedDate ?: continue
            val time = task.placedTime ?: continue
            val column = AppTimeReference.columnDateFor(date, time) ?: continue
            daysGrid.putIfAbsent(column, DayGrid())
            val day = daysGrid.getValue(column)
            val slot = AppTimeReference.localTimeToSlot(time)
            if (isPast(task)) {
                pinOnGrid(day, slot, task)
                session.pendingTasks.remove(task)
                session.unscheduledTasks.remove(task)
                if (task !in session.placedTasks) session.placedTasks.add(task)
                continue
            }
            if (!day.canPlace(task, column, slot, ignoreOverload = true, ignoreStartInPast = true) ||
                !respectsDeadlineTime(task, column, slot) ||
                (task.isFlexible() && hitsBlocked(column, slot, task))
            ) {
                markUnscheduled(task)
                continue
            }
            day.placeTask(slot, task)
            if (task !in session.placedTasks) session.placedTasks.add(task)
            session.pendingTasks.remove(task)
            session.unscheduledTasks.remove(task)
        }
    }

    private fun pinOnGrid(day: DayGrid, slot: Int, task: Task) {
        val count = task.durationSlots
        if (slot < 0 || slot + count > AppTimeReference.SLOTS_PER_DAY) return
        val grid = day.returnDayGrid()
        val already = (0 until count).all { grid[slot + it]?.id == task.id }
        if (!already) day.placeTask(slot, task)
    }

    private fun dropActiveUnscheduledOnly() {
        session.unscheduledTasks.removeAll { !isPast(it) }
    }

    private fun searchStart(): LocalDate {
        val col = AppTimeReference.currentColumnDate()
        return if (col.isAfter(baseDate)) col else baseDate
    }

    private fun placeDeadlineTasks(tasks: ArrayList<Task>) {
        val from = searchStart()
        for (task in tasks) {
            val dl = task.deadlineDate
            if (dl == null) {
                markUnscheduled(task)
                continue
            }
            var lastDay = ChronoUnit.DAYS.between(baseDate, dl) + 1
            if (lastDay < 0) {
                markUnscheduled(task)
                continue
            }
            if (lastDay >= AppTimeReference.TOTAL_DAY_HORIZON) {
                lastDay = (AppTimeReference.TOTAL_DAY_HORIZON - 1).toLong()
            }
            if (!tryPlaceDeadline(task, from, lastDay, avoidBlocked = true) &&
                !tryPlaceDeadline(task, from, lastDay, avoidBlocked = false)
            ) {
                markUnscheduled(task)
            }
        }
    }

    private fun tryPlaceDeadline(
        task: Task,
        fromDay: LocalDate,
        lastDay: Long,
        avoidBlocked: Boolean,
    ): Boolean {
        val limit = baseDate.plusDays(lastDay)
        var column = fromDay
        while (!column.isAfter(limit)) {
            daysGrid.putIfAbsent(column, DayGrid())
            val theDay = daysGrid.getValue(column)
            val startSlot = firstSlotOn(column)
            for (slot in startSlot until AppTimeReference.SLOTS_PER_DAY) {
                if (theDay.canPlace(task, column, slot, ignoreOverload = false) &&
                    respectsDeadlineTime(task, column, slot) &&
                    (!avoidBlocked || !hitsBlocked(column, slot, task))
                ) {
                    applyPlacement(theDay, task, column, slot)
                    return true
                }
            }
            column = column.plusDays(1)
        }
        return false
    }

    private fun placeFlexibleTasks(tasks: ArrayList<Task>) {
        val from = searchStart()
        val limit = baseDate.plusDays(AppTimeReference.TOTAL_DAY_HORIZON.toLong())
        for (task in tasks) {
            var placed = false
            var column = from
            while (column.isBefore(limit) && !placed) {
                daysGrid.putIfAbsent(column, DayGrid())
                val theDay = daysGrid.getValue(column)
                val startSlot = firstSlotOn(column)
                for (slot in startSlot until AppTimeReference.SLOTS_PER_DAY) {
                    if (theDay.canPlace(task, column, slot, ignoreOverload = true) &&
                        !hitsBlocked(column, slot, task)
                    ) {
                        applyPlacement(theDay, task, column, slot)
                        placed = true
                        break
                    }
                }
                column = column.plusDays(1)
            }
            if (!placed) markUnscheduled(task)
        }
    }

    private fun applyPlacement(day: DayGrid, task: Task, column: LocalDate, slot: Int) {
        day.placeTask(slot, task)
        val start = AppTimeReference.dateTimeForSlot(column, slot)
        task.placedDate = start.toLocalDate()
        task.placedTime = start.toLocalTime()
        if (task !in session.placedTasks) session.placedTasks.add(task)
    }

    private fun firstSlotOn(column: LocalDate): Int {
        val nowCol = AppTimeReference.currentColumnDate()
        if (column.isBefore(nowCol)) return AppTimeReference.SLOTS_PER_DAY
        if (column != nowCol) return 0
        val start = AppTimeReference.currentSlotIndex() + 1
        return minOf(AppTimeReference.SLOTS_PER_DAY, maxOf(0, start))
    }

    private fun markUnscheduled(task: Task) {
        if (isPast(task)) return
        removeFromGrid(task)
        session.placedTasks.remove(task)
        task.placedDate = null
        task.placedTime = null
        if (task !in unscheduledTasks) {
            unscheduledTasks.add(task)
            task.lifeCycle = TaskLifeCycle.UNSCHEDULED
        }
    }

    private fun removeFromGrid(task: Task) {
        for (day in daysGrid.values) {
            day.clearTaskFromGrid(task)
        }
    }

    private fun respectsDeadlineTime(task: Task, column: LocalDate, startSlot: Int): Boolean {
        if (!task.hasDeadline() || task.deadlineDate == null) return true
        if (task.deadlineTime == null) {
            val startDate = AppTimeReference.calendarDateForSlot(column, startSlot)
            return !startDate!!.isAfter(task.deadlineDate)
        }
        val start = AppTimeReference.dateTimeForSlot(column, startSlot)
        var durationMin = kotlin.math.round(task.durationMinutes).toInt()
        if (durationMin <= 0) durationMin = AppTimeReference.MINUTES_PER_SLOT
        val finish = start.plusMinutes(durationMin.toLong())
        val deadline = LocalDateTime.of(task.deadlineDate, task.deadlineTime)
        return !finish.isAfter(deadline)
    }

    private fun isPast(task: Task) = task.lifeCycle == TaskLifeCycle.PAST

    private fun hitsBlocked(column: LocalDate, slot: Int, task: Task): Boolean =
        session.blockedPeriod.spans(column, slot, task.durationSlots)
}

class SchedulerSession {
    val pendingTasks = ArrayList<Task>()
    val placedTasks = ArrayList<Task>()
    val unscheduledTasks = ArrayList<Task>()
    val daysGrid = sortedMapOf<LocalDate, DayGrid>()
    val blockedPeriod = BlockedTimePeriod()
    var baseDate: LocalDate = AppTimeReference.currentColumnDate()
    lateinit var engine: SchedulerEngine

    init {
        engine = SchedulerEngine(this, daysGrid)
    }

    fun updateSchedule() {
        val toSchedule = ArrayList<Task>()
        for (t in placedTasks.toList()) {
            if (!t.isQuick() && !t.isFixedTime()) toSchedule.add(t)
        }
        for (t in pendingTasks.toList()) {
            if (!t.isQuick() && !t.isFixedTime() && t !in toSchedule) toSchedule.add(t)
        }
        for (t in unscheduledTasks.toList()) {
            if (!t.isQuick() && !t.isFixedTime() && t !in toSchedule) toSchedule.add(t)
        }
        for (t in toSchedule) {
            placedTasks.remove(t)
            pendingTasks.remove(t)
            unscheduledTasks.remove(t)
            for (day in daysGrid.values) {
                day.clearTaskFromGrid(t)
            }
            t.placedDate = null
            t.placedTime = null
        }
        engine.scheduleTasks(toSchedule)
    }

    fun reloadFromTasks(all: List<Task>) {
        pendingTasks.clear()
        placedTasks.clear()
        unscheduledTasks.clear()
        daysGrid.clear()
        for (task in all) {
            if (task.isQuick()) continue
            val placedDate = task.placedDate
            val placedTime = task.placedTime
            if (placedDate != null && placedTime != null) {
                val column = AppTimeReference.columnDateFor(placedDate, placedTime)!!
                daysGrid.putIfAbsent(column, DayGrid())
                val day = daysGrid.getValue(column)
                val slot = AppTimeReference.localTimeToSlot(placedTime)
                day.placeTask(slot, task)
                placedTasks.add(task)
                RefreshLifeCycle.refresh(task)
            } else if (task.lifeCycle == TaskLifeCycle.UNSCHEDULED) {
                unscheduledTasks.add(task)
            } else {
                pendingTasks.add(task)
            }
        }
    }
}
