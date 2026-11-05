package com.opwait.ashoreprospect.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.opwait.ashoreprospect.MainActivity
import com.opwait.ashoreprospect.R
import com.opwait.ashoreprospect.domain.ReminderFires
import com.opwait.ashoreprospect.domain.VaultSeal

object ReminderNotifier {
    const val CHANNEL_ID = "task-reminders"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Task reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Lead-time banners for placed tasks"
                enableVibration(true)
            },
        )
    }

    fun show(context: Context, taskId: String, title: String, leadMinutes: Int, sound: Boolean) {
        ensureChannel(context)
        val app = context.applicationContext
        val id = (taskId.hashCode() xor (leadMinutes shl 16)) and 0x7fffffff
        val open = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TASK_ID, taskId)
        }
        val tap = PendingIntent.getActivity(
            app,
            id,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val shown = VaultSeal.displayTitle(title)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(ReminderFires.headline(shown, leadMinutes))
            .setContentText(if (leadMinutes == 0) "Starting now" else "Starts in $leadMinutes min")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(!sound)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(id, notification) }
    }
}
