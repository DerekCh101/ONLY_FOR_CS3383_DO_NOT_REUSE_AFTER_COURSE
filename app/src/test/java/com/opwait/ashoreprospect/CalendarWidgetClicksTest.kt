package com.opwait.ashoreprospect

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.opwait.ashoreprospect.domain.AppTimeReference
import com.opwait.ashoreprospect.widget.CalendarWidgetClicks
import com.opwait.ashoreprospect.widget.CalendarWidgetReceiver
import com.opwait.ashoreprospect.widget.CalendarWidgetStore
import com.opwait.ashoreprospect.widget.CalendarWidgetUiState
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CalendarWidgetClicksTest {
    private val today = LocalDate.of(2026, 10, 23)

    @Before
    fun freezeClock() {
        AppTimeReference.setClock(
            Clock.fixed(LocalDateTime.of(today, LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
        )
    }

    @After
    fun restoreClock() {
        AppTimeReference.setClock(Clock.systemDefaultZone())
    }

    private fun apply(op: String, value: String = "", current: CalendarWidgetUiState = CalendarWidgetUiState()) =
        CalendarWidgetClicks.apply(op, value, current, today)

    @Test
    fun prevAndNextChangeMonthAndStack() {
        val next = apply(CalendarWidgetReceiver.OP_NEXT)
        assertEquals(1, next!!.monthOffset)
        val next2 = apply(CalendarWidgetReceiver.OP_NEXT, current = next)
        assertEquals(2, next2!!.monthOffset)
        val prev = apply(CalendarWidgetReceiver.OP_PREV, current = next2)
        assertEquals(1, prev!!.monthOffset)
        val back = apply(CalendarWidgetReceiver.OP_PREV, current = prev)
        assertEquals(0, back!!.monthOffset)
        val earlier = apply(CalendarWidgetReceiver.OP_PREV, current = back)
        assertEquals(-1, earlier!!.monthOffset)
    }

    @Test
    fun todaySelectsTodayInMonthView() {
        val fromElsewhere = CalendarWidgetUiState(
            dayMode = true,
            selectedDate = LocalDate.of(2026, 8, 1),
            monthOffset = 3,
        )
        val next = apply(CalendarWidgetReceiver.OP_TODAY, current = fromElsewhere)!!
        assertEquals(today, next.selectedDate)
        assertFalse(next.dayMode)
        assertEquals(0, next.monthOffset)
    }

    @Test
    fun toggleDayModeOnUsesTodayAndOffKeepsSelection() {
        val start = CalendarWidgetUiState(selectedDate = LocalDate.of(2026, 10, 10))
        val day = apply(CalendarWidgetReceiver.OP_TOGGLE, current = start)!!
        assertTrue(day.dayMode)
        assertEquals(today, day.selectedDate)
        val month = apply(CalendarWidgetReceiver.OP_TOGGLE, current = day)!!
        assertFalse(month.dayMode)
        assertEquals(today, month.selectedDate)
    }

    @Test
    fun clearResetsSelectionModeAndMonth() {
        val dirty = CalendarWidgetUiState(
            dayMode = true,
            selectedDate = LocalDate.of(2026, 12, 1),
            monthOffset = 2,
        )
        val next = apply(CalendarWidgetReceiver.OP_CLEAR, current = dirty)!!
        assertTrue(next.isDefault)
        assertNull(next.selectedDate)
        assertFalse(next.dayMode)
        assertEquals(0, next.monthOffset)
    }

    @Test
    fun dayTapSelectsDateAndJumpsMonth() {
        val november = apply(CalendarWidgetReceiver.OP_DAY, "2026-11-15")!!
        assertEquals(LocalDate.of(2026, 11, 15), november.selectedDate)
        assertFalse(november.dayMode)
        assertEquals(1, november.monthOffset)

        val september = apply(CalendarWidgetReceiver.OP_DAY, "2026-09-30")!!
        assertEquals(LocalDate.of(2026, 9, 30), september.selectedDate)
        assertEquals(-1, september.monthOffset)
    }

    @Test
    fun dayTapRejectsBadValue() {
        assertNull(apply(CalendarWidgetReceiver.OP_DAY, "not-a-date"))
        assertNull(apply(CalendarWidgetReceiver.OP_DAY, ""))
    }

    @Test
    fun finishLeavesUiUnchanged() {
        val current = CalendarWidgetUiState(monthOffset = 4, selectedDate = today)
        assertEquals(current, apply(CalendarWidgetReceiver.OP_FINISH, "task-1", current))
    }

    @Test
    fun newOpenEntersAddModeOnSelectedOrToday() {
        val opened = apply(CalendarWidgetReceiver.OP_NEW_OPEN)!!
        assertTrue(opened.addMode)
        assertEquals(today, opened.selectedDate)
        val withDay = apply(
            CalendarWidgetReceiver.OP_NEW_OPEN,
            current = CalendarWidgetUiState(selectedDate = LocalDate.of(2026, 11, 1)),
        )!!
        assertTrue(withDay.addMode)
        assertEquals(LocalDate.of(2026, 11, 1), withDay.selectedDate)
    }

    @Test
    fun newDurSetsHalfHours() {
        val ui = apply(CalendarWidgetReceiver.OP_NEW_DUR, "1", CalendarWidgetUiState(addMode = true))!!
        assertEquals(2, ui.addHalfHours)
        assertEquals(1.0, ui.addDurationHours, 0.001)
        val half = apply(CalendarWidgetReceiver.OP_NEW_DUR, "0.5", ui)!!
        assertEquals(1, half.addHalfHours)
    }

    @Test
    fun newCancelLeavesAddMode() {
        val next = apply(CalendarWidgetReceiver.OP_NEW_CANCEL, current = CalendarWidgetUiState(addMode = true))!!
        assertFalse(next.addMode)
    }

    @Test
    fun unknownOpIsIgnored() {
        assertNull(apply("explode"))
    }

    @Test
    fun parsePrefersExtrasAndFillsWidgetIdFromPath() {
        val fromExtras = CalendarWidgetClicks.parse(
            opExtra = CalendarWidgetReceiver.OP_NEXT,
            valueExtra = "",
            appWidgetIdExtra = 7,
            pathSegments = listOf("7", "prev", "_"),
        )!!
        assertEquals(CalendarWidgetReceiver.OP_NEXT, fromExtras.op)
        assertEquals("", fromExtras.value)
        assertEquals(7, fromExtras.appWidgetId)
    }

    @Test
    fun parseFallsBackToPathWhenLauncherDropsExtras() {
        val parsed = CalendarWidgetClicks.parse(
            opExtra = null,
            valueExtra = null,
            appWidgetIdExtra = -1,
            pathSegments = listOf("12", "day", "2026-11-03"),
        )!!
        assertEquals(CalendarWidgetReceiver.OP_DAY, parsed.op)
        assertEquals("2026-11-03", parsed.value)
        assertEquals(12, parsed.appWidgetId)
    }

    @Test
    fun parseBlankPathValueIsEmpty() {
        val parsed = CalendarWidgetClicks.parse(
            opExtra = null,
            valueExtra = null,
            appWidgetIdExtra = -1,
            pathSegments = listOf("3", "prev", "_"),
        )!!
        assertEquals(CalendarWidgetReceiver.OP_PREV, parsed.op)
        assertEquals("", parsed.value)
        assertEquals(3, parsed.appWidgetId)
    }

    @Test
    fun parseRejectsUnknownPath() {
        assertNull(
            CalendarWidgetClicks.parse(
                opExtra = null,
                valueExtra = null,
                appWidgetIdExtra = -1,
                pathSegments = listOf("3", "hack", "x"),
            ),
        )
    }

    @Test
    fun pickUiPrefersNonDefaultGlanceThenStored() {
        val glance = CalendarWidgetUiState(monthOffset = 2)
        val stored = CalendarWidgetUiState(selectedDate = today)
        assertEquals(glance, CalendarWidgetClicks.pickUi(glance, stored))
        assertEquals(stored, CalendarWidgetClicks.pickUi(CalendarWidgetUiState(), stored))
        assertTrue(CalendarWidgetClicks.pickUi(null, null).isDefault)
    }

    @Test
    fun glancePrefsWriteMutatesTheSameObjectTheLambdaReceives() {
        val prefs = mutablePreferencesOf().toMutablePreferences()
        val same = prefs
        CalendarWidgetStore.write(
            prefs,
            CalendarWidgetUiState(dayMode = true, selectedDate = today, monthOffset = 2),
        )
        assertTrue(same === prefs)
        val roundTrip = CalendarWidgetStore.fromPrefs(prefs)
        assertTrue(roundTrip.dayMode)
        assertEquals(today, roundTrip.selectedDate)
        assertEquals(2, roundTrip.monthOffset)
    }

    @Test
    fun toolbarSequenceMonthThenDayThenClear() {
        var ui = CalendarWidgetUiState()
        ui = apply(CalendarWidgetReceiver.OP_NEXT, current = ui)!!
        ui = apply(CalendarWidgetReceiver.OP_NEXT, current = ui)!!
        assertEquals(2, ui.monthOffset)
        ui = apply(CalendarWidgetReceiver.OP_DAY, "2026-12-25", ui)!!
        assertEquals(LocalDate.of(2026, 12, 25), ui.selectedDate)
        assertEquals(2, ui.monthOffset)
        ui = apply(CalendarWidgetReceiver.OP_TOGGLE, current = ui)!!
        assertTrue(ui.dayMode)
        assertEquals(today, ui.selectedDate)
        ui = apply(CalendarWidgetReceiver.OP_CLEAR, current = ui)!!
        assertTrue(ui.isDefault)
    }

    @Test
    fun decodeValueTreatsUnderscoreAsEmpty() {
        assertEquals("", CalendarWidgetClicks.decodeValue("_"))
        assertEquals("", CalendarWidgetClicks.decodeValue(""))
        assertEquals("task-1", CalendarWidgetClicks.decodeValue("task-1"))
        assertNotNull(CalendarWidgetClicks.decodeValue("a%20b"))
    }
}
