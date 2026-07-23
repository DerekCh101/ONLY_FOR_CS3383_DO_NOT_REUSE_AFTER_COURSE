package org.example.Command;

import org.example.*;
import java.time.LocalDate;
import java.time.LocalTime;

public class AddTask implements Command {

    private final TaskManagementSystem theSystem;
    private final Task theTask;
    private final LocalDate placedDate;
    private final LocalTime placedTime;

    /** true when this task was created from Add button as Deadline/Flexible and we auto-scheduled it */
    private boolean autoScheduled = false;

    public AddTask(String taskName, int duration, int state,
                   LocalDate startDate, LocalTime startTime,
                   LocalDate deadlineDate, LocalTime deadlineTime,
                   LocalDate placedDate, LocalTime placedTime) {

        this.theSystem = TaskManagementSystem.getInstance();
        this.placedDate = placedDate;
        this.placedTime = placedTime;

        this.theTask = new Task(
                taskName, duration, state,
                startDate, startTime,
                deadlineDate, deadlineTime
        );
    }

    public AddTask(String taskName, int duration, int state,
                   LocalDate startDate, LocalTime startTime,
                   LocalDate deadlineDate, LocalTime deadlineTime) {
        this(taskName, duration, state, startDate, startTime, deadlineDate, deadlineTime, null, null);
    }

    private void doAdd() {
        if (placedDate != null && placedTime != null) {
            // Created by clicking a grid cell → place immediately
            int hourIndex = placedTime.getHour() - theSystem.getEngine().getStartingHour();
            int duration = theTask.getDurationHours();
            if (duration <= 0) duration = 2;

            theSystem.getGrid().putIfAbsent(placedDate, new Day(theSystem.getBlockedTime()));
            Day day = theSystem.getGrid().get(placedDate);

            boolean canPlace = true;
            for (int i = 0; i < duration; i++) {
                int h = hourIndex + i;
                if (h < 0 || h >= theSystem.getEngine().getTotalHours() || day.returnDayGrid()[h] != null) {
                    canPlace = false;
                    break;
                }
            }

            if (canPlace) {
                for (int i = 0; i < duration; i++) {
                    day.returnDayGrid()[hourIndex + i] = theTask;
                }
                theTask.setPlacedDate(placedDate);
                theTask.setPlacedTime(placedTime);
                theSystem.getPlacedTasks().add(theTask);
            } else {
                theSystem.getUnscheduledTasks().add(theTask);
            }
        } else {
            // Created from Add button
            if (theTask.isFixedTime()) {
                LocalDate date = theTask.getStartingDate();
                LocalTime time = theTask.getStartingTime();

                if (date != null && time != null) {
                    int hourIndex = time.getHour() - theSystem.getEngine().getStartingHour();
                    int duration = theTask.getDurationHours();
                    if (duration <= 0) duration = 2;

                    theSystem.getGrid().putIfAbsent(date, new Day(theSystem.getBlockedTime()));
                    Day day = theSystem.getGrid().get(date);

                    boolean canPlace = true;
                    for (int i = 0; i < duration; i++) {
                        int h = hourIndex + i;
                        if (h < 0 || h >= theSystem.getEngine().getTotalHours() || day.returnDayGrid()[h] != null) {
                            canPlace = false;
                            break;
                        }
                    }

                    if (canPlace) {
                        for (int i = 0; i < duration; i++) {
                            day.returnDayGrid()[hourIndex + i] = theTask;
                        }
                        theTask.setPlacedDate(date);
                        theTask.setPlacedTime(time);
                        theSystem.getPlacedTasks().add(theTask);
                    } else {
                        theSystem.getUnscheduledTasks().add(theTask);
                    }
                } else {
                    theSystem.getUnscheduledTasks().add(theTask);
                }
            } else {
                // Deadline or Flexible from Add button → pending, then auto-schedule
                theSystem.getPendingTasks().add(theTask);
                autoScheduled = true;
                theSystem.updateSchedule();
            }
        }
    }

    @Override
    public boolean execute() {
        doAdd();
        RecordedCommand.getRedoList().clear();
        RecordedCommand.getUndoList().add(this);
        return true;
    }

    @Override
    public void undo() {
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

        // If we auto-scheduled, other Deadline/Flexible may have been moved.
        // Re-run schedule without this task so the rest settle again.
        if (autoScheduled) {
            theSystem.updateSchedule();
        }

        RecordedCommand.getRedoList().add(this);
        RecordedCommand.getUndoList().remove(this);
    }

    @Override
    public void redo() {
        doAdd();
        RecordedCommand.getRedoList().remove(this);
        RecordedCommand.getUndoList().add(this);
    }
}