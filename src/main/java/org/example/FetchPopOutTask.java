package org.example;

import java.time.LocalDate;
import java.time.LocalTime;

public class FetchPopOutTask {

    private String taskName;
    private double duration;
    private int state; // 1 = Fixed, 2 = Deadline, 3 = Flexible

    private LocalDate startDate;
    private LocalTime startTime;
    private LocalDate deadlineDate;
    private LocalTime deadlineTime;

    // These two are used when the task is created from a grid click
    private LocalDate placedDate;
    private LocalTime placedTime;

    private Task task; //used when wanted to clear a task

    public FetchPopOutTask(String taskName,
                           double duration,
                           int state,
                           LocalDate startDate,
                           LocalTime startTime,
                           LocalDate deadlineDate,
                           LocalTime deadlineTime,
                           LocalDate placedDate,
                           LocalTime placedTime) {

        this.taskName = taskName;
        this.duration = duration;
        this.state = state;
        this.startDate = startDate;
        this.startTime = startTime;
        this.deadlineDate = deadlineDate;
        this.deadlineTime = deadlineTime;
        this.placedDate = placedDate;
        this.placedTime = placedTime;
    }

    // Convenience constructor when placedDate/Time are not needed
    public FetchPopOutTask(String taskName,
                           double duration,
                           int state,
                           LocalDate startDate,
                           LocalTime startTime,
                           LocalDate deadlineDate,
                           LocalTime deadlineTime) {
        this(taskName, duration, state, startDate, startTime, deadlineDate, deadlineTime, null, null);
    }

    //when wanted to clear a task from GUI
    public FetchPopOutTask(Task task){
        this.task = task;
    }
    public String getTaskName() {
        return taskName;
    }

    public double getDuration() {
        return duration;
    }

    public Task getTask() {
        return task;
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

    public LocalDate getPlacedDate() {
        return placedDate;
    }

    public LocalTime getPlacedTime() {
        return placedTime;
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
                ", placedDate=" + placedDate +
                ", placedTime=" + placedTime +
                '}';
    }
}