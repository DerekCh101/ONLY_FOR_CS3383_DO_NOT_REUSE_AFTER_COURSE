package org.example;


import java.io.FileNotFoundException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.Scanner;

import javafx.application.Platform;

public class TaskManagementSystem {

    private static TaskManagementSystem theSystem;
    private ArrayList<Task> tasks;
    private SchedulerEngine engine;
    private BlockedTimePeriod blockedTime;
    private LocalDate baseDate;
    private SchedulerApp guiReference;
    //private CLIRunner cliMode;

    private TaskManagementSystem() {
        System.out.println("System Created, this version is made in 17:00 06/04/2026.");
        tasks = new ArrayList<>();
        baseDate = LocalDate.now();
        blockedTime = new BlockedTimePeriod();
        engine = new SchedulerEngine(blockedTime, baseDate);
    }

    public static TaskManagementSystem getInstance() {
        if (theSystem != null) {
            return theSystem;
        }
        theSystem = new TaskManagementSystem();
        return theSystem;
    }
    //public CLIRunner getCliMode(){
//        return new CLIRunner();
//    }

    public void attachGUI(SchedulerApp gui) {
        this.guiReference = gui;
    }

    public ArrayList<Task> getTasks() {
        return tasks;
    }

    public Map<LocalDate, Day> getGrid() {
        return engine.getGrid();
    }

    public void updateSchedule() {
        engine.scheduleTasks(tasks);
    }

    public BlockedTimePeriod getBlockedTime() {
        return blockedTime;
    }

    public void refreshGUI() {
        if (guiReference != null) {
            Platform.runLater(() -> {
                guiReference.renderGrid();
            });
        }
    }

    public SchedulerEngine getEngine(){
        return engine;
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

            case "printAll":
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

            case "update":
                updateSchedule();
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
}
