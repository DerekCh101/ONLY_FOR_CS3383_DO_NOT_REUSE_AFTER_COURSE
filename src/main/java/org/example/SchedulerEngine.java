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
    private BlockedTimePeriod blockedTime;
    private TaskManagementSystem system;

    public SchedulerEngine(TaskManagementSystem system, LocalDate baseDate, Map<LocalDate, Day> daysGrid) {
        this.baseDate = baseDate;
        this.daysGrid = daysGrid;
        this.unscheduledTasks = system.getUnscheduledTasks();
        this.blockedTime = system.getBlockedTime();
        this.system = system;
    }

    /** Keep PAST where they sit. Refit everyone else. */
    public void refitActiveTasks() {
        placeAlreadyPlacedTasks(new ArrayList<>(system.getPlacedTasks()));

        ArrayList<Task> toSchedule = new ArrayList<>();
        addIfActive(toSchedule, system.getUnscheduledTasks());
        addIfActive(toSchedule, system.getPendingTasks());
        addIfActive(toSchedule, unscheduledTasks);
        scheduleTasks(toSchedule);
    }

    private void addIfActive(List<Task> out, List<Task> src) {
        if (src == null) return;
        for (Task task : src) {
            if (!isPast(task) && !out.contains(task)) {
                out.add(task);
            }
        }
    }

    private static boolean isPast(Task task) {
        return task != null && task.getLifeCycle() == TaskLifeCycle.PAST;
    }

    private LocalDate searchStart() {
        LocalDate today = AppTimeReference.getCurrentDate();
        if (today.isAfter(baseDate)) return today;
        return baseDate;
    }

    public void placeAlreadyPlacedTasks(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) return;

        for (Task task : tasks) {
            LocalDate date = task.getPlacedDate();
            LocalTime time = task.getPlacedTime();
            if (date == null || time == null) continue;

            daysGrid.putIfAbsent(date, new Day(blockedTime));
            Day day = daysGrid.get(date);
            int slot = AppTimeReference.localTimeToSlot(time);

            if (isPast(task)) {
                pinOnGrid(day, slot, task);
                system.getPendingTasks().remove(task);
                system.getUnscheduledTasks().remove(task);
                if (!system.getPlacedTasks().contains(task)) {
                    system.getPlacedTasks().add(task);
                }
                continue;
            }

            if (!day.canPlace(task, date, slot, true, true)
                    || !respectsDeadlineTime(task, date, slot)) {
                markUnscheduled(task);
                continue;
            }

            day.placeTask(slot, task);
            if (!system.getPlacedTasks().contains(task)) {
                system.getPlacedTasks().add(task);
            }
            system.getPendingTasks().remove(task);
            system.getUnscheduledTasks().remove(task);
        }
    }

    private void pinOnGrid(Day day, int slot, Task task) {
        int count = task.getDurationSlots();
        if (slot < 0 || slot + count > AppTimeReference.SLOTS_PER_DAY) return;
        Task[] grid = day.returnDayGrid();
        boolean already = true;
        for (int i = 0; i < count; i++) {
            if (grid[slot + i] != task) {
                already = false;
                break;
            }
        }
        if (!already) {
            day.placeTask(slot, task);
        }
    }

    public void scheduleTasks(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            dropActiveUnscheduledOnly();
            return;
        }

        dropActiveUnscheduledOnly();

        ArrayList<Task> deadlineTasks = new ArrayList<>();
        ArrayList<Task> flexibleTasks = new ArrayList<>();

        for (Task task : tasks) {
            if (isPast(task)) continue;
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

        system.getPendingTasks().removeIf(t -> !isPast(t));
        system.getUnscheduledTasks().removeAll(system.getPlacedTasks());
    }

    private void dropActiveUnscheduledOnly() {
        unscheduledTasks.removeIf(t -> !isPast(t));
    }

    private void placeDeadlineTasks(ArrayList<Task> tasks) {
        LocalDate from = searchStart();
        for (Task task : tasks) {
            if (task.getDeadLineDate() == null) {
                markUnscheduled(task);
                continue;
            }

            long lastDay = ChronoUnit.DAYS.between(baseDate, task.getDeadLineDate()) + 1;

            if (lastDay < 0) {
                markUnscheduled(task);
                continue;
            }
            if (lastDay >= AppTimeReference.TOTAL_DAY_HORIZON) {
                lastDay = AppTimeReference.TOTAL_DAY_HORIZON - 1;
            }

            boolean placed = tryPlaceDeadline(task, from, lastDay, false, false);
            if (!placed) {
                markUnscheduled(task);
            }
        }
    }

    private boolean tryPlaceDeadline(Task task, LocalDate fromDay, long lastDay,
                                     boolean ignoreBlocked, boolean ignoreOverload) {
        LocalDate limit = baseDate.plusDays(lastDay);
        for (LocalDate thisDay = fromDay;
             thisDay.isBefore(limit);
             thisDay = thisDay.plusDays(1)) {

            daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
            Day theDay = daysGrid.get(thisDay);

            int startSlot = firstSlotOn(thisDay);

            for (int slot = startSlot; slot < AppTimeReference.SLOTS_PER_DAY; slot++) {
                if (theDay.canPlace(task, thisDay, slot, ignoreBlocked, ignoreOverload)
                        && respectsDeadlineTime(task, thisDay, slot)) {

                    theDay.placeTask(slot, task);
                    task.setPlacedDate(thisDay);
                    task.setPlacedTime(AppTimeReference.slotToLocalTime(slot));
                    if (!system.getPlacedTasks().contains(task)) {
                        system.getPlacedTasks().add(task);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private void placeFlexibleTasks(ArrayList<Task> tasks) {
        LocalDate from = searchStart();
        LocalDate limit = baseDate.plusDays(AppTimeReference.TOTAL_DAY_HORIZON);

        for (Task task : tasks) {
            boolean placed = false;

            for (LocalDate thisDay = from; thisDay.isBefore(limit) && !placed; thisDay = thisDay.plusDays(1)) {
                daysGrid.putIfAbsent(thisDay, new Day(blockedTime));
                Day theDay = daysGrid.get(thisDay);
                int startSlot = firstSlotOn(thisDay);

                for (int slot = startSlot; slot < AppTimeReference.SLOTS_PER_DAY && !placed; slot++) {
                    if (theDay.canPlace(task, thisDay, slot, false, true)) {
                        theDay.placeTask(slot, task);
                        task.setPlacedDate(thisDay);
                        task.setPlacedTime(AppTimeReference.slotToLocalTime(slot));
                        if (!system.getPlacedTasks().contains(task)) {
                            system.getPlacedTasks().add(task);
                        }
                        placed = true;
                    }
                }
            }

            if (!placed) {
                markUnscheduled(task);
            }
        }
    }

    private int firstSlotOn(LocalDate day) {
        if (!day.equals(AppTimeReference.getCurrentDate())) return 0;
        int start = AppTimeReference.currentSlotIndex() + 1;
        return Math.max(0, start);
    }

    private void markUnscheduled(Task task) {
        if (isPast(task)) return;
        removeFromGrid(task);
        system.getPlacedTasks().remove(task);
        task.setPlacedDate(null);
        task.setPlacedTime(null);
        if (!unscheduledTasks.contains(task)) {
            unscheduledTasks.add(task);
            task.setLifeCycle(TaskLifeCycle.UNSCHEDULED);
        }
    }

    private void removeFromGrid(Task task) {
        for (Day day : daysGrid.values()) {
            Task[] slots = day.returnDayGrid();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == task) {
                    slots[i] = null;
                }
            }
        }
    }

    private boolean respectsDeadlineTime(Task task, LocalDate day, int startSlot) {
        if (!task.hasDeadline() || task.getDeadLineDate() == null) {
            return true;
        }
        if (day.isAfter(task.getDeadLineDate())) {
            return false;
        }
        if (day.isBefore(task.getDeadLineDate())) {
            return true;
        }
        if (task.getDeadLineTime() == null) {
            return true;
        }

        LocalTime start = AppTimeReference.slotToLocalTime(startSlot);
        long durationMin = (long) task.getDurationSlots() * AppTimeReference.MINUTES_PER_SLOT;
        if (durationMin <= 0) {
            durationMin = AppTimeReference.MINUTES_PER_SLOT;
        }

        LocalTime finish = start.plusMinutes(durationMin);
        if (finish.isBefore(start)) {
            return false;
        }
        return !finish.isAfter(task.getDeadLineTime());
    }

    public String printUnscheduledTask() {
        StringBuilder text = new StringBuilder();
        for (Task task : unscheduledTasks) {
            text.append(task.getTaskName()).append("\n");
        }
        return text.toString();
    }

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