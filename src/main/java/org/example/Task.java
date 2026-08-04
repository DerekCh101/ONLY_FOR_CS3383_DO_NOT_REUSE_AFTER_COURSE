package org.example;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public class Task {

    private final String id;
    private String taskName;
    /** Stored as minutes (30, 60, 90, …). */
    private double durationMinutes;
    private int state; // 1 = Fixed, 2 = Deadline, 3 = Flexible
    private LocalDate startingDate;
    private LocalTime startingTime;
    private LocalDate deadLineDate;
    private LocalTime deadLineTime;
    private LocalDate placedDate;
    private LocalTime placedTime;
    private TaskManagementSystem theSystem;
    private Workflow workflow;
    private TaskLifeCycle taskLifeCycle;
    private Instant placementUpdatedAt;

    public Task(String taskName,
                double durationHours,
                int state,
                LocalDate startingDate,
                LocalTime startingTime,
                LocalDate deadLineDate,
                LocalTime deadLineTime) {

        this.id = UUID.randomUUID().toString();
        this.taskName = taskName;
        this.durationMinutes = AppTimeReference.hoursToMinutes(durationHours);
        this.state = state;
        this.startingDate = startingDate;
        this.startingTime = startingTime;
        this.deadLineDate = deadLineDate;
        this.deadLineTime = deadLineTime;
        this.taskLifeCycle = TaskLifeCycle.UNSCHEDULED;
        this.workflow = Workflow.UNFINISHED;
        this.placementUpdatedAt = null;
        theSystem = TaskManagementSystem.getInstance();
    }

    private Task(String id,
                 String taskName,
                 double durationHours,
                 int state,
                 LocalDate startingDate,
                 LocalTime startingTime,
                 LocalDate deadLineDate,
                 LocalTime deadLineTime) {
        this.id = id;
        this.taskName = taskName;
        this.durationMinutes = AppTimeReference.hoursToMinutes(durationHours);
        this.state = state;
        this.startingDate = startingDate;
        this.startingTime = startingTime;
        this.deadLineDate = deadLineDate;
        this.deadLineTime = deadLineTime;
        this.taskLifeCycle = TaskLifeCycle.UNSCHEDULED;
        this.workflow = Workflow.UNFINISHED;
        this.placementUpdatedAt = null;
    }

    public static Task loadTaskFromdb(String id,
                                      String taskName,
                                      double durationHours,
                                      int state,
                                      LocalDate startingDate,
                                      LocalTime startingTime,
                                      LocalDate deadLineDate,
                                      LocalTime deadLineTime,
                                      LocalDate placedDate,
                                      LocalTime placedTime) {
        Task t = new Task(id, taskName, durationHours, state,
                startingDate, startingTime, deadLineDate, deadLineTime);
        t.placedDate = placedDate;
        t.placedTime = placedTime;
        return t;
    }

    public String getID() {
        return id;
    }

    public String getTaskName() {
        return taskName;
    }

    public double getDurationMinutes() {
        return durationMinutes;
    }

    public int getDurationSlots() {
        return AppTimeReference.minutesToSlots(durationMinutes);
    }

    /** For UI / labels (1.0, 1.5, 2.0, …). */
    public double getDurationHours() {
        return AppTimeReference.minutesToHours(durationMinutes);
    }

    public int getState() {
        return state;
    }

    public LocalDate getStartingDate() {
        return startingDate;
    }

    public LocalTime getStartingTime() {
        return startingTime;
    }

    public LocalDate getDeadLineDate() {
        return deadLineDate;
    }

    public LocalTime getDeadLineTime() {
        return deadLineTime;
    }

    public LocalDate getPlacedDate() {
        return placedDate;
    }

    public LocalTime getPlacedTime() {
        return placedTime;
    }

    public void setPlacedDate(LocalDate placedDate) {
        this.placedDate = placedDate;
    }

    public void setPlacedTime(LocalTime placedTime) {
        this.placedTime = placedTime;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public void setDurationHours(double durationHours) {
        this.durationMinutes = AppTimeReference.hoursToMinutes(durationHours);
    }

    public void setDurationMinutes(int durationMinutes) {
        int snapped = Math.max(AppTimeReference.MINUTES_PER_SLOT,
                (durationMinutes / AppTimeReference.MINUTES_PER_SLOT)
                        * AppTimeReference.MINUTES_PER_SLOT);
        this.durationMinutes = snapped;
    }

    public boolean isFixedTime() {
        return state == 1;
    }

    public boolean hasDeadline() {
        return state == 2;
    }

    public boolean isFlexible() {
        return state == 3;
    }

    public void setStartingDate(LocalDate startingDate) {
        this.startingDate = startingDate;
    }

    public void setStartingTime(LocalTime startingTime) {
        this.startingTime = startingTime;
    }

    public TaskLifeCycle getLifeCycle() {
        return taskLifeCycle;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public void setLifeCycle(TaskLifeCycle life) {
        this.taskLifeCycle = life;
    }

    public void setWorkflow(Workflow flow) {
        this.workflow = flow;
    }

    public Instant getPlacementUpdatedAt() {
        return placementUpdatedAt;
    }

    public void setPlacementUpdatedAt(Instant placementUpdatedAt) {
        this.placementUpdatedAt = placementUpdatedAt;
    }

    @Override
    public String toString() {
        return "Task{" +
                "name='" + taskName + '\'' +
                ", durationHours=" + getDurationHours() +
                ", state=" + state +
                ", start=" + startingDate + " " + startingTime +
                ", deadline=" + deadLineDate + " " + deadLineTime +
                ", placed=" + placedDate + " " + placedTime +
                ", lifeCycle=" + taskLifeCycle +
                ", workflow=" + workflow +
                ", placementUpdatedAt=" + placementUpdatedAt +
                '}';
    }
}