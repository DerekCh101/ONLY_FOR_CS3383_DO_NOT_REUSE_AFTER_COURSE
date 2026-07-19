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

    private static final int TOTAL_DAYS = 365*5;
    private static final int START_HOUR = 6;
    private static final int HOURS_PER_DAY = 17;


    public SchedulerEngine(TaskManagementSystem system, LocalDate baseDate, Map<LocalDate, Day> daysGrid) {
        this.baseDate = baseDate;
        this.daysGrid = daysGrid;
        this.unscheduledTasks = system.getUnscheduledTasks();
        this.daySearchCurrently = baseDate;
        this.blockedTime = system.getBlockedTime();
        this.daysGrid = daysGrid;
        this.system = system;
    }

    public void scheduleTasks(List<Task> tasks) {
        tasks.addAll(system.getPlacedTasks());
        system.getPlacedTasks().removeAll(system.getPlacedTasks());
        if(tasks.isEmpty()){
            system.getGrid().clear();
            unscheduledTasks.clear();
            return;
        }
        unscheduledTasks.clear();
        daySearchCurrently = baseDate;
        ArrayList<Task> fixedTasks = new ArrayList<>();
        ArrayList<Task> deadlineTasks = new ArrayList<>();
        ArrayList<Task> flexibleTasks = new ArrayList<>();

        // Split tasks by priority category
        for (Task task : tasks) {
            if (task.isFixedTime()) {
                fixedTasks.add(task);
            } else if (task.hasDeadline()) {
                deadlineTasks.add(task);
            } else if(task.isFlexible()){
                flexibleTasks.add(task);
            }
        }

        // Sort deadline tasks by earliest date, then shorter duration
        deadlineTasks.sort((t1, t2) -> {//every comparison would run this function
            int dateCompare = t1.getDeadLineDate()
                    .compareTo(t2.getDeadLineDate());
            if (dateCompare != 0) return dateCompare;//if not equal, then earlier is first
            return Integer.compare(//if equal i.e.==0,compare the duration, if t1 is greater than t2 then function would return +ve t1 will be placed behind
                    getDuration(t1),
                    getDuration(t2)
            );
        });

        placeFixedTasks(fixedTasks);
        placeDeadlineTasks(deadlineTasks);
        placeFlexibleTasks(flexibleTasks);

        system.getPendingTasks().clear();
        //safety net
        system.getUnscheduledTasks().removeAll(system.getPlacedTasks());

    }



    // Fixed tasks ignore blocked time
    private void placeFixedTasks(ArrayList<Task> tasks) {

        for (Task task : tasks) {

            long daySearch = ChronoUnit.DAYS.between(
                    baseDate,
                    task.getStartingDate()
            );

            if (daySearch < 0|| daySearch >= TOTAL_DAYS) {//must not earlier than today and not exceeding binary
                unscheduledTasks.add(task);
                continue;
            }

            int hourSearch = task.getStartingTime().getHour() - START_HOUR;
            int duration = getDuration(task);
            System.out.printf(task.getStartingDate().toString());
            daysGrid.putIfAbsent(task.getStartingDate(), new Day(blockedTime));

            Day theDay = daysGrid.get(task.getStartingDate());

            if (theDay.canPlace(hourSearch, duration, true,task.getStartingDate(), task)) {
                theDay.placeTask(hourSearch, duration, task);
                system.getPlacedTasks().add(task);
            } else {
                unscheduledTasks.add(task);
            }
        }
    }

    // Deadline tasks: first respect blocked, then allow override
    private void placeDeadlineTasks(ArrayList<Task> tasks) {

        for (Task task : tasks) {

            long lastDay = ChronoUnit.DAYS.between(baseDate, task.getDeadLineDate());

            if (lastDay < 0) {
                unscheduledTasks.add(task);
                continue;
            }

            if (lastDay >= TOTAL_DAYS) {
                lastDay = TOTAL_DAYS - 1;
            }

            int duration = getDuration(task);
            boolean placed = false;

            // Pre-loop to find next non-overloaded day
            for (LocalDate thisDay = daySearchCurrently;
                 thisDay.isBefore(baseDate.plusDays(lastDay));
                 thisDay = thisDay.plusDays(1)) {

                daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
                Day theDay = daysGrid.get(thisDay);

                if (theDay != null && !theDay.getOverloadFlag()) {
                    daySearchCurrently = thisDay;
                    break;
                }
            }

            // First pass: respect blocked time
            for (LocalDate thisDay = daySearchCurrently;
                 thisDay.isBefore(baseDate.plusDays(lastDay));
                 thisDay = thisDay.plusDays(1)) {

                daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
                Day theDay = daysGrid.get(thisDay);

                for (int hour = 0; hour < HOURS_PER_DAY && !placed; hour++) {
                    if (theDay != null && theDay.canPlace(hour, duration, false,thisDay, task)
                            && respectsDeadlineTime(task, thisDay, hour, duration, lastDay)) {

                        theDay.placeTask(hour, duration, task);
                        placed = true;
                    }
                }
            }
            if(placed){
                system.getPlacedTasks().add(task);
            }

            // Second pass: allow blocked override
            if (!placed) {
                for (LocalDate thisDay = daySearchCurrently;
                     thisDay.isBefore(baseDate.plusDays(lastDay));
                     thisDay = thisDay.plusDays(1)) {

                    daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
                    Day theDay = daysGrid.get(thisDay);

                    for (int hour = 0; hour < HOURS_PER_DAY && !placed; hour++) {
                        if (theDay != null && theDay.canPlace(hour, duration, true,thisDay, task)
                                && respectsDeadlineTime(task, thisDay, hour, duration, lastDay)) {
                            theDay.placeTask(hour, duration, task);
                            system.getPlacedTasks().add(task);
                            placed = true;
                        }
                    }
                }
            }



            if (!placed) {
                unscheduledTasks.add(task);
            }
        }
    }

    // Flexible tasks never override blocked time
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

            // Place the task
            for (LocalDate thisDay = daySearchCurrently;
                 thisDay.isBefore(baseDate.plusDays(TOTAL_DAYS)) && !placed;
                 thisDay = thisDay.plusDays(1)) {

                daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
                Day theDay = daysGrid.get(thisDay);
                int hourIdx = 0;
                if(thisDay == baseDate){
                    hourIdx = LocalTime.now().getHour() - getStartingHour();
                }
                for (int hour = hourIdx; hour < HOURS_PER_DAY && !placed; hour++) {
                    if (theDay != null && theDay.canPlace(hour, duration, false,thisDay, task)) {
                        theDay.placeTask(hour, duration, task);
                        placed = true;
                        system.getPlacedTasks().add(task);
                    }
                }
            }

            if (!placed) {
                unscheduledTasks.add(task);
            }
        }
    }

    // Default duration = 2 if invalid
    private int getDuration(Task task) {
        int d = task.getDurationHours();
        return d <= 0 ? 2 : d;
    }

    // Ensure deadline time is respected on final day
    private boolean respectsDeadlineTime(Task task,
                                         LocalDate day,
                                         int hour,
                                         int duration,
                                         long lastDay) {

        if (task.getDeadLineTime() == null)
            return true;

        if (day.isBefore(task.getDeadLineDate())){
            return true;
        }

        int finishHour =
                START_HOUR + hour + duration;

        return finishHour
                <= task.getDeadLineTime().getHour();
    }


    public String printUnscheduledTask(){
        String text = "";
        for(Task task: unscheduledTasks){
            text = text.concat(task.getTaskName()) + " " + "\n";
        }
        return text;
    }

    public int getStartingHour(){
        return START_HOUR;
    }

    public int getTotalHours(){return HOURS_PER_DAY;}
}