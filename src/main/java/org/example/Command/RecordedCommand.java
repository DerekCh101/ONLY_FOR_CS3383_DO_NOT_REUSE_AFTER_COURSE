package org.example.Command;


import java.sql.SQLException;
import java.util.ArrayList;
public class RecordedCommand implements Command {

    private static ArrayList<Command> undoList = new ArrayList<>();
    private static ArrayList<Command> redoList = new ArrayList<>();
    public static ArrayList<Command> getUndoList(){
        return undoList;
    }
    public static ArrayList<Command> getRedoList(){
        return redoList;
    }
    @Override
    public boolean execute() throws SQLException{
        // TODO Auto-generated method stub
        return true;
    }
    @Override
    public void undo() throws SQLException{

    }
    @Override
    public void redo() throws SQLException {

    }
}
