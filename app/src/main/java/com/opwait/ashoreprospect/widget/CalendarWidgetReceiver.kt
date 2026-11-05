package com.opwait.ashoreprospect.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CalendarWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                appWidgetIds.forEach { CalendarWidgetViews.push(context.applicationContext, it) }
            } catch (t: Throwable) {
                Log.e(TAG, "widget update failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CLICK) {
            val pending = goAsync()
            scope.launch {
                try {
                    CalendarWidgetClickDispatch.handle(context.applicationContext, intent)
                } catch (t: Throwable) {
                    Log.e(TAG, "widget click failed", t)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val TAG = "AshoreWidget"
        const val ACTION_CLICK = "com.opwait.ashoreprospect.widget.CLICK"
        const val EXTRA_OP = "op"
        const val EXTRA_VALUE = "value"
        const val OP_PREV = "prev"
        const val OP_NEXT = "next"
        const val OP_TODAY = "today"
        const val OP_TOGGLE = "toggle"
        const val OP_CLEAR = "clear"
        const val OP_DAY = "day"
        const val OP_FINISH = "finish"
        const val OP_CANCEL = "cancel"
        const val OP_NEW_OPEN = "new_open"
        const val OP_NEW_CANCEL = "new_cancel"
        const val OP_NEW_DUR = "new_dur"
        const val OP_NEW_CREATE = "new_create"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
