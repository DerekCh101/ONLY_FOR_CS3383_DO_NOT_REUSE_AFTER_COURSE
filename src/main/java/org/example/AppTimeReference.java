package org.example;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;

public class AppTimeReference {
    public static Clock clock = Clock.systemDefaultZone();


    public static LocalDate getStartOfWeek(LocalDate date) {
        // Returns the Monday of the week
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
    public static LocalDate getCurrentDate(){
        return LocalDate.now(clock);
    }
    public static LocalTime getCurrentTime(){
        return LocalTime.now(clock);
    }
    


}
