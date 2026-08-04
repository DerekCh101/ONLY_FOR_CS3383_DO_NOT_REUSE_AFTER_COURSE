package org.example.Command;

import org.example.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class RunSchedulerCommand implements Command {

    private final TaskManagementSystem theSystem;
    private final List<TaskSnapshot> previousState = new ArrayList<>();

    private static class TaskSnapshot {
        Task task;
        LocalDate placedDate;
        LocalTime placedTime;
        boolean wasPlaced;
    }

    public RunSchedulerCommand(TaskManagementSystem system) {
        this.theSystem = system;

        for (Task t : theSystem.getPlacedTasks()) {
            if (!t.isFixedTime()) {
                TaskSnapshot snap = new TaskSnapshot();
                snap.task = t;
                snap.placedDate = t.getPlacedDate();
                snap.placedTime = t.getPlacedTime();
                snap.wasPlaced = true;
                previousState.add(snap);
            }
        }
        for (Task t : theSystem.getPendingTasks()) {
            if (!t.isFixedTime()) {
                TaskSnapshot snap = new TaskSnapshot();
                snap.task = t;
                snap.wasPlaced = false;
                previousState.add(snap);
            }
        }
        for (Task t : theSystem.getUnscheduledTasks()) {
            if (!t.isFixedTime()) {
                TaskSnapshot snap = new TaskSnapshot();
                snap.task = t;
                snap.wasPlaced = false;
                previousState.add(snap);
            }
        }
    }

    private void doSchedule() throws SQLException {
        theSystem.updateSchedule();

        // Engine only touched memory; persist non-fixed tasks from this run
        for (TaskSnapshot snap : previousState) {
            Task t = snap.task;
            RefreshLifeCycle.refreshLifeCycle(t);
            theSystem.getTaskRepo().dbAddTask(t);
        }
    }

    @Override
    public boolean execute() throws SQLException {
        doSchedule();
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }

    @Override
    public void undo() throws SQLException {
        List<Task> toRemove = new ArrayList<>();
        for (Task t : theSystem.getPlacedTasks()) {
            if (!t.isFixedTime()) {
                toRemove.add(t);
            }
        }
        theSystem.getPlacedTasks().removeAll(toRemove);
        theSystem.getPendingTasks().clear();
        theSystem.getUnscheduledTasks().clear();

        for (Day day : theSystem.getGrid().values()) {
            Task[] slots = day.returnDayGrid();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] != null && !slots[i].isFixedTime()) {
                    slots[i] = null;
                }
            }
        }

        for (TaskSnapshot snap : previousState) {
            Task t = snap.task;

            if (snap.wasPlaced && snap.placedDate != null && snap.placedTime != null) {
                int slotIndex = AppTimeReference.localTimeToSlot(snap.placedTime);
                int durationSlots = t.getDurationSlots();
                if (durationSlots <= 0) durationSlots = 1;

                theSystem.getGrid().putIfAbsent(snap.placedDate, new Day(theSystem.getBlockedTime()));
                Day day = theSystem.getGrid().get(snap.placedDate);

                boolean canPlace = true;
                for (int i = 0; i < durationSlots; i++) {
                    int s = slotIndex + i;
                    if (s < 0 || s >= AppTimeReference.SLOTS_PER_DAY
                            || day.returnDayGrid()[s] != null) {
                        canPlace = false;
                        break;
                    }
                }

                if (canPlace) {
                    day.placeTask(slotIndex, t);
                    t.setPlacedDate(snap.placedDate);
                    t.setPlacedTime(snap.placedTime);
                    theSystem.getPlacedTasks().add(t);
                } else {
                    theSystem.getUnscheduledTasks().add(t);
                    t.setPlacedDate(null);
                    t.setPlacedTime(null);
                }
            } else {
                theSystem.getPendingTasks().add(t);
                t.setPlacedDate(null);
                t.setPlacedTime(null);
            }

            RefreshLifeCycle.refreshLifeCycle(t);
            theSystem.getTaskRepo().dbAddTask(t);
        }

        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() throws SQLException {
        doSchedule();
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }
}