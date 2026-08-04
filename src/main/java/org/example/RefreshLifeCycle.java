package org.example;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;

public final class RefreshLifeCycle { //helper method class for refreshing a life cycle

    private RefreshLifeCycle() {}

    public static void refreshLifeCycle(Task task) {
        if (task.getPlacedDate() == null || task.getPlacedTime() == null) {
            task.setLifeCycle(TaskLifeCycle.UNSCHEDULED);
            return;
        }
        LocalDate today = AppTimeReference.getCurrentDate();
        LocalTime now = AppTimeReference.getCurrentTime();
        LocalTime end = task.getPlacedTime().plusMinutes(
                (long) task.getDurationSlots() * AppTimeReference.MINUTES_PER_SLOT);

        boolean past = task.getPlacedDate().isBefore(today)
                || (task.getPlacedDate().isEqual(today) && !end.isAfter(now));

        task.setLifeCycle(past ? TaskLifeCycle.PAST : TaskLifeCycle.ONGOING);
    }

    public static void refreshLifeCycleAndSave(Task task) throws SQLException {
        refreshLifeCycle(task);
        TaskManagementSystem.getInstance().getTaskRepo().dbAddTask(task);
    }
}