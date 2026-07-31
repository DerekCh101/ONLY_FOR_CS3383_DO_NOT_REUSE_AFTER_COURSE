package org.example;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SchedulerEngine {

    private Map<LocalDate, Day> daysGrid;
    private List<Task> unscheduledTasks;
    private LocalDate baseDate;
    private LocalDate daySearchCurrently;
    private BlockedTimePeriod blockedTime;
    private TaskManagementSystem system;

    public SchedulerEngine(TaskManagementSystem system, LocalDate baseDate, Map<LocalDate, Day> daysGrid) {
        this.baseDate = baseDate;
        this.daysGrid = daysGrid;
        this.unscheduledTasks = system.getUnscheduledTasks();
        this.daySearchCurrently = baseDate;
        this.blockedTime = system.getBlockedTime();
        this.system = system;
    }

    public void scheduleTasks(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            unscheduledTasks.clear();
            return;
        }

        unscheduledTasks.clear();
        daySearchCurrently = baseDate;

        ArrayList<Task> deadlineTasks = new ArrayList<>();
        ArrayList<Task> flexibleTasks = new ArrayList<>();

        for (Task task : tasks) {
            if (task.hasDeadline()) {
                deadlineTasks.add(task);
            } else if (task.isFlexible()) {
                flexibleTasks.add(task);
            }
        }

        deadlineTasks.sort((t1, t2) -> {
            int dateCompare = t1.getDeadLineDate().compareTo(t2.getDeadLineDate());
            if (dateCompare != 0) return dateCompare;
            return Integer.compare(t2.getDurationSlots(), t1.getDurationSlots());
        });

        placeDeadlineTasks(deadlineTasks);
        placeFlexibleTasks(flexibleTasks);

        system.getPendingTasks().clear();
        system.getUnscheduledTasks().removeAll(system.getPlacedTasks());
    }

    private void placeDeadlineTasks(ArrayList<Task> tasks) {
        for (Task task : tasks) {
            long lastDay = ChronoUnit.DAYS.between(baseDate, task.getDeadLineDate()) + 1;

            if (lastDay < 0) {
                unscheduledTasks.add(task);
                continue;
            }
            if (lastDay >= AppTimeReference.TOTAL_DAY_HORIZON) {
                lastDay = AppTimeReference.TOTAL_DAY_HORIZON - 1;
            }

            boolean placed = false;

            placed = tryPlaceDeadline(task, daySearchCurrently, lastDay, false, false);

            if (!placed) {
                placed = tryPlaceDeadline(task, daySearchCurrently, lastDay, true, false);
            }

            if (!placed) {
                placed = tryPlaceDeadline(task, baseDate, lastDay, true, true);
            }

            if (!placed) {
                unscheduledTasks.add(task);
            }
        }
    }

    private boolean tryPlaceDeadline(Task task, LocalDate fromDay, long lastDay,
                                     boolean ignoreBlocked, boolean ignoreOverload) {
        for (LocalDate thisDay = fromDay;
             thisDay.isBefore(baseDate.plusDays(lastDay));
             thisDay = thisDay.plusDays(1)) {

            daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
            Day theDay = daysGrid.get(thisDay);

            for (int slot = 0; slot < AppTimeReference.SLOTS_PER_DAY; slot++) {
                if (theDay.canPlace(task, thisDay, slot, ignoreBlocked, ignoreOverload)
                        && respectsDeadlineTime(task, thisDay, slot)) {

                    theDay.placeTask(slot, task);
                    task.setPlacedDate(thisDay);
                    task.setPlacedTime(AppTimeReference.slotToLocalTime(slot));
                    system.getPlacedTasks().add(task);
                    daySearchCurrently = thisDay;
                    return true;
                }
            }
        }
        return false;
    }

    private void placeFlexibleTasks(ArrayList<Task> tasks) {
        for (Task task : tasks) {
            boolean placed = false;

            for (LocalDate thisDay = daySearchCurrently;
                 thisDay.isBefore(baseDate.plusDays(AppTimeReference.TOTAL_DAY_HORIZON));
                 thisDay = thisDay.plusDays(1)) {

                daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
                Day theDay = daysGrid.get(thisDay);
                if (theDay != null && !theDay.getOverloadFlag()) {
                    daySearchCurrently = thisDay;
                    break;
                }
            }

            for (LocalDate thisDay = daySearchCurrently;
                 thisDay.isBefore(baseDate.plusDays(AppTimeReference.TOTAL_DAY_HORIZON)) && !placed;
                 thisDay = thisDay.plusDays(1)) {

                daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
                Day theDay = daysGrid.get(thisDay);

                int startSlot = 0;
                if (thisDay.equals(AppTimeReference.getCurrentDate())) {
                    startSlot = AppTimeReference.currentSlotIndex() + 1;
                    if (startSlot < 0) startSlot = 0;
                }

                for (int slot = startSlot; slot < AppTimeReference.SLOTS_PER_DAY && !placed; slot++) {
                    if (theDay != null && theDay.canPlace(task, thisDay, slot, false, false)) {
                        theDay.placeTask(slot, task);
                        task.setPlacedDate(thisDay);
                        task.setPlacedTime(AppTimeReference.slotToLocalTime(slot));
                        system.getPlacedTasks().add(task);
                        placed = true;
                    }
                }
            }

            if (!placed) {
                unscheduledTasks.add(task);
            }
        }
    }

    private boolean respectsDeadlineTime(Task task, LocalDate day, int startSlot) {
        if (task.getDeadLineTime() == null) return true;
        if (day.isBefore(task.getDeadLineDate())) return true;

        LocalTime start = AppTimeReference.slotToLocalTime(startSlot);
        LocalTime finish = start.plusMinutes(
                (long) task.getDurationSlots() * AppTimeReference.MINUTES_PER_SLOT);

        return !finish.isAfter(task.getDeadLineTime());
    }

    public String printUnscheduledTask() {
        StringBuilder text = new StringBuilder();
        for (Task task : unscheduledTasks) {
            text.append(task.getTaskName()).append("\n");
        }
        return text.toString();
    }

    /** @deprecated use AppTimeReference; kept so old callers compile */
    public int getStartingHour() {
        return AppTimeReference.GRID_START_HOUR;
    }

    public int getTotalHours() {
        return AppTimeReference.SLOTS_PER_DAY;
    }

    public int getSlotsPerDay() {
        return AppTimeReference.SLOTS_PER_DAY;
    }
}