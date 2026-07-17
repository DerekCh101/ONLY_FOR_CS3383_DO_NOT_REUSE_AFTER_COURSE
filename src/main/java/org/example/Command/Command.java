package org.example.Command;

public interface Command{
    public boolean execute();
    public void undo();
    public void redo();

}