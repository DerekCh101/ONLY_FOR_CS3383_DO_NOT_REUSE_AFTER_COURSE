package org.ashoreprospect.Command;


import org.ashoreprospect.TaskManagementSystem;

import java.sql.SQLException;

public class Redo extends RecordedCommand {

    TaskManagementSystem theSystem;

    public Redo(TaskManagementSystem theSystem) throws  SQLException{
        this.theSystem = theSystem;
    }

    @Override
    public boolean execute() throws SQLException{
        RecordedCommand.getRedoList().getLast().redo();
        return true;
    }


}
