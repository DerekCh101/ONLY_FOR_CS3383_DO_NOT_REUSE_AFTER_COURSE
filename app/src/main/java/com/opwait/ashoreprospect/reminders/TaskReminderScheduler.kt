package com.opwait.ashoreprospect.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.opwait.ashoreprospect.data.ReminderPrefs
import com.opwait.ashoreprospect.data.SessionStore
import com.opwait.ashoreprospect.data.local.SimpleTaskDb
import com.opwait.ashoreprospect.data.toDomain
import com.opwait.ashoreprospect.domain.ReminderFires
import com.opwait.ashoreprospect.domain.Task

object TaskReminderScheduler {
    private const val REQUEST_BASE = 71000
    private const val MAX_ALARMS = 80

    fun schedule(context: Context, tasks: List<Task>, prefs: ReminderPrefs) {
        val app = context.applicationContext
        ReminderNotifier.ensureChannel(app)
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        for (i in 0 until MAX_ALARMS) {
            am.cancel(pending(app, REQUEST_BASE + i, null))
        }
        if (!prefs.enabled) return
        val now = System.currentTimeMillis()
        val fires = ReminderFires.upcoming(tasks, prefs.enabled, prefs.leads, now, maxAlarms = MAX_ALARMS)
        fires.forEachIndexed { i, fire ->
            val pi = pending(app, REQUEST_BASE + i, fire)
            setAlarm(am, fire.fireAtEpochMs, pi)
        }
    }

    suspend fun rescheduleFromDisk(context: Context) {
        val app = context.applicationContext
        val prefs = SessionStore(app).getReminderPrefs()
        val tasks = SimpleTaskDb(app).getAllTasks().mapNotNull { it.toDomain() }
        schedule(app, tasks, prefs)
    }

    private fun setAlarm(am: AlarmManager, at: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pending(context: Context, requestCode: Int, fire: ReminderFires.Fire?): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = TaskReminderReceiver.ACTION_FIRE
            if (fire != null) {
                putExtra(TaskReminderReceiver.EXTRA_TASK_ID, fire.taskId)
                putExtra(TaskReminderReceiver.EXTRA_LEAD, fire.leadMinutes)
                putExtra(TaskReminderReceiver.EXTRA_TITLE, fire.title)
                data = Uri.parse("ashore://reminder/${fire.taskId}/${fire.leadMinutes}")
            } else {
                data = Uri.parse("ashore://reminder/slot/$requestCode")
            }
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
