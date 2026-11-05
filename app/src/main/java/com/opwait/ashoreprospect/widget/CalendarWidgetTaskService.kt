package com.opwait.ashoreprospect.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.opwait.ashoreprospect.R

class CalendarWidgetTaskService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        return Factory(applicationContext, appWidgetId)
    }

    private class Factory(
        private val context: Context,
        private val appWidgetId: Int,
    ) : RemoteViewsFactory {
        private var rows: List<CalendarWidgetTaskRow> = emptyList()

        override fun onCreate() {}

        override fun onDataSetChanged() {
            rows = CalendarWidgetTaskCache.read(context, appWidgetId)
        }

        override fun onDestroy() {
            rows = emptyList()
        }

        override fun getCount(): Int = rows.size

        override fun getViewAt(position: Int): RemoteViews {
            if (position !in rows.indices) {
                return RemoteViews(context.packageName, R.layout.calendar_widget_task)
            }
            val task = rows[position]
            val row = RemoteViews(context.packageName, R.layout.calendar_widget_task)
            row.setTextViewText(R.id.task_time, task.timeLabel)
            row.setTextColor(R.id.task_time, task.mutedColor)
            if (task.finished) {
                val struck = SpannableString(task.title)
                struck.setSpan(StrikethroughSpan(), 0, struck.length, 0)
                row.setTextViewText(R.id.task_title, struck)
            } else {
                row.setTextViewText(R.id.task_title, task.title)
            }
            row.setTextColor(R.id.task_title, task.titleColor)

            when {
                task.placed && !task.finished -> {
                    row.setTextViewText(R.id.task_finish, "Finish")
                    row.setTextColor(R.id.task_finish, task.creamColor)
                    row.setOnClickFillInIntent(
                        R.id.task_finish,
                        fill(CalendarWidgetReceiver.OP_FINISH, task.id),
                    )
                }
                task.placed && task.finished -> {
                    row.setTextViewText(R.id.task_finish, "Done")
                    row.setTextColor(R.id.task_finish, task.mutedColor)
                    row.setOnClickFillInIntent(
                        R.id.task_finish,
                        fill(CalendarWidgetReceiver.OP_FINISH, task.id),
                    )
                }
                else -> row.setTextViewText(R.id.task_finish, "")
            }
            if (task.finished) {
                row.setViewVisibility(R.id.task_cancel, View.VISIBLE)
                row.setTextColor(R.id.task_cancel, task.overdueColor)
                row.setOnClickFillInIntent(
                    R.id.task_cancel,
                    fill(CalendarWidgetReceiver.OP_CANCEL, task.id),
                )
            } else {
                row.setViewVisibility(R.id.task_cancel, View.GONE)
            }
            return row
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long =
            rows.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds(): Boolean = true

        private fun fill(op: String, value: String): Intent =
            Intent().apply {
                putExtra(CalendarWidgetReceiver.EXTRA_OP, op)
                putExtra(CalendarWidgetReceiver.EXTRA_VALUE, value)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
    }
}
