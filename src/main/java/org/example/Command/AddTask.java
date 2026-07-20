package org.example.Command;

import org.example.Task;
import org.example.TaskManagementSystem;
import org.example.ValidateAddTaskInput;

import java.util.*;
import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.time.LocalTime;

public class AddTask extends RecordedCommand {

    private Task thisTask;
    private TaskManagementSystem theSystem;

    public AddTask(String taskName, int duration, int state,
                   LocalDate startDate, LocalTime startTime,
                   LocalDate deadlineDate, LocalTime deadlineTime) {

        theSystem = TaskManagementSystem.getInstance();

        if (duration <= 0) {
            duration = 2;
        }

        try {
            ValidateAddTaskInput.validateAddTaskInput(
                    taskName, duration, state,
                    startDate, startTime, deadlineDate, deadlineTime);

            thisTask = new Task(taskName, duration, state,
                    startDate, startTime, deadlineDate, deadlineTime);

        } catch (Exception ignored) {
            System.out.printf("\nSome thing went wrong, %s is not created.\n", taskName);
        }
    }

    // ===== CLI version (for debugging) =====
    public AddTask(TaskManagementSystem theSystem) {
        this.theSystem = theSystem;
        Scanner scanner = new Scanner(System.in);

        System.out.println("Enter the task name:");
        String taskName = scanner.nextLine().trim();
        while (taskName.isBlank()) {
            System.out.println("Task name cannot be blank. Enter again:");
            taskName = scanner.nextLine().trim();
        }

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH-mm");

        LocalDate startDate = null;
        LocalTime startTime = null;
        LocalDate deadlineDate = null;
        LocalTime deadlineTime = null;

        System.out.println("Enter the type of task (1 = Fixed, 2 = Deadline, 3 = Flexible):");
        int state = Integer.parseInt(scanner.nextLine().trim());

        if (state == 1) {
            System.out.println("Enter starting date (dd-MM-yyyy):");
            String dateStr = scanner.nextLine().trim();
            startDate = LocalDate.parse(dateStr, dateFormatter);

            System.out.println("Enter starting time (HH-mm):");
            String timeStr = scanner.nextLine().trim();
            startTime = LocalTime.parse(timeStr, timeFormatter);

        } else if (state == 2) {
            System.out.println("Enter deadline date (dd-MM-yyyy):");
            String dateStr = scanner.nextLine().trim();
            deadlineDate = LocalDate.parse(dateStr, dateFormatter);

            System.out.println("Enter deadline time (HH-mm):");
            String timeStr = scanner.nextLine().trim();
            deadlineTime = LocalTime.parse(timeStr, timeFormatter);
        }
        // state 3 → both stay null

        System.out.println("Expected duration in hours (default 2):");
        String durationString = scanner.nextLine().trim();
        int duration = durationString.isBlank() ? 2 : Integer.parseInt(durationString);

        try {
            ValidateAddTaskInput.validateAddTaskInput(
                    taskName, duration, state,
                    startDate, startTime, deadlineDate, deadlineTime);

            thisTask = new Task(taskName, duration, state,
                    startDate, startTime, deadlineDate, deadlineTime);

            System.out.println("Task created successfully.");
        } catch (Exception ignored) {
            System.out.printf("\nSome thing went wrong, %s is not created.\n", taskName);
        }
    }

    @Override
    public boolean execute() {
        if (thisTask != null) {
            theSystem.getPendingTasks().add(thisTask);
            RecordedCommand.getRedoList().clear();
            RecordedCommand.getUndoList().add(this);
            return true;
        }
        return false;
    }

    @Override
    public void undo() {
        if (thisTask != null) {
            theSystem.getPlacedTasks().remove(thisTask);
            RecordedCommand.getRedoList().add(this);
            RecordedCommand.getUndoList().remove(this);
            theSystem.getEngine().scheduleTasks(theSystem.getPendingTasks());
        }
    }

    @Override
    public void redo() {
        if (thisTask != null) {
            theSystem.getPendingTasks().add(thisTask);
            RecordedCommand.getRedoList().removeLast();
            RecordedCommand.getUndoList().add(this);
            theSystem.getEngine().scheduleTasks(theSystem.getPendingTasks());
        }
    }
}