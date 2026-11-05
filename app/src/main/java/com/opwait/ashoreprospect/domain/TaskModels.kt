package com.opwait.ashoreprospect.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

enum class TaskLifeCycle { PAST, UNSCHEDULED, ONGOING }

enum class Workflow { FINISHED, UNFINISHED }

enum class TaskStateKind(val intValue: Int, val apiName: String) {
    FIXED(1, "Fixed"),
    DEADLINE(2, "Deadline"),
    FLEXIBLE(3, "Flexible");

    companion object {
        fun fromInt(v: Int): TaskStateKind = entries.firstOrNull { it.intValue == v } ?: FLEXIBLE
        fun fromApi(s: String?): TaskStateKind {
            if (s == null) return FLEXIBLE
            return entries.firstOrNull { it.apiName.equals(s, true) || it.intValue.toString() == s }
                ?: FLEXIBLE
        }
    }
}

data class Task(
    val id: String,
    var title: String,
    var durationMinutes: Double,
    var state: TaskStateKind,
    var startingDate: LocalDate? = null,
    var startingTime: LocalTime? = null,
    var deadlineDate: LocalDate? = null,
    var deadlineTime: LocalTime? = null,
    var placedDate: LocalDate? = null,
    var placedTime: LocalTime? = null,
    var lifeCycle: TaskLifeCycle = TaskLifeCycle.UNSCHEDULED,
    var workflow: Workflow = Workflow.UNFINISHED,
    var placementUpdatedAt: Instant? = null,
    var lastUpdatedAt: Instant? = null,
    var description: String = "",
    var category: String = "",
    var subcategory: String = "",
    var descriptionDone: Boolean = false,
    /** Node `parent_id`. Nested timed bits belong to that parent; they are not occupancy. */
    var parentId: String = "",
) {
    val durationSlots: Int
        get() = AppTimeReference.occupancySlots(
            placedTime,
            kotlin.math.round(durationMinutes).toInt(),
        )
    val durationHours: Double get() = AppTimeReference.minutesToHours(durationMinutes.toInt())

    fun isFixedTime() = state == TaskStateKind.FIXED
    fun hasDeadline() = state == TaskStateKind.DEADLINE
    fun isFlexible() = state == TaskStateKind.FLEXIBLE

    /** JavaFX `Task.isQuick()` — nested under a parent class, not a standalone grid task. */
    fun isQuick(): Boolean = parentId.isNotBlank()

    /** Desktop: only Deadline and Flexible can sit unscheduled. Fixed never does. */
    fun isUnscheduledPending(): Boolean =
        !isQuick() &&
            (placedDate == null || placedTime == null) &&
            workflow != Workflow.FINISHED &&
            (hasDeadline() || isFlexible())

    /** Unfinished Deadline/Flexible that already ended: Run scheduler carries them forward. */
    fun shouldCarryUnfinishedPast(): Boolean =
        !isQuick() &&
            !isFixedTime() &&
            workflow != Workflow.FINISHED &&
            lifeCycle == TaskLifeCycle.PAST

    fun touchLastUpdated() {
        lastUpdatedAt = Instant.now()
    }

    fun touchPlacementUpdated() {
        val now = Instant.now()
        placementUpdatedAt = now
        lastUpdatedAt = now
    }

    companion object {
        fun newTask(
            title: String,
            durationHours: Double,
            state: TaskStateKind,
            startingDate: LocalDate? = null,
            startingTime: LocalTime? = null,
            deadlineDate: LocalDate? = null,
            deadlineTime: LocalTime? = null,
        ): Task = Task(
            id = UUID.randomUUID().toString(),
            title = title,
            durationMinutes = AppTimeReference.hoursToMinutes(durationHours).toDouble(),
            state = state,
            startingDate = startingDate,
            startingTime = startingTime,
            deadlineDate = deadlineDate,
            deadlineTime = deadlineTime,
            lastUpdatedAt = Instant.now(),
        )
    }
}

object CarryUnfinishedPast {
    fun prepare(tasks: List<Task>): Pair<List<Task>, Int> {
        var carried = 0
        val out = tasks.map { raw ->
            val t = raw.copy()
            RefreshLifeCycle.refresh(t)
            if (t.shouldCarryUnfinishedPast()) {
                carried += 1
                t.placedDate = null
                t.placedTime = null
                t.lifeCycle = TaskLifeCycle.UNSCHEDULED
                t.touchPlacementUpdated()
            }
            t
        }
        return out to carried
    }
}

object RefreshLifeCycle {
    fun refresh(task: Task) {
        if (task.placedDate == null || task.placedTime == null) {
            task.lifeCycle = TaskLifeCycle.UNSCHEDULED
            return
        }
        val start = LocalDateTime.of(task.placedDate, task.placedTime)
        val mins = maxOf(1, kotlin.math.round(task.durationMinutes).toLong())
        val end = start.plusMinutes(mins)
        val now = AppTimeReference.getCurrentDateTime()
        task.lifeCycle = if (!end.isAfter(now)) TaskLifeCycle.PAST else TaskLifeCycle.ONGOING
    }
}

