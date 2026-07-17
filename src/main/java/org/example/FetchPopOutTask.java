package org.example;

import java.time.LocalDate;
import java.time.LocalTime;

public class FetchPopOutTask {

    private String taskName;
    private boolean fixed;
    private boolean hasDeadline;
    private int duration;

    private LocalDate deadlineDate;
    private LocalTime deadlineTime;

    private LocalDate startDate;
    private LocalTime startTime;

    private LocalDate clickedDate;
    private LocalTime clickedTime;

    public FetchPopOutTask(String taskName, boolean fixed, boolean hasDeadline,
                           int duration,
                           LocalDate deadlineDate, LocalTime deadlineTime,
                           LocalDate startDate, LocalTime startTime,
                           LocalDate clickedDate, LocalTime clickedTime) {

        this.taskName = taskName;
        this.fixed = fixed;
        this.hasDeadline = hasDeadline;
        this.duration = duration;
        this.deadlineDate = deadlineDate;
        this.deadlineTime = deadlineTime;
        this.startDate = startDate;
        this.startTime = startTime;
        this.clickedDate = clickedDate;
        this.clickedTime = clickedTime;
    }

    // ==================== GETTERS ====================

    public String getTaskName() {
        return taskName;
    }

    public boolean isFixed() {
        return fixed;
    }

    public boolean hasDeadline() {
        return hasDeadline;
    }

    public int getDuration() {
        return duration;
    }

    public LocalDate getDeadlineDate() {
        return deadlineDate;
    }

    public LocalTime getDeadlineTime() {
        return deadlineTime;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalDate getClickedDate() {
        return clickedDate;
    }

    public LocalTime getClickedTime() {
        return clickedTime;
    }

    // Optional: toString for debugging
    @Override
    public String toString() {
        return "FetchPopOutTask{" +
                "taskName='" + taskName + '\'' +
                ", fixed=" + fixed +
                ", hasDeadline=" + hasDeadline +
                ", duration=" + duration +
                ", clickedDate=" + clickedDate +
                '}';
    }
}