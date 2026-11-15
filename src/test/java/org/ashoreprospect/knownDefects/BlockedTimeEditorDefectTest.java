package org.ashoreprospect.knownDefects;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.BlockedTime;
import org.ashoreprospect.BlockedTimePeriod;
import org.ashoreprospect.Day;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the blocked-time editor and arithmetic defects.
 *
 * <p>See {@code KNOWN_DEFECTS.md} in this package. When fixed, move each assertion into
 * {@code BlockedTimePeriodTest} asserting the intended behaviour instead.
 */
@ResourceLock("app-clock")
class BlockedTimeEditorDefectTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    private Clock originalClock;

    @BeforeEach
    void setUp() {
        originalClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(Clock.fixed(
                Instant.parse("2026-10-12T07:00:00Z"), ZoneId.of("UTC")));
    }

    @AfterEach
    void tearDown() {
        AppTimeReference.setClock(originalClock);
    }

    @Test
    void changeBlockedTime_rejectsEveryEdit() {
        // DEFECT: validatePeriod() checks the new window against `periods`, but
        // rebuildPeriods() stored the *edited row's own live* LocalTime[] array (from
        // BlockedTime.asPeriod()). The row therefore always overlaps itself and is rolled
        // back, so changeBlockedTime() can never change anything.
        java.io.InputStream originalIn = System.in;
        try {
            System.setIn(new java.io.ByteArrayInputStream("1\n14-00\n15-30\n".getBytes()));
            BlockedTimePeriod period = new BlockedTimePeriod();
            period.replaceBlockedPeriods(java.util.List.of(
                    daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0))));

            period.changeBlockedTime();

            assertEquals(LocalTime.of(12, 0), period.blockedPeriod.get(0).getStartTime(),
                    "PINNED: the edit to 14:00 was rolled back");
            assertEquals(LocalTime.of(13, 0), period.blockedPeriod.get(0).getEndTime(),
                    "PINNED: the edit to 15:30 was rolled back");
            assertTrue(period.isBlocked(LocalTime.of(12, 30)));
            assertFalse(period.isBlocked(LocalTime.of(14, 30)));
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void changeBlockedTime_rejectsAnEditDeniedOnlyByItself() {
        // DEFECT: same self-overlap. The new 19:00-20:00 window does not clash with the other
        // row (12:00-13:00) at all, yet the edit is rejected because it overlaps the row's own
        // pre-edit copy still sitting in `periods`.
        java.io.InputStream originalIn = System.in;
        try {
            System.setIn(new java.io.ByteArrayInputStream("1\n19-00\n20-00\n".getBytes()));
            BlockedTimePeriod period = new BlockedTimePeriod();
            period.replaceBlockedPeriods(java.util.List.of(
                    daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0)),
                    daily("gym", LocalTime.of(18, 0), LocalTime.of(19, 0))));

            period.changeBlockedTime();

            assertEquals(LocalTime.of(12, 0), period.blockedPeriod.get(0).getStartTime(),
                    "PINNED: a legal edit was rejected");
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void violatesBlocked_crossingMidnightChecksTheNextCalendarDate() {
        // DEFECT: violatesBlocked(date, start, hours) calls start.plusHours(i) but keeps the
        // original date, so 23:00 + 2h = 01:00 is still compared against MON and never sees
        // the TUE row.
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.replaceBlockedPeriods(java.util.List.of(
                BlockedTime.once("night", MON.plusDays(1), LocalTime.of(1, 0), LocalTime.of(2, 0))));

        assertFalse(period.violatesBlocked(MON, LocalTime.of(23, 0), 3),
                "PINNED: the 01:00 row on TUE is invisible from MON");
        assertFalse(period.violatesBlocked(MON, LocalTime.of(23, 0), 4));
        // The row does block its own date.
        assertTrue(period.violatesBlocked(MON.plusDays(1), LocalTime.of(0, 0), 2));
        assertFalse(period.violatesBlocked(MON.plusDays(1), LocalTime.of(0, 0), 1));
    }

    @Test
    void returnTotalBlockedTime_truncatesHalfHoursToWholeHours() {
        // DEFECT: Duration.toHours() truncates, so a 30-minute block counts as 0 hours and
        // the day keeps all 48 slots available.
        BlockedTimePeriod period = new BlockedTimePeriod();
        period.replaceBlockedPeriods(java.util.List.of(
                daily("half", LocalTime.of(12, 0), LocalTime.of(12, 30))));

        assertEquals(0, period.returnTotalBlockedTime(),
                "PINNED: a 30-minute block is counted as zero hours");
        assertEquals(AppTimeReference.SLOTS_PER_DAY, new Day(period).getAvailableSlots());

        period.replaceBlockedPeriods(java.util.List.of(
                daily("one", LocalTime.of(12, 0), LocalTime.of(13, 0)),
                daily("two", LocalTime.of(15, 0), LocalTime.of(16, 30))));
        assertEquals(2, period.returnTotalBlockedTime(),
                "PINNED: 0.5h of the 1.5h block is dropped");
        assertEquals(AppTimeReference.SLOTS_PER_DAY - 4, new Day(period).getAvailableSlots());
    }

    private static BlockedTime daily(String title, LocalTime start, LocalTime end) {
        return BlockedTime.daily(title, start, end);
    }
}
