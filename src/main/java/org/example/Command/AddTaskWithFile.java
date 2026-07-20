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
                    duration = Integer.parseInt(durationString);
                }
            }

            // State
            if (file.hasNextLine()) {
                String stateString = file.nextLine().trim();
                if (!stateString.isBlank()) {
                    state = Integer.parseInt(stateString);
                }
            }

            // Read extra fields according to state
            if (state == 1) { // Fixed → needs start date + time
                if (file.hasNextLine()) {
                    String dateStr = file.nextLine().trim();
                    if (!dateStr.isBlank()) {
                        startDate = LocalDate.parse(dateStr, dateFormatter);
                    }
                }
                if (file.hasNextLine()) {
                    String timeStr = file.nextLine().trim();
                    if (!timeStr.isBlank()) {
                        startTime = LocalTime.parse(timeStr, timeFormatter);
                    }
                }
            } else if (state == 2) { // Deadline → needs deadline date + time
                if (file.hasNextLine()) {
                    String dateStr = file.nextLine().trim();
                    if (!dateStr.isBlank()) {
                        deadlineDate = LocalDate.parse(dateStr, dateFormatter);
                    }
                }
                if (file.hasNextLine()) {
                    String timeStr = file.nextLine().trim();
                    if (!timeStr.isBlank()) {
                        deadlineTime = LocalTime.parse(timeStr, timeFormatter);
                    }
                }
            }
            // state 3 (Flexible) → nothing more to read

            try {
                ValidateAddTaskInput.validateAddTaskInput(
                        taskName, duration, state,
                        startDate, startTime, deadlineDate, deadlineTime);

                Task thisTask = new Task(
                        taskName, duration, state,
                        startDate, startTime, deadlineDate, deadlineTime);

                addedTaskList.add(thisTask);

            } catch (Exception ignored) {
                System.out.printf("\nSome thing went wrong, %s is not created.\n", taskName);
            }
        }

        file.close();
        System.out.println("File loading complete.");
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
            theSystem.getPlacedTasks().remove(task);
        }
        theSystem.getEngine().scheduleTasks(theSystem.getPlacedTasks());
        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() {
        for (Task task : addedTaskList) {
            theSystem.getPendingTasks().add(task);
        }
        theSystem.getEngine().scheduleTasks(theSystem.getPendingTasks());
        RecordedCommand.getRedoList().removeLast();
        RecordedCommand.getUndoList().add(this);
    }
}