object ValidateAddTaskInput {
    fun validate(
        name: String?,
        durationHours: Double,
        state: TaskStateKind,
        startDate: LocalDate?,
        startTime: LocalTime?,
        deadlineDate: LocalDate?,
        deadlineTime: LocalTime?,
    ): Boolean {
        if (name.isNullOrBlank()) return false
        if (durationHours <= 0 || durationHours >= AppTimeReference.SLOTS_PER_DAY) return false
        val today = AppTimeReference.getCurrentDate()
        val now = AppTimeReference.getCurrentTime()
        return when (state) {
            TaskStateKind.FIXED -> {
                if (startDate == null || startTime == null) return false
                if (deadlineDate != null || deadlineTime != null) return false
                if (startDate.isBefore(today)) return false
                !(startDate == today && startTime.isBefore(now))
            }
            TaskStateKind.DEADLINE -> {
                if (deadlineDate == null || deadlineTime == null) return false
                if (startDate != null || startTime != null) return false
                if (deadlineDate.isBefore(today)) return false
                !(deadlineDate == today && deadlineTime.isBefore(now))
            }
            TaskStateKind.FLEXIBLE -> {
                startDate == null && startTime == null && deadlineDate == null && deadlineTime == null
            }
        }
    }
}

object TaskDue {
    fun isOverdue(task: Task): Boolean {
        if (task.isQuick() || task.workflow == Workflow.FINISHED) return false
        val date = task.deadlineDate ?: return false
        val time = task.deadlineTime ?: LocalTime.MAX
        return LocalDateTime.of(date, time).isBefore(AppTimeReference.getCurrentDateTime())
    }
}

/** JavaFX nested timed bits: they belong to a parent and must not occupy the week grid. */
object NestedTasks {
    fun ofParent(all: List<Task>, parent: Task): List<Task> {
        if (parent.isQuick()) return emptyList()
        val id = parent.id
        return all.filter { it.parentId == id }.sortedWith(
            compareBy<Task> { it.placedTime == null }.thenBy { it.placedTime },
        )
    }

    fun mainTask(all: List<Task>, task: Task): Task {
        if (!task.isQuick()) return task
        return all.find { it.id == task.parentId } ?: task
    }
}

object MonthCellTasks {
    fun onDay(tasks: List<Task>, date: LocalDate): List<Task> {
        val placed = tasks.filter { t ->
            if (t.isQuick()) return@filter false
            val pd = t.placedDate ?: return@filter false
            val pt = t.placedTime ?: return@filter false
            AppTimeReference.columnDateFor(pd, pt) == date
        }
        val placedIds = placed.mapTo(HashSet()) { it.id }
        val dueUnscheduled = tasks.filter { t ->
            t.id !in placedIds &&
                t.isUnscheduledPending() &&
                t.hasDeadline() &&
                t.deadlineDate == date
        }
        return placed + dueUnscheduled
    }
}

object TaskPlacement {
    fun placeError(
        task: Task,
        date: LocalDate,
        time: LocalTime,
        others: List<Task>,
        blocked: BlockedTimePeriod,
    ): String? {
        val nowFloor = AppTimeReference.getCurrentDateTime().withSecond(0).withNano(0)
        if (LocalDateTime.of(date, time).isBefore(nowFloor)) {
            return "Cannot create a task in the past."
        }
        val probe = task.copy(placedDate = date, placedTime = time)
        return durationChangeError(probe, probe.durationHours, others, blocked)
    }

    fun durationChangeError(
        task: Task,
        hours: Double,
        others: List<Task>,
        blocked: BlockedTimePeriod,
    ): String? {
        if (hours <= 0 || hours > 24) return "Invalid task fields"
        val mins = AppTimeReference.hoursToMinutes(hours)
        val pd = task.placedDate
        val pt = task.placedTime
        if (pd == null || pt == null) return null
        val probe = task.copy(durationMinutes = mins.toDouble())
        val slots = probe.durationSlots
        val col = AppTimeReference.columnDateFor(pd, pt) ?: return "Cannot place task in that slot"
        val start = AppTimeReference.localTimeToSlot(pt)
        if (start + slots > AppTimeReference.SLOTS_PER_DAY) return "Duration does not fit this day"
        if (task.isFlexible() && blocked.spans(col, start, slots)) {
            return "Flexible tasks cannot sit in blocked time"
        }
        if (task.hasDeadline() && task.deadlineDate != null && task.deadlineTime != null) {
            val startDt = LocalDateTime.of(pd, pt)
            val finish = startDt.plusMinutes(mins.toLong())
            if (finish.isAfter(LocalDateTime.of(task.deadlineDate, task.deadlineTime))) {
                return "Duration would pass the deadline"
            }
        }
        for (slot in start until (start + slots)) {
            for (other in others) {
                if (other.id == task.id || other.isQuick()) continue
                val opd = other.placedDate ?: continue
                val opt = other.placedTime ?: continue
                val ocol = AppTimeReference.columnDateFor(opd, opt) ?: continue
                if (ocol != col) continue
                val os = AppTimeReference.localTimeToSlot(opt)
                if (slot in os until (os + other.durationSlots)) {
                    return "Cannot place task in that slot"
                }
            }
        }
        return null
    }
}
