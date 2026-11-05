package com.opwait.ashoreprospect.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
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
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

class CalendarGlanceWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext
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
        val appWidgetId = runCatching { GlanceAppWidgetManager(app).getAppWidgetId(id) }.getOrNull()
        val glanceUi = runCatching {
            CalendarWidgetStore.fromPrefs(getAppWidgetState(app, PreferencesGlanceStateDefinition, id))
        }.getOrNull()
        val spUi = if (appWidgetId != null) CalendarWidgetStore.read(app, appWidgetId) else null
        val ui = CalendarWidgetClicks.pickUi(glanceUi, spUi)
        provideContent {
            GlanceTheme {
                CompositionLocalProvider(LocalWidgetColors provides widgetColors(light)) {
                    CalendarWidgetRoot(tasks, ui, signedIn, appWidgetId ?: 0)
                }
            }
        }
    }
}

private fun widgetColors(light: Boolean) = if (light) {
    WidgetColors(
        bg = ColorProvider(Color(0xFFF3EEE4)),
        slotPast = ColorProvider(Color(0xFFBDC8C8)),
        cream = ColorProvider(Color(0xFF1C2430)),
        muted = ColorProvider(Color(0xFF5C564C)),
        pastText = ColorProvider(Color(0xFF5C564C)),
        overdue = ColorProvider(Color(0xFFC62828)),
        today = ColorProvider(Color(0xFFE53935)),
        dot = ColorProvider(Color(0xFF1C2430)),
        selectWash = ColorProvider(Color(0xFFC8E6C9)),
    )
} else {
    WidgetColors(
        bg = ColorProvider(Color(0xFF1C2430)),
        slotPast = ColorProvider(Color(0xFF10151C)),
        cream = ColorProvider(Color(0xFFF3EEE4)),
        muted = ColorProvider(Color(0xFFC9C2B6)),
        pastText = ColorProvider(Color(0xFF8B8680)),
        overdue = ColorProvider(Color(0xFFC62828)),
        today = ColorProvider(Color(0xFFE53935)),
        dot = ColorProvider(Color(0xFFF3EEE4)),
        selectWash = ColorProvider(Color(0xFF1E3A2A)),
    )
}

private data class WidgetColors(
    val bg: ColorProvider,
    val slotPast: ColorProvider,
    val cream: ColorProvider,
    val muted: ColorProvider,
    val pastText: ColorProvider,
    val overdue: ColorProvider,
    val today: ColorProvider,
    val dot: ColorProvider,
    val selectWash: ColorProvider,
)

private val LocalWidgetColors = compositionLocalOf { widgetColors(false) }

private val Bg: ColorProvider @Composable get() = LocalWidgetColors.current.bg
private val SlotPast: ColorProvider @Composable get() = LocalWidgetColors.current.slotPast
private val Cream: ColorProvider @Composable get() = LocalWidgetColors.current.cream
private val Muted: ColorProvider @Composable get() = LocalWidgetColors.current.muted
private val PastText: ColorProvider @Composable get() = LocalWidgetColors.current.pastText
private val Overdue: ColorProvider @Composable get() = LocalWidgetColors.current.overdue
private val Today: ColorProvider @Composable get() = LocalWidgetColors.current.today
private val Dot: ColorProvider @Composable get() = LocalWidgetColors.current.dot
private val SelectWash: ColorProvider @Composable get() = LocalWidgetColors.current.selectWash

private fun widgetClick(context: Context, appWidgetId: Int, op: String, value: String = ""): Action {
    val intent = Intent(context, CalendarWidgetClickActivity::class.java).apply {
        action = CalendarWidgetReceiver.ACTION_CLICK
        data = CalendarWidgetClicks.clickUri(appWidgetId, op, value)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_NO_ANIMATION or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        putExtra(CalendarWidgetReceiver.EXTRA_OP, op)
        putExtra(CalendarWidgetReceiver.EXTRA_VALUE, value)
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }
    return actionStartActivity(intent)
}

