package org.example.Command;

import org.example.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;

public class MoveTask implements Command {

    private final TaskManagementSystem theSystem;
    private final Task task;

    private final LocalDate oldDate;
    private final int oldStartSlot;
    private final LocalDate newDate;
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
        this.oldDate = oldDate;
        this.oldStartSlot = oldStartSlot;
        this.newDate = newDate;
        this.newStartSlot = newStartSlot;

        if (task.isFixedTime()) {
            this.oldStartingDate = task.getStartingDate();
            this.oldStartingTime = task.getStartingTime();
        }
    }

    /** Same deadline / range rules as drag UI — hard stop for DB. */
    private boolean isLegalTarget() {
        if (task == null) return false;

        int slotCount = task.getDurationSlots();
        if (slotCount <= 0) return false;
        if (newStartSlot + slotCount > AppTimeReference.SLOTS_PER_DAY) return false;

        LocalTime newStart = AppTimeReference.slotToLocalTime(newStartSlot);
        LocalDate today = AppTimeReference.getCurrentDate();
        LocalTime now = AppTimeReference.getCurrentTime();

        if (newDate.isBefore(today)
                || (newDate.isEqual(today) && !newStart.isAfter(now))) {
            return false;
        }

        if (task.hasDeadline()) {
            LocalDate deadlineDate = task.getDeadLineDate();
            LocalTime deadlineTime = task.getDeadLineTime();
            if (deadlineDate == null) return false;
            if (newDate.isAfter(deadlineDate)) return false;

            if (newDate.isEqual(deadlineDate) && deadlineTime != null) {
                long durationMin = (long) slotCount * AppTimeReference.MINUTES_PER_SLOT;
                LocalTime finish = newStart.plusMinutes(durationMin);
                boolean wrappedPastMidnight = finish.isBefore(newStart);

                if (newStart.isAfter(deadlineTime)
                        || wrappedPastMidnight
                        || finish.isAfter(deadlineTime)) {
                    return false;
                }
            }
        }

        theSystem.getGrid().putIfAbsent(newDate, new Day(theSystem.getBlockedTime()));
        Day newDay = theSystem.getGrid().get(newDate);
        Task[] slots = newDay.returnDayGrid();
        for (int i = 0; i < slotCount; i++) {
            Task existing = slots[newStartSlot + i];
            if (existing != null && existing != task) return false;
        }
        return true;
    }

    private void clearSlots(LocalDate date, int startSlot) {
        Day day = theSystem.getGrid().get(date);
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

    private void doMove() throws SQLException {
        clearSlots(oldDate, oldStartSlot);

        theSystem.getGrid().putIfAbsent(newDate, new Day(theSystem.getBlockedTime()));
        Day newDay = theSystem.getGrid().get(newDate);
        newDay.placeTask(newStartSlot, task);

        LocalTime newTime = AppTimeReference.slotToLocalTime(newStartSlot);
        task.setPlacedDate(newDate);
        task.setPlacedTime(newTime);

        if (task.isFixedTime()) {
            task.setStartingDate(newDate);
            task.setStartingTime(newTime);
        }

        if (!theSystem.getPlacedTasks().contains(task)) {
            theSystem.getPlacedTasks().add(task);
        }

        RefreshLifeCycle.refreshLifeCycle(task);
        theSystem.getTaskRepo().dbAddTask(task);
    }

    @Override
    public boolean execute() throws SQLException {
        if (!isLegalTarget()) {
            return false; // do not move, do not write DB, do not push undo
        }
        doMove();
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }

    @Override
    public void undo() throws SQLException {
        clearSlots(newDate, newStartSlot);

        theSystem.getGrid().putIfAbsent(oldDate, new Day(theSystem.getBlockedTime()));
        Day oldDay = theSystem.getGrid().get(oldDate);
        oldDay.placeTask(oldStartSlot, task);

        LocalTime oldTime = AppTimeReference.slotToLocalTime(oldStartSlot);
        task.setPlacedDate(oldDate);
        task.setPlacedTime(oldTime);

        if (task.isFixedTime()) {
            task.setStartingDate(oldStartingDate);
            task.setStartingTime(oldStartingTime);
        }
        RefreshLifeCycle.refreshLifeCycle(task);
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