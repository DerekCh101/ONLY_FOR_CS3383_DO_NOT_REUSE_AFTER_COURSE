package com.opwait.ashoreprospect.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.opwait.ashoreprospect.domain.AppTimeReference
import com.opwait.ashoreprospect.ui.AddTaskDialog
import com.opwait.ashoreprospect.ui.AddTaskPrefill
import com.opwait.ashoreprospect.ui.theme.AppThemePack
import com.opwait.ashoreprospect.ui.theme.ashoreprospectTheme
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch

/**
 * Floating Add Task sheet over the launcher — same form as in-app Add task.
 * Home-screen RemoteViews cannot host a real keyboard; this sheet is the supported way
 * to type a title without opening the main scheduler.
 */
class WidgetAddTaskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val today = AppTimeReference.currentColumnDate()
        val date = intent.getStringExtra(EXTRA_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: today
        val time = intent.getStringExtra(EXTRA_TIME)
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?: if (date == today) {
                AppTimeReference.getCurrentTime().withSecond(0).withNano(0)
            } else {
                LocalTime.of(9, 0)
            }
        val hours = intent.getDoubleExtra(EXTRA_DURATION_HOURS, 1.0).takeIf { it > 0 } ?: 1.0
        val prefill = AddTaskPrefill(date = date, time = time, durationHours = hours)

        setContent {
            ashoreprospectTheme(darkTheme = AppThemePack.isDark) {
                val scope = rememberCoroutineScope()
                var saving by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            if (!saving) finish()
                        },
                ) {
                    AddTaskDialog(
                        prefill = prefill,
                        onDismiss = { if (!saving) finish() },
                        onSave = { title, dur, kind, sd, st, dd, dt, pd, pt, desc, descDone ->
                            if (saving) return@AddTaskDialog
                            saving = true
                            scope.launch {
                                val err = WidgetAddTaskSaver.save(
                                    this@WidgetAddTaskActivity,
                                    title, dur, kind, sd, st, dd, dt, pd, pt, desc, descDone,
                                )
                                if (err != null) {
                                    Toast.makeText(
                                        this@WidgetAddTaskActivity,
                                        err,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    saving = false
                                } else {
                                    finish()
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_DATE = "widget_add_date"
        const val EXTRA_TIME = "widget_add_time"
        const val EXTRA_DURATION_HOURS = "widget_add_duration_hours"
    }
}
