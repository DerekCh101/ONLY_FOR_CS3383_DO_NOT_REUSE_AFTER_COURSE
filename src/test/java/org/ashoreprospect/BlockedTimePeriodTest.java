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

    private static BlockedTime row(String title, LocalTime start, LocalTime end) {
        return BlockedTime.daily(title, start, end);
    }
}
