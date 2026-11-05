package com.opwait.ashoreprospect.widget

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.opwait.ashoreprospect.domain.AppTimeReference
import java.time.LocalDate
import java.time.YearMonth

data class CalendarWidgetUiState(
    val dayMode: Boolean = false,
    val selectedDate: LocalDate? = null,
    val monthOffset: Int = 0,
    val addMode: Boolean = false,
    /** Duration in half-hours: 1=30m, 2=1h, 3=1.5h, 4=2h */
    val addHalfHours: Int = 2,
) {
    val isDefault: Boolean
        get() = !dayMode && selectedDate == null && monthOffset == 0 && !addMode && addHalfHours == 2

    val addDurationHours: Double
        get() = (addHalfHours.coerceIn(1, 4)) * 0.5
}

object CalendarWidgetStore {
    private const val NAME = "calendar_widget_state"
    private val DAY_MODE = booleanPreferencesKey("day_mode")
    private val SELECTED = stringPreferencesKey("selected")
    private val MONTH_OFFSET = intPreferencesKey("month_offset")
    private val ADD_MODE = booleanPreferencesKey("add_mode")
    private val ADD_HALF = intPreferencesKey("add_half")

    fun fromPrefs(prefs: Preferences): CalendarWidgetUiState {
        val dateRaw = prefs[SELECTED]
        return CalendarWidgetUiState(
            dayMode = prefs[DAY_MODE] ?: false,
            selectedDate = dateRaw?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            monthOffset = prefs[MONTH_OFFSET] ?: 0,
            addMode = prefs[ADD_MODE] ?: false,
            addHalfHours = prefs[ADD_HALF] ?: 2,
        )
    }

    fun write(prefs: MutablePreferences, ui: CalendarWidgetUiState) {
        prefs[DAY_MODE] = ui.dayMode
        prefs[MONTH_OFFSET] = ui.monthOffset
        prefs[ADD_MODE] = ui.addMode
        prefs[ADD_HALF] = ui.addHalfHours.coerceIn(1, 4)
        val date = ui.selectedDate
        if (date != null) {
            prefs[SELECTED] = date.toString()
        } else {
            prefs.remove(SELECTED)
        }
    }

    fun read(context: Context, appWidgetId: Int): CalendarWidgetUiState {
        val p = prefs(context)
        val dateRaw = p.getString(k(appWidgetId, "selected"), null)
        return CalendarWidgetUiState(
            dayMode = p.getBoolean(k(appWidgetId, "day_mode"), false),
            selectedDate = dateRaw?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            monthOffset = p.getInt(k(appWidgetId, "month_offset"), 0),
            addMode = p.getBoolean(k(appWidgetId, "add_mode"), false),
            addHalfHours = p.getInt(k(appWidgetId, "add_half"), 2).coerceIn(1, 4),
        )
    }

    fun write(context: Context, appWidgetId: Int, ui: CalendarWidgetUiState) {
        prefs(context).edit()
            .putBoolean(k(appWidgetId, "day_mode"), ui.dayMode)
            .putInt(k(appWidgetId, "month_offset"), ui.monthOffset)
            .putBoolean(k(appWidgetId, "add_mode"), ui.addMode)
            .putInt(k(appWidgetId, "add_half"), ui.addHalfHours.coerceIn(1, 4))
            .apply {
                if (ui.selectedDate != null) {
                    putString(k(appWidgetId, "selected"), ui.selectedDate.toString())
                } else {
                    remove(k(appWidgetId, "selected"))
                }
            }
            .commit()
    }

    suspend fun update(
        context: Context,
        glanceId: GlanceId,
        transform: (CalendarWidgetUiState) -> CalendarWidgetUiState,
    ) {
        val app = context.applicationContext
        val manager = GlanceAppWidgetManager(app)
        val appWidgetId = runCatching { manager.getAppWidgetId(glanceId) }.getOrNull()
        val glanceCurrent = runCatching {
            fromPrefs(getAppWidgetState(app, PreferencesGlanceStateDefinition, glanceId))
        }.getOrNull()
        val storedCurrent = if (appWidgetId != null) read(app, appWidgetId) else null
        val current = CalendarWidgetClicks.pickUi(glanceCurrent, storedCurrent)
        val next = transform(current)
        if (appWidgetId != null) {
            write(app, appWidgetId, next)
            CalendarWidgetViews.push(app, appWidgetId)
            return
        }
        updateAppWidgetState(app, glanceId) { prefs ->
            write(prefs, next)
        }
        CalendarWidgetViews.pushAll(app)
    }

    /** Month offset from the current 06:00-column month to the month containing `date`. */
    fun monthOffsetFor(date: LocalDate): Int {
        val base = AppTimeReference.currentMonth()
        val target = YearMonth.from(date)
        return (target.year - base.year) * 12 + (target.monthValue - base.monthValue)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private fun k(id: Int, field: String) = "w${id}_$field"
}
