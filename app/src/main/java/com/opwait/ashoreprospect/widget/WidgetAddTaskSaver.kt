package com.opwait.ashoreprospect.widget

import android.content.Context
import com.opwait.ashoreprospect.data.TaskRepository
import com.opwait.ashoreprospect.domain.AppTimeReference
import com.opwait.ashoreprospect.domain.BlockedTimePeriod
import com.opwait.ashoreprospect.domain.RefreshLifeCycle
import com.opwait.ashoreprospect.domain.SchedulerSession
import com.opwait.ashoreprospect.domain.Task
import com.opwait.ashoreprospect.domain.TaskPlacement
import com.opwait.ashoreprospect.domain.TaskStateKind
import com.opwait.ashoreprospect.domain.ValidateAddTaskInput
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persist a task from the home-screen quick-add sheet (no MainActivity / scheduler UI). */
object WidgetAddTaskSaver {
    suspend fun save(
        context: Context,
        title: String,
        durationHours: Double,
        state: TaskStateKind,
        startDate: LocalDate?,
        startTime: LocalTime?,
        deadlineDate: LocalDate?,
        deadlineTime: LocalTime?,
        placedDate: LocalDate?,
        placedTime: LocalTime?,
        description: String,
        descriptionDone: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        if (!ValidateAddTaskInput.validate(
                title, durationHours, state,
                startDate, startTime, deadlineDate, deadlineTime,
            )
        ) {
            return@withContext "Invalid task fields"
        }
        val task = Task.newTask(
            title.trim(), durationHours, state,
            startDate, startTime, deadlineDate, deadlineTime,
        )
        task.description = description
        task.descriptionDone = descriptionDone

        val repo = TaskRepository(context.applicationContext)
        val snap = repo.loadLocalWorkspace()
        val blocked = BlockedTimePeriod().also { it.replaceAll(snap.blocked) }

        val placeDate = placedDate ?: startDate
        val placeTime = placedTime ?: startTime
        val startClock = when {
            placeDate != null && placeTime != null -> LocalDateTime.of(placeDate, placeTime)
            task.isFixedTime() && startDate != null && startTime != null ->
                LocalDateTime.of(startDate, startTime)
            else -> null
        }
        val nowFloor = AppTimeReference.getCurrentDateTime().withSecond(0).withNano(0)
        if (startClock != null && startClock.isBefore(nowFloor)) {
            return@withContext "Cannot create a task in the past."
        }
        if (task.hasDeadline() && deadlineDate != null && deadlineTime != null) {
            val due = LocalDateTime.of(deadlineDate, deadlineTime)
            if (!due.isAfter(AppTimeReference.getCurrentDateTime())) {
                return@withContext "Cannot set deadline in the past."
            }
        }

        when {
            placeDate != null && placeTime != null -> {
                val err = TaskPlacement.placeError(
                    task, placeDate, placeTime, snap.tasks, blocked,
                )
                if (err != null) return@withContext err
                task.placedDate = placeDate
                task.placedTime = placeTime
            }
            task.isFlexible() || task.hasDeadline() -> {
                val session = SchedulerSession()
                session.blockedPeriod.replaceAll(snap.blocked)
                session.reloadFromTasks(snap.tasks)
                session.pendingTasks.add(task)
                session.updateSchedule()
                if (task !in session.placedTasks) {
                    return@withContext "Could not schedule task — no free slot"
                }
            }
            else -> return@withContext "Invalid task fields"
        }

        RefreshLifeCycle.refresh(task)
        task.touchPlacementUpdated()
        val nodeErr = repo.upsertTask(task)
        runCatching { repo.pullFromNode() }
        CalendarWidgetViews.pushAll(context.applicationContext)
        nodeErr
    }
}
