// Last being maintained 21/7/2026
//for early-debug only

package org.example.CliRunner;

import org.example.Command.*;
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
                    Command clearTask = new ClearTask(theSystem);
                    clearTask.execute();
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