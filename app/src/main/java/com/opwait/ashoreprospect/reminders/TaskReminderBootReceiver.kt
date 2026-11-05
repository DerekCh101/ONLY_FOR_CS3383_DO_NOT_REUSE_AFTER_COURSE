package com.opwait.ashoreprospect.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.opwait.ashoreprospect.widget.CalendarWidgetRefresh

class TaskReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext
                TaskReminderScheduler.rescheduleFromDisk(app)
                CalendarWidgetRefresh.updateAll(app)
            } finally {
                pending.finish()
            }
        }
    }
}
