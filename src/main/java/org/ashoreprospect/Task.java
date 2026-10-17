/** One calendar task: name, duration, placement, category, description, timestamps. */
package org.ashoreprospect;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public class Task {

    private final String id;
    private String taskName;
    /** Stored as minutes (30, 60, 90, …). */
    private double durationMinutes;
    private int state; // 1 = Fixed, 2 = Deadline, 3 = Flexible
    private LocalDate startingDate;
    private LocalTime startingTime;
    private LocalDate deadLineDate;
    private LocalTime deadLineTime;
    private LocalDate placedDate;
    private LocalTime placedTime;
    private Workflow workflow;
    private TaskLifeCycle taskLifeCycle;
    private Instant placementUpdatedAt;
    private Instant lastUpdatedAt;
    private String category = "";
    private String subcategory = "";
    private String description = "";
    private boolean descriptionDone;

    /**
     * New task created by the user (GUI / CLI).
     * Id is allocated so it does not collide with live tasks or tombstones.
     */
    public Task(String taskName,
                double durationHours,
                int state,
                LocalDate startingDate,
                LocalTime startingTime,
                LocalDate deadLineDate,
                LocalTime deadLineTime) {

        this.id = allocateIdSafely();
        this.taskName = clip(taskName, 100);
        this.durationMinutes = AppTimeReference.hoursToMinutes(durationHours);
        this.state = state;
        this.startingDate = startingDate;
        this.startingTime = startingTime;
        this.deadLineDate = deadLineDate;
        this.deadLineTime = deadLineTime;
        this.taskLifeCycle = TaskLifeCycle.UNSCHEDULED;
        this.workflow = Workflow.UNFINISHED;
        this.placementUpdatedAt = null;
        this.lastUpdatedAt = Instant.now();
    }

    /** Load path — keep the id from DB, never re-allocate. */
    private Task(String id,
                 String taskName,
                 double durationHours,
                 int state,
                 LocalDate startingDate,
                 LocalTime startingTime,
                 LocalDate deadLineDate,
                 LocalTime deadLineTime) {
        this.id = id;
        this.taskName = taskName;
        this.durationMinutes = AppTimeReference.hoursToMinutes(durationHours);
        this.state = state;
        this.startingDate = startingDate;
        this.startingTime = startingTime;
        this.deadLineDate = deadLineDate;
        this.deadLineTime = deadLineTime;
        this.taskLifeCycle = TaskLifeCycle.UNSCHEDULED;
        this.workflow = Workflow.UNFINISHED;
        this.placementUpdatedAt = null;
        this.lastUpdatedAt = null;
    }

    /**
     * Prefer TaskRepository conflict check; fall back to random UUID if system not ready.
     */
    private static String allocateIdSafely() {
        try {
            TaskManagementSystem sys = TaskManagementSystem.getInstance();
            if (sys != null && sys.getTaskRepo() != null) {
                return sys.getTaskRepo().allocateNewTaskId();
            }
        } catch (Exception ignored) {
            // bootstrap / tests
        }
        return java.util.UUID.randomUUID().toString();
    }

    public static Task loadTaskFromdb(String id,
                                      String taskName,
                                      double durationHours,
                                      int state,
                                      LocalDate startingDate,
                                      LocalTime startingTime,
                                      LocalDate deadLineDate,
                                      LocalTime deadLineTime,
                                      LocalDate placedDate,
                                      LocalTime placedTime) {
        Task t = new Task(id, taskName, durationHours, state,
                startingDate, startingTime, deadLineDate, deadLineTime);
        t.placedDate = placedDate;
        t.placedTime = placedTime;
        return t;
    }

    public String getID() {
        return id;
    }

    public String getTaskName() {
        return taskName;
    }

    public double getDurationMinutes() {
        return durationMinutes;
    }

    public int getDurationSlots() {
        return AppTimeReference.minutesToSlots(durationMinutes);
    }

    public double getDurationHours() {
        return AppTimeReference.minutesToHours(durationMinutes);
    }

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public LocalDate getStartingDate() {
        return startingDate;
    }

    public LocalTime getStartingTime() {
        return startingTime;
    }

    public LocalDate getDeadLineDate() {
        return deadLineDate;
    }

    public LocalTime getDeadLineTime() {
        return deadLineTime;
    }

    public void setDeadLineDate(LocalDate deadLineDate) {
        this.deadLineDate = deadLineDate;
    }

    public void setDeadLineTime(LocalTime deadLineTime) {
        this.deadLineTime = deadLineTime;
    }

    public LocalDate getPlacedDate() {
        return placedDate;
    }

    public LocalTime getPlacedTime() {
        return placedTime;
    }

    public void setPlacedDate(LocalDate placedDate) {
        this.placedDate = placedDate;
    }

    public void setPlacedTime(LocalTime placedTime) {
        this.placedTime = placedTime;
    }

    public void setTaskName(String taskName) {
        this.taskName = clip(taskName, 100);
    }

    public void setDurationHours(double durationHours) {
        this.durationMinutes = AppTimeReference.hoursToMinutes(durationHours);
    }

    public void setDurationMinutes(int durationMinutes) {
        int snapped = Math.max(AppTimeReference.MINUTES_PER_SLOT,
                (durationMinutes / AppTimeReference.MINUTES_PER_SLOT)
                        * AppTimeReference.MINUTES_PER_SLOT);
        this.durationMinutes = snapped;
    }

    public boolean isFixedTime() {
        return state == 1;
    }

    public boolean hasDeadline() {
        return state == 2;
    }

    public boolean isFlexible() {
        return state == 3;
    }

    /** Only Deadline and Flexible may sit in the unscheduled list. */
    public boolean canGoUnscheduled() {
        return hasDeadline() || isFlexible();
    }

    public String getCategory() {
        return category == null ? "" : category;
    }

    public void setCategory(String category) {
        this.category = clip(category, 80);
    }

    public String getSubcategory() {
        return subcategory == null ? "" : subcategory;
    }

    public void setSubcategory(String subcategory) {
        this.subcategory = clip(subcategory, 80);
    }

    public String getDescription() {
        return description == null ? "" : description;
    }

    public void setDescription(String description) {
        String n = description == null ? "" : description;
        if (n.startsWith("AP1.")) {
            this.description = n;
            return;
        }
        this.description = n.length() > 8000 ? n.substring(0, 8000) : n;
    }

    private static String clip(String s, int max) {
        if (s != null && s.startsWith("AP1.")) return s;
        String n = s == null ? "" : s.trim();
        return n.length() > max ? n.substring(0, max) : n;
    }

    public boolean isDescriptionDone() {
        return descriptionDone;
    }

    public void setDescriptionDone(boolean descriptionDone) {
        this.descriptionDone = descriptionDone;
    }

    public void copyExtrasFrom(Task src) {
        if (src == null) return;
        setCategory(src.getCategory());
        setSubcategory(src.getSubcategory());
        setDescription(src.getDescription());
        setDescriptionDone(src.isDescriptionDone());
    }

    public void setStartingDate(LocalDate startingDate) {
        this.startingDate = startingDate;
    }

    public void setStartingTime(LocalTime startingTime) {
        this.startingTime = startingTime;
    }

    public TaskLifeCycle getLifeCycle() {
        return taskLifeCycle;
    }

    public Workflow getWorkflow() {
        return workflow;
    }

    public void setLifeCycle(TaskLifeCycle life) {
        this.taskLifeCycle = life;
    }

    public void setWorkflow(Workflow flow) {
        this.workflow = flow;
    }

    public Instant getPlacementUpdatedAt() {
        return placementUpdatedAt;
    }

    public void setPlacementUpdatedAt(Instant placementUpdatedAt) {
        this.placementUpdatedAt = placementUpdatedAt;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(Instant lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public void touchLastUpdated() {
        this.lastUpdatedAt = Instant.now();
    }

    public void touchPlacementUpdated() {
        Instant now = Instant.now();
        this.placementUpdatedAt = now;
        this.lastUpdatedAt = now;
    }

    @Override
    public String toString() {
        return "Task{" +
                "id='" + id + '\'' +
                ", name='" + taskName + '\'' +
                ", durationHours=" + getDurationHours() +
                ", state=" + state +
                ", start=" + startingDate + " " + startingTime +
                ", deadline=" + deadLineDate + " " + deadLineTime +
                ", placed=" + placedDate + " " + placedTime +
                ", lifeCycle=" + taskLifeCycle +
                ", workflow=" + workflow +
                ", placementUpdatedAt=" + placementUpdatedAt +
                ", lastUpdatedAt=" + lastUpdatedAt +
                '}';
    }
}