package org.ashoreprospect.Command;

import org.ashoreprospect.Day;
import org.ashoreprospect.RefreshLifeCycle;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
import org.ashoreprospect.TaskManagementSystem;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deletes only tasks whose lifecycle is PAST. Leaves upcoming work alone. */
public class ClearPastTasks implements Command {

    private final TaskManagementSystem theSystem;
    private final List<Task> savedTasks = new ArrayList<>();

    public ClearPastTasks(TaskManagementSystem theSystem) {
        this.theSystem = theSystem;
    }

    @Override
    public boolean execute() throws SQLException {
        RefreshLifeCycle.refreshAll(theSystem);
        savedTasks.clear();
        Set<Task> past = new LinkedHashSet<>();
        for (Task t : theSystem.getPlacedTasks()) {
            if (t != null && t.getLifeCycle() == TaskLifeCycle.PAST) past.add(t);
        }
        for (Task t : theSystem.getPendingTasks()) {
            if (t != null && t.getLifeCycle() == TaskLifeCycle.PAST) past.add(t);
        }
        for (Task t : theSystem.getUnscheduledTasks()) {
            if (t != null && t.getLifeCycle() == TaskLifeCycle.PAST) past.add(t);
        }
        if (past.isEmpty()) {
            System.out.println("No past tasks to clear.");
            return true;
        }
        savedTasks.addAll(past);
        for (Task t : past) {
            theSystem.getPlacedTasks().remove(t);
            theSystem.getPendingTasks().remove(t);
            theSystem.getUnscheduledTasks().remove(t);
            for (Day day : theSystem.getGrid().values()) {
                Task[] slots = day.returnDayGrid();
                boolean touched = false;
                for (int i = 0; i < slots.length; i++) {
                    if (slots[i] == t) {
                        slots[i] = null;
                        touched = true;
                    }
                }
                if (touched) day.recomputeLoadFromGrid();
            }
            theSystem.getTaskRepo().dbClearTask(t);
        }
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        System.out.println("Cleared past tasks: " + savedTasks.size());
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
                int slot = org.ashoreprospect.AppTimeReference.localTimeToSlot(t.getPlacedTime());
                day.placeTask(slot, t);
                theSystem.getPlacedTasks().add(t);
            } else if (t.getLifeCycle() == TaskLifeCycle.UNSCHEDULED) {
                theSystem.getUnscheduledTasks().add(t);
            } else {
                theSystem.getPendingTasks().add(t);
            }
            org.ashoreprospect.RefreshLifeCycle.refreshLifeCycle(t);
            theSystem.getTaskRepo().dbRestoreTask(t);
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
