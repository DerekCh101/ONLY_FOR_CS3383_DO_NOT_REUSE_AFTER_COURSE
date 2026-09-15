package org.example;

import java.io.FileNotFoundException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javafx.application.Platform;
import org.example.CliRunner.CLIRunner;
import org.example.Command.*;
import org.example.GUI.SchedulerController;
import org.example.db.TaskRepository;
import org.example.net.Api;

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
    private TaskRepository repo;
    private volatile boolean pollRunning;

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
        repo = new TaskRepository();

        try {
            repo.dbfetchAll(this);
        } catch (SQLException e) {
            System.out.println("Failed to load tasks: " + e.getMessage());
            e.printStackTrace();
        }

        startNodePoll();
    }

    public static TaskManagementSystem getInstance() {
        if (theSystem != null) {
            return theSystem;
        }
        theSystem = new TaskManagementSystem();
        return theSystem;
    }

    public static void resetInstance() {
        if (theSystem != null) {
            theSystem.pollRunning = false;
        }
        theSystem = null;
    }

    public void attachGUI(SchedulerController controller) {
        this.guiController = controller;
        refreshGUI();
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

    public TaskRepository getTaskRepo() {
        return repo;
    }

    private void startNodePoll() {
        if (pollRunning) return;
        pollRunning = true;
        Thread t = new Thread(() -> {
            while (pollRunning) {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    break;
                }
                if (!pollRunning) break;
                if (Api.INSTANCE.getToken() == null || Api.INSTANCE.getToken().isBlank()) {
                    continue;
                }
                try {
                    if (repo.pullFromNode()) {
                        repo.reloadFromSqlite(this);
                        refreshGUI();
                    }
                } catch (Exception e) {
                    System.err.println("node poll: " + e.getMessage());
                }
            }
        }, "node-poll");
        t.setDaemon(true);
        t.start();
    }

    public void updateSchedule() {
        ArrayList<Task> toSchedule = new ArrayList<>();

        for (Task t : new ArrayList<>(placedTasks)) {
            if (!t.isFixedTime()) {
                toSchedule.add(t);
            }
        }
        for (Task t : new ArrayList<>(pendingTasks)) {
            if (!t.isFixedTime() && !toSchedule.contains(t)) {
                toSchedule.add(t);
            }
        }
        for (Task t : new ArrayList<>(unscheduledTasks)) {
            if (!t.isFixedTime() && !toSchedule.contains(t)) {
                toSchedule.add(t);
            }
        }

        for (Task t : toSchedule) {
            placedTasks.remove(t);
            pendingTasks.remove(t);
            unscheduledTasks.remove(t);
            for (Day day : daysGrid.values()) {
                Task[] slots = day.returnDayGrid();
                boolean touched = false;
                for (int i = 0; i < slots.length; i++) {
                    if (slots[i] == t) {
                        slots[i] = null;
                        touched = true;
                    }
                }
                if (touched) {
                    day.recomputeLoadFromGrid();
                }
            }
            t.setPlacedDate(null);
            t.setPlacedTime(null);
        }

        engine.scheduleTasks(toSchedule);
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
        try {
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
                case "finishTask":
                    Command finishTask = new FinishTask(getPopupTask().getTask());
                    if (!finishTask.execute()) {
                        return false;
                    }
                    refreshGUI();
                    return true;

                case "unfinishTask":
                    Command unfinishTask = new UnfinishTask(getPopupTask().getTask());
                    if (!unfinishTask.execute()) {
                        return false;
                    }
                    refreshGUI();
                    return true;

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
        } catch (SQLException e) {
            System.out.println("Database error: " + e.getMessage());
            e.printStackTrace();
            return false;
        } catch (Exception e) {
            System.out.println("Command failed: " + e.getMessage());
            e.printStackTrace();
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