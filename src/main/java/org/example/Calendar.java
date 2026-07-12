package org.example;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;

public class Calendar {
    public static LocalDate getStartOfWeek(LocalDate date) {
        // Returns the Monday of the week
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