private fun openAppAction(context: Context, dateIso: String? = null): Action {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent(context, com.opwait.ashoreprospect.MainActivity::class.java)
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (!dateIso.isNullOrBlank()) {
        launch.putExtra(com.opwait.ashoreprospect.MainActivity.EXTRA_OPEN_COLUMN_DATE, dateIso)
    }
    return actionStartActivity(launch)
}

@Composable
private fun CalendarWidgetRoot(
    tasks: List<Task>,
    ui: CalendarWidgetUiState,
    signedIn: Boolean,
    appWidgetId: Int,
) {
    val dayMode = ui.dayMode
    val monthOffset = ui.monthOffset
    val selected = ui.selectedDate
    val today = AppTimeReference.currentColumnDate()
    val listDate = when {
        dayMode -> today
        selected != null -> selected
        else -> null
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Bg)
            .padding(8.dp),
    ) {
        WidgetToolbar(dayMode, monthOffset, appWidgetId)
        if (!dayMode) {
            MonthCalendar(
                monthOffset = monthOffset,
                tasks = tasks,
                today = today,
                selected = selected,
                appWidgetId = appWidgetId,
            )
        } else {
            Text(
                "Today · ${englishDay(today)}",
                style = TextStyle(color = Cream, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        if (listDate != null) {
            DayTaskList(listDate, tasks, appWidgetId)
        } else {
            Text(
                "Tap a day for name and time",
                style = TextStyle(color = Muted, fontSize = 11.sp),
            )
        }
        if (!signedIn) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                "Sign in in the app to load tasks",
                style = TextStyle(color = Muted, fontSize = 10.sp),
            )
        }
    }
}

