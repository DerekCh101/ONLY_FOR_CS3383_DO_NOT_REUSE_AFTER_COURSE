package org.example.Command;

import org.example.AppTimeReference;
import org.example.Day;
import org.example.Task;
import org.example.TaskManagementSystem;

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

    private void doMove() {
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
    }

    @Override
    public boolean execute() {
        doMove();
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }

    @Override
    public void undo() {
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

        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() {
        doMove();
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }
}