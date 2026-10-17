/** Advances task lifecycle (PENDING / ONGOING / PAST) as the clock moves. */
package org.ashoreprospect;

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
            if (before != task.getLifeCycle()) {
                changed = true;
                if (before != TaskLifeCycle.PAST
                        && task.getLifeCycle() == TaskLifeCycle.PAST
                        && task.getWorkflow() == Workflow.UNFINISHED) {
                    carryUnfinishedPast(system, task);
                }
            }
        }
        return changed;
    }

    /**
     * Leftover unfinished past work is noted on the next later session
     * with the same category + subcategory + name.
     */
    static void carryUnfinishedPast(TaskManagementSystem system, Task past) {
        if (system == null || past == null) return;
        if (past.getCategory().isBlank() && past.getSubcategory().isBlank()) return;
        Task next = null;
        java.time.LocalDateTime pastStart = past.getPlacedDate() == null || past.getPlacedTime() == null
                ? null : java.time.LocalDateTime.of(past.getPlacedDate(), past.getPlacedTime());
        for (Task t : system.getPlacedTasks()) {
            if (t == past) continue;
            if (!sameSession(past, t)) continue;
            if (t.getPlacedDate() == null || t.getPlacedTime() == null) continue;
            java.time.LocalDateTime start = java.time.LocalDateTime.of(t.getPlacedDate(), t.getPlacedTime());
            if (pastStart != null && !start.isAfter(pastStart)) continue;
            if (next == null
                    || start.isBefore(java.time.LocalDateTime.of(next.getPlacedDate(), next.getPlacedTime()))) {
                next = t;
            }
        }
        if (next == null) {
            String hold = "Unfinished leftover — no later session of this category/sub/name yet.";
            if (!past.getDescription().contains(hold)) {
                String body = past.getDescription();
                past.setDescription(body.isBlank() ? hold : body + "\n" + hold);
                past.touchLastUpdated();
                try {
                    system.getTaskRepo().dbAddTask(past);
                } catch (SQLException ignored) {
                }
            }
            system.offerCarryOrphan(past);
            return;
        }
        String note = "Leftover from " + past.getPlacedDate() + " “" + past.getTaskName() + "”.";
        if (!next.getDescription().contains(note)) {
            String leftover = stripCarryNotes(past.getDescription());
            String extra = leftover.isBlank() ? note : note + "\n" + leftover;
            String body = next.getDescription();
            next.setDescription(body.isBlank() ? extra : body + "\n" + extra);
            next.touchLastUpdated();
            try {
                system.getTaskRepo().dbAddTask(next);
            } catch (SQLException ignored) {
            }
        }
    }

    private static String stripCarryNotes(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            String t = line.trim();
            if (t.startsWith("Leftover from ") || t.startsWith("Unfinished leftover")) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString().trim();
    }

    private static boolean sameSession(Task a, Task b) {
        return a.getTaskName().equalsIgnoreCase(b.getTaskName())
                && a.getCategory().equalsIgnoreCase(b.getCategory())
                && a.getSubcategory().equalsIgnoreCase(b.getSubcategory());
    }

    public static void refreshLifeCycleAndSave(Task task) throws SQLException {
        refreshLifeCycle(task);
        TaskManagementSystem.getInstance().getTaskRepo().dbUpdateWorkflow(task);
    }
}