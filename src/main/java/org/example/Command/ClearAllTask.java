package org.example.Command;

import org.example.Task;
import org.example.TaskManagementSystem;

import java.util.ArrayList;

public class ClearAllTask implements Command{
    TaskManagementSystem theSystem;
    ArrayList<Task> listofTasks;

    public ClearAllTask(TaskManagementSystem theSystem) {
        this.theSystem = theSystem;
        listofTasks = new ArrayList<>();
        this.listofTasks.addAll(theSystem.getPlacedTasks());
        System.out.println("Task finished, removed from Calendar.");
    }

    @Override
    public boolean execute(){
        theSystem.getPlacedTasks().removeAll(listofTasks);
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }
    @Override
    public void undo(){
        if(RecordedCommand.getUndoList()!=null){
            theSystem.getPlacedTasks().addAll(listofTasks);
            RecordedCommand.getRedoList().add(this);
            RecordedCommand.getUndoList().remove(this);
            System.out.println("redolist: "+RecordedCommand.getRedoList());
            System.out.println("undolist: "+RecordedCommand.getUndoList());
        }
    }
    @Override
    public void redo(){
        theSystem.getPlacedTasks().removeAll(listofTasks);
        RecordedCommand.getRedoList().removeLast();
        RecordedCommand.getUndoList().add(this);
        System.out.println("redolist: "+RecordedCommand.getRedoList());
        System.out.println("undolist: "+RecordedCommand.getUndoList());
    }
}



