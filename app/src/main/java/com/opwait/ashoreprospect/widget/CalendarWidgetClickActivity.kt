package com.opwait.ashoreprospect.widget

import android.app.Activity
import android.os.Bundle

/**
 * Widget host on this phone delivers activity PendingIntents (App works) but not
 * broadcasts, so month/day clicks start this trampoline, update the widget, and finish.
 */
class CalendarWidgetClickActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CalendarWidgetClickDispatch.enqueue(applicationContext, intent)
        finish()
    }
}
