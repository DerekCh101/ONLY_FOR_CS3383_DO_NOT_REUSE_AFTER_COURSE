package org.example.Command;


import org.example.Task;
import org.example.TaskManagementSystem;

public class PrintAllTask implements Command{
    
    private TaskManagementSystem theSystem;

    public PrintAllTask(TaskManagementSystem theSystem){
        this.theSystem = theSystem;
    }

    @Override
    public boolean execute() {
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
