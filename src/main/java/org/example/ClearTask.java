package org.example;


import java.util.Scanner;

public class ClearTask implements Command {

    TaskManagementSystem theSystem;
    Task thisTask;
    Scanner scanner;


    ClearTask(TaskManagementSystem theSystem){
        this.theSystem = theSystem;
        System.out.println("Enter the taskName");
        scanner = new Scanner(System.in);
        String taskName = scanner.nextLine();
        for(Task task: theSystem.getTasks()){
            if(task.getTaskName().equals(taskName)){
                this.thisTask = task;
                break;
            }
        }
        System.out.println("Task Finsihed, removed from Calendar.");
    }
    @Override
    public void execute(){
        theSystem.getTasks().remove(thisTask);
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
    }
    @Override
    public void undo(){
        if(RecordedCommand.getUndoList()!=null){
            theSystem.getTasks().add(thisTask);
            RecordedCommand.getRedoList().add(this);
            RecordedCommand.getUndoList().remove(this);
            System.out.println("redolist: "+RecordedCommand.getRedoList());
            System.out.println("undolist: "+RecordedCommand.getUndoList());
        }
    }
    @Override
    public void redo(){
        theSystem.getTasks().remove(thisTask);
        RecordedCommand.getRedoList().removeLast();
        RecordedCommand.getUndoList().add(this);
        System.out.println("redolist: "+RecordedCommand.getRedoList());
        System.out.println("undolist: "+RecordedCommand.getUndoList());
    }
}

