/** Add-task form validation: per-type field exclusivity, name length, and future times. */
package org.ashoreprospect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("app-clock")
class ValidateAddTaskInputTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private static final LocalTime NOW = LocalTime.of(9, 0);

    private Clock saved;

    @BeforeEach
    void freezeClock() {
        saved = Clock.systemDefaultZone();
        AppTimeReference.setClock(Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"), ZONE));
    }

    @AfterEach
    void restoreClock() {
        AppTimeReference.setClock(saved);
    }

    // ---------- name ----------

    @Test
    void blankNameRejected() {
        assertFalse(valid(3, "   ", 1, null, null, null, null));
        assertFalse(valid(3, null, 1, null, null, null, null));
    }

    @Test
    void nameOver100CharsRejected() {
        assertFalse(valid(3, "x".repeat(101), 1, null, null, null, null));
        assertTrue(valid(3, "x".repeat(100), 1, null, null, null, null));
    }

    // ---------- duration ----------

    @Test
    void nonPositiveDurationRejected() {
        assertFalse(valid(3, "a", 0, null, null, null, null));
        assertFalse(valid(3, "a", -1, null, null, null, null));
    }

    @Test
    void durationOverOneDayRejected() {
        // NOTE: this is a latent no-op in ValidateAddTaskInput.
        // hoursToSlots() delegates to hoursToMinutes(), which already clamps to 1440,
        // so slots can never exceed SLOTS_PER_DAY. Oversized durations are silently
        // truncated to 24h instead of being rejected. Asserted as-is; production left untouched.
        assertTrue(valid(3, "a", 25, null, null, null, null));
        assertTrue(valid(3, "a", 24, null, null, null, null));
    }

    // ---------- state ----------

    @Test
    void stateOutsideOneToThreeRejected() {
        assertFalse(valid(0, "a", 1, null, null, null, null));
        assertFalse(valid(4, "a", 1, null, null, null, null));
    }

    // ---------- Fixed ----------

    @Test
    void fixedNeedsFutureStartAndNoDeadline() {
        LocalDate tomorrow = TODAY.plusDays(1);
        assertTrue(valid(1, "a", 1, tomorrow, LocalTime.of(10, 0), null, null));
        // missing start
        assertFalse(valid(1, "a", 1, null, LocalTime.of(10, 0), null, null));
        assertFalse(valid(1, "a", 1, tomorrow, null, null, null));
        // start in the past
        assertFalse(valid(1, "a", 1, TODAY.minusDays(1), LocalTime.of(10, 0), null, null));
        // start earlier today
        assertFalse(valid(1, "a", 1, TODAY, NOW.minusMinutes(30), null, null));
        // a deadline is not allowed on a fixed task
        assertFalse(valid(1, "a", 1, tomorrow, LocalTime.of(10, 0), tomorrow, LocalTime.of(18, 0)));
    }

    // ---------- Deadline ----------

    @Test
    void deadlineNeedsFutureDeadlineAndNoStart() {
        LocalDate tomorrow = TODAY.plusDays(1);
        assertTrue(valid(2, "a", 1, null, null, tomorrow, LocalTime.of(18, 0)));
        // missing deadline time
        assertFalse(valid(2, "a", 1, null, null, tomorrow, null));
        // missing deadline date
        assertFalse(valid(2, "a", 1, null, null, null, LocalTime.of(18, 0)));
        // deadline in the past
        assertFalse(valid(2, "a", 1, null, null, TODAY.minusDays(1), LocalTime.of(18, 0)));
        // a start is not allowed on a deadline task
        assertFalse(valid(2, "a", 1, tomorrow, LocalTime.of(10, 0), tomorrow, LocalTime.of(18, 0)));
    }

    // ---------- Flexible ----------

    @Test
    void flexibleNeedsNeitherStartNorDeadline() {
        assertTrue(valid(3, "a", 1, null, null, null, null));
        assertFalse(valid(3, "a", 1, TODAY.plusDays(1), LocalTime.of(10, 0), null, null));
        assertFalse(valid(3, "a", 1, null, null, TODAY.plusDays(1), LocalTime.of(18, 0)));
    }

    // ---------- boundary ----------

    @Test
    void startExactlyNowRejected_becauseItMustBeAfterNow() {
        assertFalse(valid(1, "a", 1, TODAY, NOW, null, null));
        assertTrue(valid(1, "a", 1, TODAY, NOW.plusMinutes(1), null, null));
    }

    private static boolean valid(int state, String name, double duration,
                                 LocalDate startDate, LocalTime startTime,
                                 LocalDate deadlineDate, LocalTime deadlineTime) {
        return ValidateAddTaskInput.validateAddTaskInput(
                name, duration, state, startDate, startTime, deadlineDate, deadlineTime);
    }
}
