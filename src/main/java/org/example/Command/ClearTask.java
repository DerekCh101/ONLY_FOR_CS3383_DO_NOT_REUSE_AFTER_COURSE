package org.example.Command;

import org.example.Day;
import org.example.Task;
import org.example.TaskManagementSystem;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Scanner;

public class ClearTask implements Command {

    private TaskManagementSystem theSystem;
    private Task thisTask;
    private LocalDate placedDate;
    private  LocalTime placedTime;

    public ClearTask(TaskManagementSystem theSystem) {
        this.theSystem = theSystem;
        Scanner scanner = new Scanner(System.in);

        System.out.println("Enter the task name to clear:");
        String taskName = scanner.nextLine().trim();

        // Search in placed tasks first
        for (Task task : theSystem.getPlacedTasks()) {
            if (task.getTaskName().equalsIgnoreCase(taskName)) {
                this.thisTask = task;
                break;
            }
        }

        // If not found, search in pending / unscheduled
        if (thisTask == null) {
            for (Task task : theSystem.getPendingTasks()) {
                if (task.getTaskName().equalsIgnoreCase(taskName)) {
                    this.thisTask = task;
                    break;
                }
            }
        }
        if (thisTask == null) {
            for (Task task : theSystem.getUnscheduledTasks()) {
                if (task.getTaskName().equalsIgnoreCase(taskName)) {
                    this.thisTask = task;
                    break;
                }
            }
        }

        if (thisTask == null) {
            System.out.println("Task not found.");
        } else {
            System.out.println("Task found: " + thisTask.getTaskName());
        }
    }
    public ClearTask(Task task) {
        this.theSystem = TaskManagementSystem.getInstance();
        this.thisTask = task;
        this.placedDate = task.getPlacedDate();
        this.placedTime = task.getPlacedTime();
    }



    @Override
    public boolean execute() {
        if (thisTask == null) {
            return false;
        }

        // Remove from all lists
        theSystem.getPlacedTasks().remove(thisTask);
        theSystem.getPendingTasks().remove(thisTask);
        theSystem.getUnscheduledTasks().remove(thisTask);

        // Clear from the grid
        for (Day day : theSystem.getGrid().values()) {
            Task[] slots = day.returnDayGrid();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == thisTask) {
                    slots[i] = null;
                }
            }
        }

        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        System.out.println("Task cleared from calendar.");
        return true;
    }

    @Override
    public void undo() {
        if (thisTask != null) {
            theSystem.getPlacedTasks().add(thisTask);
            theSystem.getGrid().get(thisTask.getPlacedDate()).placeTask(
                    placedTime.getHour()-theSystem.getEngine().getStartingHour(),
                    thisTask.getDurationHours(), thisTask);
            RecordedCommand.getRedoList().add(this);
            RecordedCommand.getUndoList().remove(this);
        }
    }

    @Override
    public void redo() {
        execute();
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }
}