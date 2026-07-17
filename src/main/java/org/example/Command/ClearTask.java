package org.example.Command;


import org.example.Task;
import org.example.TaskManagementSystem;

import java.util.Scanner;

public class ClearTask implements Command {

    TaskManagementSystem theSystem;
    Task thisTask;
    Scanner scanner;


    public ClearTask(TaskManagementSystem theSystem){
        this.theSystem = theSystem;
        System.out.println("Enter the taskName");
        scanner = new Scanner(System.in);
        String taskName = scanner.nextLine();
        for(Task task: theSystem.getPlacedTasks()){
            if(task.getTaskName().equals(taskName)){
                this.thisTask = task;
                break;
            }
        }
        System.out.println("Task Finsihed, removed from Calendar.");
    }
    @Override
    public boolean execute(){
        theSystem.getPlacedTasks().remove(thisTask);
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }
    @Override
    public void undo(){
        if(RecordedCommand.getUndoList()!=null){
            theSystem.getPlacedTasks().add(thisTask);
            RecordedCommand.getRedoList().add(this);
            RecordedCommand.getUndoList().remove(this);
            System.out.println("redolist: "+RecordedCommand.getRedoList());
            System.out.println("undolist: "+RecordedCommand.getUndoList());
        }
    }
    @Override
    public void redo(){
        theSystem.getPlacedTasks().remove(thisTask);
        RecordedCommand.getRedoList().removeLast();
        RecordedCommand.getUndoList().add(this);
        System.out.println("redolist: "+RecordedCommand.getRedoList());
        System.out.println("undolist: "+RecordedCommand.getUndoList());
    }
}

