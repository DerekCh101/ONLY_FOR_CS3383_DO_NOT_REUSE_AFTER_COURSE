package com.opwait.ashoreprospect

import com.opwait.ashoreprospect.data.SessionStore
import com.opwait.ashoreprospect.data.looksLikeTypewriterJson
import com.opwait.ashoreprospect.data.parseTypewriterBundle
import com.opwait.ashoreprospect.data.parseTypewriterMaybeSealed
import com.opwait.ashoreprospect.data.toJson
import com.opwait.ashoreprospect.domain.DescriptionChecklist
import com.opwait.ashoreprospect.domain.ReminderFires
import com.opwait.ashoreprospect.domain.ImageMetadataStrip
import com.opwait.ashoreprospect.domain.VaultCrypto
import com.opwait.ashoreprospect.ui.applyEditorTab
import com.opwait.ashoreprospect.ui.applyInlineWrap
import com.opwait.ashoreprospect.ui.applyLinePrefix
import com.opwait.ashoreprospect.ui.lineNumberGutter
import com.opwait.ashoreprospect.domain.VaultSeal
import com.opwait.ashoreprospect.domain.AppTimeReference
import com.opwait.ashoreprospect.domain.AuthRules
import com.opwait.ashoreprospect.domain.BlockedTime
import com.opwait.ashoreprospect.domain.BlockedTimePeriod
import com.opwait.ashoreprospect.domain.CarryUnfinishedPast
import com.opwait.ashoreprospect.domain.DayGrid
import com.opwait.ashoreprospect.domain.MonthCellTasks
import com.opwait.ashoreprospect.domain.NestedTasks
import com.opwait.ashoreprospect.domain.TaskDue
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.opwait.ashoreprospect.widget.CalendarWidgetStore
import com.opwait.ashoreprospect.widget.CalendarWidgetUiState
import com.opwait.ashoreprospect.domain.SchedulerSession
import com.opwait.ashoreprospect.domain.Task
import com.opwait.ashoreprospect.domain.TypewriterBundle
import com.opwait.ashoreprospect.domain.TypewriterHtml
import com.opwait.ashoreprospect.ui.theme.AppThemePack
import com.opwait.ashoreprospect.ui.theme.SchedulerColors
import com.opwait.ashoreprospect.ui.theme.cssHex
import com.opwait.ashoreprospect.domain.TypewriterDiaryEntry
import com.opwait.ashoreprospect.domain.Workflow
import com.opwait.ashoreprospect.domain.TaskPlacement
import com.opwait.ashoreprospect.domain.TaskStateKind
import com.opwait.ashoreprospect.domain.ValidateAddTaskInput
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

class ParityUnitTest {
    @Test
    fun dayStartsAtSixAndHasFortyEightSlots() {
        assertEquals(6, AppTimeReference.GRID_START_HOUR)
        assertEquals(48, AppTimeReference.SLOTS_PER_DAY)
        assertEquals(LocalTime.of(6, 0), AppTimeReference.slotToLocalTime(0))
        assertEquals(LocalTime.of(0, 0), AppTimeReference.slotToLocalTime(36))
        assertTrue(AppTimeReference.formatSlotLabel(36).endsWith("+"))
    }

    @Test
    fun occupancySlotsFloorStartAndCeilEndLikeJavaFx() {
        assertEquals(125, AppTimeReference.hoursToMinutes(125 / 60.0))
        assertEquals(78, AppTimeReference.hoursToMinutes(1.3))
        assertEquals(5, AppTimeReference.minutesToSlots(125))
        val slots = AppTimeReference.occupancySlots(LocalTime.of(12, 23), 125)
        assertEquals(5, slots)
        val startSlot = AppTimeReference.localTimeToSlot(LocalTime.of(12, 23))
        assertEquals(AppTimeReference.localTimeToSlot(LocalTime.of(12, 0)), startSlot)
        val endSlot = startSlot + slots
        assertEquals(AppTimeReference.localTimeToSlot(LocalTime.of(14, 30)), endSlot)
        val t = Task.newTask("x", 125 / 60.0, TaskStateKind.FIXED, LocalDate.of(2026, 10, 21), LocalTime.of(12, 23))
        t.placedDate = LocalDate.of(2026, 10, 21)
        t.placedTime = LocalTime.of(12, 23)
        assertEquals(5, t.durationSlots)
        assertEquals(125, kotlin.math.round(t.durationMinutes).toInt())
    }

