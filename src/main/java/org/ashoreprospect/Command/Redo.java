/** Pop the last undone command and call redo(). */
package org.ashoreprospect.Command;


import org.ashoreprospect.TaskManagementSystem;

import java.sql.SQLException;

public class Redo extends RecordedCommand {

    TaskManagementSystem theSystem;

    public Redo(TaskManagementSystem theSystem) throws  SQLException{
        this.theSystem = theSystem;
    }

    @Override
    public boolean execute() throws SQLException {
        if (RecordedCommand.getRedoList().isEmpty()) {
            System.out.println("Nothing to redo.");
            return false;
        }
        RecordedCommand.getRedoList().getLast().redo();
        return true;
    }


}
