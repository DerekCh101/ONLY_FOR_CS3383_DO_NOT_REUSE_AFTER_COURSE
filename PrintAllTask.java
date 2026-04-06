public class PrintAllTask implements Command{
    
    private TaskManagementSystem theSystem;

    public PrintAllTask(TaskManagementSystem theSystem){
        this.theSystem = theSystem;
    }

    @Override
    public void execute() {
        for(Task task: theSystem.getTasks()){
            System.out.println(task);
        }
    }
    @Override
    public void undo(){

    }
    @Override
    public void redo(){
        
    }


}
