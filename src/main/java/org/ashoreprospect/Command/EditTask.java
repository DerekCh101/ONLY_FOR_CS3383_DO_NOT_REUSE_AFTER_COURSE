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
        apply(after);
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }

    @Override
    public void undo() throws SQLException {
        apply(before);
        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() throws SQLException {
        apply(after);
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }

    private void apply(Snapshot s) throws SQLException {
        TaskManagementSystem sys = TaskManagementSystem.getInstance();
        LocalDate placedDate = task.getPlacedDate();
        LocalTime placedTime = task.getPlacedTime();
        int oldSlots = task.getDurationSlots();
        task.setTaskName(s.name);
        task.setDurationHours(s.durationHours);
        task.setDeadLineDate(s.deadlineDate);
        task.setDeadLineTime(s.deadlineTime);
        task.setCategory(s.category);
        task.setSubcategory(s.subcategory);
        task.setDescription(s.description);
        task.setDescriptionDone(s.descriptionDone);
        task.touchLastUpdated();
        if (placedDate != null && placedTime != null && oldSlots != task.getDurationSlots()) {
            relayoutPlaced(sys, placedDate, placedTime, oldSlots);
        } else if (reschedule && task.canGoUnscheduled()) {
            sys.updateSchedule();
        }
        RefreshLifeCycle.refreshLifeCycle(task);
        sys.getTaskRepo().dbAddTask(task);
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
        BlockedTimePeriod blocks = sys.getBlockedTime();
        for (int i = 0; i < count; i++) {
            Task existing = slots[start + i];
            if (existing != null && existing != task) return false;
            if (blocks != null) {
                java.time.LocalDateTime dt = AppTimeReference.dateTimeForSlot(col, start + i);
                if (blocks.isBlocked(dt.toLocalDate(), dt.toLocalTime())) return false;
            }
        }
        return true;
    }

    public static final class Snapshot {
        public String name;
        public double durationHours;
        public LocalDate deadlineDate;
        public LocalTime deadlineTime;
        public String category;
        public String subcategory;
        public String description;
        public boolean descriptionDone;

        public static Snapshot of(Task t) {
            Snapshot s = new Snapshot();
            s.name = t.getTaskName();
            s.durationHours = t.getDurationHours();
            s.deadlineDate = t.getDeadLineDate();
            s.deadlineTime = t.getDeadLineTime();
            s.category = t.getCategory();
            s.subcategory = t.getSubcategory();
            s.description = t.getDescription();
            s.descriptionDone = t.isDescriptionDone();
            return s;
        }
    }
}
