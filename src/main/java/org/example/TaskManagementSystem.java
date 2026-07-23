package org.example;

import java.io.FileNotFoundException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javafx.application.Platform;
import org.example.CliRunner.CLIRunner;
import org.example.Command.*;
import org.example.GUI.SchedulerController;

public class TaskManagementSystem {

    private static TaskManagementSystem theSystem;
    private List<Task> pendingTasks;
    private List<Task> placedTasks;
    private List<Task> unscheduledTasks;
    private SchedulerEngine engine;
    private BlockedTimePeriod blockedTime;
    private LocalDate baseDate;
    private SchedulerController guiController;
    private boolean cliFlag;
    private Map<LocalDate, Day> daysGrid;
    private FetchPopOutTask popupTask;

    private TaskManagementSystem() {
        System.out.println("System Created - with RunSchedulerCommand");
        pendingTasks = new ArrayList<>();
        placedTasks = new ArrayList<>();
        unscheduledTasks = new ArrayList<>();
        baseDate = LocalDate.now();
        blockedTime = new BlockedTimePeriod();
        daysGrid = new TreeMap<>();
        engine = new SchedulerEngine(this, baseDate, daysGrid);
        cliFlag = false;
    }

    public static TaskManagementSystem getInstance() {
        if (theSystem != null) {
            return theSystem;
        }
        theSystem = new TaskManagementSystem();
        return theSystem;
    }

    public void attachGUI(SchedulerController controller) {
        this.guiController = controller;
    }

    public List<Task> getPendingTasks() {
        return pendingTasks;
    }

    public List<Task> getPlacedTasks() {
        return placedTasks;
    }

    public List<Task> getUnscheduledTasks() {
        return unscheduledTasks;
    }

    public BlockedTimePeriod getBlockedTime() {
        return blockedTime;
    }

    public Map<LocalDate, Day> getGrid() {
        return daysGrid;
    }

    public SchedulerEngine getEngine() {
        return engine;
    }

    /**
     * Only Deadline + Flexible tasks are re-scheduled.
     * Fixed tasks are left untouched.
     */
    public void updateSchedule() {
        List<Task> tasksToSchedule = new ArrayList<>();

        for (Task t : pendingTasks) {
            if (!t.isFixedTime()) {
                tasksToSchedule.add(t);
            }
        }
        for (Task t : placedTasks) {
            if (!t.isFixedTime()) {
                tasksToSchedule.add(t);
            }
        }

        // Keep only Fixed tasks
        List<Task> fixedKeep = new ArrayList<>();
        for (Task t : placedTasks) {
            if (t.isFixedTime()) {
                fixedKeep.add(t);
            }
        }

        // Clear non-Fixed from grid
        for (Day day : daysGrid.values()) {
            Task[] slots = day.returnDayGrid();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] != null && !slots[i].isFixedTime()) {
                    slots[i] = null;
                }
            }
        }

        placedTasks.clear();
        placedTasks.addAll(fixedKeep);

        engine.scheduleTasks(tasksToSchedule);
    }

    public void refreshGUI() {
        if (guiController != null) {
            Platform.runLater(() -> guiController.refresh());
        }
    }

    public FetchPopOutTask fetchPopupTask(FetchPopOutTask popupTask) {
        this.popupTask = popupTask;
        return popupTask;
    }

    public FetchPopOutTask getPopupTask() {
        return popupTask;
    }

    public boolean command(String cmdString) {

        switch (cmdString) {

            case "addTask":
                if (!ValidateAddTaskInput.validateAddTaskInput(
                        popupTask.getTaskName(),
                        popupTask.getDuration(),
                        popupTask.getState(),
                        popupTask.getStartDate(),
                        popupTask.getStartTime(),
                        popupTask.getDeadlineDate(),
                        popupTask.getDeadlineTime())) {
                    return false;
                }

                Command addTaskFromGUI = new AddTask(
                        popupTask.getTaskName(),
                        popupTask.getDuration(),
                        popupTask.getState(),
                        popupTask.getStartDate(),
                        popupTask.getStartTime(),
                        popupTask.getDeadlineDate(),
                        popupTask.getDeadlineTime(),
                        popupTask.getPlacedDate(),
                        popupTask.getPlacedTime()
                );

                if (!addTaskFromGUI.execute()) {
                    return false;
                }

                // AddTask already auto-schedules Deadline/Flexible created from Add button
                refreshGUI();
                return true;

            case "addTaskFromFile":
                try {
                    Command addTaskWithFile = new AddTaskWithFile(this);
                    if (!addTaskWithFile.execute()) {
                        return false;
                    }
                    refreshGUI();
                    return true;
                } catch (FileNotFoundException e) {
                    System.out.println("File Not Found!");
                    return false;
                }

            case "clearTask":
                Command clearTask = new ClearTask(getPopupTask().getTask());
                if (!clearTask.execute()) {
                    return false;
                }
                refreshGUI();
                return true;

            case "printAllTask":
                Command printAll = new PrintAllTask(this);
                return printAll.execute();

            case "clearAllTask":
                Command clearAllTask = new ClearAllTask(this);
                if (!clearAllTask.execute()) {
                    return false;
                }
                refreshGUI();
                return true;

            case "undo":
                RecordedCommand undo = new Undo(this);
                if (!undo.execute()) {
                    return false;
                }
                refreshGUI();
                return true;

            case "redo":
                RecordedCommand redo = new Redo(this);
                if (!redo.execute()) {
                    return false;
                }
                refreshGUI();
                return true;

            case "updateGUI":
                refreshGUI();
                return true;

            case "runScheduler":
                // Now uses the proper Command so it is undoable
                Command runScheduler = new RunSchedulerCommand(this);
                if (!runScheduler.execute()) {
                    return false;
                }
                refreshGUI();
                System.out.println("Scheduler ran (undoable).");
                return true;

            case "exit":
                System.exit(0);
                return true;

            case "addTaskFromCli":
                System.out.println("CLI addTask is currently disabled. Please use the GUI.");
                return false;

            default:
                System.out.println("Invalid Command.");
                return false;
        }
    }

    public CLIRunner getCliMode() {
        return new CLIRunner();
    }

    public boolean getCliFlag() {
        return cliFlag;
    }
}