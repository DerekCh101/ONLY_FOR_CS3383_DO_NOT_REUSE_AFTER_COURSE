/** Blocked-row overlap rules: same kind, overlapping clocks, at least one shared day. */
package org.ashoreprospect;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Instant;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockedTimePeriodTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);   // a Monday
    private static final LocalDate TUE = LocalDate.of(2026, 10, 13);   // a Tuesday

    // ---------- clockOverlap ----------

    @Test
    void clockOverlap_trueWhenIntervalsCross() {
        assertTrue(BlockedTimePeriod.clockOverlap(
                row("a", LocalTime.of(8, 0), LocalTime.of(10, 0)),
                row("b", LocalTime.of(9, 0), LocalTime.of(11, 0))));
    }

    @Test
    void clockOverlap_falseForTouchingEdges() {
        assertFalse(BlockedTimePeriod.clockOverlap(
                row("a", LocalTime.of(8, 0), LocalTime.of(9, 0)),
                row("b", LocalTime.of(9, 0), LocalTime.of(10, 0))));
    }

    @Test
    void clockOverlap_falseForDisjoint() {
        assertFalse(BlockedTimePeriod.clockOverlap(
                row("a", LocalTime.of(8, 0), LocalTime.of(9, 0)),
                row("b", LocalTime.of(14, 0), LocalTime.of(15, 0))));
    }

    @Test
    void clockOverlap_falseForInvalidRange() {
        assertFalse(BlockedTimePeriod.clockOverlap(
                row("a", LocalTime.of(10, 0), LocalTime.of(8, 0)),
                row("b", LocalTime.of(9, 0), LocalTime.of(11, 0))));
    }

    @Test
    void clockOverlap_falseWhenTimesMissing() {
        BlockedTime a = new BlockedTime("a", "x", null, null, null, null, Instant.now(), "u");
        assertFalse(BlockedTimePeriod.clockOverlap(a, row("b", LocalTime.of(9, 0), LocalTime.of(10, 0))));
    }

    // ---------- daysCanMeet ----------

    @Test
    void daysCanMeet_dailyMeetsAnything() {
        BlockedTime daily = row("daily", LocalTime.of(8, 0), LocalTime.of(9, 0));
        BlockedTime weekly = BlockedTime.weekly("w", DayOfWeek.WEDNESDAY,
                LocalTime.of(11, 0), LocalTime.of(12, 0));
        BlockedTime once = BlockedTime.once("o", MON, LocalTime.of(11, 0), LocalTime.of(12, 0));
        assertTrue(BlockedTimePeriod.daysCanMeet(daily, weekly));
        assertTrue(BlockedTimePeriod.daysCanMeet(weekly, daily));
        assertTrue(BlockedTimePeriod.daysCanMeet(daily, once));
    }

    @Test
    void daysCanMeet_sameWeekdayYes() {
        BlockedTime a = BlockedTime.weekly("a", DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0));
        BlockedTime b = BlockedTime.weekly("b", DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 0));
        assertTrue(BlockedTimePeriod.daysCanMeet(a, b));
    }

    @Test
    void daysCanMeet_differentWeekdayNo() {
        BlockedTime a = BlockedTime.weekly("a", DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0));
        BlockedTime b = BlockedTime.weekly("b", DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertFalse(BlockedTimePeriod.daysCanMeet(a, b));
    }

    @Test
    void daysCanMeet_dateMatchesWeekday() {
        BlockedTime monday = BlockedTime.weekly("w", DayOfWeek.MONDAY,
                LocalTime.of(8, 0), LocalTime.of(9, 0));
        BlockedTime onMon = BlockedTime.once("o", MON, LocalTime.of(8, 0), LocalTime.of(9, 0));
        BlockedTime onTue = BlockedTime.once("o", TUE, LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertTrue(BlockedTimePeriod.daysCanMeet(onMon, monday));
        assertFalse(BlockedTimePeriod.daysCanMeet(onTue, monday));
    }

    @Test
    void daysCanMeet_twoDatesOnlyIfSame() {
        BlockedTime a = BlockedTime.once("a", MON, LocalTime.of(8, 0), LocalTime.of(9, 0));
        BlockedTime b = BlockedTime.once("b", MON, LocalTime.of(9, 0), LocalTime.of(10, 0));
        BlockedTime c = BlockedTime.once("c", TUE, LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertTrue(BlockedTimePeriod.daysCanMeet(a, b));
        assertFalse(BlockedTimePeriod.daysCanMeet(a, c));
    }

    // ---------- blockedOverlap ----------

    @Test
    void blockedOverlap_requiresSameKind() {
        BlockedTime normal = row("n", LocalTime.of(8, 0), LocalTime.of(10, 0));
        BlockedTime exception = row("e", LocalTime.of(8, 0), LocalTime.of(10, 0));
        exception.setException(true);
        assertFalse(BlockedTimePeriod.blockedOverlap(normal, exception));
    }

    @Test
    void blockedOverlap_dailyRowsClashOnClockOnly() {
        BlockedTime a = row("a", LocalTime.of(8, 0), LocalTime.of(10, 0));
        BlockedTime b = row("b", LocalTime.of(9, 0), LocalTime.of(11, 0));
        assertTrue(BlockedTimePeriod.blockedOverlap(a, b));
    }

    @Test
    void blockedOverlap_sameKindDifferentDaysDoNotClash() {
        BlockedTime a = BlockedTime.weekly("a", DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0));
        BlockedTime b = BlockedTime.weekly("b", DayOfWeek.FRIDAY, LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertFalse(BlockedTimePeriod.blockedOverlap(a, b));
    }

    @Test
    void blockedOverlap_nullSafe() {
        assertFalse(BlockedTimePeriod.blockedOverlap(null, row("a", LocalTime.of(8, 0), LocalTime.of(9, 0))));
        assertFalse(BlockedTimePeriod.blockedOverlap(row("a", LocalTime.of(8, 0), LocalTime.of(9, 0)), null));
    }

    // ---------- isBlocked ----------

    @Test
    void isBlocked_dailyCoversEveryDay() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.replaceBlockedPeriods(java.util.List.of(
                BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0))));
        assertTrue(period.isBlocked(MON, LocalTime.of(12, 30)));
        assertTrue(period.isBlocked(TUE, LocalTime.of(12, 0)));
        assertFalse(period.isBlocked(MON, LocalTime.of(13, 0)));
        assertFalse(period.isBlocked(MON, LocalTime.of(11, 59)));
    }

    @Test
    void isBlocked_specificDateOnly() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.replaceBlockedPeriods(java.util.List.of(
                BlockedTime.once("off", MON, LocalTime.of(15, 0), LocalTime.of(16, 0))));
        assertTrue(period.isBlocked(MON, LocalTime.of(15, 30)));
        assertFalse(period.isBlocked(TUE, LocalTime.of(15, 30)));
    }

    @Test
    void isBlocked_exceptionFreesMatchingDate() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        BlockedTime exception = BlockedTime.daily("free", LocalTime.of(12, 0), LocalTime.of(13, 0));
        exception.setException(true);
        period.replaceBlockedPeriods(java.util.List.of(
                BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0)),
                exception));
        assertFalse(period.isBlocked(MON, LocalTime.of(12, 30)));
    }

    @Test
    void isBlocked_nullSafe() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        assertFalse(period.isBlocked(null, LocalTime.of(12, 0)));
        assertFalse(period.isBlocked(MON, null));
    }

    // ---------- conflictMessage / conflictAmong ----------

    @Test
    void conflictMessage_nullForNonOverlapping() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.replaceBlockedPeriods(java.util.List.of(
                BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0))));
        assertNull(period.conflictMessage(BlockedTime.daily("x", LocalTime.of(14, 0), LocalTime.of(15, 0))));
    }

    @Test
    void conflictMessage_reportsOverlapWithExisting() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.replaceBlockedPeriods(java.util.List.of(
                BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0))));
        String msg = period.conflictMessage(BlockedTime.daily("x", LocalTime.of(12, 30), LocalTime.of(14, 0)));
        assertNotNull(msg);
        assertTrue(msg.contains("lunch"));
    }

    @Test
    void conflictMessage_ignoresSelfBySameId() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        BlockedTime same = BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        period.replaceBlockedPeriods(java.util.List.of(same));
        assertNull(period.conflictMessage(same));
    }

    @Test
    void conflictMessage_rejectsReversedRange() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        String msg = period.conflictMessage(BlockedTime.daily("x", LocalTime.of(14, 0), LocalTime.of(13, 0)));
        assertEquals("End must be after start.", msg);
    }

    @Test
    void conflictAmong_catchesClashInsideOneBatch() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        java.util.List<BlockedTime> batch = java.util.List.of(
                BlockedTime.daily("a", LocalTime.of(8, 0), LocalTime.of(10, 0)),
                BlockedTime.daily("b", LocalTime.of(9, 0), LocalTime.of(11, 0)));
        String msg = period.conflictAmong(batch);
        assertNotNull(msg);
        assertTrue(msg.contains("this add"));
    }

    @Test
    void conflictAmong_nullWhenBatchIsClean() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        java.util.List<BlockedTime> batch = java.util.List.of(
                BlockedTime.daily("a", LocalTime.of(8, 0), LocalTime.of(9, 0)),
                BlockedTime.daily("b", LocalTime.of(9, 0), LocalTime.of(10, 0)));
        assertNull(period.conflictAmong(batch));
    }

    // ---------- totals / legacy periods ----------

    @Test
    void returnTotalBlockedTime_countsOnlyDailyRows() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.replaceBlockedPeriods(java.util.List.of(
                BlockedTime.daily("a", LocalTime.of(8, 0), LocalTime.of(10, 0)),
                BlockedTime.once("b", MON, LocalTime.of(12, 0), LocalTime.of(15, 0))));
        assertEquals(2, period.returnTotalBlockedTime());
    }

    @Test
    void oneOffException_overridesMatchingDailyRow() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        BlockedTime daily = BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        BlockedTime exception = BlockedTime.once("free", MON, LocalTime.of(12, 0), LocalTime.of(13, 0));
        exception.setException(true);
        period.replaceBlockedPeriods(java.util.List.of(daily, exception));

        assertFalse(period.isBlocked(MON, LocalTime.of(12, 30)));
        assertTrue(period.isBlocked(TUE, LocalTime.of(12, 30)));
    }

    @Test
    void isBlocked_excludesExactEndBoundaryButIncludesStart() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.replaceBlockedPeriods(java.util.List.of(
                BlockedTime.daily("window", LocalTime.of(9, 0), LocalTime.of(10, 0))));

        assertTrue(period.isBlocked(MON, LocalTime.of(9, 0)));
        assertTrue(period.isBlocked(MON, LocalTime.of(9, 30)));
        assertFalse(period.isBlocked(MON, LocalTime.of(10, 0)));
    }

    @Test
    void addBlockedTime_rejectsStartBeforeSixAm() {
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(
                "05-00\n06-00\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.io.InputStream originalIn = System.in;
        try {
            System.setIn(in);
            BlockedTimePeriod period = new BlockedTimePeriod();
            period.addBlockedTime();
            assertTrue(period.blockedPeriod.isEmpty());
        } finally {
            System.setIn(originalIn);
        }

        java.io.ByteArrayInputStream in2 = new java.io.ByteArrayInputStream(
                "06-00\n07-00\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            System.setIn(in2);
            BlockedTimePeriod period = new BlockedTimePeriod();
            period.addBlockedTime();
            assertEquals(1, period.blockedPeriod.size());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void dailyRange_blocksEachDateInRangeOnly() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        LocalDate start = MON;
        LocalDate end = MON.plusDays(2);
        period.replaceBlockedPeriods(BlockedTime.dailyRange("repeat", start, end,
                LocalTime.of(14, 0), LocalTime.of(15, 0)));

        assertTrue(period.isBlocked(start, LocalTime.of(14, 30)));
        assertTrue(period.isBlocked(start.plusDays(1), LocalTime.of(14, 30)));
        assertTrue(period.isBlocked(end, LocalTime.of(14, 30)));
        assertFalse(period.isBlocked(end.plusDays(1), LocalTime.of(14, 30)));
    }

    @Test
    void violatesBlocked_checksEachHourOfDuration() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.replaceBlockedPeriods(java.util.List.of(
                BlockedTime.daily("a", LocalTime.of(10, 0), LocalTime.of(11, 0))));
        assertFalse(period.violatesBlocked(LocalTime.of(9, 0), 1));
        assertTrue(period.violatesBlocked(LocalTime.of(9, 0), 2));
    }

    @Test
    void describe_usesFriendlyLabels() {
        assertTrue(BlockedTimePeriod.describe(row("lunch", LocalTime.of(8, 0), LocalTime.of(9, 0)))
                .contains("every day"));
        assertTrue(BlockedTimePeriod.describe(
                BlockedTime.weekly("gym", DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0)))
                .contains("MONDAY"));
    }

    @Test
    void replaceBlockedPeriods_nullIsEmpty() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.replaceBlockedPeriods(null);
        assertTrue(period.blockedPeriod.isEmpty());
        assertTrue(period.periods.isEmpty());
    }

    // ---------- plan I: remaining BlockedTimePeriod branches ----------

    @Test
    void violatesBlocked_crossingMidnightChecksTheNextCalendarDate() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.replaceBlockedPeriods(java.util.List.of(
                BlockedTime.once("night", MON.plusDays(1), LocalTime.of(1, 0), LocalTime.of(2, 0))));

        // PINNED: violatesBlocked() calls start.plusHours(i) but keeps the original date,
        // so 23:00 + 2h = 01:00 still compares against MON and never sees the TUE row.
        assertFalse(period.violatesBlocked(MON, LocalTime.of(23, 0), 3));
        assertFalse(period.violatesBlocked(MON, LocalTime.of(23, 0), 4));
        // The row does block its own date.
        assertTrue(period.violatesBlocked(MON.plusDays(1), LocalTime.of(0, 0), 2));
        assertFalse(period.violatesBlocked(MON.plusDays(1), LocalTime.of(0, 0), 1));
    }

    @Test
    void isBlocked_timeOverload_usesDailyRowsOnly() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        BlockedTime oneOff = BlockedTime.once("one off", MON, LocalTime.of(9, 0), LocalTime.of(10, 0));
        BlockedTime weekly = BlockedTime.weekly("gym", DayOfWeek.MONDAY,
                LocalTime.of(11, 0), LocalTime.of(12, 0));
        BlockedTime everyDay = row("lunch", LocalTime.of(13, 0), LocalTime.of(14, 0));
        BlockedTime exception = BlockedTime.daily("free", LocalTime.of(15, 0), LocalTime.of(16, 0));
        exception.setException(true);
        period.replaceBlockedPeriods(java.util.List.of(oneOff, weekly, everyDay, exception));

        // Only rows that are daily and not exceptions are rebuilt into `periods`.
        assertEquals(1, period.periods.size());
        assertTrue(period.isBlocked(LocalTime.of(13, 30)));
        assertFalse(period.isBlocked(LocalTime.of(9, 30)));
        assertFalse(period.isBlocked(LocalTime.of(11, 30)));
        assertFalse(period.isBlocked(LocalTime.of(15, 30)));
        // The date-aware overload does see the one-off row.
        assertTrue(period.isBlocked(MON, LocalTime.of(9, 30)));
    }

    @Test
    void returnTotalBlockedTime_truncatesHalfHoursToWholeHours() {
        // PINNED: Duration.toHours() truncates, so a 30-minute block counts as 0 hours
        // and the day keeps all 48 slots available.
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.replaceBlockedPeriods(java.util.List.of(
                row("half", LocalTime.of(12, 0), LocalTime.of(12, 30))));
        assertEquals(0, period.returnTotalBlockedTime());
        assertEquals(AppTimeReference.SLOTS_PER_DAY,
                new Day(period).getAvailableSlots());

        period.replaceBlockedPeriods(java.util.List.of(
                row("one", LocalTime.of(12, 0), LocalTime.of(13, 0)),
                row("two", LocalTime.of(15, 0), LocalTime.of(16, 30))));
        assertEquals(2, period.returnTotalBlockedTime());
        assertEquals(AppTimeReference.SLOTS_PER_DAY - 4,
                new Day(period).getAvailableSlots());
    }

    @Test
    void deleteBlockedTime_removesTheSelectedRowAndRebuildsPeriods() {
        java.io.InputStream originalIn = System.in;
        try {
            // BlockedTimePeriod() captures System.in in its Scanner, so setIn must come first.
            System.setIn(new java.io.ByteArrayInputStream("1\n".getBytes()));
            BlockedTimePeriod period = new BlockedTimePeriod();
            period.replaceBlockedPeriods(java.util.List.of(
                    row("first", LocalTime.of(8, 0), LocalTime.of(9, 0)),
                    row("second", LocalTime.of(10, 0), LocalTime.of(11, 0))));
            assertEquals(2, period.periods.size());

            period.deleteBlockedTime();

            assertEquals(1, period.blockedPeriod.size());
            assertEquals("second", period.blockedPeriod.get(0).getTitle());
            assertEquals(1, period.periods.size());
            assertFalse(period.isBlocked(LocalTime.of(8, 30)));
            assertTrue(period.isBlocked(LocalTime.of(10, 30)));
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void deleteBlockedTime_invalidSelectionLeavesEverythingAlone() {
        java.io.InputStream originalIn = System.in;
        try {
            for (String choice : java.util.List.of("0", "3", "seven", "")) {
                System.setIn(new java.io.ByteArrayInputStream((choice + "\n").getBytes()));
                BlockedTimePeriod period = new BlockedTimePeriod();
                period.replaceBlockedPeriods(java.util.List.of(
                        row("only", LocalTime.of(8, 0), LocalTime.of(9, 0))));

                period.deleteBlockedTime();

                assertEquals(1, period.blockedPeriod.size(), "choice=" + choice);
                assertEquals(1, period.periods.size(), "choice=" + choice);
            }
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void changeBlockedTime_rejectsEveryEditOfSingleRowSet() {
        // PINNED: BlockedTimePeriod.validatePeriod() checks the new window against
        // `periods`, but rebuildPeriods() stores each row's live LocalTime[] array from
        // BlockedTime.asPeriod(). The edited row is therefore compared against itself
        // and always "overlaps", so changeBlockedTime() rolls back every time.
        java.io.InputStream originalIn = System.in;
        try {
            System.setIn(new java.io.ByteArrayInputStream("1\n14-00\n15-30\n".getBytes()));
            BlockedTimePeriod period = new BlockedTimePeriod();
            period.replaceBlockedPeriods(java.util.List.of(
                    row("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0))));

            period.changeBlockedTime();

            assertEquals(LocalTime.of(12, 0), period.blockedPeriod.get(0).getStartTime());
            assertEquals(LocalTime.of(13, 0), period.blockedPeriod.get(0).getEndTime());
            assertTrue(period.isBlocked(LocalTime.of(12, 30)));
            assertFalse(period.isBlocked(LocalTime.of(14, 30)));
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void changeBlockedTime_rejectsAnEditThatWouldOverlapAnotherRow() {
        // PINNED: validatePeriod() runs after the new times are already written and
        // rebuildPeriods() has put the edited row back into `periods`. The edited row
        // therefore always clashes with itself, so an edit of one row in a two-row set
        // is rejected even when the new window overlaps only that row's old copy —
        // and, as here, even when it only overlaps nothing else but itself.
        java.io.InputStream originalIn = System.in;
        try {
            System.setIn(new java.io.ByteArrayInputStream("1\n19-00\n20-00\n".getBytes()));
            BlockedTimePeriod period = new BlockedTimePeriod();
            period.replaceBlockedPeriods(java.util.List.of(
                    row("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0)),
                    row("gym", LocalTime.of(18, 0), LocalTime.of(19, 0))));

            period.changeBlockedTime();

            assertEquals(LocalTime.of(12, 0), period.blockedPeriod.get(0).getStartTime());
            assertEquals(LocalTime.of(13, 0), period.blockedPeriod.get(0).getEndTime());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void changeBlockedTime_invalidSelectionOrTimesLeavesTheRowAlone() {
        java.io.InputStream originalIn = System.in;
        try {
            for (String input : java.util.List.of(
                    "2\n14-00\n15-00\n",       // out-of-range selection
                    "0\n14-00\n15-00\n",       // below the first row
                    "x\n",                     // not a number
                    "1\n14-00\n09-00\n",       // end before start
                    "1\n04-00\n05-00\n",       // outside 06:00–23:00
                    "1\n12-30\n13-30\n")) {    // overlaps the existing row
                System.setIn(new java.io.ByteArrayInputStream(input.getBytes()));
                BlockedTimePeriod period = new BlockedTimePeriod();
                period.replaceBlockedPeriods(java.util.List.of(
                        row("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0))));

                period.changeBlockedTime();

                assertEquals(LocalTime.of(12, 0), period.blockedPeriod.get(0).getStartTime(),
                        "input=" + input);
                assertEquals(LocalTime.of(13, 0), period.blockedPeriod.get(0).getEndTime(),
                        "input=" + input);
            }
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void changeBlockedTime_withNoRowsIsANoOp() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.changeBlockedTime();
        period.deleteBlockedTime();
        assertTrue(period.blockedPeriod.isEmpty());
    }

    @Test
    void addBlockedTime_acceptsTheDashedFormatAndRejectsOutOfRangeClocks() {
        java.io.InputStream originalIn = System.in;
        try {
            // The same "HH-mm" formatter accepts "09-00": '-' is a literal and "00"
            // satisfies the minute-of-hour field. Only 06:00–23:00 is enforced.
            System.setIn(new java.io.ByteArrayInputStream("09-00\n10-00\n".getBytes()));
            BlockedTimePeriod accepted = new BlockedTimePeriod();
            accepted.addBlockedTime();
            assertEquals(1, accepted.blockedPeriod.size());
            assertEquals(LocalTime.of(9, 0), accepted.blockedPeriod.get(0).getStartTime());

            for (String input : java.util.List.of(
                    "nonsense\n09:00\n",            // bad start
                    "09:00\nnonsense\n",            // bad end
                    "05:00\n09:00\n",               // before 06:00
                    "09:00\n23:30\n")) {            // after 23:00
                System.setIn(new java.io.ByteArrayInputStream(input.getBytes()));
                BlockedTimePeriod period = new BlockedTimePeriod();
                period.addBlockedTime();

                assertTrue(period.blockedPeriod.isEmpty(), "input=" + input);
                assertTrue(period.periods.isEmpty(), "input=" + input);
            }
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void addBlockedTime_rejectsAnOverlappingPeriod() {
        java.io.InputStream originalIn = System.in;
        try {
            System.setIn(new java.io.ByteArrayInputStream("12-30\n13-30\n".getBytes()));
            BlockedTimePeriod period = new BlockedTimePeriod();
            period.replaceBlockedPeriods(java.util.List.of(
                    row("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0))));

            period.addBlockedTime();

            assertEquals(1, period.blockedPeriod.size());
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void exceptionRows_clashOnlyAmongThemselves() {
        BlockedTime exception = BlockedTime.daily("free", LocalTime.of(12, 0), LocalTime.of(13, 0));
        exception.setException(true);
        BlockedTime exceptionTwin = BlockedTime.daily("free 2", LocalTime.of(12, 30), LocalTime.of(13, 30));
        exceptionTwin.setException(true);
        BlockedTime normal = row("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));

        assertTrue(BlockedTimePeriod.blockedOverlap(exception, exceptionTwin));
        assertFalse(BlockedTimePeriod.blockedOverlap(exception, normal));
        assertFalse(BlockedTimePeriod.blockedOverlap(normal, exception));
    }

    @Test
    void describe_labelsEveryRowKind() {
        BlockedTime exception = BlockedTime.weekly("holiday", DayOfWeek.FRIDAY,
                LocalTime.of(8, 0), LocalTime.of(9, 0));
        exception.setException(true);
        assertTrue(BlockedTimePeriod.describe(exception).startsWith("holiday exception FRIDAY"));

        BlockedTime oneOff = BlockedTime.once("trip", MON, LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertTrue(BlockedTimePeriod.describe(oneOff).contains(MON.toString()));

        BlockedTime untitled = new BlockedTime("id", "   ", null, null,
                LocalTime.of(8, 0), LocalTime.of(9, 0), null, null);
        assertTrue(BlockedTimePeriod.describe(untitled).startsWith("blocked every day"));
    }

    @Test
    void conflictMessage_and_conflictAmong_describeTheClash() {
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.replaceBlockedPeriods(java.util.List.of(
                row("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0))));

        assertNull(period.conflictMessage(row("ok", LocalTime.of(14, 0), LocalTime.of(15, 0))));
        // PINNED: a null input and a row with no clock times report the same message.
        assertEquals("Invalid time.", period.conflictMessage(
                new BlockedTime("no-times", "bare", null, null, null, null, null, null)));
        assertEquals("End must be after start.",
                period.conflictMessage(row("backwards", LocalTime.of(15, 0), LocalTime.of(14, 0))));
        assertTrue(period.conflictMessage(row("clash", LocalTime.of(12, 30), LocalTime.of(13, 30)))
                .startsWith("Overlaps existing blocked time: lunch"));

        // The same id never clashes with itself, which is how edits re-save a row.
        BlockedTime self = row("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        self.setId("lunch-id");
        period.replaceBlockedPeriods(java.util.List.of(self));
        BlockedTime edited = row("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        edited.setId("lunch-id");
        assertNull(period.conflictMessage(edited));

        java.util.List<BlockedTime> batch = java.util.List.of(
                row("monday run", LocalTime.of(7, 0), LocalTime.of(8, 0)),
                row("monday run 2", LocalTime.of(7, 30), LocalTime.of(8, 30)));
        assertNull(period.conflictAmong(java.util.List.of(batch.get(0))));
        assertTrue(period.conflictAmong(batch).startsWith("Overlaps in this add: monday run"));
        assertNull(period.conflictAmong(new java.util.ArrayList<>()));
    }

    private static BlockedTime row(String title, LocalTime start, LocalTime end) {
        return BlockedTime.daily(title, start, end);
    }
}
