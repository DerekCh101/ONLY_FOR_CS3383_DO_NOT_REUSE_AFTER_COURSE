package com.opwait.ashoreprospect.widget

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object CalendarWidgetRefresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun updateAll(context: Context) {
        CalendarWidgetViews.pushAll(context.applicationContext)
    }

    fun updateAllAsync(context: Context) {
        scope.launch { updateAll(context) }
    }
}
