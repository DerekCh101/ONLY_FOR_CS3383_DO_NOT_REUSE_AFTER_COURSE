package org.example.Command;

import org.example.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;

public class AddTask implements Command {

    private final TaskManagementSystem theSystem;
    private final Task theTask;
    private final LocalDate placedDate;
    private final LocalTime placedTime;
    private boolean autoScheduled = false;

    public AddTask(String taskName, double durationHours, int state,
                   LocalDate startDate, LocalTime startTime,
                   LocalDate deadlineDate, LocalTime deadlineTime,
                   LocalDate placedDate, LocalTime placedTime) {

        this.theSystem = TaskManagementSystem.getInstance();
        this.placedDate = placedDate;
        this.placedTime = placedTime;

        this.theTask = new Task(
                taskName, durationHours, state,
                startDate, startTime,
                deadlineDate, deadlineTime
        );
    }

    public AddTask(String taskName, double durationHours, int state,
                   LocalDate startDate, LocalTime startTime,
                   LocalDate deadlineDate, LocalTime deadlineTime) {
        this(taskName, durationHours, state, startDate, startTime,
                deadlineDate, deadlineTime, null, null);
    }

    /**
     * @return false if rejected (nothing left in lists / DB)
     */
    private boolean doAdd() throws SQLException {
        if (placedDate != null && placedTime != null) {
            return placeOnGrid(placedDate, placedTime);
        }

        if (theTask.isFixedTime()) {
            LocalDate date = theTask.getStartingDate();
            LocalTime time = theTask.getStartingTime();
            if (date != null && time != null) {
                return placeOnGrid(date, time);
            }
            return false;
        }

        theSystem.getPendingTasks().add(theTask);
        autoScheduled = true;
        theSystem.updateSchedule();

        if (!theSystem.getPlacedTasks().contains(theTask)) {
            rollbackTask();
            return false;
        }
        RefreshLifeCycle.refreshLifeCycle(theTask);
        theTask.touchPlacementUpdated();
        theSystem.getTaskRepo().dbAddTask(theTask);
        return true;
    }

    /**
     * @return false if cannot place (does NOT add to unscheduled)
     */
    private boolean placeOnGrid(LocalDate date, LocalTime time) throws SQLException {
        int slotIndex = AppTimeReference.localTimeToSlot(time);
        int slotCount = theTask.getDurationSlots();

        theSystem.getGrid().putIfAbsent(date, new Day(theSystem.getBlockedTime()));
        Day day = theSystem.getGrid().get(date);

        boolean canPlace = true;
        if (slotIndex < 0 || slotIndex + slotCount > AppTimeReference.SLOTS_PER_DAY) {
            canPlace = false;
        } else {
            for (int i = 0; i < slotCount; i++) {
                if (day.returnDayGrid()[slotIndex + i] != null) {
                    canPlace = false;
                    break;
                }
            }
        }

        if (!canPlace) {
            return false;
        }

        day.placeTask(slotIndex, theTask);
        theTask.setPlacedDate(date);
        theTask.setPlacedTime(AppTimeReference.slotToLocalTime(slotIndex));
        theSystem.getPlacedTasks().add(theTask);
        RefreshLifeCycle.refreshLifeCycle(theTask);
        theTask.touchPlacementUpdated();
        theSystem.getTaskRepo().dbAddTask(theTask);
        return true;
    }

    private void rollbackTask() {
        theSystem.getPendingTasks().remove(theTask);
        theSystem.getPlacedTasks().remove(theTask);
        theSystem.getUnscheduledTasks().remove(theTask);

        for (Day day : theSystem.getGrid().values()) {
            Task[] slots = day.returnDayGrid();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == theTask) {
                    slots[i] = null;
                }
            }
        }

        theTask.setPlacedDate(null);
        theTask.setPlacedTime(null);
    }

    @Override
    public boolean execute() throws SQLException {
        if (!doAdd()) {
            return false;
        }
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }

    @Override
    public void undo() throws SQLException {
        theSystem.getPendingTasks().remove(theTask);
        theSystem.getPlacedTasks().remove(theTask);
        theSystem.getUnscheduledTasks().remove(theTask);

        for (Day day : theSystem.getGrid().values()) {
            Task[] slots = day.returnDayGrid();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == theTask) {
                    slots[i] = null;
                }
            }
        }
        theSystem.getTaskRepo().dbClearTask(theTask);

        if (autoScheduled) {
            theSystem.updateSchedule();
        }

        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() throws SQLException {
        if (!doAdd()) {
            return;
        }
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }
}