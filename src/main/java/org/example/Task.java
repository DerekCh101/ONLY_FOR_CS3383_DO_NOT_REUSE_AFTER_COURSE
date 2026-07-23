package org.example;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public class Task {

    private final String id;
    private String taskName;
    private int durationHours;
    private int state; // 1 = Fixed, 2 = Deadline, 3 = Flexible

    private LocalDate startingDate;   // only for Fixed
    private LocalTime startingTime;   // only for Fixed
    private LocalDate deadLineDate;   // only for Deadline
    private LocalTime deadLineTime;   // only for Deadline

    // Actual position on the grid (used by all types)
    private LocalDate placedDate;
    private LocalTime placedTime;

    public Task(String taskName,
                int durationHours,
                int state,
                LocalDate startingDate,
                LocalTime startingTime,
                LocalDate deadLineDate,
                LocalTime deadLineTime) {

        this.id = UUID.randomUUID().toString();
        this.taskName = taskName;
        this.durationHours = durationHours;
        this.state = state;
        this.startingDate = startingDate;
        this.startingTime = startingTime;
        this.deadLineDate = deadLineDate;
        this.deadLineTime = deadLineTime;
    }

    // ==================== Getters ====================

    public String getID() {
        return id;
    }

    public String getTaskName() {
        return taskName;
    }

    public int getDurationHours() {
        return durationHours;
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

    // ==================== Setters ====================

    public void setPlacedDate(LocalDate placedDate) {
        this.placedDate = placedDate;
    }

    public void setPlacedTime(LocalTime placedTime) {
        this.placedTime = placedTime;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public void setDurationHours(int durationHours) {
        this.durationHours = durationHours;
    }

    // ==================== Type helpers ====================

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

    @Override
    public String toString() {
        return "Task{" +
                "name='" + taskName + '\'' +
                ", duration=" + durationHours +
                ", state=" + state +
                ", start=" + startingDate + " " + startingTime +
                ", deadline=" + deadLineDate + " " + deadLineTime +
                ", placed=" + placedDate + " " + placedTime +
                '}';
    }
}