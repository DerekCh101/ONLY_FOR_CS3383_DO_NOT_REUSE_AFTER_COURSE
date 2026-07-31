package org.example;

import java.time.LocalDate;
import java.time.LocalTime;

public class Day {

    private final BlockedTimePeriod blockedTime;
    private int availableSlots;
    private final Task[] todayTaskGrid;
    private boolean overloadFlag;

    public Day(BlockedTimePeriod blockedTime) {
        this.todayTaskGrid = new Task[AppTimeReference.SLOTS_PER_DAY];
        this.blockedTime = blockedTime;
        this.availableSlots = AppTimeReference.SLOTS_PER_DAY
                - blockedTime.returnTotalBlockedTime() * AppTimeReference.SLOTS_PER_HOUR;
        this.overloadFlag = false;
    }

    public boolean canPlace(Task task, LocalDate thisDay, int slotIndex,
                            boolean ignoreBlocked, boolean ignoreOverload) {

        int slotCount = task.getDurationSlots();

        if (slotIndex < 0 || slotIndex + slotCount > AppTimeReference.SLOTS_PER_DAY) {
            return false;
        }

        if (thisDay.equals(AppTimeReference.getCurrentDate())) {
            if (slotIndex <= AppTimeReference.currentSlotIndex()) {
                return false;
            }
        }

        if (!ignoreOverload && overloadFlag) {
            return false;
        }

        for (int i = 0; i < slotCount; i++) {
            int slot = slotIndex + i;

            if (todayTaskGrid[slot] != null
                    && !task.getID().equals(todayTaskGrid[slot].getID())) {
                return false;
            }

            if (!ignoreBlocked) {
                LocalTime checkTime = AppTimeReference.slotToLocalTime(slot);
                if (blockedTime.isBlocked(checkTime)) {
                    return false;
                }
            }
        }
        return true;
    }

    public void placeTask(int slotIndex, Task task) {
        int slotCount = task.getDurationSlots();
        for (int i = 0; i < slotCount; i++) {
            todayTaskGrid[slotIndex + i] = task;
        }
        availableSlots -= slotCount;
        if (availableSlots <= AppTimeReference.SLOTS_PER_HOUR) {
            overloadFlag = true;
        }
    }

    public Task[] returnDayGrid() {
        return todayTaskGrid;
    }

    public boolean getOverloadFlag() {
        return overloadFlag;
    }

    public int getAvailableSlots() {
        return availableSlots;
    }
}