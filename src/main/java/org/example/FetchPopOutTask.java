package org.example;

import java.time.LocalDate;
import java.time.LocalTime;

public class FetchPopOutTask {

    private String taskName;
    private int duration;
    private int state; // 1 = Fixed, 2 = Deadline, 3 = Flexible
    private LocalDate startDate;
    private LocalTime startTime;
    private LocalDate deadlineDate;
    private LocalTime deadlineTime;

    public FetchPopOutTask(String taskName,
                           int duration,
                           int state,
                           LocalDate startDate,
                           LocalTime startTime,
                           LocalDate deadlineDate,
                           LocalTime deadlineTime) {

        this.taskName = taskName;
        this.duration = duration;
        this.state = state;
        this.startDate = startDate;
        this.startTime = startTime;
        this.deadlineDate = deadlineDate;
        this.deadlineTime = deadlineTime;
    }

    public String getTaskName() {
        return taskName;
    }

    public int getDuration() {
        return duration;
    }

    public int getState() {
        return state;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalDate getDeadlineDate() {
        return deadlineDate;
    }

    public LocalTime getDeadlineTime() {
        return deadlineTime;
    }

    public boolean isFixed() {
        return state == 1;
    }

    public boolean isDeadline() {
        return state == 2;
    }

    public boolean isFlexible() {
        return state == 3;
    }

    @Override
    public String toString() {
        return "FetchPopOutTask{" +
                "taskName='" + taskName + '\'' +
                ", duration=" + duration +
                ", state=" + state +
                ", startDate=" + startDate +
                ", startTime=" + startTime +
                ", deadlineDate=" + deadlineDate +
                ", deadlineTime=" + deadlineTime +
                '}';
    }
}