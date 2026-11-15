/** Hong Kong public-holiday map: fixed dates plus a few pinned floating dates. */
package org.ashoreprospect.GUI;

import org.ashoreprospect.AppTimeReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only the pure {@code map()} helper is exercised here; no JavaFX node is created.
 */
@ResourceLock("app-clock")
class HongKongHolidaysTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");

    private Clock saved;

    @BeforeEach
    void freezeClock() {
        saved = Clock.systemDefaultZone();
        setYear(2026);
    }

    @AfterEach
    void restoreClock() {
        AppTimeReference.setClock(saved);
    }

    private static void setYear(int year) {
        AppTimeReference.setClock(Clock.fixed(
                Instant.parse(year + "-06-15T09:00:00Z"), ZONE));
    }

    @Test
    void fixedHolidays_coverTheYearBeforeThroughTwoYearsAfter() {
        Map<LocalDate, String> holidays = MonthGridBuilder.HongKongHolidays.map();

        for (int year = 2025; year <= 2028; year++) {
            assertEquals("New Year's Day", holidays.get(LocalDate.of(year, 1, 1)));
            assertEquals("Labour Day", holidays.get(LocalDate.of(year, 5, 1)));
            assertEquals("HKSAR Establishment Day", holidays.get(LocalDate.of(year, 7, 1)));
            assertEquals("National Day", holidays.get(LocalDate.of(year, 10, 1)));
            assertEquals("Christmas Day", holidays.get(LocalDate.of(year, 12, 25)));
            assertEquals("The first weekday after Christmas Day",
                    holidays.get(LocalDate.of(year, 12, 26)));
        }
        // The sliding window adds 24 fixed dates; the hard-coded 2026/2027 floating
        // entries are added on top, giving 38 distinct days for a 2026 clock.
        assertEquals(38, holidays.size());
    }

    @Test
    void yearsOutsideTheWindowHaveNoFixedEntries() {
        Map<LocalDate, String> holidays = MonthGridBuilder.HongKongHolidays.map();

        assertFalse(holidays.containsKey(LocalDate.of(2024, 1, 1)));
        assertFalse(holidays.containsKey(LocalDate.of(2029, 1, 1)));
        assertFalse(holidays.containsKey(LocalDate.of(2024, 12, 25)));
    }

    @Test
    void known2026FloatingDatesArePresent() {
        Map<LocalDate, String> holidays = MonthGridBuilder.HongKongHolidays.map();

        assertEquals("Lunar New Year", holidays.get(LocalDate.of(2026, 2, 17)));
        assertEquals("Lunar New Year", holidays.get(LocalDate.of(2026, 2, 18)));
        assertEquals("Lunar New Year", holidays.get(LocalDate.of(2026, 2, 19)));
        assertEquals("Good Friday", holidays.get(LocalDate.of(2026, 4, 3)));
        assertEquals("Easter Monday", holidays.get(LocalDate.of(2026, 4, 6)));
        assertEquals("Dragon Boat Festival", holidays.get(LocalDate.of(2026, 6, 19)));
        assertEquals("Chung Yeung Festival", holidays.get(LocalDate.of(2026, 10, 19)));
    }

    @Test
    void theWindowSlidesWithTheAppClock() {
        setYear(2030);
        Map<LocalDate, String> holidays = MonthGridBuilder.HongKongHolidays.map();

        assertTrue(holidays.containsKey(LocalDate.of(2029, 1, 1)));
        assertTrue(holidays.containsKey(LocalDate.of(2032, 12, 26)));
        assertFalse(holidays.containsKey(LocalDate.of(2028, 1, 1)));

        // PINNED: the 2026/2027 floating dates are hard-coded and are added regardless
        // of which year the clock reports.
        assertTrue(holidays.containsKey(LocalDate.of(2026, 2, 17)));
    }

    @Test
    void everyEntryHasANonBlankLabel() {
        Map<LocalDate, String> holidays = MonthGridBuilder.HongKongHolidays.map();

        assertFalse(holidays.isEmpty());
        for (Map.Entry<LocalDate, String> entry : holidays.entrySet()) {
            assertTrue(entry.getKey() != null, "null holiday date");
            assertTrue(entry.getValue() != null && !entry.getValue().isBlank(),
                    "blank label for " + entry.getKey());
        }
    }
}
