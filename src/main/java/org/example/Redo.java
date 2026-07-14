package org.example;


public class Redo extends RecordedCommand {

    TaskManagementSystem theSystem;

    public Redo(TaskManagementSystem theSystem){
        this.theSystem = theSystem;
    }

    @Override
    public void execute(){
        RecordedCommand.getRedoList().getLast().redo();
    }


}
