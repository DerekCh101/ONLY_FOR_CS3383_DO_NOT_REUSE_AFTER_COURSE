package com.opwait.ashoreprospect.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.opwait.ashoreprospect.data.TaskRepository
import com.opwait.ashoreprospect.domain.AppTimeReference
import com.opwait.ashoreprospect.domain.NestedTasks
import com.opwait.ashoreprospect.domain.RefreshLifeCycle
import com.opwait.ashoreprospect.domain.Workflow
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object CalendarWidgetClickDispatch {
    private const val TAG = "AshoreWidget"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun enqueue(context: Context, intent: Intent) {
        val app = context.applicationContext
        val copy = Intent(intent)
        scope.launch {
            try {
                handle(app, copy)
            } catch (t: Throwable) {
                Log.e(TAG, "widget click failed", t)
            }
        }
    }

    suspend fun handle(context: Context, intent: Intent) {
        val parsed = CalendarWidgetClicks.parse(intent) ?: run {
            Log.w(TAG, "click ignored action=${intent.action} data=${intent.data}")
            return
        }
        val ids = widgetIds(context, parsed.appWidgetId)
        Log.i(TAG, "click op=${parsed.op} value=${parsed.value} widget=${parsed.appWidgetId} ids=${ids.size}")
        when (parsed.op) {
            CalendarWidgetReceiver.OP_FINISH -> {
                toggleFinish(context, parsed.value)
                CalendarWidgetViews.pushAll(context)
                return
            }
            CalendarWidgetReceiver.OP_CANCEL -> {
                cancelTask(context, parsed.value)
                CalendarWidgetViews.pushAll(context)
                return
            }
            // Full Add Task sheet over the launcher (widgets cannot host a real keyboard).
            CalendarWidgetReceiver.OP_NEW_OPEN,
            CalendarWidgetReceiver.OP_NEW_CREATE,
            -> {
                ids.forEach { appWidgetId ->
                    val ui = CalendarWidgetStore.read(context, appWidgetId)
                    openQuickAddSheet(context, appWidgetId, ui)
                    CalendarWidgetStore.write(context, appWidgetId, ui.copy(addMode = false))
                    CalendarWidgetViews.push(context, appWidgetId)
                }
                return
            }
        }
        ids.forEach { appWidgetId ->
            val next = CalendarWidgetClicks.apply(
                parsed.op,
                parsed.value,
                CalendarWidgetStore.read(context, appWidgetId),
            ) ?: return@forEach
            CalendarWidgetStore.write(context, appWidgetId, next)
            CalendarWidgetViews.push(context, appWidgetId)
        }
    }

    private fun widgetIds(context: Context, parsedId: Int?): IntArray {
        if (parsedId != null && parsedId > 0) return intArrayOf(parsedId)
        return AppWidgetManager.getInstance(context).getAppWidgetIds(
            ComponentName(context, CalendarWidgetReceiver::class.java),
        )
    }

    private fun openQuickAddSheet(context: Context, appWidgetId: Int, ui: CalendarWidgetUiState) {
        val today = AppTimeReference.currentColumnDate()
        val date = ui.selectedDate ?: today
        val time = if (date == today) {
            AppTimeReference.getCurrentTime().withSecond(0).withNano(0)
        } else {
            LocalTime.of(9, 0)
        }
        val launch = Intent(context, WidgetAddTaskActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(WidgetAddTaskActivity.EXTRA_DATE, date.toString())
            putExtra(WidgetAddTaskActivity.EXTRA_TIME, "%02d:%02d".format(time.hour, time.minute))
            putExtra(WidgetAddTaskActivity.EXTRA_DURATION_HOURS, ui.addDurationHours)
        }
        context.startActivity(launch)
    }

    private suspend fun toggleFinish(context: Context, taskId: String) {
        if (taskId.isBlank()) return
        val repo = TaskRepository(context)
        val snap = repo.loadLocalWorkspace()
        val task = snap.tasks.find { it.id == taskId } ?: return
        if (task.placedDate == null || task.placedTime == null) return
        val next = if (task.workflow == Workflow.FINISHED) Workflow.UNFINISHED else Workflow.FINISHED
        val updated = task.copy(workflow = next)
        RefreshLifeCycle.refresh(updated)
        repo.upsertTask(updated)
        runCatching { repo.pullFromNode() }
    }

    private suspend fun cancelTask(context: Context, taskId: String) {
        if (taskId.isBlank()) return
        val repo = TaskRepository(context)
        val snap = repo.loadLocalWorkspace()
        val task = snap.tasks.find { it.id == taskId } ?: return
        if (task.workflow != Workflow.FINISHED) return
        val parent = NestedTasks.mainTask(snap.tasks, task)
        for (q in NestedTasks.ofParent(snap.tasks, parent)) {
            repo.deleteTask(q)
        }
        repo.deleteTask(parent)
        runCatching { repo.pullFromNode() }
    }
}
