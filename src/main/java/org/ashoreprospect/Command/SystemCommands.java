/** Named GUI/console actions (addTask, undo, …). Each name builds a Command. */
package org.ashoreprospect.Command;

import org.ashoreprospect.FetchPopOutTask;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.ValidateAddTaskInput;

import java.io.FileNotFoundException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Named actions for GUI / console. Each action builds a {@link Command} and executes it.
 * Open for new names (register) without editing {@link TaskManagementSystem}.
 */
public final class SystemCommands {

    @FunctionalInterface
    public interface Action {
        boolean run(TaskManagementSystem sys) throws Exception;
    }

    private static final Map<String, Action> BY_NAME = new LinkedHashMap<>();

    static {
        put("addTask", SystemCommands::addTask);
        put("addTaskFromFile", SystemCommands::addTaskFromFile);
        put("finishTask", sys -> exec(sys, new FinishTask(taskFromPopup(sys)), true));
        put("unfinishTask", sys -> exec(sys, new UnfinishTask(taskFromPopup(sys)), true));
        put("clearTask", sys -> exec(sys, new ClearTask(taskFromPopup(sys)), true));
        put("printAllTask", sys -> exec(sys, new PrintAllTask(sys), false));
        put("printAll", sys -> exec(sys, new PrintAllTask(sys), false));
        put("clearAllTask", sys -> exec(sys, new ClearAllTask(sys), true));
        put("clearPastTasks", sys -> exec(sys, new ClearPastTasks(sys), true));
        put("clearPast", sys -> exec(sys, new ClearPastTasks(sys), true));
        put("undo", sys -> exec(sys, new Undo(sys), true));
        put("redo", sys -> exec(sys, new Redo(sys), true));
        put("updateGUI", sys -> {
            sys.refreshGUI();
            return true;
        });
        put("update", sys -> {
            sys.updateSchedule();
            sys.refreshGUI();
            System.out.println("Schedule updated.");
            return true;
        });
        put("runScheduler", sys -> {
            boolean ok = exec(sys, new RunSchedulerCommand(sys), true);
            if (ok) System.out.println("Scheduler ran (undoable).");
            return ok;
        });
        put("exit", sys -> {
            System.exit(0);
            return true;
        });
        put("addTaskFromCli", sys -> {
            System.out.println("CLI addTask is currently disabled. Please use the GUI.");
            return false;
        });
    }

    private SystemCommands() {}

    public static void put(String name, Action action) {
        BY_NAME.put(name, action);
    }

    public static Set<String> names() {
        return Collections.unmodifiableSet(BY_NAME.keySet());
    }

    public static boolean known(String name) {
        return name != null && BY_NAME.containsKey(name);
    }

    public static boolean run(String name, TaskManagementSystem sys) throws Exception {
        if (name == null || name.isBlank()) return false;
        Action action = BY_NAME.get(name);
        if (action == null) {
            System.out.println("Invalid Command.");
            return false;
        }
        return action.run(sys);
    }

    public static boolean exec(TaskManagementSystem sys, Command cmd, boolean refresh) throws Exception {
        if (cmd == null) return false;
        if (!cmd.execute()) return false;
        if (refresh && sys != null) sys.refreshGUI();
        return true;
    }

    private static boolean addTask(TaskManagementSystem sys) throws Exception {
        FetchPopOutTask pop = sys.getPopupTask();
        if (pop == null) return false;
        if (!ValidateAddTaskInput.validateAddTaskInput(
                pop.getTaskName(),
                pop.getDuration(),
                pop.getState(),
                pop.getStartDate(),
                pop.getStartTime(),
                pop.getDeadlineDate(),
                pop.getDeadlineTime())) {
            return false;
        }
        return exec(sys, new AddTask(
                pop.getTaskName(),
                pop.getDuration(),
                pop.getState(),
                pop.getStartDate(),
                pop.getStartTime(),
                pop.getDeadlineDate(),
                pop.getDeadlineTime(),
                pop.getPlacedDate(),
                pop.getPlacedTime()
        ), true);
    }

    private static boolean addTaskFromFile(TaskManagementSystem sys) throws Exception {
        try {
            return exec(sys, new AddTaskWithFile(sys), true);
        } catch (FileNotFoundException e) {
            System.out.println("File Not Found!");
            return false;
        }
    }

    private static org.ashoreprospect.Task taskFromPopup(TaskManagementSystem sys) {
        FetchPopOutTask pop = sys.getPopupTask();
        return pop == null ? null : pop.getTask();
    }
}
