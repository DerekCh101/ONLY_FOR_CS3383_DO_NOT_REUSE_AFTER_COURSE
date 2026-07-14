package org.example;


import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class SchedulerEngine {

    private Map<LocalDate, Day> daysGrid;
    private ArrayList<Task> unscheduledTasks;
    private LocalDate baseDate;
    private LocalDate daySearchCurrently;
    private BlockedTimePeriod blockedTime;
    //private  CLIRunner debugConsole; //debug only

    private static final int TOTAL_DAYS = 365*5;
    private static final int START_HOUR = 6;
    private static final int HOURS_PER_DAY = 17;


    public SchedulerEngine(BlockedTimePeriod blockedTime, LocalDate baseDate) {
        this.baseDate = baseDate;
        this.daysGrid = new TreeMap<>();
        this.unscheduledTasks = new ArrayList<>();
        this.daySearchCurrently = baseDate;
        this.blockedTime = blockedTime;
    }

    public Map<LocalDate, Day> getGrid() {
        return daysGrid;
    }

    public ArrayList<Task> getUnscheduledTasks() {
        return unscheduledTasks;
    }
 
    public void scheduleTasks(ArrayList<Task> tasks) {
        
        unscheduledTasks.clear();
        daySearchCurrently = baseDate;
        ArrayList<Task> fixedTasks = new ArrayList<>();
        ArrayList<Task> deadlineTasks = new ArrayList<>();
        ArrayList<Task> flexibleTasks = new ArrayList<>();

        // Split tasks by priority category
        for (Task task : tasks) {
            if (task.isFixedTime()) {
                fixedTasks.add(task);
            } else if (task.getDeadLineDate() != null) {
                deadlineTasks.add(task);
            } else {
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

            daysGrid.putIfAbsent(task.getStartingDate(), new Day(blockedTime));

            Day theDay = daysGrid.get(task.getStartingDate());

            if (theDay.canPlace(hourSearch, duration, true)) {
                theDay.placeTask(hourSearch, duration, task);
            } else {
                unscheduledTasks.add(task);
            }
        }
    }

    // Deadline tasks: first respect blocked, then allow override
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
                    if (theDay != null && theDay.canPlace(hour, duration, false)
                            && respectsDeadlineTime(task, thisDay, hour, duration, lastDay)) {

                        theDay.placeTask(hour, duration, task);
                        placed = true;
                    }
                }
            }

            // Second pass: allow blocked override
            if (!placed) {
                for (LocalDate thisDay = daySearchCurrently;
                     thisDay.isBefore(baseDate.plusDays(lastDay));
                     thisDay = thisDay.plusDays(1)) {

                    daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
                    Day theDay = daysGrid.get(thisDay);

                    for (int hour = 0; hour < HOURS_PER_DAY && !placed; hour++) {
                        if (theDay != null && theDay.canPlace(hour, duration, true)
                                && respectsDeadlineTime(task, thisDay, hour, duration, lastDay)) {

                            theDay.placeTask(hour, duration, task);
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

                for (int hour = 0; hour < HOURS_PER_DAY && !placed; hour++) {
                    if (theDay != null && theDay.canPlace(hour, duration, false)) {
                        theDay.placeTask(hour, duration, task);
                        placed = true;
                    }
                }
            }

            if (!placed) {
                unscheduledTasks.add(task);
            }
        }
    }

    // Fill grid with task


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

    public void clearAllTasks(){
        unscheduledTasks.clear();
    }

    public void putIfAbsent(LocalDate date){
        daysGrid.putIfAbsent(date,new Day(blockedTime));
    }
}

