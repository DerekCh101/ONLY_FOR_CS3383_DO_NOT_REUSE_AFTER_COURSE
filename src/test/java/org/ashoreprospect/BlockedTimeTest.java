/** Blocked-row factories, day matching, exception freeing, and title clipping. */
package org.ashoreprospect;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockedTimeTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    @Test
    void daily_hasNoDateOrWeekday() {
        BlockedTime b = BlockedTime.daily("a", LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertTrue(b.isDaily());
        assertFalse(b.isException());
        assertEquals(null, b.getSpecificDate());
        assertEquals(null, b.getWeekday());
    }

    @Test
    void weekly_keepsWeekday() {
        BlockedTime b = BlockedTime.weekly("a", DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertEquals(DayOfWeek.MONDAY, b.getWeekday());
        assertFalse(b.isDaily());
    }

    @Test
    void once_keepsSpecificDate() {
        BlockedTime b = BlockedTime.once("a", MON, LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertEquals(MON, b.getSpecificDate());
        assertFalse(b.isDaily());
    }

    @Test
    void appliesOn_dailyMatchesEveryDay() {
        BlockedTime b = BlockedTime.daily("a", LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertTrue(b.appliesOn(MON));
        assertTrue(b.appliesOn(MON.plusDays(200)));
    }

    @Test
    void appliesOn_weeklyMatchesOnlyThatWeekday() {
        BlockedTime b = BlockedTime.weekly("a", DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertTrue(b.appliesOn(MON));
        assertFalse(b.appliesOn(MON.plusDays(1)));
    }

    @Test
    void appliesOn_specificDateMatchesOnlyThatDate() {
        BlockedTime b = BlockedTime.once("a", MON, LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertTrue(b.appliesOn(MON));
        assertFalse(b.appliesOn(MON.plusDays(1)));
    }

    @Test
    void appliesOn_nullIsFalse() {
        BlockedTime b = BlockedTime.daily("a", LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertFalse(b.appliesOn(null));
    }

    @Test
    void freesOn_onlyWhenException() {
        BlockedTime plain = BlockedTime.once("a", MON, LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertFalse(plain.freesOn(MON));

        plain.setException(true);
        assertTrue(plain.freesOn(MON));
        assertFalse(plain.freesOn(MON.plusDays(1)));
    }

    @Test
    void exceptionIsNotDaily() {
        BlockedTime b = BlockedTime.daily("a", LocalTime.of(8, 0), LocalTime.of(9, 0));
        b.setException(true);
        assertFalse(b.isDaily());
    }

    @Test
    void dailyRange_buildsOneRowPerDay() {
        List<BlockedTime> rows = BlockedTime.dailyRange("x", MON, MON.plusDays(2),
                LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).appliesOn(MON));
        assertTrue(rows.get(2).appliesOn(MON.plusDays(2)));
    }

    @Test
    void dailyRange_sameStartAndEndIsSingleRow() {
        List<BlockedTime> rows = BlockedTime.dailyRange("x", MON, MON,
                LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertEquals(1, rows.size());
    }

    @Test
    void dailyRange_reversedRangeIsEmpty() {
        assertTrue(BlockedTime.dailyRange("x", MON, MON.minusDays(1),
                LocalTime.of(8, 0), LocalTime.of(9, 0)).isEmpty());
    }

    @Test
    void dailyRange_nullIsEmpty() {
        assertTrue(BlockedTime.dailyRange("x", null, MON, LocalTime.of(8, 0), LocalTime.of(9, 0)).isEmpty());
    }

    @Test
    void title_isClippedTo100Chars() {
        String longName = "x".repeat(150);
        BlockedTime b = BlockedTime.daily(longName, LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertEquals(100, b.getTitle().length());
    }

    @Test
    void blankTitleBecomesBlocked() {
        assertEquals("blocked", BlockedTime.daily("   ", LocalTime.of(8, 0), LocalTime.of(9, 0)).getTitle());
        assertEquals("blocked", BlockedTime.daily(null, LocalTime.of(8, 0), LocalTime.of(9, 0)).getTitle());
    }

    @Test
    void sealedTitleIsPassedThroughUntouched() {
        String sealed = "AP1." + "a".repeat(300);
        BlockedTime b = BlockedTime.daily(sealed, LocalTime.of(8, 0), LocalTime.of(9, 0));
        assertEquals(sealed, b.getTitle());
    }

    @Test
    void asPeriod_exposesStartAndEnd() {
        BlockedTime b = BlockedTime.daily("a", LocalTime.of(8, 0), LocalTime.of(9, 0));
        LocalTime[] p = b.asPeriod();
        assertEquals(LocalTime.of(8, 0), p[0]);
        assertEquals(LocalTime.of(9, 0), p[1]);
    }

    @Test
    void touch_movesTimestampForward() {
        BlockedTime b = BlockedTime.daily("a", LocalTime.of(8, 0), LocalTime.of(9, 0));
        b.setLastUpdatedAt(java.time.Instant.EPOCH);
        b.touch();
        assertTrue(b.getLastUpdatedAt().isAfter(java.time.Instant.EPOCH));
    }
}
