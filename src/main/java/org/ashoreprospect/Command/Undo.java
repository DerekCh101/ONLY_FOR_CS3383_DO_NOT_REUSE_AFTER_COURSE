/** Pop the last recorded command and call undo(). */
package org.ashoreprospect.Command;


import org.ashoreprospect.TaskManagementSystem;

import java.sql.SQLException;

public class Undo extends RecordedCommand {

    TaskManagementSystem theSystem;

    public Undo(TaskManagementSystem theSystem){
        this.theSystem = theSystem;
    }

    @Override
    public boolean execute() throws SQLException {
        RecordedCommand.getUndoList().getLast().undo();
        return true;

    }

}
