/** Stdin Command> loop for debug. Named actions go through SystemCommands. */
// Last being maintained 21/7/2026
// for early-debug only

package org.ashoreprospect.CliRunner;

import org.ashoreprospect.Command.ClearTask;
import org.ashoreprospect.Command.SystemCommands;
import org.ashoreprospect.Command.TaskLookup;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;

import java.util.Scanner;

/** Stdin REPL. Named actions go through {@link SystemCommands}; only prompts stay here. */
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

            try {
                if ("exit".equals(cmdString)) {
                    running = false;
                    input.close();
                    System.exit(0);
                    break;
                }
                if ("addTask".equals(cmdString) || "addTaskFromCli".equals(cmdString)) {
                    SystemCommands.run("addTaskFromCli", theSystem);
                } else if ("clearTask".equals(cmdString)) {
                    System.out.print("Enter task name to clear: ");
                    String name = input.nextLine().trim();
                    Task found = TaskLookup.anywhere(theSystem, name);
                    if (found == null) {
                        System.out.println("Task not found: " + name);
                    } else {
                        new ClearTask(found).execute();
                        System.out.println("Cleared: " + name);
                    }
                } else if (SystemCommands.known(cmdString)) {
                    SystemCommands.run(cmdString, theSystem);
                } else {
                    System.out.println("Invalid Command.");
                }
            } catch (Exception e) {
                System.out.println("Command failed: " + e.getMessage());
            }

            cmdString = null;
        }
    }

    public static void main(String[] args) {
        TaskManagementSystem theSystem = TaskManagementSystem.getInstance();
        theSystem.getCliMode().command(null);
    }
}