    @Test
    fun pastSlotsAreBeforeTheCurrentSlot() {
        val zone = ZoneId.of("UTC")
        AppTimeReference.setClock(
            Clock.fixed(LocalDateTime.of(2026, 10, 18, 15, 0).atZone(zone).toInstant(), zone),
        )
        try {
            val col = AppTimeReference.currentColumnDate()
            val nowSlot = AppTimeReference.currentSlotIndex()
            assertTrue(AppTimeReference.isSlotInPast(col, nowSlot - 1))
            assertFalse(AppTimeReference.isSlotInPast(col, nowSlot))
            assertFalse(AppTimeReference.isSlotInPast(col, nowSlot + 1))
            assertTrue(AppTimeReference.isSlotInPast(col.minusDays(1), 0))
            assertFalse(AppTimeReference.isSlotInPast(col.plusDays(1), 0))
        } finally {
            AppTimeReference.setClock(Clock.systemDefaultZone())
        }
    }

    @Test
    fun weekZeroUsesColumnDateBeforeSix() {
        val zone = java.time.ZoneId.of("UTC")
        val mondayNight = java.time.LocalDateTime.of(2026, 10, 12, 1, 30)
        AppTimeReference.setClock(
            java.time.Clock.fixed(mondayNight.atZone(zone).toInstant(), zone),
        )
        try {
            assertEquals(java.time.DayOfWeek.MONDAY, LocalDate.of(2026, 10, 12).dayOfWeek)
            assertEquals(LocalDate.of(2026, 10, 11), AppTimeReference.currentColumnDate())
            assertEquals(LocalDate.of(2026, 10, 5), AppTimeReference.currentWeekStart())
            assertEquals(java.time.YearMonth.of(2026, 10), AppTimeReference.currentMonth())
            assertEquals(LocalDate.of(2026, 10, 12), AppTimeReference.nextOpenDateFor(LocalTime.of(9, 0)))
            assertEquals(LocalDate.of(2026, 10, 12), AppTimeReference.nextOpenDateFor(LocalTime.of(17, 0)))
            assertEquals(LocalDate.of(2026, 10, 13), AppTimeReference.nextOpenDateFor(LocalTime.of(1, 0)))
        } finally {
            AppTimeReference.setClock(java.time.Clock.systemDefaultZone())
        }
    }

    @Test
    fun schedulerSearchesCurrentColumnBeforeSix() {
        val zone = java.time.ZoneId.of("UTC")
        val mondayNight = java.time.LocalDateTime.of(2026, 10, 12, 1, 30)
        AppTimeReference.setClock(
            java.time.Clock.fixed(mondayNight.atZone(zone).toInstant(), zone),
        )
        try {
            val session = SchedulerSession()
            val flex = Task.newTask("flex", 1.0, TaskStateKind.FLEXIBLE)
            session.pendingTasks.add(flex)
            session.updateSchedule()
            assertTrue(flex in session.placedTasks)
            assertEquals(LocalTime.of(2, 0), flex.placedTime)
            assertEquals(
                LocalDate.of(2026, 10, 11),
                AppTimeReference.columnDateFor(flex.placedDate, flex.placedTime),
            )
        } finally {
            AppTimeReference.setClock(java.time.Clock.systemDefaultZone())
        }
    }

    @Test
    fun weekdayEncodingSundayIsZero() {
        assertEquals(0, BlockedTime.weekdayToInt(java.time.DayOfWeek.SUNDAY))
        assertEquals(java.time.DayOfWeek.SUNDAY, BlockedTime.weekdayToDayOfWeek(0))
        assertEquals(java.time.DayOfWeek.MONDAY, BlockedTime.weekdayToDayOfWeek(1))
    }

    @Test
    fun weeklyBlockedConflictsOnlySameWeekday() {
        val mon = BlockedTime.weekly(java.time.DayOfWeek.MONDAY, "gym", LocalTime.of(7, 0), LocalTime.of(8, 0))
        val tue = BlockedTime.weekly(java.time.DayOfWeek.TUESDAY, "gym", LocalTime.of(7, 0), LocalTime.of(8, 0))
        val mon2 = BlockedTime.weekly(java.time.DayOfWeek.MONDAY, "run", LocalTime.of(7, 30), LocalTime.of(8, 30))
        assertFalse(BlockedTimePeriod.sameLayer(mon, tue))
        assertTrue(BlockedTimePeriod.sameLayer(mon, mon2))
        val period = BlockedTimePeriod()
        period.add(mon)
        assertEquals(null, period.conflictMessage(tue))
        assertTrue(period.conflictMessage(mon2)!!.contains("Overlaps"))
        val monday = LocalDate.of(2026, 10, 12)
        assertEquals(java.time.DayOfWeek.MONDAY, monday.dayOfWeek)
        assertTrue(period.isBlocked(monday, LocalTime.of(7, 15)))
        assertFalse(period.isBlocked(monday.plusDays(1), LocalTime.of(7, 15)))
    }

