/** Change fields of an existing task (name, duration, description, …). */
package org.ashoreprospect.Command;

import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.BlockedTimePeriod;
import org.ashoreprospect.Day;
import org.ashoreprospect.RefreshLifeCycle;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;

/** Undoable edit of name, duration, deadline, extras, then optional re-place. */
public class EditTask implements Command {

    private final Task task;
    private final Snapshot before;
    private final Snapshot after;
    private final boolean reschedule;

    public EditTask(Task task, Snapshot after, boolean reschedule) {
        this.task = task;
        this.before = Snapshot.of(task);
        this.after = after;
        this.reschedule = reschedule;
    }

    @Override
    public boolean execute() throws SQLException {
        apply(after, false);
        after.placedDate = task.getPlacedDate();
        after.placedTime = task.getPlacedTime();
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }

    @Override
    public void undo() throws SQLException {
        apply(before, true);
        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() throws SQLException {
        apply(after, true);
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }

    private void apply(Snapshot s, boolean restorePlacement) throws SQLException {
        TaskManagementSystem sys = TaskManagementSystem.getInstance();
        LocalDate placedDate = task.getPlacedDate();
        LocalTime placedTime = task.getPlacedTime();
        int oldSlots = task.getDurationSlots();
        task.setTaskName(s.name);
        task.setDurationHours(s.durationHours);
        task.setDeadLineDate(s.deadlineDate);
        task.setDeadLineTime(s.deadlineTime);
        task.setDescription(s.description);
        task.setDescriptionDone(s.descriptionDone);
        task.touchLastUpdated();
        if (restorePlacement) {
            restorePlacement(sys, s.placedDate, s.placedTime);
        } else if (placedDate != null && placedTime != null && oldSlots != task.getDurationSlots()) {
            relayoutPlaced(sys, placedDate, placedTime, oldSlots);
        } else if (reschedule && task.canGoUnscheduled()) {
            sys.updateSchedule();
        }
        RefreshLifeCycle.refreshLifeCycle(task);
        sys.getTaskRepo().dbAddTask(task);
    }

    private void restorePlacement(TaskManagementSystem sys, LocalDate placedDate,
                                  LocalTime placedTime) {
        for (Day day : sys.getGrid().values()) {
            Task[] slots = day.returnDayGrid();
            boolean changed = false;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == task) {
                    slots[i] = null;
                    changed = true;
                }
            }
            if (changed) {
                day.recomputeLoadFromGrid();
            }
        }

        sys.getPlacedTasks().remove(task);
        sys.getPendingTasks().remove(task);
        sys.getUnscheduledTasks().remove(task);

        if (placedDate == null || placedTime == null) {
            task.setPlacedDate(null);
            task.setPlacedTime(null);
            if (task.canGoUnscheduled()) {
                sys.getUnscheduledTasks().add(task);
            } else {
                sys.getPendingTasks().add(task);
            }
            return;
        }

        task.setPlacedDate(placedDate);
        task.setPlacedTime(placedTime);
        sys.getPlacedTasks().add(task);
        if (!task.isQuick()) {
            LocalDate column = AppTimeReference.columnDateFor(placedDate, placedTime);
            int start = AppTimeReference.localTimeToSlot(placedTime);
            Day day = sys.getGrid().computeIfAbsent(
                    column, date -> new Day(sys.getBlockedTime()));
            day.placeTask(start, task);
            day.recomputeLoadFromGrid();
        }
    }

    private void relayoutPlaced(TaskManagementSystem sys, LocalDate placedDate,
                                LocalTime placedTime, int oldSlots) {
        LocalDate col = AppTimeReference.columnDateFor(placedDate, placedTime);
        int start = AppTimeReference.localTimeToSlot(placedTime);
        sys.getGrid().putIfAbsent(col, new Day(sys.getBlockedTime()));
        Day day = sys.getGrid().get(col);
        Task[] slots = day.returnDayGrid();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == task) slots[i] = null;
        }
        boolean alreadyStarted = !AppTimeReference.dateTimeForSlot(col, start)
                .isAfter(AppTimeReference.getCurrentDateTime());
        boolean fits = alreadyStarted
                ? canGrowInPlace(sys, day, col, start)
                : day.canPlace(task, col, start, false, true);
        if (fits) {
            day.placeTask(start, task);
            day.recomputeLoadFromGrid();
            return;
        }
        if (reschedule && task.canGoUnscheduled()) {
            task.setPlacedDate(null);
            task.setPlacedTime(null);
            sys.getPlacedTasks().remove(task);
            if (!sys.getUnscheduledTasks().contains(task)) {
                sys.getUnscheduledTasks().add(task);
            }
            sys.updateSchedule();
            return;
        }
        task.setDurationHours(AppTimeReference.slotsToHours(Math.max(1, oldSlots)));
        day.placeTask(start, task);
        day.recomputeLoadFromGrid();
    }

    private boolean canGrowInPlace(TaskManagementSystem sys, Day day,
                                   LocalDate col, int start) {
        int count = task.getDurationSlots();
        if (start < 0 || start + count > AppTimeReference.SLOTS_PER_DAY) return false;
        Task[] slots = day.returnDayGrid();
        for (int i = 0; i < count; i++) {
            Task existing = slots[start + i];
            if (existing != null && existing != task) return false;
        }
        return true;
    }

    public static final class Snapshot {
        public String name;
        public double durationHours;
        public LocalDate deadlineDate;
        public LocalTime deadlineTime;
        public String description;
        public boolean descriptionDone;
        public LocalDate placedDate;
        public LocalTime placedTime;

        public static Snapshot of(Task t) {
            Snapshot s = new Snapshot();
            s.name = t.getTaskName();
            s.durationHours = t.getDurationHours();
            s.deadlineDate = t.getDeadLineDate();
            s.deadlineTime = t.getDeadLineTime();
            s.description = t.getDescription();
            s.descriptionDone = t.isDescriptionDone();
            s.placedDate = t.getPlacedDate();
            s.placedTime = t.getPlacedTime();
            return s;
        }
    }
}
