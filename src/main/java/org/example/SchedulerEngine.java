package org.example;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class SchedulerEngine {

    private Map<LocalDate, Day> daysGrid;
    private List<Task> unscheduledTasks;
    private LocalDate baseDate;
    private LocalDate daySearchCurrently;
    private BlockedTimePeriod blockedTime;
    private TaskManagementSystem system;

    private static final int TOTAL_DAYS = 365 * 5;
    private static final int START_HOUR = 6;
    private static final int HOURS_PER_DAY = 17;

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
            // Fixed tasks are ignored here
        }

        // Sort deadline: earliest deadline first, then longer duration first
        deadlineTasks.sort((t1, t2) -> {
            int dateCompare = t1.getDeadLineDate().compareTo(t2.getDeadLineDate());
            if (dateCompare != 0) return dateCompare;
            return Integer.compare(getDuration(t2), getDuration(t1));
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
            if (lastDay >= TOTAL_DAYS) {
                lastDay = TOTAL_DAYS - 1;
            }

            boolean placed = false;
            boolean ignoredBlocked = false;
            boolean ignoreOverload = false;

            // 1st pass
            for (LocalDate thisDay = daySearchCurrently;
                 thisDay.isBefore(baseDate.plusDays(lastDay)) && !placed;
                 thisDay = thisDay.plusDays(1)) {

                daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
                Day theDay = daysGrid.get(thisDay);

                for (int hour = 0; hour < HOURS_PER_DAY && !placed; hour++) {
                    if (theDay.canPlace(task, thisDay, hour, ignoredBlocked, ignoreOverload)
                            && respectsDeadlineTime(task, thisDay, hour, getDuration(task), lastDay)) {

                        theDay.placeTask(hour, getDuration(task), task);
                        task.setPlacedDate(thisDay);
                        task.setPlacedTime(LocalTime.of(START_HOUR + hour, 0));
                        system.getPlacedTasks().add(task);
                        placed = true;
                        daySearchCurrently = thisDay;
                    }
                }
            }

            // 2nd pass
            if (!placed) {
                ignoredBlocked = true;
                for (LocalDate thisDay = daySearchCurrently;
                     thisDay.isBefore(baseDate.plusDays(lastDay)) && !placed;
                     thisDay = thisDay.plusDays(1)) {

                    daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
                    Day theDay = daysGrid.get(thisDay);

                    for (int hour = 0; hour < HOURS_PER_DAY && !placed; hour++) {
                        if (theDay.canPlace(task, thisDay, hour, ignoredBlocked, ignoreOverload)
                                && respectsDeadlineTime(task, thisDay, hour, getDuration(task), lastDay)) {

                            theDay.placeTask(hour, getDuration(task), task);
                            task.setPlacedDate(thisDay);
                            task.setPlacedTime(LocalTime.of(START_HOUR + hour, 0));
                            system.getPlacedTasks().add(task);
                            placed = true;
                            daySearchCurrently = thisDay;
                        }
                    }
                }
            }

            // 3rd pass
            if (!placed) {
                ignoreOverload = true;
                for (LocalDate thisDay = baseDate;
                     thisDay.isBefore(baseDate.plusDays(lastDay)) && !placed;
                     thisDay = thisDay.plusDays(1)) {

                    daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
                    Day theDay = daysGrid.get(thisDay);

                    for (int hour = 0; hour < HOURS_PER_DAY && !placed; hour++) {
                        if (theDay.canPlace(task, thisDay, hour, ignoredBlocked, ignoreOverload)
                                && respectsDeadlineTime(task, thisDay, hour, getDuration(task), lastDay)) {

                            theDay.placeTask(hour, getDuration(task), task);
                            task.setPlacedDate(thisDay);
                            task.setPlacedTime(LocalTime.of(START_HOUR + hour, 0));
                            system.getPlacedTasks().add(task);
                            placed = true;
                            daySearchCurrently = thisDay;
                        }
                    }
                }
            }

            if (!placed) {
                unscheduledTasks.add(task);
            }
        }
    }

    private void placeFlexibleTasks(ArrayList<Task> tasks) {
        for (Task task : tasks) {

            int duration = getDuration(task);
            boolean placed = false;

            // Find next non-overloaded day
            for (LocalDate thisDay = daySearchCurrently;
                 thisDay.isBefore(baseDate.plusDays(TOTAL_DAYS));
                 thisDay = thisDay.plusDays(1)) {

                daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
                Day theDay = daysGrid.get(thisDay);

                if (theDay != null && !theDay.getOverloadFlag()) {
                    daySearchCurrently = thisDay;
                    break;
                }
            }

            for (LocalDate thisDay = daySearchCurrently;
                 thisDay.isBefore(baseDate.plusDays(TOTAL_DAYS)) && !placed;
                 thisDay = thisDay.plusDays(1)) {

                daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
                Day theDay = daysGrid.get(thisDay);

                int hourIdx = 0;
                if (thisDay.equals(baseDate)) {
                    hourIdx = LocalTime.now().getHour() - getStartingHour();
                    if (hourIdx < 0) hourIdx = 0;
                }

                boolean ignoredBlocked = false;
                boolean ignoreOverload = false;

                for (int hour = hourIdx; hour < HOURS_PER_DAY && !placed; hour++) {
                    if (theDay != null && theDay.canPlace(task, thisDay, hour, ignoredBlocked, ignoreOverload)) {
                        theDay.placeTask(hour, duration, task);
                        task.setPlacedDate(thisDay);
                        task.setPlacedTime(LocalTime.of(START_HOUR + hour, 0));
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

    private int getDuration(Task task) {
        int d = task.getDurationHours();
        return d <= 0 ? 2 : d;
    }

    private boolean respectsDeadlineTime(Task task, LocalDate day, int hour, int duration, long lastDay) {
        if (task.getDeadLineTime() == null) return true;
        if (day.isBefore(task.getDeadLineDate())) return true;

        int finishHour = START_HOUR + hour + duration;
        return finishHour <= task.getDeadLineTime().getHour();
    }

    public String printUnscheduledTask() {
        StringBuilder text = new StringBuilder();
        for (Task task : unscheduledTasks) {
            text.append(task.getTaskName()).append("\n");
        }
        return text.toString();
    }

    public int getStartingHour() {
        return START_HOUR;
    }

    public int getTotalHours() {
        return HOURS_PER_DAY;
    }
}