/**
 * {@code TaskReminders.formatLead}: the lead-time wording used in the notification body.
 *
 * <p>Only the pure formatter is exercised. {@code tick()} is private and reaches the
 * singleton, and {@code start()} builds a JavaFX {@code Timeline}, so neither is touched.
 */
package org.ashoreprospect.GUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskRemindersTest {

    @Test
    void nonPositiveLeadMeansWhenItStarts() {
        assertEquals("when it starts", TaskReminders.formatLead(0));
        assertEquals("when it starts", TaskReminders.formatLead(-1));
        assertEquals("when it starts", TaskReminders.formatLead(-30));
        assertEquals("when it starts", TaskReminders.formatLead(Integer.MIN_VALUE));
    }

    @Test
    void wholeDaysUseSingularAndPlural() {
        assertEquals("1 day", TaskReminders.formatLead(1440));
        assertEquals("2 days", TaskReminders.formatLead(2880));
        assertEquals("14 days", TaskReminders.formatLead(20160));
    }

    @Test
    void wholeHoursUseSingularAndPlural() {
        assertEquals("1 hour", TaskReminders.formatLead(60));
        assertEquals("2 hours", TaskReminders.formatLead(120));
        assertEquals("23 hours", TaskReminders.formatLead(1380));
    }

    @Test
    void dayWinsOverHourWhenBothDivide() {
        // 1440 is also a whole number of hours; the day branch is checked first.
        assertEquals("1 day", TaskReminders.formatLead(1440));
        assertEquals("3 days", TaskReminders.formatLead(4320));
    }

    @Test
    void nonRoundValuesFallThroughToMinutes() {
        assertEquals("1 min", TaskReminders.formatLead(1));
        assertEquals("90 min", TaskReminders.formatLead(90));
        assertEquals("59 min", TaskReminders.formatLead(59));
        assertEquals("61 min", TaskReminders.formatLead(61));
        // 1441 is neither a whole day nor a whole hour, so it stays in minutes.
        assertEquals("1441 min", TaskReminders.formatLead(1441));
        // PINNED: 1500 still divides by 60, so the hour branch wins and a 25-hour lead
        // reads as "25 hours" rather than "1500 min".
        assertEquals("25 hours", TaskReminders.formatLead(1500));
    }
}
