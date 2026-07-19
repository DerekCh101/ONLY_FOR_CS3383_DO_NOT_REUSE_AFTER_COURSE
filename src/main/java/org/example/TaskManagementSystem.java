package org.example;


import java.io.FileNotFoundException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javafx.application.Platform;
import javafx.stage.Popup;
import org.example.CliRunner.CLIRunner;
import org.example.Command.*;

public class TaskManagementSystem {

    private static TaskManagementSystem theSystem;
    private List<Task> pendingTasks;
    private List<Task> placedTasks;
    private List<Task> unscheduledTasks;
    private SchedulerEngine engine;
    private BlockedTimePeriod blockedTime;
    private LocalDate baseDate;
    private SchedulerController guiController;
    private CLIRunner cliMode;
    private boolean cliFlag;
    private Map<LocalDate, Day> daysGrid;
    private FetchPopOutTask popupTask;


    private TaskManagementSystem() {
        System.out.println("System Created, this version is made in 17:00 06/04/2026.");
        pendingTasks = new ArrayList<>();
        placedTasks = new ArrayList<>();
        unscheduledTasks = new ArrayList<>();
        baseDate = LocalDate.now();
        blockedTime = new BlockedTimePeriod();
        daysGrid = new TreeMap<>();
        engine = new SchedulerEngine(this, baseDate,daysGrid);
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
    public List<Task> getPlacedTasks() {return placedTasks; }
    public List<Task> getUnscheduledTasks() {return unscheduledTasks;}
    public BlockedTimePeriod getBlockedTime() {
        return blockedTime;
    }
    public Map<LocalDate, Day> getGrid() {return daysGrid;}
    public SchedulerEngine getEngine(){return engine;}

    public void updateSchedule() {//doesn't render grid by itself
        engine.scheduleTasks(placedTasks);
    } 

    public void refreshGUI() {
        if (guiController != null) {
            Platform.runLater(() -> {
                guiController.refresh();
            });
        }
    }

    public FetchPopOutTask fetchPopupTask(FetchPopOutTask popupTask){
        this.popupTask = popupTask;
        return popupTask;
    }
    public FetchPopOutTask getPopupTask(){
        return popupTask;
    }

    public boolean command(String cmdString) {

        switch (cmdString) {

            case "addTask":

                if(!ValidateAddTaskInput.validateAddTaskInput(
                        popupTask.getTaskName(),
                        popupTask.getDuration(),
                        popupTask.getState(),
                        popupTask.getClickedDate(),
                        popupTask.getClickedTime(),
                        popupTask.getSecDate(),
                        popupTask.getSecTime())){
                    return false;
                }

                Command addTaskFromGUI = new AddTask(
                        popupTask.getTaskName(),
                        popupTask.getDuration(),
                        popupTask.getState(),
                        popupTask.getClickedDate(),
                        popupTask.getClickedTime(),
                        popupTask.getSecDate(),
                        popupTask.getSecTime()
                );

                if (!addTaskFromGUI.execute()) {
                    return false;
                }
                engine.scheduleTasks(pendingTasks);
                return true;


            case "addTaskFromFile":
                try {
                    Command addTaskWithFile = new AddTaskWithFile(this);

                    if (!addTaskWithFile.execute()) {
                        return false;
                    }

                    engine.scheduleTasks(pendingTasks);
                    return true;

                } catch (FileNotFoundException e) {
                    System.out.println("File Not Found!");
                    return false;
                }

            case "clearTask":
                Command clearTask = new ClearTask(this);

                if (!clearTask.execute()) {
                    return false;
                }
                engine.scheduleTasks(placedTasks);
                return true;

            case "printAllTask":
                Command printAll = new PrintAllTask(this);

                if (!printAll.execute()) {
                    return false;
                }

                return true;

            case "clearAllTask":
                Command clearAllTask = new ClearAllTask(this);

                if (!clearAllTask.execute()) {
                    return false;
                }

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
                System.out.println("GUI updated.");
                return true;

            case "runScheduler":
                updateSchedule();
                refreshGUI();
                System.out.println("GUI updated.");
                return true;

            case "exit":
                System.exit(0);
                return true;

            case "addTaskFromCli":
                Command addTask = new AddTask(this);

                if (!addTask.execute()) {
                    return false;
                }

                return true;

            default:
                System.out.println("Invalid Command.");
                return false;
        }
    }
    public CLIRunner getCliMode(){
        return new CLIRunner();
    }
    public boolean getCliFlag(){
        return cliFlag;
    }
}
