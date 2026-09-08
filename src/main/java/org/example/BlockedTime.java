package org.example;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BlockedTime {

    private String id;
    private String title;
    private DayOfWeek weekday;
    private LocalDate specificDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private Instant lastUpdatedAt;
    private String userId;
    private boolean exception;

    public BlockedTime(String id, String title, DayOfWeek weekday, LocalDate specificDate,
                       LocalTime startTime, LocalTime endTime, Instant lastUpdatedAt, String userId) {
        this.id = id;
        this.title = title;
        this.weekday = weekday;
        this.specificDate = specificDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.lastUpdatedAt = lastUpdatedAt != null ? lastUpdatedAt : Instant.now();
        this.userId = userId;
        this.exception = false;
    }

    public static BlockedTime daily(String title, LocalTime start, LocalTime end) {
        return new BlockedTime(UUID.randomUUID().toString(), title, null, null, start, end, Instant.now(), null);
    }

    public static BlockedTime weekly(String title, DayOfWeek weekday, LocalTime start, LocalTime end) {
        return new BlockedTime(UUID.randomUUID().toString(), title, weekday, null, start, end, Instant.now(), null);
    }

    public static BlockedTime once(String title, LocalDate date, LocalTime start, LocalTime end) {
        return new BlockedTime(UUID.randomUUID().toString(), title, null, date, start, end, Instant.now(), null);
    }

    public static List<BlockedTime> dailyRange(String title, LocalDate from, LocalDate to,
                                               LocalTime start, LocalTime end) {
        List<BlockedTime> out = new ArrayList<>();
        if (from == null || to == null || to.isBefore(from)) return out;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(once(title, d, start, end));
        }
        return out;
    }

    public boolean appliesOn(LocalDate date) {
        if (date == null) return false;
        if (specificDate != null) return specificDate.equals(date);
        if (weekday != null) return date.getDayOfWeek() == weekday;
        return true;
    }

    public boolean isDaily() {
        return specificDate == null && weekday == null && !exception;
    }

    public LocalTime[] asPeriod() {
        return new LocalTime[]{startTime, endTime};
    }

    public void touch() {
        this.lastUpdatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public DayOfWeek getWeekday() { return weekday; }
    public void setWeekday(DayOfWeek weekday) { this.weekday = weekday; }

    public LocalDate getSpecificDate() { return specificDate; }
    public void setSpecificDate(LocalDate specificDate) { this.specificDate = specificDate; }

    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }

    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }

    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public boolean isException() { return exception; }
    public void setException(boolean exception) { this.exception = exception; }
}