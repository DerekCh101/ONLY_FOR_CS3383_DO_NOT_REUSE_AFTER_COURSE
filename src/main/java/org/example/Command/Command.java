package org.example.Command;

import java.sql.SQLException;

public interface Command{
    public boolean execute() throws SQLException;
    public void undo() throws SQLException;
    public void redo() throws SQLException;

}