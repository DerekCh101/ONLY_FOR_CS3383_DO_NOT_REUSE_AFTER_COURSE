package org.example;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;

public final class RefreshLifeCycle {

    private RefreshLifeCycle() {}

    public static void refreshLifeCycle(Task task) {
        if (task == null) return;
        if (task.getPlacedDate() == null || task.getPlacedTime() == null) {
            task.setLifeCycle(TaskLifeCycle.UNSCHEDULED);
            return;
        }
        LocalDate today = AppTimeReference.getCurrentDate();
        LocalTime now = AppTimeReference.getCurrentTime();
        LocalTime end = task.getPlacedTime().plusMinutes(
                (long) task.getDurationSlots() * AppTimeReference.MINUTES_PER_SLOT);
        boolean wrapped = !end.isAfter(task.getPlacedTime()) && task.getDurationSlots() > 0;

        boolean past = task.getPlacedDate().isBefore(today)
                || (task.getPlacedDate().isEqual(today) && (wrapped || !end.isAfter(now)));

        task.setLifeCycle(past ? TaskLifeCycle.PAST : TaskLifeCycle.ONGOING);
    }

    /** @return true if any task changed PAST / ONGOING / UNSCHEDULED */
    public static boolean refreshAll(TaskManagementSystem system) {
        if (system == null) return false;
        boolean changed = false;
        for (Task task : system.getPlacedTasks()) { // use your real list getter
            TaskLifeCycle before = task.getLifeCycle();
            refreshLifeCycle(task);
            if (before != task.getLifeCycle()) changed = true;
        }
        return changed;
    }

    public static void refreshLifeCycleAndSave(Task task) throws SQLException {
        refreshLifeCycle(task);
        TaskManagementSystem.getInstance().getTaskRepo().dbUpdateWorkflow(task);
        // only if you also persist lifecycle; otherwise drop the db call here
    }
}