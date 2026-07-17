package org.example;


import java.io.FileNotFoundException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javafx.application.Platform;
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

    public void updateSchedule() {
        theSystem.command("printAllTask");
        engine.scheduleTasks(placedTasks);
    }

    public void refreshGUI() {
        if (guiController != null) {
            Platform.runLater(() -> {
                guiController.refresh();
            });
        }
    }


    public void command(String cmdString) {

        switch (cmdString) {

            case "addTask":
                Command addTask = new AddTask(this);
                addTask.execute();
                cmdString = null;
                break;

            case "addTaskFromFile"://for debugging mainly
                try {
                    Command addTaskWithFile = new AddTaskWithFile(this);
                    addTaskWithFile.execute();
                    engine.scheduleTasks(pendingTasks);
                } catch (FileNotFoundException e) {
                    System.out.println("File Not Found!");
                }
                cmdString = null;
                break;

            case "clearTask":
                Command clearTask = new ClearTask(this);
                clearTask.execute();
                cmdString = null;
                break;

            case "printAllTask":
                Command printAll = new PrintAllTask(this);
                printAll.execute();
                cmdString = null;
                break;
            case "clearAllTask":
                Command clearAllTask = new ClearAllTask(this);
                clearAllTask.execute();
                cmdString = null;
                break;

            case "undo":
                RecordedCommand undo = new Undo(this);
                undo.execute();
                cmdString = null;
                break;

            case "redo":
                RecordedCommand redo = new Redo(this);
                redo.execute();
                cmdString = null;
                break;

            case "updateGUI":
                refreshGUI();
                System.out.println("GUI updated.");
                cmdString = null;
                break;

            case "runScheduler":
                updateSchedule();
                refreshGUI();
                System.out.println("GUI updated.");
                cmdString = null;
                break;

            case "exit":
                System.exit(0);
                break;


            default:
                System.out.println("Invalid Command.");
                cmdString = null;
                break;
        }

    }
    public CLIRunner getCliMode(){
        return new CLIRunner();
    }
    public boolean getCliFlag(){
        return cliFlag;
    }
}
