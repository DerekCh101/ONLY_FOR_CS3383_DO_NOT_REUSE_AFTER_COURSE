/** Find a live task by UUID or name for CLI verbs. */
package org.ashoreprospect.Command;

import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;

/** Find a live task by id or name. Used by in-app CLI verbs, not by GUI popups. */
public final class TaskLookup {

    private TaskLookup() {}

    public static Task placed(TaskManagementSystem sys, String idOrName) {
        if (sys == null || idOrName == null) return null;
        for (Task t : sys.getPlacedTasks()) {
            if (idOrName.equals(t.getID()) || idOrName.equalsIgnoreCase(t.getTaskName())) return t;
        }
        return null;
    }

    public static Task anywhere(TaskManagementSystem sys, String idOrName) {
        Task placed = placed(sys, idOrName);
        if (placed != null) return placed;
        if (sys == null || idOrName == null) return null;
        for (Task t : sys.getPendingTasks()) {
            if (idOrName.equals(t.getID()) || idOrName.equalsIgnoreCase(t.getTaskName())) return t;
        }
        for (Task t : sys.getUnscheduledTasks()) {
            if (idOrName.equals(t.getID()) || idOrName.equalsIgnoreCase(t.getTaskName())) return t;
        }
        return null;
    }
}
