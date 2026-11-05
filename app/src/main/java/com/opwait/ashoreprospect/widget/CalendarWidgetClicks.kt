package com.opwait.ashoreprospect.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import com.opwait.ashoreprospect.domain.AppTimeReference
import java.time.LocalDate

/** Pure click reducer for the calendar widget. No Glance/RemoteViews here so it can be unit-tested. */
object CalendarWidgetClicks {
    data class Parsed(
        val op: String,
        val value: String,
        val appWidgetId: Int?,
    )

    private val OPS = setOf(
        CalendarWidgetReceiver.OP_PREV,
        CalendarWidgetReceiver.OP_NEXT,
        CalendarWidgetReceiver.OP_TODAY,
        CalendarWidgetReceiver.OP_TOGGLE,
        CalendarWidgetReceiver.OP_CLEAR,
        CalendarWidgetReceiver.OP_DAY,
        CalendarWidgetReceiver.OP_FINISH,
        CalendarWidgetReceiver.OP_CANCEL,
        CalendarWidgetReceiver.OP_NEW_OPEN,
        CalendarWidgetReceiver.OP_NEW_CANCEL,
        CalendarWidgetReceiver.OP_NEW_DUR,
        CalendarWidgetReceiver.OP_NEW_CREATE,
    )

    fun clickUri(appWidgetId: Int, op: String, value: String): Uri {
        val encoded = Uri.encode(value.ifBlank { "_" })
        return Uri.parse("ashore://widget/$appWidgetId/$op/$encoded")
    }

    fun parse(intent: Intent): Parsed? {
        val extraId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        return parse(
            opExtra = intent.getStringExtra(CalendarWidgetReceiver.EXTRA_OP),
            valueExtra = intent.getStringExtra(CalendarWidgetReceiver.EXTRA_VALUE),
            appWidgetIdExtra = extraId,
            pathSegments = intent.data?.pathSegments.orEmpty(),
        )
    }

    fun parse(
        opExtra: String?,
        valueExtra: String?,
        appWidgetIdExtra: Int,
        pathSegments: List<String>,
    ): Parsed? {
        val op = when {
            opExtra in OPS -> opExtra
            pathSegments.getOrNull(1) in OPS -> pathSegments[1]
            pathSegments.getOrNull(0) in OPS -> pathSegments[0]
            else -> null
        } ?: return null
        val valueRaw = when {
            !valueExtra.isNullOrEmpty() -> valueExtra
            pathSegments.getOrNull(1) == op -> pathSegments.getOrNull(2)
            pathSegments.getOrNull(0) == op -> pathSegments.getOrNull(1)
            else -> null
        }
        val value = decodeValue(valueRaw)
        val idFromPath = pathSegments.getOrNull(0)?.toIntOrNull()
        val appWidgetId = when {
            appWidgetIdExtra >= 0 -> appWidgetIdExtra
            idFromPath != null && pathSegments.getOrNull(1) == op -> idFromPath
            else -> null
        }
        return Parsed(op, value, appWidgetId)
    }

    fun pickUi(
        glance: CalendarWidgetUiState?,
        stored: CalendarWidgetUiState?,
    ): CalendarWidgetUiState = when {
        glance != null && !glance.isDefault -> glance
        stored != null && !stored.isDefault -> stored
        glance != null -> glance
        stored != null -> stored
        else -> CalendarWidgetUiState()
    }

    fun apply(
        op: String,
        value: String,
        current: CalendarWidgetUiState,
        today: LocalDate = AppTimeReference.currentColumnDate(),
    ): CalendarWidgetUiState? {
        return when (op) {
            CalendarWidgetReceiver.OP_PREV -> current.copy(monthOffset = current.monthOffset - 1, addMode = false)
            CalendarWidgetReceiver.OP_NEXT -> current.copy(monthOffset = current.monthOffset + 1, addMode = false)
            CalendarWidgetReceiver.OP_TODAY -> current.copy(
                selectedDate = today,
                dayMode = false,
                monthOffset = 0,
                addMode = false,
            )
            CalendarWidgetReceiver.OP_TOGGLE -> {
                val next = !current.dayMode
                current.copy(
                    dayMode = next,
                    selectedDate = if (next) today else current.selectedDate,
                    addMode = false,
                )
            }
            CalendarWidgetReceiver.OP_CLEAR -> current.copy(
                dayMode = false,
                selectedDate = null,
                monthOffset = 0,
                addMode = false,
            )
            CalendarWidgetReceiver.OP_DAY -> {
                val date = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
                current.copy(
                    selectedDate = date,
                    dayMode = false,
                    monthOffset = CalendarWidgetStore.monthOffsetFor(date),
                    addMode = false,
                )
            }
            CalendarWidgetReceiver.OP_NEW_OPEN -> current.copy(
                addMode = true,
                selectedDate = current.selectedDate ?: today,
                dayMode = false,
                addHalfHours = current.addHalfHours.coerceIn(1, 4),
            )
            CalendarWidgetReceiver.OP_NEW_CANCEL -> current.copy(addMode = false)
            CalendarWidgetReceiver.OP_NEW_DUR -> {
                val half = when (value) {
                    "0.5", "05", "30m" -> 1
                    "1", "1.0", "1h" -> 2
                    "1.5", "15", "1.5h" -> 3
                    "2", "2.0", "2h" -> 4
                    else -> value.toIntOrNull()?.coerceIn(1, 4)
                } ?: return null
                current.copy(addMode = true, addHalfHours = half)
            }
            CalendarWidgetReceiver.OP_NEW_CREATE,
            CalendarWidgetReceiver.OP_FINISH,
            CalendarWidgetReceiver.OP_CANCEL,
            -> current
            else -> null
        }
    }

    internal fun decodeValue(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "_") return ""
        return try {
            java.net.URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (_: Exception) {
            raw
        }
    }
}
