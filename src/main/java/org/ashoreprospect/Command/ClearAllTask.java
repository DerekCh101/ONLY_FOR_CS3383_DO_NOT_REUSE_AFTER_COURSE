/** Delete every task for this user (undoable). */
package org.ashoreprospect.Command;

import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ClearAllTask implements Command {

    private final TaskManagementSystem theSystem;
    private final List<Task> savedTasks = new ArrayList<>();
    private boolean captured;

    public ClearAllTask(TaskManagementSystem theSystem) {
        this.theSystem = theSystem;
    }

    @Override
    public boolean execute() throws SQLException {
        if (!captured) {
            savedTasks.clear();
            savedTasks.addAll(theSystem.getPlacedTasks());
            savedTasks.addAll(theSystem.getPendingTasks());
            savedTasks.addAll(theSystem.getUnscheduledTasks());
            captured = true;
        }
        applyDelete();
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        System.out.println("All tasks cleared.");
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
