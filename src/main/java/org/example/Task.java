package org.example;

import java.util.UUID;
import java.time.LocalDate;
import java.time.LocalTime;

public class Task {

    private UUID uuid;
    private final String id;
    private String taskName;
    private LocalDate deadLineDate; // optional
    private LocalTime deadLineTime; // optional
    private int durationHours;      // optional, default 2
    private LocalDate startingDate; // optional
    private LocalTime startingTime; // optional
    private boolean isFixed;
    private boolean isFlexible;
    private boolean hasDeadline;
    private int state;
//        while (repository.existsById(uuid)) {   // Very rare loop
//            uuid = UUID.randomUUID();
//        }

    public Task(String tName, int duration, int state,
                LocalDate startDate, LocalTime startTime,
                LocalDate deadlineDate, LocalTime deadlineTime) {

        this.uuid = UUID.randomUUID();
        this.id = uuid.toString();
        this.taskName = tName;
        this.durationHours = duration;
        this.state = state;

        if (state == 1) { // Fixed
            isFixed = true;
            hasDeadline = false;
            isFlexible = false;
            this.startingDate = startDate;
            this.startingTime = startTime;
            this.deadLineDate = null;
            this.deadLineTime = null;
        } else if (state == 2) { // Deadline
            isFixed = false;
            hasDeadline = true;
            isFlexible = false;
            this.startingDate = null;
            this.startingTime = null;
            this.deadLineDate = deadlineDate;
            this.deadLineTime = deadlineTime;
        } else if (state == 3) { // Flexible
            isFixed = false;
            hasDeadline = false;
            isFlexible = true;
            this.startingDate = null;
            this.startingTime = null;
            this.deadLineDate = null;
            this.deadLineTime = null;
        }
    }



    public String getTaskName() {return taskName;}
    public LocalDate getDeadLineDate() {return deadLineDate;}
    public LocalTime getDeadLineTime() { return deadLineTime;}
    public int getDurationHours() { return durationHours;}
    public LocalDate getStartingDate() { return startingDate;}
    public LocalTime getStartingTime() { return startingTime;}


    public String getID(){
        return id;
    }

    public boolean isFixedTime() { return isFixed;}
    public boolean isFlexible(){ return isFlexible;}
    public boolean hasDeadline(){ return hasDeadline;}


    @Override
    public String toString() {
        return String.format("{%s},{%d},{%d},{%s},{%s},{%s},{%s}",
                taskName, durationHours, state, startingDate, startingTime,
                deadLineDate,deadLineTime);
    }
}