@Composable
private fun Hit(
    label: String,
    action: Action,
    large: Boolean = false,
    muted: Boolean = false,
) {
    Box(
        modifier = GlanceModifier
            .clickable(action)
            .padding(horizontal = if (large) 10.dp else 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                color = if (muted) Muted else Cream,
                fontSize = if (large) 20.sp else 12.sp,
                fontWeight = if (large) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

@Composable
private fun WidgetToolbar(dayMode: Boolean, monthOffset: Int, appWidgetId: Int) {
    val context = LocalContext.current
    val month = AppTimeReference.currentMonth().plusMonths(monthOffset.toLong())
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!dayMode) {
            Hit("<", widgetClick(context, appWidgetId, CalendarWidgetReceiver.OP_PREV), large = true)
            Text(
                "${month.month.getDisplayName(JavaTextStyle.SHORT, Locale.ENGLISH)} ${month.year}",
                style = TextStyle(color = Cream, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.defaultWeight().padding(horizontal = 4.dp),
            )
            Hit(">", widgetClick(context, appWidgetId, CalendarWidgetReceiver.OP_NEXT), large = true)
        } else {
            Text(
                "Day mode",
                style = TextStyle(color = Cream, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.defaultWeight(),
            )
        }
        if (!dayMode) {
            Hit("Today", widgetClick(context, appWidgetId, CalendarWidgetReceiver.OP_TODAY))
        }
        Hit(
            if (dayMode) "Month" else "Day",
            widgetClick(context, appWidgetId, CalendarWidgetReceiver.OP_TOGGLE),
        )
        Hit("Clear", widgetClick(context, appWidgetId, CalendarWidgetReceiver.OP_CLEAR), muted = true)
        Hit("App", openAppAction(context), muted = true)
    }
    Spacer(GlanceModifier.height(4.dp))
}

@Composable
private fun RowScope.WeekdayHead(label: String) {
    Text(
        label,
        modifier = GlanceModifier.defaultWeight(),
        style = TextStyle(color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center),
    )
}

@Composable
private fun MonthCalendar(
    monthOffset: Int,
    tasks: List<Task>,
    today: LocalDate,
    selected: LocalDate?,
    appWidgetId: Int,
) {
    val month = AppTimeReference.currentMonth().plusMonths(monthOffset.toLong())
    val first = month.atDay(1)
    val start = AppTimeReference.getStartOfWeek(first)
    val days = (0 until 42).map { start.plusDays(it.toLong()) }
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        WeekdayHead("M")
        WeekdayHead("T")
        WeekdayHead("W")
        WeekdayHead("T")
        WeekdayHead("F")
        WeekdayHead("S")
        WeekdayHead("S")
    }
    days.chunked(7).forEach { week ->
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            week.forEach { date ->
                DayCell(date, month.month, tasks, today, selected == date, appWidgetId)
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(
    date: LocalDate,
    month: java.time.Month,
    tasks: List<Task>,
    today: LocalDate,
    isSelected: Boolean,
    appWidgetId: Int,
) {
    val inMonth = date.month == month
    val dayTasks = MonthCellTasks.onDay(tasks, date)
    val hasDot = dayTasks.isNotEmpty()
    val numColor = when {
        date == today -> Today
        !inMonth -> Muted
        date.isBefore(today) -> PastText
        else -> Cream
    }
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .defaultWeight()
            .clickable(widgetClick(context, appWidgetId, CalendarWidgetReceiver.OP_DAY, date.toString()))
            .background(
                when {
                    isSelected -> SelectWash
                    date.isBefore(today) -> SlotPast
                    else -> Bg
                },
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${date.dayOfMonth}${if (hasDot) "•" else ""}",
            style = TextStyle(
                color = if (hasDot && dayTasks.any { TaskDue.isOverdue(it) }) Overdue else numColor,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

@Composable
private fun DayTaskList(date: LocalDate, tasks: List<Task>, appWidgetId: Int) {
    val dayTasks = MonthCellTasks.onDay(tasks, date)
        .sortedWith(
            compareBy(
                { t: Task -> if (t.workflow == Workflow.FINISHED) 1 else 0 },
                { t: Task -> !TaskDue.isOverdue(t) },
                { t: Task -> widgetTaskTime(t) ?: LocalTime.MAX },
                { t: Task -> VaultSeal.displayTitle(t.title) },
            ),
        )
    val context = LocalContext.current
    Text(
        englishDay(date),
        modifier = GlanceModifier.clickable(openAppAction(context, date.toString())),
        style = TextStyle(color = Cream, fontSize = 12.sp, fontWeight = FontWeight.Medium),
    )
    if (dayTasks.isEmpty()) {
        Text("No tasks", style = TextStyle(color = Muted, fontSize = 11.sp))
        return
    }
    dayTasks.take(8).forEach { task ->
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskRow(task, appWidgetId)
        }
    }
    if (dayTasks.size > 8) {
        Text("+${dayTasks.size - 8} more", style = TextStyle(color = Muted, fontSize = 10.sp))
    }
}

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

@Composable
private fun RowScope.TaskRow(task: Task, appWidgetId: Int) {
    val title = VaultSeal.displayTitle(task.title)
    val time = widgetTaskTimeLabel(task)
    val finished = task.workflow == Workflow.FINISHED
    val placed = task.placedDate != null && task.placedTime != null
    val titleColor = when {
        finished -> Muted
        TaskDue.isOverdue(task) -> Overdue
        else -> Cream
    }
    Text(
        time,
        modifier = GlanceModifier.width(56.dp),
        style = TextStyle(color = Muted, fontSize = 11.sp),
    )
    Text(
        title,
        modifier = GlanceModifier.defaultWeight().padding(start = 4.dp),
        style = TextStyle(color = titleColor, fontSize = 12.sp),
    )
    if (placed && !finished) {
        val context = LocalContext.current
        Hit(
            "Finish",
            widgetClick(context, appWidgetId, CalendarWidgetReceiver.OP_FINISH, task.id),
        )
    } else if (finished) {
        Text("Done", style = TextStyle(color = Muted, fontSize = 10.sp))
    }
}

private fun englishDay(date: LocalDate): String {
    val dow = date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.ENGLISH)
    val ord = when (date.dayOfMonth) {
        1, 21, 31 -> "st"
        2, 22 -> "nd"
        3, 23 -> "rd"
        else -> "th"
    }
    val mon = date.month.getDisplayName(JavaTextStyle.SHORT, Locale.ENGLISH)
    return "$dow ${date.dayOfMonth}$ord $mon"
}
