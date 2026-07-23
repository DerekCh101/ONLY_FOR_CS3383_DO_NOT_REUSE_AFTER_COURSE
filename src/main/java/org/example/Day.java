package org.example;

import java.time.LocalDate;
import java.time.LocalTime;

public class Day {

    private final BlockedTimePeriod blockedTime;
    private int availableHours;
    private final Task[] todayTaskGrid;
    private boolean overloadFlag;

    private static final int START_HOUR = 6;
    private static final int HOURS_PER_DAY = 17;

    public Day(BlockedTimePeriod blockedTime) {
        this.todayTaskGrid = new Task[HOURS_PER_DAY];
        this.blockedTime = blockedTime;
        this.availableHours = HOURS_PER_DAY - blockedTime.returnTotalBlockedTime();
        this.overloadFlag = false;
    }

    public boolean canPlace(Task task, LocalDate thisDay, int hour,
                            boolean ignoreBlocked, boolean ignoreOverload) {

        int duration = task.getDurationHours();
        if (duration <= 0) duration = 2;

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

        // Overload check
        if (!ignoreOverload && overloadFlag) {
            return false;
        }

        for (int i = 0; i < duration; i++) {
            int slot = hour + i;

            // Time conflict with another task
            if (todayTaskGrid[slot] != null && !task.getID().equals(todayTaskGrid[slot].getID())) {
                return false;
            }

            // Blocked time check
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

    public Task[] returnDayGrid() {
        return todayTaskGrid;
    }

    public boolean getOverloadFlag() {
        return overloadFlag;
    }

    public int getAvailableHours() {
        return availableHours;
    }
}