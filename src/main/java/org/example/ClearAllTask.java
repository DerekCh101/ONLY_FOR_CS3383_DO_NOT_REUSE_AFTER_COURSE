package org.example;

import java.util.ArrayList;

public class ClearAllTask implements Command{
    TaskManagementSystem theSystem;
    ArrayList<Task> listofTasks;

    ClearAllTask(TaskManagementSystem theSystem) {
        this.theSystem = theSystem;
        listofTasks = new ArrayList<>();
        this.listofTasks.addAll(theSystem.getTasks());
        System.out.println("Task finished, removed from Calendar.");
    }

    @Override
    public void execute(){
        theSystem.getTasks().removeAll(listofTasks);
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
    }
    @Override
    public void undo(){
        if(RecordedCommand.getUndoList()!=null){
            theSystem.getTasks().addAll(listofTasks);
            RecordedCommand.getRedoList().add(this);
            RecordedCommand.getUndoList().remove(this);
            System.out.println("redolist: "+RecordedCommand.getRedoList());
            System.out.println("undolist: "+RecordedCommand.getUndoList());
        }
    }
    @Override
    public void redo(){
        theSystem.getTasks().removeAll(listofTasks);
        RecordedCommand.getRedoList().removeLast();
        RecordedCommand.getUndoList().add(this);
        System.out.println("redolist: "+RecordedCommand.getRedoList());
        System.out.println("undolist: "+RecordedCommand.getUndoList());
    }
}



