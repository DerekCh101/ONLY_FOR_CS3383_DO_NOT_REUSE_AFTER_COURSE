package com.opwait.ashoreprospect.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.opwait.ashoreprospect.MainActivity
import com.opwait.ashoreprospect.R
import com.opwait.ashoreprospect.data.SessionStore
import com.opwait.ashoreprospect.data.TaskRepository
import com.opwait.ashoreprospect.domain.AppTimeReference
import com.opwait.ashoreprospect.domain.MonthCellTasks
import com.opwait.ashoreprospect.domain.Task
import com.opwait.ashoreprospect.domain.TaskDue
import com.opwait.ashoreprospect.domain.VaultSeal
import com.opwait.ashoreprospect.domain.Workflow
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

object CalendarWidgetViews {
    suspend fun push(context: Context, appWidgetId: Int) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        try {
            val session = SessionStore(app)
            val tasks = try {
                if (!session.getToken().isNullOrBlank()) {
                    session.restoreRememberedDek()
                }
                TaskRepository(app).loadLocalWorkspace().tasks
            } catch (_: Throwable) {
                emptyList()
            }
            val signedIn = try {
                !session.getToken().isNullOrBlank()
            } catch (_: Throwable) {
                false
            }
            val light = try {
                session.getThemePack() == "light"
            } catch (_: Throwable) {
                false
            }
            val ui = CalendarWidgetStore.read(app, appWidgetId)
            val views = build(app, appWidgetId, ui, tasks, signedIn, light)
            manager.updateAppWidget(appWidgetId, views)
            manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.tasks_list)
        } catch (_: Throwable) {
            manager.updateAppWidget(appWidgetId, fallback(app, appWidgetId))
        }
    }

    private fun fallback(context: Context, appWidgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_loading)
        val open = openApp(context, appWidgetId)
        views.setOnClickPendingIntent(R.id.widget_loading_root, open)
        views.setOnClickPendingIntent(R.id.widget_loading_label, open)
        return views
    }

    suspend fun pushAll(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(
            android.content.ComponentName(app, CalendarWidgetReceiver::class.java),
        )
        ids.forEach { push(app, it) }
    }

    private fun build(
        context: Context,
        appWidgetId: Int,
        ui: CalendarWidgetUiState,
        tasks: List<Task>,
        signedIn: Boolean,
        light: Boolean,
    ): RemoteViews {
        val colors = if (light) Light else Dark
        if (ui.addMode) return buildAdd(context, appWidgetId, ui, colors)
        val today = AppTimeReference.currentColumnDate()
        val views = RemoteViews(context.packageName, R.layout.calendar_widget)
        views.setInt(R.id.widget_root, "setBackgroundColor", colors.bg)
        paintToolbar(context, views, appWidgetId, ui, colors)
        val weekdayIds = intArrayOf(R.id.wd0, R.id.wd1, R.id.wd2, R.id.wd3, R.id.wd4, R.id.wd5, R.id.wd6)
        weekdayIds.forEach { views.setTextColor(it, colors.muted) }

        val monthVisible = if (ui.dayMode) View.GONE else View.VISIBLE
        views.setViewVisibility(R.id.btn_prev, monthVisible)
        views.setViewVisibility(R.id.btn_next, monthVisible)
        views.setViewVisibility(R.id.btn_today, monthVisible)
        views.setViewVisibility(R.id.weekday_row, monthVisible)
        views.setViewVisibility(R.id.weeks, monthVisible)

        views.removeAllViews(R.id.weeks)
        if (!ui.dayMode) {
            val month = AppTimeReference.currentMonth().plusMonths(ui.monthOffset.toLong())
            val start = AppTimeReference.getStartOfWeek(month.atDay(1))
            val days = (0 until 42).map { start.plusDays(it.toLong()) }
            days.chunked(7).forEach { week ->
                val row = RemoteViews(context.packageName, R.layout.calendar_widget_week)
                week.forEach { date ->
                    row.addView(R.id.week_row, dayCell(context, appWidgetId, date, month.month, tasks, today, ui.selectedDate == date, colors))
                }
                views.addView(R.id.weeks, row)
            }
        } else {
            views.setTextViewText(R.id.month_title, "Day mode")
        }

        val listDate = when {
            ui.dayMode -> today
            ui.selectedDate != null -> ui.selectedDate
            else -> null
        }
        val dayTasks = if (listDate != null) {
            MonthCellTasks.onDay(tasks, listDate).sortedWith(
                compareBy(
                    { t: Task -> if (t.workflow == Workflow.FINISHED) 1 else 0 },
                    { t: Task -> !TaskDue.isOverdue(t) },
                    { t: Task -> widgetTaskTime(t) ?: LocalTime.MAX },
                    { t: Task -> VaultSeal.displayTitle(t.title) },
                ),
            )
        } else {
            emptyList()
        }
        CalendarWidgetTaskCache.write(context, appWidgetId, dayTasks.map { task ->
            val finished = task.workflow == Workflow.FINISHED
            val titleColor = when {
                finished -> colors.muted
                TaskDue.isOverdue(task) -> colors.overdue
                else -> colors.cream
            }
            CalendarWidgetTaskRow(
                id = task.id,
                title = VaultSeal.displayTitle(task.title),
                timeLabel = widgetTaskTimeLabel(task),
                finished = finished,
                placed = task.placedDate != null && task.placedTime != null,
                overdue = TaskDue.isOverdue(task),
                titleColor = titleColor,
                mutedColor = colors.muted,
                creamColor = colors.cream,
                overdueColor = colors.overdue,
            )
        })
        val svc = Intent(context, CalendarWidgetTaskService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("ashore://widget/tasks/$appWidgetId")
        }
        views.setRemoteAdapter(R.id.tasks_list, svc)
        views.setEmptyView(R.id.tasks_list, R.id.list_empty)
        views.setPendingIntentTemplate(R.id.tasks_list, listClickTemplate(context, appWidgetId))

        if (listDate != null) {
            views.setViewVisibility(R.id.list_heading, View.VISIBLE)
            views.setTextViewText(R.id.list_heading, englishDay(listDate))
            views.setTextColor(R.id.list_heading, colors.cream)
            views.setOnClickPendingIntent(R.id.list_heading, openApp(context, appWidgetId, listDate.toString()))
            views.setTextViewText(R.id.list_empty, "No tasks")
            views.setTextColor(R.id.list_empty, colors.muted)
        } else {
            views.setViewVisibility(R.id.list_heading, View.GONE)
            views.setTextViewText(R.id.list_empty, "Tap a day for name and time")
            views.setTextColor(R.id.list_empty, colors.muted)
        }
        views.setTextColor(R.id.btn_new_task, colors.cream)
        views.setOnClickPendingIntent(
            R.id.btn_new_task,
            clickPi(context, appWidgetId, CalendarWidgetReceiver.OP_NEW_OPEN),
        )
        views.setViewVisibility(R.id.signin_hint, if (signedIn) View.GONE else View.VISIBLE)
        views.setTextColor(R.id.signin_hint, colors.muted)
        return views
    }

    private fun buildAdd(
        context: Context,
        appWidgetId: Int,
        ui: CalendarWidgetUiState,
        colors: Palette,
    ): RemoteViews {
        val today = AppTimeReference.currentColumnDate()
        val date = ui.selectedDate ?: today
        val views = RemoteViews(context.packageName, R.layout.calendar_widget_add)
        views.setInt(R.id.add_root, "setBackgroundColor", colors.bg)
        views.setTextColor(R.id.add_title, colors.cream)
        views.setTextColor(R.id.add_date, colors.cream)
        views.setTextColor(R.id.add_hint, colors.muted)
        views.setTextColor(R.id.add_dur_label, colors.muted)
        views.setTextColor(R.id.add_summary, colors.cream)
        views.setTextViewText(R.id.add_date, englishDay(date))
        val hours = ui.addDurationHours
        val time = if (date == today) {
            AppTimeReference.getCurrentTime().withSecond(0).withNano(0)
        } else {
            LocalTime.of(9, 0)
        }
        views.setTextViewText(
            R.id.add_summary,
            "Quick add sheet · ${formatHours(hours)} · starts %02d:%02d".format(time.hour, time.minute),
        )
        fun paintDur(id: Int, half: Int) {
            val on = ui.addHalfHours == half
            views.setTextColor(id, if (on) colors.today else colors.cream)
            views.setOnClickPendingIntent(
                id,
                clickPi(context, appWidgetId, CalendarWidgetReceiver.OP_NEW_DUR, half.toString()),
            )
        }
        paintDur(R.id.add_dur_05, 1)
        paintDur(R.id.add_dur_10, 2)
        paintDur(R.id.add_dur_15, 3)
        paintDur(R.id.add_dur_20, 4)
        views.setTextColor(R.id.add_cancel, colors.muted)
        views.setTextColor(R.id.add_create, colors.cream)
        views.setOnClickPendingIntent(
            R.id.add_cancel,
            clickPi(context, appWidgetId, CalendarWidgetReceiver.OP_NEW_CANCEL),
        )
        views.setOnClickPendingIntent(
            R.id.add_create,
            clickPi(context, appWidgetId, CalendarWidgetReceiver.OP_NEW_CREATE),
        )
        return views
    }

    private fun formatHours(hours: Double): String = when (hours) {
        0.5 -> "30m"
        1.0 -> "1h"
        1.5 -> "1.5h"
        2.0 -> "2h"
        else -> "${hours}h"
    }

    private fun paintToolbar(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        ui: CalendarWidgetUiState,
        colors: Palette,
    ) {
        val month = AppTimeReference.currentMonth().plusMonths(ui.monthOffset.toLong())
        if (!ui.dayMode) {
            views.setTextViewText(
                R.id.month_title,
                "${month.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${month.year}",
            )
        }
        views.setTextColor(R.id.month_title, colors.cream)
        views.setTextColor(R.id.btn_prev, colors.cream)
        views.setTextColor(R.id.btn_next, colors.cream)
        views.setTextColor(R.id.btn_today, colors.cream)
        views.setTextColor(R.id.btn_toggle, colors.cream)
        views.setTextColor(R.id.btn_app, colors.muted)
        views.setTextViewText(R.id.btn_toggle, if (ui.dayMode) "Month" else "Day")
        views.setOnClickPendingIntent(R.id.btn_prev, clickPi(context, appWidgetId, CalendarWidgetReceiver.OP_PREV))
        views.setOnClickPendingIntent(R.id.btn_next, clickPi(context, appWidgetId, CalendarWidgetReceiver.OP_NEXT))
        views.setOnClickPendingIntent(R.id.btn_today, clickPi(context, appWidgetId, CalendarWidgetReceiver.OP_TODAY))
        views.setOnClickPendingIntent(R.id.btn_toggle, clickPi(context, appWidgetId, CalendarWidgetReceiver.OP_TOGGLE))
        views.setOnClickPendingIntent(R.id.btn_app, openApp(context, appWidgetId))
    }

    private fun dayCell(
        context: Context,
        appWidgetId: Int,
        date: LocalDate,
        month: java.time.Month,
        tasks: List<Task>,
        today: LocalDate,
        isSelected: Boolean,
        colors: Palette,
    ): RemoteViews {
        val cell = RemoteViews(context.packageName, R.layout.calendar_widget_day)
        val inMonth = date.month == month
        val dayTasks = MonthCellTasks.onDay(tasks, date)
        val hasDot = dayTasks.isNotEmpty()
        val numColor = when {
            date == today -> colors.today
            !inMonth -> colors.muted
            date.isBefore(today) -> colors.pastText
            else -> colors.cream
        }
        val bg = when {
            isSelected -> colors.selectWash
            date.isBefore(today) -> colors.slotPast
            else -> colors.bg
        }
        val overdue = hasDot && dayTasks.any { TaskDue.isOverdue(it) }
        cell.setTextViewText(R.id.day_num, "${date.dayOfMonth}${if (hasDot) "•" else ""}")
        cell.setTextColor(R.id.day_num, if (overdue) colors.overdue else numColor)
        cell.setInt(R.id.day_num, "setBackgroundColor", bg)
        cell.setOnClickPendingIntent(
            R.id.day_num,
            clickPi(context, appWidgetId, CalendarWidgetReceiver.OP_DAY, date.toString()),
        )
        return cell
    }

    private fun clickPi(context: Context, appWidgetId: Int, op: String, value: String = ""): PendingIntent {
        val intent = Intent(context, CalendarWidgetReceiver::class.java).apply {
            action = CalendarWidgetReceiver.ACTION_CLICK
            data = CalendarWidgetClicks.clickUri(appWidgetId, op, value)
            putExtra(CalendarWidgetReceiver.EXTRA_OP, op)
            putExtra(CalendarWidgetReceiver.EXTRA_VALUE, value)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(context, requestCode(appWidgetId, op, value), intent, PI_FLAGS)
    }

    private fun listClickTemplate(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, CalendarWidgetReceiver::class.java).apply {
            action = CalendarWidgetReceiver.ACTION_CLICK
            data = Uri.parse("ashore://widget/$appWidgetId/list/_")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        // Template must be mutable so row fill-in intents can attach Finish/Done/X extras.
        return PendingIntent.getBroadcast(context, requestCode(appWidgetId, "list", ""), intent, PI_FLAGS_MUTABLE)
    }

    private fun openApp(context: Context, appWidgetId: Int, dateIso: String? = null): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch.data = CalendarWidgetClicks.clickUri(appWidgetId, "app", dateIso.orEmpty())
        if (!dateIso.isNullOrBlank()) {
            launch.putExtra(MainActivity.EXTRA_OPEN_COLUMN_DATE, dateIso)
        }
        return PendingIntent.getActivity(
            context,
            requestCode(appWidgetId, "app", dateIso.orEmpty()),
            launch,
            PI_FLAGS,
        )
    }

    private fun requestCode(appWidgetId: Int, op: String, value: String): Int =
        31 * (31 * appWidgetId + op.hashCode()) + value.hashCode()

    private fun widgetTaskTime(task: Task): LocalTime? {
        if (task.placedDate != null && task.placedTime != null) return task.placedTime
        if (task.isUnscheduledPending() && task.hasDeadline()) return task.deadlineTime
        return null
    }

    private fun widgetTaskTimeLabel(task: Task): String {
        val placed = task.placedTime
        if (task.placedDate != null && placed != null) {
            return "%02d:%02d".format(placed.hour, placed.minute)
        }
        if (task.isUnscheduledPending() && task.hasDeadline()) {
            val due = task.deadlineTime
            return if (due != null) "Due %02d:%02d".format(due.hour, due.minute) else "Due"
        }
        return task.state.apiName
    }

    private fun englishDay(date: LocalDate): String {
        val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        val ord = when (date.dayOfMonth) {
            1, 21, 31 -> "st"
            2, 22 -> "nd"
            3, 23 -> "rd"
            else -> "th"
        }
        val mon = date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        return "$dow ${date.dayOfMonth}$ord $mon"
    }

    private data class Palette(
        val bg: Int,
        val slotPast: Int,
        val cream: Int,
        val muted: Int,
        val pastText: Int,
        val overdue: Int,
        val today: Int,
        val selectWash: Int,
    )

    private val Dark = Palette(
        bg = Color.parseColor("#1C2430"),
        slotPast = Color.parseColor("#10151C"),
        cream = Color.parseColor("#F3EEE4"),
        muted = Color.parseColor("#C9C2B6"),
        pastText = Color.parseColor("#8B8680"),
        overdue = Color.parseColor("#C62828"),
        today = Color.parseColor("#E53935"),
        selectWash = Color.parseColor("#1E3A2A"),
    )

    private val Light = Palette(
        bg = Color.parseColor("#F3EEE4"),
        slotPast = Color.parseColor("#BDC8C8"),
        cream = Color.parseColor("#1C2430"),
        muted = Color.parseColor("#5C564C"),
        pastText = Color.parseColor("#5C564C"),
        overdue = Color.parseColor("#C62828"),
        today = Color.parseColor("#E53935"),
        selectWash = Color.parseColor("#C8E6C9"),
    )

    private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    private const val PI_FLAGS_MUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
}
