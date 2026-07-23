package org.example.Command;

import org.example.Task;
import org.example.TaskManagementSystem;
import org.example.ValidateAddTaskInput;

import java.io.InputStream;
import java.util.*;
import java.time.format.DateTimeFormatter;
import java.io.FileNotFoundException;
import java.time.LocalDate;
import java.time.LocalTime;

public class AddTaskWithFile implements Command {

    private ArrayList<Task> addedTaskList = new ArrayList<>();
    private TaskManagementSystem theSystem;

    public AddTaskWithFile(TaskManagementSystem theSystem) throws FileNotFoundException {
        this.theSystem = theSystem;

        InputStream input = getClass().getClassLoader().getResourceAsStream("taskInput1.txt");
        if (input == null) {
            throw new FileNotFoundException("taskInput1.txt not found in resources");
        }

        Scanner file = new Scanner(input);

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH-mm");

        while (file.hasNextLine()) {

            String taskName = file.nextLine().trim();
            if (taskName.isBlank()) {
                continue;
            }

            int duration = 2;
            int state = 0;
            LocalDate startDate = null;
            LocalTime startTime = null;
            LocalDate deadlineDate = null;
            LocalTime deadlineTime = null;

            // Duration
            if (file.hasNextLine()) {
                String durationString = file.nextLine().trim();
                if (!durationString.isBlank()) {
                    try {
                        duration = Integer.parseInt(durationString);
                    } catch (NumberFormatException e) {
                        duration = 2;
                    }
                }
            }

            // State
            if (file.hasNextLine()) {
                String stateString = file.nextLine().trim();
                if (!stateString.isBlank()) {
                    try {
                        state = Integer.parseInt(stateString);
                    } catch (NumberFormatException e) {
                        state = 3; // default to Flexible
                    }
                }
            }

            // Read extra fields according to state
            if (state == 1) { // Fixed → needs start date + time
                if (file.hasNextLine()) {
                    String dateStr = file.nextLine().trim();
                    if (!dateStr.isBlank()) {
                        try {
                            startDate = LocalDate.parse(dateStr, dateFormatter);
                        } catch (Exception e) {
                            startDate = null;
                        }
                    }
                }
                if (file.hasNextLine()) {
                    String timeStr = file.nextLine().trim();
                    if (!timeStr.isBlank()) {
                        try {
                            startTime = LocalTime.parse(timeStr, timeFormatter);
                        } catch (Exception e) {
                            startTime = null;
                        }
                    }
                }
            } else if (state == 2) { // Deadline → needs deadline date + time
                if (file.hasNextLine()) {
                    String dateStr = file.nextLine().trim();
                    if (!dateStr.isBlank()) {
                        try {
                            deadlineDate = LocalDate.parse(dateStr, dateFormatter);
                        } catch (Exception e) {
                            deadlineDate = null;
                        }
                    }
                }
                if (file.hasNextLine()) {
                    String timeStr = file.nextLine().trim();
                    if (!timeStr.isBlank()) {
                        try {
                            deadlineTime = LocalTime.parse(timeStr, timeFormatter);
                        } catch (Exception e) {
                            deadlineTime = LocalTime.of(23, 59);
                        }
                    } else {
                        deadlineTime = LocalTime.of(23, 59);
                    }
                }
            }
            // state 3 (Flexible) → nothing more to read

            try {
                boolean valid = ValidateAddTaskInput.validateAddTaskInput(
                        taskName, duration, state,
                        startDate, startTime, deadlineDate, deadlineTime);

                if (valid) {
                    Task thisTask = new Task(
                            taskName, duration, state,
                            startDate, startTime, deadlineDate, deadlineTime);

                    addedTaskList.add(thisTask);
                } else {
                    System.out.printf("Validation failed, %s is not created.\n", taskName);
                }

            } catch (Exception e) {
                System.out.printf("Something went wrong, %s is not created.\n", taskName);
            }
        }

        file.close();
        System.out.println("File loading complete. Tasks prepared: " + addedTaskList.size());
    }

    @Override
    public boolean execute() {
        for (Task task : addedTaskList) {
            theSystem.getPendingTasks().add(task);
        }
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        System.out.println("Tasks added from file.");
        return true;
    }

    @Override
    public void undo() {
        for (Task task : addedTaskList) {
            theSystem.getPendingTasks().remove(task);
            theSystem.getPlacedTasks().remove(task);
            theSystem.getUnscheduledTasks().remove(task);

            // Also clear from grid
            for (var day : theSystem.getGrid().values()) {
                Task[] slots = day.returnDayGrid();
                for (int i = 0; i < slots.length; i++) {
                    if (slots[i] == task) {
                        slots[i] = null;
                    }
                }
            }
        }
        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() {
        for (Task task : addedTaskList) {
            theSystem.getPendingTasks().add(task);
        }
        RecordedCommand.getRedoList().removeLast();
        RecordedCommand.getUndoList().add(this);
    }
}