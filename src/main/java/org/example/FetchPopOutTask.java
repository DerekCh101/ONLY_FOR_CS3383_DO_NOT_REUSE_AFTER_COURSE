package org.example;

import java.time.LocalDate;
import java.time.LocalTime;

public class FetchPopOutTask {

    private String taskName;
    private int duration;
    private int state; // 1 = Fixed, 2 = Deadline, 3 = Flexible
    private LocalDate clickedDate;
    private LocalTime clickedTime;
    private LocalDate secDate;
    private LocalTime secTime;

    public FetchPopOutTask(String taskName,
                           int duration,
                           int state,
                           LocalDate clickedDate,
                           LocalTime clickedTime,
                           LocalDate secDate,
                           LocalTime secTime) {

        this.taskName = taskName;
        this.duration = duration;
        this.state = state;
        this.clickedDate = clickedDate;
        this.clickedTime = clickedTime;
        this.secDate = secDate;
        this.secTime = secTime;
    }

    // ==================== GETTERS ====================

    public String getTaskName() {
        return taskName;
    }

    public int getDuration() {
        return duration;
    }

    public int getState() {
        return state;
    }

    public LocalDate getClickedDate() {
        return clickedDate;
    }

    public LocalTime getClickedTime() {
        return clickedTime;
    }

    public LocalDate getSecDate() {
        return secDate;
    }

    public LocalTime getSecTime() {
        return secTime;
    }

    // Optional helper methods (cleaner than checking state everywhere)

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
                ", clickedDate=" + clickedDate +
                ", clickedTime=" + clickedTime +
                ", secDate=" + secDate +
                ", secTime=" + secTime +
                '}';
    }
}