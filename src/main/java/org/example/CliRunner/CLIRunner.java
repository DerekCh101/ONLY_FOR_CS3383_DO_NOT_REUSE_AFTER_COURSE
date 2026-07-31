// Last being maintained 21/7/2026
// for early-debug only

package org.example.CliRunner;

import org.example.Command.*;
import org.example.Task;
import org.example.TaskManagementSystem;

import java.io.FileNotFoundException;
import java.util.Scanner;

public class CLIRunner {

    private final TaskManagementSystem theSystem = TaskManagementSystem.getInstance();

    public void command(String cmdString) {

        boolean running = true;
        Scanner input = new Scanner(System.in);

        while (running) {

            if (cmdString == null) {
                System.out.print("Command> ");
                cmdString = input.nextLine().trim();
            }
            if (cmdString.isEmpty()) {
                cmdString = null;
                continue;
            }

            switch (cmdString) {

                case "addTask":
                    System.out.println("CLI addTask is currently disabled.");
                    System.out.println("Please use the GUI to add tasks.");
                    cmdString = null;
                    break;

                case "addTaskFromFile":
                    try {
                        Command addTaskWithFile = new AddTaskWithFile(theSystem);
                        addTaskWithFile.execute();
                    } catch (FileNotFoundException e) {
                        System.out.println("File Not Found!");
                    }
                    cmdString = null;
                    break;

                case "clearTask":
                    System.out.print("Enter task name to clear: ");
                    String name = input.nextLine().trim();
                    Task found = null;
                    for (Task t : theSystem.getPlacedTasks()) {
                        if (t.getTaskName().equals(name)) {
                            found = t;
                            break;
                        }
                    }
                    if (found == null) {
                        for (Task t : theSystem.getPendingTasks()) {
                            if (t.getTaskName().equals(name)) {
                                found = t;
                                break;
                            }
                        }
                    }
                    if (found == null) {
                        for (Task t : theSystem.getUnscheduledTasks()) {
                            if (t.getTaskName().equals(name)) {
                                found = t;
                                break;
                            }
                        }
                    }
                    if (found == null) {
                        System.out.println("Task not found: " + name);
                    } else {
                        Command clearTask = new ClearTask(found);
                        clearTask.execute();
                        System.out.println("Cleared: " + name);
                    }
                    cmdString = null;
                    break;

                case "printAll":
                    Command printAll = new PrintAllTask(theSystem);
                    printAll.execute();
                    cmdString = null;
                    break;

                case "undo":
                    RecordedCommand undo = new Undo(theSystem);
                    undo.execute();
                    cmdString = null;
                    break;

                case "redo":
                    RecordedCommand redo = new Redo(theSystem);
                    redo.execute();
                    cmdString = null;
                    break;

                case "update":
                    theSystem.updateSchedule();
                    System.out.println("Schedule updated.");
                    cmdString = null;
                    break;

                case "exit":
                    running = false;
                    input.close();
                    System.exit(0);
                    break;

                default:
                    System.out.println("Invalid Command.");
                    cmdString = null;
                    break;
            }
        }
    }

    public static void main(String[] args) {
        TaskManagementSystem theSystem = TaskManagementSystem.getInstance();
        theSystem.getCliMode().command(null);
    }
}