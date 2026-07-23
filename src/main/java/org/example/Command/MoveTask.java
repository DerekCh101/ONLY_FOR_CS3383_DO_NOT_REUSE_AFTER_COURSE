package org.example.Command;

import org.example.Day;
import org.example.Task;
import org.example.TaskManagementSystem;

import java.time.LocalDate;
import java.time.LocalTime;

public class MoveTask implements Command {

    private final TaskManagementSystem theSystem;
    private final Task task;

    private final LocalDate oldDate;
    private final int oldStartHour;
    private final LocalDate newDate;
    private final int newStartHour;
    private final int duration;

    private LocalDate oldStartingDate;
    private LocalTime oldStartingTime;

    public MoveTask(Task task,
                    LocalDate oldDate, int oldStartHour,
                    LocalDate newDate, int newStartHour,
                    int duration) {

        this.theSystem = TaskManagementSystem.getInstance();
        this.task = task;
        this.oldDate = oldDate;
        this.oldStartHour = oldStartHour;
        this.newDate = newDate;
        this.newStartHour = newStartHour;
        this.duration = duration;

        if (task.isFixedTime()) {
            this.oldStartingDate = task.getStartingDate();
            this.oldStartingTime = task.getStartingTime();
        }
    }

    private void doMove() {
        // Clear old position
        Day oldDay = theSystem.getGrid().get(oldDate);
        if (oldDay != null) {
            for (int i = 0; i < duration; i++) {
                int h = oldStartHour + i;
                if (h < theSystem.getEngine().getTotalHours()) {
                    oldDay.returnDayGrid()[h] = null;
                }
            }
        }

        // Place at new position
        theSystem.getGrid().putIfAbsent(newDate, new Day(theSystem.getBlockedTime()));
        Day newDay = theSystem.getGrid().get(newDate);

        for (int i = 0; i < duration; i++) {
            newDay.returnDayGrid()[newStartHour + i] = task;
        }

        LocalTime newTime = LocalTime.of(
                theSystem.getEngine().getStartingHour() + newStartHour, 0);

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
        // Clear new position
        Day newDay = theSystem.getGrid().get(newDate);
        if (newDay != null) {
            for (int i = 0; i < duration; i++) {
                int h = newStartHour + i;
                if (h < theSystem.getEngine().getTotalHours()) {
                    newDay.returnDayGrid()[h] = null;
                }
            }
        }

        // Restore old position
        theSystem.getGrid().putIfAbsent(oldDate, new Day(theSystem.getBlockedTime()));
        Day oldDay = theSystem.getGrid().get(oldDate);

        for (int i = 0; i < duration; i++) {
            oldDay.returnDayGrid()[oldStartHour + i] = task;
        }

        LocalTime oldTime = LocalTime.of(
                theSystem.getEngine().getStartingHour() + oldStartHour, 0);

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