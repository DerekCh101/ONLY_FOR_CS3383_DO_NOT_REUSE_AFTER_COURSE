package org.example.Command;

import org.example.Day;
import org.example.Task;
import org.example.TaskManagementSystem;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ClearAllTask implements Command {

    private final TaskManagementSystem theSystem;
    private final List<Task> savedTasks = new ArrayList<>();

    public ClearAllTask(TaskManagementSystem theSystem) {
        this.theSystem = theSystem;
    }

    @Override
    public boolean execute() throws SQLException {
        savedTasks.clear();
        savedTasks.addAll(theSystem.getPlacedTasks());
        savedTasks.addAll(theSystem.getPendingTasks());
        savedTasks.addAll(theSystem.getUnscheduledTasks());

        theSystem.getPlacedTasks().clear();
        theSystem.getPendingTasks().clear();
        theSystem.getUnscheduledTasks().clear();

        // CRITICAL: drop Day objects so overload flags cannot linger
        theSystem.getGrid().clear();

        theSystem.getTaskRepo().dbCleanAll();

        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        System.out.println("All tasks cleared.");
        return true;
    }

    @Override
    public void undo() throws SQLException {
        for (Task t : savedTasks) {
            if (t.getPlacedDate() != null && t.getPlacedTime() != null) {
                theSystem.getGrid().putIfAbsent(
                        t.getPlacedDate(),
                        new Day(theSystem.getBlockedTime()));
                Day day = theSystem.getGrid().get(t.getPlacedDate());
                int slot = org.example.AppTimeReference.localTimeToSlot(t.getPlacedTime());
                day.placeTask(slot, t);
                theSystem.getPlacedTasks().add(t);
            } else if (t.getLifeCycle() == org.example.TaskLifeCycle.UNSCHEDULED) {
                theSystem.getUnscheduledTasks().add(t);
            } else {
                theSystem.getPendingTasks().add(t);
            }
            org.example.RefreshLifeCycle.refreshLifeCycle(t);
            theSystem.getTaskRepo().dbAddTask(t);
        }

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