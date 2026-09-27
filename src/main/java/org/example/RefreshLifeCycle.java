package org.example;

import java.sql.SQLException;
import java.time.LocalDateTime;

public final class RefreshLifeCycle {

    private RefreshLifeCycle() {}

    public static void refreshLifeCycle(Task task) {
        if (task == null) return;
        if (task.getPlacedDate() == null || task.getPlacedTime() == null) {
            task.setLifeCycle(TaskLifeCycle.UNSCHEDULED);
            return;
        }

        LocalDateTime start = LocalDateTime.of(task.getPlacedDate(), task.getPlacedTime());
        int slots = task.getDurationSlots();
        if (slots < 1) slots = 1;
        LocalDateTime end = start.plusMinutes((long) slots * AppTimeReference.MINUTES_PER_SLOT);
        LocalDateTime now = AppTimeReference.getCurrentDateTime();

        task.setLifeCycle(!end.isAfter(now) ? TaskLifeCycle.PAST : TaskLifeCycle.ONGOING);
    }

    /** @return true if any task changed PAST / ONGOING / UNSCHEDULED */
    public static boolean refreshAll(TaskManagementSystem system) {
        if (system == null) return false;
        boolean changed = false;
        for (Task task : system.getPlacedTasks()) {
            TaskLifeCycle before = task.getLifeCycle();
            refreshLifeCycle(task);
            if (before != task.getLifeCycle()) changed = true;
        }
        return changed;
    }

    public static void refreshLifeCycleAndSave(Task task) throws SQLException {
        refreshLifeCycle(task);
        TaskManagementSystem.getInstance().getTaskRepo().dbUpdateWorkflow(task);
    }
}