package org.example;

import java.time.*;

public class Day{
        
    private BlockedTimePeriod blockedTime;
    private int availableHours;
    private Task[] todayTaskGrid;
    private boolean overloadFlag;
    private static final int START_HOUR = 6;
    private static final int HOURS_PER_DAY = 17;

    public Day(BlockedTimePeriod blockedTime){
        this.todayTaskGrid = new Task[HOURS_PER_DAY];
        this.blockedTime = blockedTime;
        this.availableHours = HOURS_PER_DAY - blockedTime.returnTotalBlockedTime();
        this.overloadFlag = false;
    }

    // Check slot availability with optional blocked rule
    public boolean canPlace(int hour,
                             int duration, boolean ignoreBlocked) {

        if (hour <
             0 || hour + duration > HOURS_PER_DAY)
            return false;
        
        if(overloadFlag){
            return false;
        }

        for (int i = 0; i < duration; i++) {

            if (todayTaskGrid[hour + i] != null)
                return false;

            
            if (!ignoreBlocked) {
                LocalTime checkTime =
                LocalTime.of(START_HOUR + hour + i, 0);
                
                if (blockedTime.isBlocked(checkTime))
                    return false;
            }

        }

        return true;
    }

    // Fill grid with task
    public void placeTask(int hour, int duration, Task task) {

        for (int i = 0; i < duration; i++) {
            todayTaskGrid[hour + i] = task;
        }
        availableHours = availableHours - duration;
        if(availableHours<=2){
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
