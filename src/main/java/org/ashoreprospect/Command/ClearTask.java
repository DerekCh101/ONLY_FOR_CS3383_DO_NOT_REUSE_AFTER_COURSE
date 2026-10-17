/** Delete one task and tombstone the id so it does not come back on sync. */
package org.ashoreprospect.Command;

import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Day;
import org.ashoreprospect.RefreshLifeCycle;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;

public class ClearTask implements Command {

    private final TaskManagementSystem theSystem;
    private final Task thisTask;

    private LocalDate savedDate;
    private LocalTime savedTime;
    private int savedStartSlot;
    private boolean wasPlaced;

    public ClearTask(Task task) {
        this.theSystem = TaskManagementSystem.getInstance();
        this.thisTask = task;
    }

    @Override
    public boolean execute() throws SQLException {
        if (thisTask == null || thisTask.getID() == null) return false;

        wasPlaced = false;
        savedDate = thisTask.getPlacedDate();
        savedTime = thisTask.getPlacedTime();
        savedStartSlot = 0;

        if (savedDate != null && savedTime != null) {
            savedStartSlot = AppTimeReference.localTimeToSlot(savedTime);
            wasPlaced = true;
        }

        theSystem.getPlacedTasks().remove(thisTask);
        theSystem.getPendingTasks().remove(thisTask);
        theSystem.getUnscheduledTasks().remove(thisTask);

        for (Day day : theSystem.getGrid().values()) {
            Task[] slots = day.returnDayGrid();
            boolean touched = false;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == thisTask) {
                    slots[i] = null;
                    touched = true;
                }
            }
            if (touched) day.recomputeLoadFromGrid();
        }

        theSystem.getTaskRepo().dbClearTask(thisTask);

        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }

    @Override
    public void undo() throws SQLException {
        if (thisTask == null) return;

        if (wasPlaced && savedDate != null) {
            LocalDate col = AppTimeReference.columnDateFor(savedDate, savedTime);
            theSystem.getGrid().putIfAbsent(col, new Day(theSystem.getBlockedTime()));
            Day day = theSystem.getGrid().get(col);
            day.placeTask(savedStartSlot, thisTask);
            thisTask.setPlacedDate(savedDate);
            thisTask.setPlacedTime(AppTimeReference.slotToLocalTime(savedStartSlot));
            if (!theSystem.getPlacedTasks().contains(thisTask)) {
                theSystem.getPlacedTasks().add(thisTask);
            }
            RefreshLifeCycle.refreshLifeCycle(thisTask);
        } else {
            if (!theSystem.getPendingTasks().contains(thisTask)) {
                theSystem.getPendingTasks().add(thisTask);
            }
        }

        theSystem.getTaskRepo().dbRestoreTask(thisTask);

        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() throws SQLException {
        execute();
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }
}