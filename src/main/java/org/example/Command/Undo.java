package org.example.Command;


import org.example.TaskManagementSystem;

public class Undo extends RecordedCommand {

    TaskManagementSystem theSystem;

    public Undo(TaskManagementSystem theSystem){
        this.theSystem = theSystem;
    }

    @Override
    public boolean execute(){
        RecordedCommand.getUndoList().getLast().undo();
        return true;

    }

}
