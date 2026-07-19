package org.example;

import java.time.*;

public class Day {

    private final BlockedTimePeriod blockedTime;
    private int availableHours;
    private final Task[] todayTaskGrid;
    private boolean overloadFlag;

    private static final int START_HOUR = 6;
    private static final int HOURS_PER_DAY = 17;

    public Day(BlockedTimePeriod blockedTime){
        this.todayTaskGrid = new Task[HOURS_PER_DAY];
        this.blockedTime = blockedTime;
        this.availableHours = HOURS_PER_DAY - blockedTime.returnTotalBlockedTime();
        this.overloadFlag = false;
    }

    // Main improved version - validates current time on today
    public boolean canPlace(int hour, int duration, boolean ignoreBlocked, LocalDate thisDay, Task task) {
        if (hour < 0 || hour + duration > HOURS_PER_DAY) {
            return false;
        }

        // Prevent scheduling in the past on the current day
        if (thisDay.equals(LocalDate.now())) {
            int currentHour = LocalTime.now().getHour();
            int taskStartHour = START_HOUR + hour;
            if (taskStartHour < currentHour) {
                return false;
            }
        }

        if (!ignoreBlocked && overloadFlag) {//deadline task can get in overload day
            return false;
        }

        for (int i = 0; i < duration; i++) {
            int slot = hour + i;

            if (todayTaskGrid[slot] != null && !task.getID().equals(todayTaskGrid[slot].getID())) {
                return false;
            }

            if (!ignoreBlocked) {
                LocalTime checkTime = LocalTime.of(START_HOUR + slot, 0);
                if (blockedTime.isBlocked(checkTime)) {
                    return false;
                }
            }
        }
        return true;
    }


    public void placeTask(int hour, int duration, Task task) {
        for (int i = 0; i < duration; i++) {
            todayTaskGrid[hour + i] = task;
        }
        availableHours = availableHours - duration;
        if (availableHours <= 2) {
            overloadFlag = true;
        }
    }

    public Task[] returnDayGrid(){
        return todayTaskGrid;
    }

    public boolean getOverloadFlag(){
        return overloadFlag;
    }
}