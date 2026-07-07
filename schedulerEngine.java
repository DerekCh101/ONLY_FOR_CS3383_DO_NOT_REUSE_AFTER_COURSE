import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class SchedulerEngine {

    private Day[] Daysgrid; 
    private ArrayList<Task> unscheduledTasks;
    private LocalDate baseDate;
    private int dayIndexCurrently;

    private static final int TOTAL_DAYS = 365*5;
    private static final int START_HOUR = 6;
    private static final int HOURS_PER_DAY = 17;


    public schedulerEngine(LocalDate baseDate) {
        this.baseDate = baseDate;
        this.Daysgrid = new Day[TOTAL_DAYS];
        this.unscheduledTasks = new ArrayList<>();
    }

    public Day[] getGrid() {
        return Daysgrid;
    }

    public ArrayList<Task> getUnscheduledTasks() {
        return unscheduledTasks;
    }
 
    public void scheduleTasks(ArrayList<Task> tasks) {
        
        unscheduledTasks.clear();
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

            long dayIndex = ChronoUnit.DAYS.between(
                    baseDate,
                    task.getStartingDate()
            );

            if (dayIndex < 0|| dayIndex >= TOTAL_DAYS) {//must not earlier than today and not exceeding binary
                unscheduledTasks.add(task);
                continue;
            }

            int hourIndex = task.getStartingTime().getHour() - START_HOUR;
            int duration = getDuration(task);

            if (Daysgrid[(int)dayIndex].canPlace((int) dayIndex, hourIndex, duration, true)) {
                placeTask((int) dayIndex, hourIndex, duration, task);
            } else {
                unscheduledTasks.add(task);
            }
        }
    }

    // Deadline tasks: first respect blocked, then allow override
    private void placeDeadlineTasks(ArrayList<Task> tasks) {

        for (Task task : tasks) {

            long lastDay = ChronoUnit.DAYS.between(
                    baseDate,
                    task.getDeadLineDate()
            );

            if (lastDay < 0) {
                unscheduledTasks.add(task);
                continue;
            }

            if (lastDay >= TOTAL_DAYS) {
                lastDay = TOTAL_DAYS - 1;
            }

            int duration = getDuration(task);
            boolean placed = false;

            // First pass: respect blocked time
            for (int day = 0; day <= lastDay && !placed; day++) {
                for (int hour = 0; hour < HOURS_PER_DAY && !placed; hour++) {

                    if (Daysgrid[day].canPlace(day, hour, duration, false)
                            && respectsDeadlineTime(task, day, hour,
                                                    duration, lastDay)) {

                        placeTask(day, hour, duration, task);
                        placed = true;
                    }
                }
            }

            // Second pass: allow blocked override if cannot be accommodate in first pass
            if (!placed) {
                for (int day = 0; day <= lastDay && !placed; day++) {
                    for (int hour = 0; hour < HOURS_PER_DAY && !placed; hour++) {

                        if (canPlace(day, hour, duration,true)
                                && respectsDeadlineTime(task, day, hour,
                                                        duration, lastDay)) {

                            placeTask(day, hour, duration, task);
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

            for (int day = 0; day < TOTAL_DAYS && !placed; day++) {
                for (int hour = 0; hour < HOURS_PER_DAY && !placed; hour++) {

                    if (Daysgrid[day].canPlace(day, hour, duration, false)) {
                        Daysgrid[day].placeTask(day, hour, duration, task);
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
                                         int day,
                                         int hour,
                                         int duration,
                                         long lastDay) {

        if (task.getDeadLineTime() == null) 
            return true;

        if (day < lastDay)
            return true;

        int finishHour = 
                START_HOUR + hour + duration;

        return finishHour
                <= task.getDeadLineTime().getHour();
    }
}