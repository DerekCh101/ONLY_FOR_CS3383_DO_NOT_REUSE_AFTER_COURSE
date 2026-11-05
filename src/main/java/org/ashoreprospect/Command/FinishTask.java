/** Mark a placed task finished. */
package org.ashoreprospect.Command;

import org.ashoreprospect.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class FinishTask implements Command {

    private final TaskManagementSystem theSystem;
    private final Task thisTask;
    private final List<Task> finishedQuicks = new ArrayList<>();

    public FinishTask(Task task) {
        this.theSystem = TaskManagementSystem.getInstance();
        this.thisTask = task;
    }

    @Override
    public boolean execute() throws SQLException {
        if (thisTask == null || thisTask.getID() == null) return false;
        if (thisTask.getWorkflow() == Workflow.FINISHED) return false;
        if(thisTask.getPlacedDate() == null || thisTask.getPlacedTime() == null) return false;
        //note that only past and scheduled task can be marked finished
        markSlots(Workflow.FINISHED);
        thisTask.setWorkflow(Workflow.FINISHED);
        theSystem.getTaskRepo().dbUpdateWorkflow(thisTask);
        finishAffiliated();
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }

    @Override
    public void undo() throws SQLException {
        if (thisTask == null || thisTask.getID() == null) return;
        if(thisTask.getPlacedDate() == null || thisTask.getPlacedTime() == null) return; //note that only past and scheduled task can be marked finished
        unfinishAffiliated();
        markSlots(Workflow.UNFINISHED);
        thisTask.setWorkflow(Workflow.UNFINISHED);
        theSystem.getTaskRepo().dbUpdateWorkflow(thisTask);

        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() throws SQLException {

        if (thisTask == null || thisTask.getID() == null) return;
        if(thisTask.getPlacedDate() == null || thisTask.getPlacedTime() == null) return; //note that only past and scheduled task can be marked finished
        markSlots(Workflow.FINISHED);
        thisTask.setWorkflow(Workflow.FINISHED);
        theSystem.getTaskRepo().dbUpdateWorkflow(thisTask);
        finishAffiliated();
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }

    private void finishAffiliated() throws SQLException {
        finishedQuicks.clear();
        if (thisTask == null || thisTask.isQuick()) return;
        for (Task q : theSystem.quicksOf(thisTask)) {
            if (q == null || q.getWorkflow() == Workflow.FINISHED) continue;
            q.setWorkflow(Workflow.FINISHED);
            theSystem.getTaskRepo().dbUpdateWorkflow(q);
            finishedQuicks.add(q);
        }
    }

    private void unfinishAffiliated() throws SQLException {
        for (Task q : finishedQuicks) {
            if (q == null || q.getWorkflow() != Workflow.FINISHED) continue;
            q.setWorkflow(Workflow.UNFINISHED);
            theSystem.getTaskRepo().dbUpdateWorkflow(q);
        }
    }

    private void markSlots(Workflow state) {
        if (theSystem.getGrid() == null) return;
        Day thatDay = theSystem.getGrid().get(AppTimeReference.columnDateFor(thisTask.getPlacedDate(), thisTask.getPlacedTime()));
        if (thatDay == null) return;
        Task[] slots = thatDay.returnDayGrid();
        int theSlot = AppTimeReference.localTimeToSlot(thisTask.getPlacedTime());
        for (int i = 0; i < thisTask.getDurationSlots(); i++) {
            if (theSlot < 0 || theSlot >= slots.length || slots[theSlot] == null) break;
            slots[theSlot].setWorkflow(state);
            theSlot++;
        }
    }
}