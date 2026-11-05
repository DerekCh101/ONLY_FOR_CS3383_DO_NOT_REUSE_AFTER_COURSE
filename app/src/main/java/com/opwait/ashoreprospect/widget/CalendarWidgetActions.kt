package com.opwait.ashoreprospect.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.opwait.ashoreprospect.data.TaskRepository
import com.opwait.ashoreprospect.domain.AppTimeReference
import com.opwait.ashoreprospect.domain.NestedTasks
import com.opwait.ashoreprospect.domain.RefreshLifeCycle
import com.opwait.ashoreprospect.domain.Workflow
import java.time.LocalDate

object WidgetActionKeys {
    val DATE = ActionParameters.Key<String>("date")
    val TASK_ID = ActionParameters.Key<String>("task_id")
}

class SelectDayAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val iso = parameters[WidgetActionKeys.DATE] ?: return
        val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return
        val monthOffset = CalendarWidgetStore.monthOffsetFor(date)
        CalendarWidgetStore.update(context, glanceId) {
            it.copy(selectedDate = date, dayMode = false, monthOffset = monthOffset)
        }
    }
}

class PrevMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CalendarWidgetStore.update(context, glanceId) {
            it.copy(monthOffset = it.monthOffset - 1)
        }
    }
}

class NextMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CalendarWidgetStore.update(context, glanceId) {
            it.copy(monthOffset = it.monthOffset + 1)
        }
    }
}

class ToggleDayModeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CalendarWidgetStore.update(context, glanceId) { cur ->
            val next = !cur.dayMode
            cur.copy(
                dayMode = next,
                selectedDate = if (next) AppTimeReference.currentColumnDate() else cur.selectedDate,
            )
        }
    }
}

class TodayWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val today = AppTimeReference.currentColumnDate()
        CalendarWidgetStore.update(context, glanceId) {
            it.copy(
                selectedDate = today,
                dayMode = false,
                monthOffset = 0,
            )
        }
    }
}

class ClearWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CalendarWidgetStore.update(context, glanceId) {
            it.copy(dayMode = false, selectedDate = null, monthOffset = 0)
        }
    }
}

class FinishTaskAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val taskId = parameters[WidgetActionKeys.TASK_ID] ?: return
        val repo = TaskRepository(context.applicationContext)
        val snap = repo.loadLocalWorkspace()
        val task = snap.tasks.find { it.id == taskId } ?: return
        val parent = NestedTasks.mainTask(snap.tasks, task)
        if (parent.placedDate == null || parent.placedTime == null) return
        val next = if (parent.workflow == Workflow.FINISHED) Workflow.UNFINISHED else Workflow.FINISHED
        val affiliated = listOf(parent) + NestedTasks.ofParent(snap.tasks, parent)
        for (row in affiliated) {
            val updated = row.copy(workflow = next)
            RefreshLifeCycle.refresh(updated)
            repo.upsertTask(updated)
        }
        runCatching { repo.pullFromNode() }
        CalendarWidgetRefresh.updateAll(context)
    }
}

class OpenAppAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        parameters[WidgetActionKeys.DATE]?.let { iso ->
            launch.putExtra(
                com.opwait.ashoreprospect.MainActivity.EXTRA_OPEN_COLUMN_DATE,
                iso,
            )
        }
        context.startActivity(launch)
    }
}
