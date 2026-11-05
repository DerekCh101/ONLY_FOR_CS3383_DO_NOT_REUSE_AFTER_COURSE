package com.opwait.ashoreprospect.domain

import java.time.LocalDateTime
import java.time.ZoneId

/** Device-local reminder instants from placed start minus lead minutes. */
object ReminderFires {
    data class Fire(
        val taskId: String,
        val title: String,
        val leadMinutes: Int,
        val fireAtEpochMs: Long,
    )

    fun upcoming(
        tasks: List<Task>,
        enabled: Boolean,
        leads: Set<Int>,
        nowEpochMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        maxAlarms: Int = 80,
    ): List<Fire> {
        if (!enabled || leads.isEmpty()) return emptyList()
        val out = mutableListOf<Fire>()
        for (task in tasks) {
            if (task.isQuick() || task.workflow == Workflow.FINISHED) continue
            val date = task.placedDate ?: continue
            val time = task.placedTime ?: continue
            val startMs = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
            for (lead in leads) {
                if (lead < 0) continue
                val fireAt = startMs - lead * 60_000L
                if (fireAt > nowEpochMs) {
                    out.add(Fire(task.id, VaultSeal.displayTitle(task.title), lead, fireAt))
                }
            }
        }
        return out.sortedWith(compareBy<Fire> { it.fireAtEpochMs }.thenBy { it.taskId }.thenBy { it.leadMinutes })
            .take(maxAlarms)
    }

    fun headline(title: String, leadMinutes: Int): String = when (leadMinutes) {
        0 -> title
        1440 -> "$title · 1 day"
        else -> "$title · ${leadMinutes}m"
    }
}
