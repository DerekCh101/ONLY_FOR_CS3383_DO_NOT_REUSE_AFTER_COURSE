package com.opwait.ashoreprospect.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CalendarWidgetTaskRow(
    val id: String,
    val title: String,
    val timeLabel: String,
    val finished: Boolean,
    val placed: Boolean,
    val overdue: Boolean,
    val titleColor: Int,
    val mutedColor: Int,
    val creamColor: Int,
    val overdueColor: Int,
)

object CalendarWidgetTaskCache {
    private const val NAME = "calendar_widget_tasks"

    fun write(context: Context, appWidgetId: Int, rows: List<CalendarWidgetTaskRow>) {
        val arr = JSONArray()
        for (r in rows) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("title", r.title)
                    .put("time", r.timeLabel)
                    .put("finished", r.finished)
                    .put("placed", r.placed)
                    .put("overdue", r.overdue)
                    .put("titleColor", r.titleColor)
                    .put("mutedColor", r.mutedColor)
                    .put("creamColor", r.creamColor)
                    .put("overdueColor", r.overdueColor),
            )
        }
        prefs(context).edit().putString(key(appWidgetId), arr.toString()).commit()
    }

    fun read(context: Context, appWidgetId: Int): List<CalendarWidgetTaskRow> {
        val raw = prefs(context).getString(key(appWidgetId), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        CalendarWidgetTaskRow(
                            id = o.getString("id"),
                            title = o.getString("title"),
                            timeLabel = o.optString("time"),
                            finished = o.optBoolean("finished"),
                            placed = o.optBoolean("placed"),
                            overdue = o.optBoolean("overdue"),
                            titleColor = o.getInt("titleColor"),
                            mutedColor = o.getInt("mutedColor"),
                            creamColor = o.getInt("creamColor"),
                            overdueColor = o.getInt("overdueColor"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private fun key(id: Int) = "w$id"
}
