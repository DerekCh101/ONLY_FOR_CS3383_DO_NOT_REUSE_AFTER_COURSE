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
    private boolean fixedTime;

    Task(String tName, LocalDate dDate, LocalTime dTime, int durations, LocalDate sD, LocalTime sT) {
        this.uuid = UUID.randomUUID();
//        while (repository.existsById(uuid)) {   // Very rare loop
//            uuid = UUID.randomUUID();
//        }
        this.id = uuid.toString();
        this.taskName = tName;
        this.deadLineDate = dDate;
        this.deadLineTime = dTime;
        this.durationHours = durations;

        if (sD == null) {
            fixedTime = false;
        } else {
            fixedTime = true;
            this.startingDate = sD;
            this.startingTime = sT;
        }
    }



    public String getTaskName() {
        return taskName;
    }

    public LocalDate getDeadLineDate() {
        return deadLineDate;
    }

    public LocalTime getDeadLineTime() {
        return deadLineTime;
    }

    public int getDurationHours() {
        return durationHours;
    }

    public LocalDate getStartingDate() {
        return startingDate;
    }

    public LocalTime getStartingTime() {
        return startingTime;
    }

    public boolean isFixedTime() {
        return fixedTime;
    }

    public String getID(){
        return id;
    }

    @Override
    public String toString() {
        return String.format("{%s},{%s},{%s},{%s},{%s}",
                taskName, deadLineDate, deadLineTime,
                durationHours, fixedTime);
    }
}