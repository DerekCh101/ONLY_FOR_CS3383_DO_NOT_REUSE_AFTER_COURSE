package org.example.Command;

import org.example.*;

import java.sql.SQLException;
import java.time.LocalDate;

public class FinishTask implements Command {

    private final TaskManagementSystem theSystem;
    private final Task thisTask;

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
        Day thatDay = theSystem.getGrid().get(thisTask.getPlacedDate());
        if (thatDay == null) return false;
        Task[] slots = thatDay.returnDayGrid();
        int theSlot = AppTimeReference.localTimeToSlot(thisTask.getPlacedTime());
        for(int i = 0; i < thisTask.getDurationSlots(); i++){
            if (theSlot < 0 || theSlot >= slots.length || slots[theSlot] == null) break;
            slots[theSlot].setWorkflow(Workflow.FINISHED);
            theSlot++;
        }
        thisTask.setWorkflow(Workflow.FINISHED);
        theSystem.getTaskRepo().dbUpdateWorkflow(thisTask);
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }

    @Override
    public void undo() throws SQLException {
        if (thisTask == null || thisTask.getID() == null) return;
        if(thisTask.getPlacedDate() == null || thisTask.getPlacedTime() == null) return; //note that only past and scheduled task can be marked finished
        Day thatDay = theSystem.getGrid().get(thisTask.getPlacedDate());
        if (thatDay == null) return;
        Task[] slots = thatDay.returnDayGrid();
        int theSlot = AppTimeReference.localTimeToSlot(thisTask.getPlacedTime());
        for(int i = 0; i < thisTask.getDurationSlots(); i++){
            if (theSlot < 0 || theSlot >= slots.length || slots[theSlot] == null) break;
            slots[theSlot].setWorkflow(Workflow.UNFINISHED);
            theSlot++;
        }

        thisTask.setWorkflow(Workflow.UNFINISHED);
        theSystem.getTaskRepo().dbUpdateWorkflow(thisTask);

        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() throws SQLException {

        if (thisTask == null || thisTask.getID() == null) return;
        if(thisTask.getPlacedDate() == null || thisTask.getPlacedTime() == null) return; //note that only past and scheduled task can be marked finished
        Day thatDay = theSystem.getGrid().get(thisTask.getPlacedDate());
        if (thatDay == null) return;
        Task[] slots = thatDay.returnDayGrid();
        int theSlot = AppTimeReference.localTimeToSlot(thisTask.getPlacedTime());
        for(int i = 0; i < thisTask.getDurationSlots(); i++){
            if (theSlot < 0 || theSlot >= slots.length || slots[theSlot] == null) break;
            slots[theSlot].setWorkflow(Workflow.FINISHED);
            theSlot++;
        }
        thisTask.setWorkflow(Workflow.FINISHED);
        theSystem.getTaskRepo().dbUpdateWorkflow(thisTask);
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }
}