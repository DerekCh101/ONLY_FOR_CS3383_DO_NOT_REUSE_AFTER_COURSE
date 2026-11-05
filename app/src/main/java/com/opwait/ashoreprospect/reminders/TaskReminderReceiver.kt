package com.opwait.ashoreprospect.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opwait.ashoreprospect.data.SessionStore
import com.opwait.ashoreprospect.data.local.SimpleTaskDb
import com.opwait.ashoreprospect.data.toDomain
import com.opwait.ashoreprospect.domain.VaultSeal
import com.opwait.ashoreprospect.domain.Workflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val lead = intent.getIntExtra(EXTRA_LEAD, 0)
        val titleFallback = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val session = SessionStore(context.applicationContext)
                if (!session.getToken().isNullOrBlank()) {
                    session.restoreRememberedDek()
                }
                val db = SimpleTaskDb(context.applicationContext)
                val task = db.getAllTasks().mapNotNull { it.toDomain() }.find { it.id == taskId }
                if (task == null || task.workflow == Workflow.FINISHED) return@launch
                val prefs = session.getReminderPrefs()
                if (!prefs.enabled) return@launch
                ReminderNotifier.show(
                    context.applicationContext,
                    taskId,
                    VaultSeal.displayTitle(task.title.ifBlank { titleFallback }),
                    lead,
                    prefs.sound,
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.opwait.ashoreprospect.ACTION_TASK_REMINDER"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_LEAD = "lead"
        const val EXTRA_TITLE = "title"
    }
}
