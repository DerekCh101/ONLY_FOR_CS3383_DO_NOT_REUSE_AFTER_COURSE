package org.ashoreprospect.Command;


import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;

public class PrintAllTask implements Command{
    
    private TaskManagementSystem theSystem;

    public PrintAllTask(TaskManagementSystem theSystem){
        this.theSystem = theSystem;
    }

    @Override
    public boolean execute() {
        System.out.println("Current placed Task: ");
        for(Task task: theSystem.getPlacedTasks()){
            System.out.println(task);
        }
        return true;
    }
    @Override
    public void undo(){

    }
    @Override
    public void redo(){
        
    }


}
