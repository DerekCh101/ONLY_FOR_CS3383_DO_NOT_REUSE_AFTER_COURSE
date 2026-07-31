package org.example.Command;

import org.example.*;
import java.time.LocalDate;
import java.time.LocalTime;

public class AddTask implements Command {

    private final TaskManagementSystem theSystem;
    private final Task theTask;
    private final LocalDate placedDate;
    private final LocalTime placedTime;
    private boolean autoScheduled = false;

    public AddTask(String taskName, double durationHours, int state,
                   LocalDate startDate, LocalTime startTime,
                   LocalDate deadlineDate, LocalTime deadlineTime,
                   LocalDate placedDate, LocalTime placedTime) {

        this.theSystem = TaskManagementSystem.getInstance();
        this.placedDate = placedDate;
        this.placedTime = placedTime;

        this.theTask = new Task(
                taskName, durationHours, state,
                startDate, startTime,
                deadlineDate, deadlineTime
        );
    }

    public AddTask(String taskName, double durationHours, int state,
                   LocalDate startDate, LocalTime startTime,
                   LocalDate deadlineDate, LocalTime deadlineTime) {
        this(taskName, durationHours, state, startDate, startTime, deadlineDate, deadlineTime, null, null);
    }

    private void doAdd() {
        if (placedDate != null && placedTime != null) {
            placeOnGrid(placedDate, placedTime);
        } else if (theTask.isFixedTime()) {
            LocalDate date = theTask.getStartingDate();
            LocalTime time = theTask.getStartingTime();
            if (date != null && time != null) {
                placeOnGrid(date, time);
            } else {
                theSystem.getUnscheduledTasks().add(theTask);
            }
        } else {
            theSystem.getPendingTasks().add(theTask);
            autoScheduled = true;
            theSystem.updateSchedule();
        }
    }

    private void placeOnGrid(LocalDate date, LocalTime time) {
        int slotIndex = AppTimeReference.localTimeToSlot(time);
        int slotCount = theTask.getDurationSlots();

        theSystem.getGrid().putIfAbsent(date, new Day(theSystem.getBlockedTime()));
        Day day = theSystem.getGrid().get(date);

        boolean canPlace = true;
        if (slotIndex < 0 || slotIndex + slotCount > AppTimeReference.SLOTS_PER_DAY) {
            canPlace = false;
        } else {
            for (int i = 0; i < slotCount; i++) {
                if (day.returnDayGrid()[slotIndex + i] != null) {
                    canPlace = false;
                    break;
                }
            }
        }

        if (canPlace) {
            day.placeTask(slotIndex, theTask);
            theTask.setPlacedDate(date);
            theTask.setPlacedTime(AppTimeReference.slotToLocalTime(slotIndex));
            theSystem.getPlacedTasks().add(theTask);
        } else {
            theSystem.getUnscheduledTasks().add(theTask);
        }
    }

    @Override
    public boolean execute() {
        doAdd();
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }

    @Override
    public void undo() {
        theSystem.getPendingTasks().remove(theTask);
        theSystem.getPlacedTasks().remove(theTask);
        theSystem.getUnscheduledTasks().remove(theTask);

        for (Day day : theSystem.getGrid().values()) {
            Task[] slots = day.returnDayGrid();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == theTask) {
                    slots[i] = null;
                }
            }
        }

        if (autoScheduled) {
            theSystem.updateSchedule();
        }

        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() {
        doAdd();
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }
}