    @Test
    fun datedBlockedExpiresAfterFiveMinuteGrace() {
        val day = LocalDate.of(2026, 10, 12)
        val row = BlockedTime.once(day, "lunch", LocalTime.of(12, 0), LocalTime.of(13, 0))
        val justAfterEnd = java.time.LocalDateTime.of(day, LocalTime.of(13, 3))
        assertFalse(row.isDatedExpired(justAfterEnd, graceMinutes = 5))
        val afterGrace = java.time.LocalDateTime.of(day, LocalTime.of(13, 6))
        assertTrue(row.isDatedExpired(afterGrace, graceMinutes = 5))
        val daily = BlockedTime.daily("always", LocalTime.of(12, 0), LocalTime.of(13, 0))
        assertFalse(daily.isDatedExpired(afterGrace, graceMinutes = 5))
    }

    @Test
    fun exceptionFreesDailyBlock() {
        val period = BlockedTimePeriod()
        val day = LocalDate.of(2026, 10, 12)
        period.add(BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0)))
        assertTrue(period.isBlocked(day, LocalTime.of(12, 15)))
        period.add(
            BlockedTime.dailyRange(day, day, "free", LocalTime.of(12, 0), LocalTime.of(13, 0), exception = true).first(),
        )
        assertFalse(period.isBlocked(day, LocalTime.of(12, 15)))
    }

    @Test
    fun dailyOverlapRejected() {
        val period = BlockedTimePeriod()
        period.add(BlockedTime.daily("a", LocalTime.of(9, 0), LocalTime.of(11, 0)))
        val conflict = period.conflictMessage(BlockedTime.daily("b", LocalTime.of(10, 0), LocalTime.of(12, 0)))
        assertTrue(conflict != null)
    }

    @Test
    fun flexibleNeedsNoDates() {
        assertTrue(
            ValidateAddTaskInput.validate("x", 1.0, TaskStateKind.FLEXIBLE, null, null, null, null),
        )
        assertFalse(
            ValidateAddTaskInput.validate("", 1.0, TaskStateKind.FLEXIBLE, null, null, null, null),
        )
    }

    @Test
    fun flexibleNeverUsesBlockedDeadlinePrefersThenAllows() {
        val zone = ZoneId.of("UTC")
        AppTimeReference.setClock(Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), zone))
        try {
            val day = LocalDate.of(2026, 10, 13)
            val session = SchedulerSession()
            session.baseDate = day
            session.blockedPeriod.add(
                BlockedTime.daily("breakfast", LocalTime.of(6, 0), LocalTime.of(8, 0)),
            )

            val flex = Task.newTask("flex", 1.0, TaskStateKind.FLEXIBLE)
            session.pendingTasks.add(flex)
            session.updateSchedule()
            assertTrue(flex in session.placedTasks)
            val flexTime = flex.placedTime!!
            assertFalse(flexTime.hour == 6 || flexTime.hour == 7)

            val easy = Task.newTask(
                "easy-dl",
                1.0,
                TaskStateKind.DEADLINE,
                deadlineDate = day,
                deadlineTime = LocalTime.of(18, 0),
            )
            session.pendingTasks.add(easy)
            session.updateSchedule()
            assertTrue(easy in session.placedTasks)
            val easyTime = easy.placedTime!!
            assertFalse(easyTime.hour == 6 || easyTime.hour == 7)

            val tight = Task.newTask(
                "tight-dl",
                1.0,
                TaskStateKind.DEADLINE,
                deadlineDate = day,
                deadlineTime = LocalTime.of(7, 0),
            )
            session.pendingTasks.add(tight)
            session.updateSchedule()
            assertTrue(tight in session.placedTasks)
            assertEquals(LocalTime.of(6, 0), tight.placedTime)
        } finally {
            AppTimeReference.setClock(Clock.systemDefaultZone())
        }
    }

    @Test
    fun registerRejectsMissingRecoveryFields() {
        assertTrue(AuthRules.registerError("u", "short", "", "", "") != null)
        assertTrue(AuthRules.registerError("u", "password1", "not-an-email", "question long enough", "answ") != null)
        assertTrue(AuthRules.registerError("u", "password1", "a@b.com", "short", "answ") != null)
        assertTrue(AuthRules.registerError("u", "password1", "a@b.com", "question long enough", "ab") != null)
        assertEquals(
            null,
            AuthRules.registerError("u", "password1", "a@b.com", "question long enough", "answ"),
        )
    }

    @Test
    fun resetNeedsTokenAnswerAndLongPassword() {
        assertTrue(AuthRules.resetError("", "answer", "password1") != null)
        assertTrue(AuthRules.resetError("tok", "ab", "password1") != null)
        assertTrue(AuthRules.resetError("tok", "answer", "short") != null)
        assertEquals(null, AuthRules.resetError("tok", "answer", "password1"))
    }

    @Test
    fun vaultSealIsAp1PrefixOnly() {
        assertTrue(VaultSeal.isSealed("AP1.abc"))
        assertFalse(VaultSeal.isSealed("hello"))
        assertEquals("Untitled", VaultSeal.displayTitle("AP1.abc"))
        assertEquals("Standup", VaultSeal.displayTitle("Standup"))
        assertEquals("", VaultSeal.displayTitle(""))
    }

    @Test
    fun idleMinutesClampNeverTwoToThirty() {
        assertEquals(0, SessionStore.clampIdleMinutes(0))
        assertEquals(0, SessionStore.clampIdleMinutes(-3))
        assertEquals(2, SessionStore.clampIdleMinutes(1))
        assertEquals(10, SessionStore.clampIdleMinutes(10))
        assertEquals(7, SessionStore.clampIdleMinutes(7))
        assertEquals(30, SessionStore.clampIdleMinutes(99))
    }

    @Test
    fun typewriterLineGutterCountsLogicalLines() {
        assertEquals("1", lineNumberGutter(""))
        assertEquals("1\n2\n3", lineNumberGutter("a\nb\nc"))
    }

    @Test
    fun editorTabInsertsFourSpacesLikeVsCode() {
        val (next, start, end) = applyEditorTab("ab", 1, 1, outdent = false)
        assertEquals("a    b", next)
        assertEquals(5, start)
        assertEquals(5, end)
        val indented = applyEditorTab("one\ntwo", 0, 7, outdent = false)
        assertEquals("    one\n    two", indented.first)
        val out = applyEditorTab("    one\n    two", 0, 15, outdent = true)
        assertEquals("one\ntwo", out.first)
    }

    @Test
    fun inlineWrapTogglesBoldMarkers() {
        val wrapped = applyInlineWrap("Hello", 0, 5, "**", "**")
        assertEquals("**Hello**", wrapped.first)
        assertEquals(2, wrapped.second)
        assertEquals(7, wrapped.third)
        val peeled = applyInlineWrap(wrapped.first, 2, 7, "**", "**")
        assertEquals("Hello", peeled.first)
    }

    @Test
    fun linePrefixTogglesHeadingLikeHtmlEditor() {
        val h1 = applyLinePrefix("Hello", 0, 5, "# ")
        assertEquals("# Hello", h1.first)
        val back = applyLinePrefix(h1.first, 0, h1.first.length, "# ")
        assertEquals("Hello", back.first)
        val h2 = applyLinePrefix("# Hello", 0, 7, "## ")
        assertEquals("## Hello", h2.first)
    }

    @Test
    fun vaultCryptoMatchesDesktopAp1RoundTrip() {
        try {
            VaultCrypto.lock()
            assertTrue(VaultCrypto.unlock("user-1", "password1"))
            VaultCrypto.enableWrites()
            val sealed = VaultCrypto.seal("Standup notes")
            assertTrue(VaultSeal.isSealed(sealed))
            assertEquals("Standup notes", VaultCrypto.open(sealed))
            assertEquals("Standup notes", VaultSeal.displayTitle(sealed))
            VaultCrypto.lock()
            assertEquals(sealed, VaultCrypto.open(sealed))
            assertTrue(VaultCrypto.unlock("user-1", "wrong-password-xx"))
            assertEquals(sealed, VaultCrypto.open(sealed))
        } finally {
            VaultCrypto.lock()
        }
    }

    @Test
    fun reminderFiresSkipPastAndFinished() {
        val zone = java.time.ZoneId.of("UTC")
        val start = java.time.LocalDateTime.of(2026, 10, 17, 15, 0)
        val startMs = start.atZone(zone).toInstant().toEpochMilli()
        val placed = Task.newTask("Standup", 1.0, TaskStateKind.FIXED, start.toLocalDate(), start.toLocalTime())
        placed.placedDate = start.toLocalDate()
        placed.placedTime = start.toLocalTime()
        val finished = placed.copy(id = "fin", workflow = com.opwait.ashoreprospect.domain.Workflow.FINISHED)
        val fires = ReminderFires.upcoming(
            listOf(placed, finished),
            enabled = true,
            leads = setOf(0, 5, 1440),
            nowEpochMs = startMs - 6 * 60_000L,
            zone = zone,
        )
        assertEquals(listOf(5, 0), fires.map { it.leadMinutes })
        assertTrue(fires.none { it.taskId == "fin" })
        assertEquals("Standup · 5m", ReminderFires.headline("Standup", 5))
    }

    @Test
    fun descriptionChecklistRoundTrip() {
        val raw = "[x] bought milk\n[ ] call Sam"
        val lines = DescriptionChecklist.parse(raw)
        assertEquals(2, lines.size)
        assertTrue(lines[0].done)
        assertEquals("bought milk", lines[0].text)
        assertFalse(lines[1].done)
        assertEquals("[x] bought milk\n[ ] call Sam", DescriptionChecklist.encode(lines))
        assertFalse(DescriptionChecklist.allDone(lines))
        lines[1].done = true
        assertTrue(DescriptionChecklist.allDone(lines))
        assertEquals("", DescriptionChecklist.encode(emptyList()))
        assertEquals("[ ] leftover prose", DescriptionChecklist.encode(DescriptionChecklist.parse("leftover prose")))
    }

    @Test
    fun carryUnfinishedPastDropsDeadlineAndFlexibleOnly() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.LocalDateTime.of(2026, 10, 17, 12, 0)
        AppTimeReference.setClock(java.time.Clock.fixed(now.atZone(zone).toInstant(), zone))
        try {
            val day = LocalDate.of(2026, 10, 16)
            val dead = Task.newTask("Due", 1.0, TaskStateKind.DEADLINE, deadlineDate = day, deadlineTime = LocalTime.of(17, 0))
            dead.placedDate = day
            dead.placedTime = LocalTime.of(10, 0)
            val flex = Task.newTask("Flex", 1.0, TaskStateKind.FLEXIBLE)
            flex.placedDate = day
            flex.placedTime = LocalTime.of(10, 0)
            val fixed = Task.newTask("Fixed", 1.0, TaskStateKind.FIXED, day, LocalTime.of(10, 0))
            fixed.placedDate = day
            fixed.placedTime = LocalTime.of(10, 0)
            val done = dead.copy(id = "fin", workflow = Workflow.FINISHED)
            done.placedDate = day
            done.placedTime = LocalTime.of(10, 0)
            val (out, n) = CarryUnfinishedPast.prepare(listOf(dead, flex, fixed, done))
            assertEquals(2, n)
            assertEquals(null, out[0].placedDate)
            assertEquals(null, out[1].placedDate)
            assertEquals(day, out[2].placedDate)
            assertEquals(day, out[3].placedDate)
        } finally {
            AppTimeReference.setClock(java.time.Clock.systemDefaultZone())
        }
    }

    @Test
    fun monthCellIncludesUnscheduledDeadlineOnDueDay() {
        val day = LocalDate.of(2026, 10, 20)
        val placed = Task.newTask("Placed", 1.0, TaskStateKind.FIXED, day, LocalTime.of(10, 0))
        placed.placedDate = day
        placed.placedTime = LocalTime.of(10, 0)
        val due = Task.newTask("Due", 1.0, TaskStateKind.DEADLINE, deadlineDate = day, deadlineTime = LocalTime.of(17, 0))
        val other = Task.newTask("Later", 1.0, TaskStateKind.DEADLINE, deadlineDate = day.plusDays(1), deadlineTime = LocalTime.of(17, 0))
        val finished = due.copy(id = "fin", workflow = Workflow.FINISHED)
        val flex = Task.newTask("Flex", 1.0, TaskStateKind.FLEXIBLE)
        val onDay = MonthCellTasks.onDay(listOf(placed, due, other, finished, flex), day)
        assertEquals(listOf("Placed", "Due"), onDay.map { it.title })
    }

    @Test
    fun widgetMonthOffsetFromColumnMonth() {
        val columnDay = LocalDate.of(2026, 10, 20)
        AppTimeReference.setClock(
            Clock.fixed(LocalDateTime.of(columnDay, LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
        )
        try {
            assertEquals(0, CalendarWidgetStore.monthOffsetFor(LocalDate.of(2026, 10, 1)))
            assertEquals(1, CalendarWidgetStore.monthOffsetFor(LocalDate.of(2026, 11, 15)))
            assertEquals(-1, CalendarWidgetStore.monthOffsetFor(LocalDate.of(2026, 9, 30)))
        } finally {
            AppTimeReference.setClock(Clock.systemDefaultZone())
        }
    }

    @Test
    fun widgetPrefsKeepSelectedDay() {
        val ui = CalendarWidgetUiState(
            dayMode = false,
            selectedDate = LocalDate.of(2026, 10, 20),
            monthOffset = 1,
        )
        val prefs = mutablePreferencesOf()
        CalendarWidgetStore.write(prefs, ui)
        assertEquals(ui, CalendarWidgetStore.fromPrefs(prefs))
        CalendarWidgetStore.write(prefs, ui.copy(selectedDate = null, monthOffset = 0))
        val cleared = CalendarWidgetStore.fromPrefs(prefs)
        assertEquals(null, cleared.selectedDate)
        assertEquals(0, cleared.monthOffset)
    }

    @Test
    fun taskDueOverdueRespectsFinishedAndDeadline() {
        val day = LocalDate.of(2026, 10, 20)
        AppTimeReference.setClock(Clock.fixed(LocalDateTime.of(day, LocalTime.of(18, 0)).toInstant(ZoneOffset.UTC), ZoneOffset.UTC))
        try {
            val open = Task.newTask("Late", 1.0, TaskStateKind.DEADLINE, deadlineDate = day, deadlineTime = LocalTime.of(9, 0))
            assertTrue(TaskDue.isOverdue(open))
            val done = open.copy(workflow = Workflow.FINISHED)
            assertFalse(TaskDue.isOverdue(done))
        } finally {
            AppTimeReference.setClock(Clock.systemDefaultZone())
        }
    }

    @Test
    fun durationChangeRejectsOverlapAndKeepsUnscheduled() {
        val day = LocalDate.of(2026, 10, 20)
        val first = Task.newTask("A", 1.0, TaskStateKind.FIXED, day, LocalTime.of(10, 0))
        first.placedDate = day
        first.placedTime = LocalTime.of(10, 0)
        val second = Task.newTask("B", 1.0, TaskStateKind.FIXED, day, LocalTime.of(11, 0))
        second.placedDate = day
        second.placedTime = LocalTime.of(11, 0)
        val blocked = BlockedTimePeriod()
        assertEquals(
            "Cannot place task in that slot",
            TaskPlacement.durationChangeError(first, 2.0, listOf(first, second), blocked),
        )
        assertEquals(null, TaskPlacement.durationChangeError(first, 1.0, listOf(first, second), blocked))
        val open = Task.newTask("C", 1.0, TaskStateKind.FLEXIBLE)
        assertEquals(null, TaskPlacement.durationChangeError(open, 3.0, emptyList(), blocked))
    }

    @Test
    fun placeErrorRejectsPastAndOverlap() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.LocalDateTime.of(2026, 10, 20, 9, 0)
        AppTimeReference.setClock(java.time.Clock.fixed(now.atZone(zone).toInstant(), zone))
        try {
            val day = now.toLocalDate()
            val open = Task.newTask("Flex", 1.0, TaskStateKind.FLEXIBLE)
            val blocked = BlockedTimePeriod()
            assertEquals(
                "Cannot create a task in the past.",
                TaskPlacement.placeError(open, day, LocalTime.of(8, 0), emptyList(), blocked),
            )
            val occupant = Task.newTask("A", 1.0, TaskStateKind.FIXED, day, LocalTime.of(11, 0))
            occupant.placedDate = day
            occupant.placedTime = LocalTime.of(11, 0)
            assertEquals(
                "Cannot place task in that slot",
                TaskPlacement.placeError(open, day, LocalTime.of(11, 0), listOf(occupant), blocked),
            )
            assertEquals(
                null,
                TaskPlacement.placeError(open, day, LocalTime.of(14, 0), listOf(occupant), blocked),
            )
        } finally {
            AppTimeReference.setClock(java.time.Clock.systemDefaultZone())
        }
    }

    @Test
    fun startedTaskCanStayOnGrid() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.LocalDateTime.of(2026, 10, 17, 12, 0)
        AppTimeReference.setClock(java.time.Clock.fixed(now.atZone(zone).toInstant(), zone))
        try {
            val day = now.toLocalDate()
            val grid = DayGrid()
            val task = Task.newTask("Live", 2.0, TaskStateKind.FIXED, day, LocalTime.of(10, 0))
            val slot = AppTimeReference.localTimeToSlot(LocalTime.of(10, 0))
            assertFalse(grid.canPlace(task, day, slot, ignoreOverload = true))
            assertTrue(
                grid.canPlace(task, day, slot, ignoreOverload = true, ignoreStartInPast = true),
            )
        } finally {
            AppTimeReference.setClock(java.time.Clock.systemDefaultZone())
        }
    }

    @Test
    fun nestedQuickEventDoesNotOccupyAsStandalone() {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.LocalDateTime.of(2026, 10, 17, 9, 0)
        AppTimeReference.setClock(java.time.Clock.fixed(now.atZone(zone).toInstant(), zone))
        try {
            val day = now.toLocalDate()
            val parent = Task.newTask("Class", 2.0, TaskStateKind.FIXED, day, LocalTime.of(16, 30))
            parent.placedDate = day
            parent.placedTime = LocalTime.of(16, 30)
            val nested = Task.newTask("QTE", 0.25, TaskStateKind.FIXED, day, LocalTime.of(17, 0))
            nested.parentId = parent.id
            nested.placedDate = day
            nested.placedTime = LocalTime.of(17, 0)
            assertTrue(nested.isQuick())
            assertFalse(nested.isUnscheduledPending())
            assertEquals(parent.id, NestedTasks.mainTask(listOf(parent, nested), nested).id)
            assertEquals(1, NestedTasks.ofParent(listOf(parent, nested), parent).size)
            val blocked = BlockedTimePeriod()
            val open = Task.newTask("Other", 0.5, TaskStateKind.FLEXIBLE)
            assertEquals(
                "Cannot place task in that slot",
                TaskPlacement.placeError(open, day, LocalTime.of(17, 0), listOf(parent, nested), blocked),
            )
            assertEquals(
                null,
                TaskPlacement.placeError(open, day, LocalTime.of(17, 0), listOf(nested), blocked),
            )
            val onDay = MonthCellTasks.onDay(listOf(parent, nested), day)
            assertEquals(listOf(parent.id), onDay.map { it.id })
        } finally {
            AppTimeReference.setClock(java.time.Clock.systemDefaultZone())
        }
    }

    @Test
    fun jpegExifApp1CountsAsPrivacyMetadata() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x04, 0x00, 0x00,
            0xFF.toByte(), 0xDA.toByte(),
        )
        assertTrue(ImageMetadataStrip.isJpeg(jpeg))
        assertTrue(ImageMetadataStrip.jpegHasPrivacyMarkers(jpeg))
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00,
            'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(),
            0, 0, 0, 0,
        )
        assertTrue(ImageMetadataStrip.isPng(png))
        assertFalse(ImageMetadataStrip.pngHasTextChunks(png))
    }

    @Test
    fun javafxHtmlEditorBodyBecomesReadableText() {
        val html = """<html dir="ltr" style="background-color: rgb(26, 34, 44);"><body contenteditable="true"><p>Hello&nbsp;world</p><p>Second</p></body></html>"""
        assertTrue(TypewriterHtml.looksLikeHtml(html))
        assertEquals("Hello world\nSecond", TypewriterHtml.toReadable(html))
        assertFalse(TypewriterHtml.looksLikeHtml("plain note"))
        assertEquals("plain note", TypewriterHtml.toReadable("plain note"))
    }

    @Test
    fun storedTextKeepsCenterAlignAndDropsEditorShell() {
        val dump =
            """<html><head></head><body contenteditable="true"><p style="text-align: center;">Hello</p></body></html>"""
        val stored = TypewriterHtml.storedText(dump)
        assertFalse(stored.contains("contenteditable"))
        assertFalse(stored.contains("<html"))
        assertTrue(stored.contains("text-align: center"))
        assertEquals(stored, TypewriterHtml.htmlForEditor(stored))
        assertEquals("Hello", TypewriterHtml.plainText(stored))
        val round = TypewriterHtml.storedText(TypewriterHtml.editorDocument(stored, true))
        assertTrue(round.contains("text-align: center"))
        assertEquals("<p>a</p><p>b</p>", TypewriterHtml.htmlForEditor("a\nb"))
        val page = TypewriterHtml.editorDocument("<p>Hello</p>", true)
        assertTrue(page.contains("ashore-ln"))
        assertTrue(page.contains("ashoreFontSize"))
        assertTrue(page.contains("ashoreForeColor"))
        assertTrue(page.contains("padding:8px 12px 24px 44px"))
        assertTrue(page.contains("overflow-y:auto"))
        assertTrue(page.contains("line-height:normal"))
        assertTrue(page.contains("text-underline-position:under"))
        assertTrue(page.contains("text-decoration-skip-ink:none"))
        assertTrue(page.contains("underline line-through"))
        assertTrue(TypewriterHtml.EDITOR_BOOT_JS.contains("ashoreStrikeThrough"))
        assertTrue(TypewriterHtml.EDITOR_BOOT_JS.contains("ashoreUnderline"))
        assertFalse(page.contains("line-height:22px"))
        assertTrue(TypewriterHtml.EDITOR_BOOT_JS.contains("offsetHeight"))
        assertTrue(TypewriterHtml.EDITOR_BOOT_JS.contains("ashorePaintLnAfterLayout"))
        assertFalse(TypewriterHtml.storedText(page).contains("ashorePaintLn"))
        val three = TypewriterHtml.htmlForEditor("a\nb\nHello world")
        assertEquals(3, Regex("(?i)<p\\b").findAll(three).count())
        val withGutter =
            """<p><font style="font-size: 56pt;">Hello world</font></p><div id="ashore-ln"><span>1</span></div>"""
        val inner = TypewriterHtml.storedText(withGutter)
        assertTrue(inner.contains("56pt"))
        assertFalse(inner.contains("ashore-ln"))
    }

    @Test
    fun typewriterHtmlIsNotANotebook() {
        val html = """<html dir="ltr" style="background-color: rgb(26, 34, 44);"><body contenteditable="true"><p><br></p></body></html>"""
        assertFalse(looksLikeTypewriterJson(html))
        assertEquals(null, parseTypewriterMaybeSealed(html, "2026-10-18T00:00:00Z"))
        val empty = TypewriterBundle()
        assertFalse(empty.hasSubstance())
    }

    @Test
    fun typewriterRoundTrip() {
        val json = """{"diary":[{"date":"2026-10-12","id":"d1","title":"t","body":"hello","images":[]}],"lists":[{"id":"l1","title":"L","items":[{"text":"a","done":true}]}],"pages":[{"id":"p1","parent_id":null,"kind":"note","title":"N","body":"b","images":[],"pos":0}]}"""
        val bundle = parseTypewriterBundle(json, "2026-10-12T00:00:00Z")
        assertEquals(1, bundle.diary.size)
        assertEquals("hello", bundle.diary[0].body)
        assertTrue(bundle.lists[0].items[0].done)
        val again = parseTypewriterBundle(bundle.toJson(), bundle.lastUpdatedAt)
        assertEquals("hello", again.diary[0].body)
    }

    @Test
    fun diaryTitleOnlyIsKeptOnPrune() {
        val bundle = TypewriterBundle()
        bundle.diary.add(TypewriterDiaryEntry(date = "2026-10-17", title = "heading"))
        bundle.diary.add(TypewriterDiaryEntry(date = "2026-10-16"))
        bundle.pruneEmptyDiary()
        assertEquals(1, bundle.diary.size)
        assertEquals("heading", bundle.diary[0].title)
        assertTrue(bundle.diary[0].hasContent())
    }

    @Test
    fun javafxLightPackIsCreamPaperDarkInk() {
        AppThemePack.apply(AppThemePack.LIGHT)
        try {
            assertFalse(SchedulerColors.isDark)
            assertEquals("#F3EEE4", SchedulerColors.DarkChrome.cssHex())
            assertEquals("#1C2430", SchedulerColors.Cream.cssHex())
            assertEquals("#FFFFFF", SchedulerColors.Paper.cssHex())
            val page = TypewriterHtml.editorDocument(
                "<p>Hi</p>",
                true,
                SchedulerColors.DarkChrome.cssHex(),
                SchedulerColors.Cream.cssHex(),
                SchedulerColors.OnDarkMuted.cssHex(),
            )
            assertTrue(page.contains("background:#F3EEE4"))
            assertTrue(page.contains("color:#1C2430"))
        } finally {
            AppThemePack.apply(AppThemePack.DARK)
        }
        assertTrue(SchedulerColors.isDark)
        assertEquals("light", AppThemePack.normalize("LIGHT"))
        assertEquals("dark", AppThemePack.normalize("nope"))
    }
}
