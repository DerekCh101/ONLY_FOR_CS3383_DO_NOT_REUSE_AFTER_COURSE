public class CLIRunner {
    public static void main(String[] arg){
        TaskManagementSystem theSystem;
        theSystem = TaskManagementSystem.getInstance();
        theSystem.command("addTaskWithFile");


    }
}