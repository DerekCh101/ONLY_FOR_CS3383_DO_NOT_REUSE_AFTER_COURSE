/** Delete leftover past tasks that were never finished. */
package org.ashoreprospect.Command;

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
    private boolean captured;

    public ClearPastTasks(TaskManagementSystem theSystem) {
        this.theSystem = theSystem;
    }

    @Override
    public boolean execute() throws SQLException {
        RefreshLifeCycle.refreshAll(theSystem);
        if (!captured) {
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
                return false;
            }
            savedTasks.addAll(past);
            captured = true;
        }
        applyDelete();
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        System.out.println("Cleared past tasks: " + savedTasks.size());
        return true;
    }

    @Override
    public void undo() throws SQLException {
        applyRestore();
        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() throws SQLException {
        applyDelete();
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }

    private void applyDelete() throws SQLException {
        theSystem.getTaskRepo().lockHistory();
        try {
            theSystem.getTaskRepo().bumpHistoryEpoch();
            theSystem.getTaskRepo().dbClearTasks(savedTasks);
            theSystem.getTaskRepo().reloadFromSqlite(theSystem);
        } finally {
            theSystem.getTaskRepo().unlockHistory();
        }
    }

    private void applyRestore() throws SQLException {
        theSystem.getTaskRepo().lockHistory();
        try {
            theSystem.getTaskRepo().bumpHistoryEpoch();
            theSystem.getTaskRepo().dbRestoreTasks(savedTasks, false);
            theSystem.getTaskRepo().reloadFromSqlite(theSystem);
        } finally {
            theSystem.getTaskRepo().unlockHistory();
        }
    }
}
