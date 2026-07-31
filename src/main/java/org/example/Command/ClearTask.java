package org.example.Command;

import org.example.AppTimeReference;
import org.example.Day;
import org.example.Task;
import org.example.TaskManagementSystem;

import java.time.LocalDate;
import java.time.LocalTime;

public class ClearTask implements Command {

    private final TaskManagementSystem theSystem;
    private final Task thisTask;

    private LocalDate savedDate;
    private int savedStartSlot;
    private boolean wasPlaced;

    public ClearTask(Task task) {
        this.theSystem = TaskManagementSystem.getInstance();
        this.thisTask = task;
    }

    @Override
    public boolean execute() {
        if (thisTask == null) return false;

        wasPlaced = false;
        savedDate = thisTask.getPlacedDate();
        savedStartSlot = 0;

        if (savedDate != null && thisTask.getPlacedTime() != null) {
            savedStartSlot = AppTimeReference.localTimeToSlot(thisTask.getPlacedTime());
            wasPlaced = true;
        }

        theSystem.getPlacedTasks().remove(thisTask);
        theSystem.getPendingTasks().remove(thisTask);
        theSystem.getUnscheduledTasks().remove(thisTask);

        for (Day day : theSystem.getGrid().values()) {
            Task[] slots = day.returnDayGrid();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == thisTask) {
                    slots[i] = null;
                }
            }
        }

        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }

    @Override
    public void undo() {
        if (thisTask == null) return;

        if (wasPlaced && savedDate != null) {
            theSystem.getGrid().putIfAbsent(savedDate, new Day(theSystem.getBlockedTime()));
            Day day = theSystem.getGrid().get(savedDate);
            day.placeTask(savedStartSlot, thisTask);

            thisTask.setPlacedDate(savedDate);
            thisTask.setPlacedTime(AppTimeReference.slotToLocalTime(savedStartSlot));

            if (!theSystem.getPlacedTasks().contains(thisTask)) {
                theSystem.getPlacedTasks().add(thisTask);
            }
        } else {
            if (!theSystem.getPendingTasks().contains(thisTask)) {
                theSystem.getPendingTasks().add(thisTask);
            }
        }

        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() {
        execute();
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }
}