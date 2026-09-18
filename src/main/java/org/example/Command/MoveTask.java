package org.example.Command;

import org.example.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class MoveTask implements Command {

    private final TaskManagementSystem theSystem;
    private final Task task;

    private final LocalDate oldColumn;
    private final int oldStartSlot;
    private final LocalDate newColumn;
    private final int newStartSlot;

    private LocalDate oldStartingDate;
    private LocalTime oldStartingTime;

    public MoveTask(Task task,
                    LocalDate oldDate, int oldStartSlot,
                    LocalDate newDate, int newStartSlot,
                    int ignoredDurationHoursForCompat) {
        this(task, oldDate, oldStartSlot, newDate, newStartSlot);
    }

    public MoveTask(Task task,
                    LocalDate oldDate, int oldStartSlot,
                    LocalDate newDate, int newStartSlot) {

        this.theSystem = TaskManagementSystem.getInstance();
        this.task = task;
        this.oldColumn = oldDate;
        this.oldStartSlot = oldStartSlot;
        this.newColumn = newDate;
        this.newStartSlot = newStartSlot;

        if (task.isFixedTime()) {
            this.oldStartingDate = task.getStartingDate();
            this.oldStartingTime = task.getStartingTime();
        }
    }

    private boolean isLegalTarget() {
        if (task == null) return false;

        int slotCount = task.getDurationSlots();
        if (slotCount <= 0) return false;
        if (newStartSlot + slotCount > AppTimeReference.SLOTS_PER_DAY) return false;

        LocalDateTime newStart = AppTimeReference.dateTimeForSlot(newColumn, newStartSlot);
        if (!newStart.isAfter(AppTimeReference.getCurrentDateTime())) {
            return false;
        }

        if (task.hasDeadline()) {
            LocalDate deadlineDate = task.getDeadLineDate();
            LocalTime deadlineTime = task.getDeadLineTime();
            if (deadlineDate == null) return false;
            LocalDateTime finish = newStart.plusMinutes(
                    (long) slotCount * AppTimeReference.MINUTES_PER_SLOT);
            LocalDateTime deadline = deadlineTime == null
                    ? deadlineDate.plusDays(1).atStartOfDay()
                    : LocalDateTime.of(deadlineDate, deadlineTime);
            if (finish.isAfter(deadline)) return false;
        }

        theSystem.getGrid().putIfAbsent(newColumn, new Day(theSystem.getBlockedTime()));
        Day newDay = theSystem.getGrid().get(newColumn);
        Task[] slots = newDay.returnDayGrid();
        for (int i = 0; i < slotCount; i++) {
            Task existing = slots[newStartSlot + i];
            if (existing != null && existing != task) return false;
        }
        return true;
    }

    private void clearSlots(LocalDate column, int startSlot) {
        Day day = theSystem.getGrid().get(column);
        if (day == null) return;
        int slotCount = task.getDurationSlots();
        Task[] slots = day.returnDayGrid();
        for (int i = 0; i < slotCount; i++) {
            int s = startSlot + i;
            if (s >= 0 && s < slots.length && slots[s] == task) {
                slots[s] = null;
            }
        }
    }

    private void applyClock(LocalDate column, int startSlot) {
        LocalDateTime start = AppTimeReference.dateTimeForSlot(column, startSlot);
        task.setPlacedDate(start.toLocalDate());
        task.setPlacedTime(start.toLocalTime());
        if (task.isFixedTime()) {
            task.setStartingDate(start.toLocalDate());
            task.setStartingTime(start.toLocalTime());
        }
    }

    private void doMove() throws SQLException {
        clearSlots(oldColumn, oldStartSlot);

        theSystem.getGrid().putIfAbsent(newColumn, new Day(theSystem.getBlockedTime()));
        Day newDay = theSystem.getGrid().get(newColumn);
        newDay.placeTask(newStartSlot, task);

        applyClock(newColumn, newStartSlot);

        if (!theSystem.getPlacedTasks().contains(task)) {
            theSystem.getPlacedTasks().add(task);
        }

        RefreshLifeCycle.refreshLifeCycle(task);
        task.touchPlacementUpdated();
        theSystem.getTaskRepo().dbAddTask(task);
    }

    @Override
    public boolean execute() throws SQLException {
        if (!isLegalTarget()) {
            return false;
        }
        doMove();
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }

    @Override
    public void undo() throws SQLException {
        clearSlots(newColumn, newStartSlot);

        theSystem.getGrid().putIfAbsent(oldColumn, new Day(theSystem.getBlockedTime()));
        Day oldDay = theSystem.getGrid().get(oldColumn);
        oldDay.placeTask(oldStartSlot, task);

        applyClock(oldColumn, oldStartSlot);

        if (task.isFixedTime()) {
            task.setStartingDate(oldStartingDate);
            task.setStartingTime(oldStartingTime);
        }
        RefreshLifeCycle.refreshLifeCycle(task);
        task.touchPlacementUpdated();
        theSystem.getTaskRepo().dbAddTask(task);

        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() throws SQLException {
        if (!isLegalTarget()) {
            return;
        }
        doMove();
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }
}