package org.example.Command;

import org.example.*;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Scanner;

public class AddTaskWithFile implements Command {

    private final ArrayList<Task> addedTaskList = new ArrayList<>();
    private final TaskManagementSystem theSystem;

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
            if (taskName.isBlank()) continue;

            int duration = 2;
            int state = 0;
            LocalDate startDate = null;
            LocalTime startTime = null;
            LocalDate deadlineDate = null;
            LocalTime deadlineTime = null;

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

            if (file.hasNextLine()) {
                String stateString = file.nextLine().trim();
                if (!stateString.isBlank()) {
                    try {
                        state = Integer.parseInt(stateString);
                    } catch (NumberFormatException e) {
                        state = 3;
                    }
                }
            }

            if (state == 1) {
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
            } else if (state == 2) {
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

            try {
                boolean valid = ValidateAddTaskInput.validateAddTaskInput(
                        taskName, duration, state,
                        startDate, startTime, deadlineDate, deadlineTime);
                if (valid) {
                    addedTaskList.add(new Task(
                            taskName, duration, state,
                            startDate, startTime, deadlineDate, deadlineTime));
                } else {
                    System.out.printf("Validation failed, %s is not created.%n", taskName);
                }
            } catch (Exception e) {
                System.out.printf("Something went wrong, %s is not created.%n", taskName);
            }
        }

        file.close();
        System.out.println("File loading complete. Tasks prepared: " + addedTaskList.size());
    }

    private void placeFixedIfPossible(Task task) {
        LocalDate date = task.getStartingDate();
        LocalTime time = task.getStartingTime();
        if (date == null || time == null) {
            theSystem.getPendingTasks().add(task);
            return;
        }

        int slotIndex = AppTimeReference.localTimeToSlot(time);
        int slotCount = task.getDurationSlots();

        theSystem.getGrid().putIfAbsent(date, new Day(theSystem.getBlockedTime()));
        Day day = theSystem.getGrid().get(date);

        boolean canPlace = slotIndex >= 0
                && slotIndex + slotCount <= AppTimeReference.SLOTS_PER_DAY;
        if (canPlace) {
            for (int i = 0; i < slotCount; i++) {
                if (day.returnDayGrid()[slotIndex + i] != null) {
                    canPlace = false;
                    break;
                }
            }
        }

        if (canPlace) {
            day.placeTask(slotIndex, task);
            task.setPlacedDate(date);
            task.setPlacedTime(AppTimeReference.slotToLocalTime(slotIndex));
            theSystem.getPlacedTasks().add(task);
        } else {
            theSystem.getPendingTasks().add(task);
        }
    }

    private void applyToSystem() {
        for (Task task : addedTaskList) {
            if (task.isFixedTime()) {
                placeFixedIfPossible(task);
            } else {
                theSystem.getPendingTasks().add(task);
            }
        }
        theSystem.updateSchedule();
    }

    private void removeFromSystem(Task task) {
        theSystem.getPendingTasks().remove(task);
        theSystem.getPlacedTasks().remove(task);
        theSystem.getUnscheduledTasks().remove(task);
        for (Day day : theSystem.getGrid().values()) {
            Task[] slots = day.returnDayGrid();
            boolean touched = false;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == task) {
                    slots[i] = null;
                    touched = true;
                }
            }
            if (touched) {
                day.recomputeLoadFromGrid();
            }
        }
        task.setPlacedDate(null);
        task.setPlacedTime(null);
    }

    @Override
    public boolean execute() throws SQLException {
        applyToSystem();
        for (Task task : addedTaskList) {
            RefreshLifeCycle.refreshLifeCycle(task);
            theSystem.getTaskRepo().dbAddTask(task);
        }
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        System.out.println("Tasks added from file (DB saved).");
        return true;
    }

    @Override
    public void undo() throws SQLException {
        for (Task task : addedTaskList) {
            removeFromSystem(task);
            theSystem.getTaskRepo().dbClearTask(task);
        }
        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() throws SQLException {
        applyToSystem();
        for (Task task : addedTaskList) {
            RefreshLifeCycle.refreshLifeCycle(task);
            theSystem.getTaskRepo().dbAddTask(task);
        }
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }
}