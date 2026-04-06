public class Undo extends RecordedCommand {

    TaskManagementSystem theSystem;

    public Undo(TaskManagementSystem theSystem){
        this.theSystem = theSystem;
    }

    @Override
    public void execute(){
        RecordedCommand.getUndoList().getLast().undo();
    }

}
