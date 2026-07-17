package org.example.Command;


import org.example.TaskManagementSystem;

public class Redo extends RecordedCommand {

    TaskManagementSystem theSystem;

    public Redo(TaskManagementSystem theSystem){
        this.theSystem = theSystem;
    }

    @Override
    public boolean execute(){
        RecordedCommand.getRedoList().getLast().redo();
        return true;
    }


}
