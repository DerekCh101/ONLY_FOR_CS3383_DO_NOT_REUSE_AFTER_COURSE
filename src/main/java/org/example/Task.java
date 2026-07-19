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
    private LocalDate clickedDate;
    private LocalTime clickedTime;
    private boolean isFixed;
    private boolean isFlexible;
    private boolean hasDeadline;
    private int state;

    public Task(String tName, int duration,int state, LocalDate clickedDate, LocalTime clickedTime, LocalDate secDate, LocalTime secTime) {
        this.uuid = UUID.randomUUID();
//        while (repository.existsById(uuid)) {   // Very rare loop
//            uuid = UUID.randomUUID();
//        }
        this.id = uuid.toString();
        this.taskName = tName;
        this.durationHours = duration;

        if(state==1){//fixed task
            this.state=1;
            isFixed = true;
            hasDeadline=false;
            isFlexible=false;
            startingDate = clickedDate;
            startingTime = clickedTime;
            this.clickedDate = clickedDate;
            this.clickedTime = clickedTime;
        }
        if(state==2){//deadline Task
            this.state=2;
            isFixed = false;
            hasDeadline=true;
            isFlexible=false;
            deadLineDate = secDate;
            deadLineTime = secTime;
            this.clickedDate = clickedDate;
            this.clickedTime = clickedTime;
        }
        if(state==3){//flexible task
            this.state=3;
            isFixed = false;
            hasDeadline=false;
            isFlexible=true;
            this.clickedDate = clickedDate;
            this.clickedTime = clickedTime;
        }
    }



    public String getTaskName() {return taskName;}
    public LocalDate getDeadLineDate() {return deadLineDate;}
    public LocalTime getDeadLineTime() { return deadLineTime;}
    public int getDurationHours() { return durationHours;}
    public LocalDate getStartingDate() { return startingDate;}
    public LocalTime getStartingTime() { return startingTime;}
    public LocalDate getClickedDate(){ return clickedDate;}
    public LocalTime getClickedTime(){ return clickedTime;}

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