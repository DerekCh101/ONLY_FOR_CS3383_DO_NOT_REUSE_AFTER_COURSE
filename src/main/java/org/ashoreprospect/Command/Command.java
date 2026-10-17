/** One undoable user action. GUI, in-app CLI, and console all execute this. */
package org.ashoreprospect.Command;

import java.sql.SQLException;

public interface Command {
    boolean execute() throws SQLException;

    default void undo() throws SQLException {}

    default void redo() throws SQLException {}
}