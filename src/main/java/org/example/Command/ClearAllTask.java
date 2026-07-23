package org.example.Command;

import org.example.Day;
import org.example.Task;
import org.example.TaskManagementSystem;

import java.util.ArrayList;

public class ClearAllTask implements Command {

    private TaskManagementSystem theSystem;
    private ArrayList<Task> savedTasks = new ArrayList<>();

    public ClearAllTask(TaskManagementSystem theSystem) {
        this.theSystem = theSystem;

        // Save current state so we can undo
        savedTasks.addAll(theSystem.getPlacedTasks());
        savedTasks.addAll(theSystem.getPendingTasks());
        savedTasks.addAll(theSystem.getUnscheduledTasks());

        System.out.println("Preparing to clear all tasks...");
    }

    @Override
    public boolean execute() {
        // Clear all lists
        theSystem.getPlacedTasks().clear();
        theSystem.getPendingTasks().clear();
        theSystem.getUnscheduledTasks().clear();

        // Clear the entire grid
        for (Day day : theSystem.getGrid().values()) {
            Task[] slots = day.returnDayGrid();
            for (int i = 0; i < slots.length; i++) {
                slots[i] = null;
            }
        }

        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        System.out.println("All tasks cleared from calendar.");
        return true;
    }

    @Override
    public void undo() {
        // Restore the lists
        theSystem.getPlacedTasks().clear();
        theSystem.getPendingTasks().clear();
        theSystem.getUnscheduledTasks().clear();

        for (Task t : savedTasks) {
            if (t.isFixedTime()) {
                theSystem.getPlacedTasks().add(t);
            } else {
                theSystem.getPendingTasks().add(t);
            }
        }

        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
        System.out.println("Clear All has been undone.");
    }

    @Override
    public void redo() {
        execute();
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }
}