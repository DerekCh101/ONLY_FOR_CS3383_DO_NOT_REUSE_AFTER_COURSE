/** Fields collected from the add/edit task dialog, passed into AddTask. */
package org.ashoreprospect;

import java.time.LocalDate;
import java.time.LocalTime;

public class FetchPopOutTask {

    private String taskName;
    private double duration;
    private int state; // 1 = Fixed, 2 = Deadline, 3 = Flexible

    private LocalDate startDate;
    private LocalTime startTime;
    private LocalDate deadlineDate;
    private LocalTime deadlineTime;

    // These two are used when the task is created from a grid click
    private LocalDate placedDate;
    private LocalTime placedTime;

    private String description = "";
    private boolean descriptionDone;
    private String parentId = "";
    private Task task; //used when wanted to clear a task

    public FetchPopOutTask(String taskName,
                           double duration,
                           int state,
                           LocalDate startDate,
                           LocalTime startTime,
                           LocalDate deadlineDate,
                           LocalTime deadlineTime,
                           LocalDate placedDate,
                           LocalTime placedTime) {

        this.taskName = clip(taskName, 100);
        this.duration = duration;
        this.state = state;
        this.startDate = startDate;
        this.startTime = startTime;
        this.deadlineDate = deadlineDate;
        this.deadlineTime = deadlineTime;
        this.placedDate = placedDate;
        this.placedTime = placedTime;
    }

    // Convenience constructor when placedDate/Time are not needed
    public FetchPopOutTask(String taskName,
                           double duration,
                           int state,
                           LocalDate startDate,
                           LocalTime startTime,
                           LocalDate deadlineDate,
                           LocalTime deadlineTime) {
        this(taskName, duration, state, startDate, startTime, deadlineDate, deadlineTime, null, null);
    }

    //when wanted to clear a task from GUI
    public FetchPopOutTask(Task task){
        this.task = task;
    }
    public String getTaskName() {
        return taskName;
    }

    public double getDuration() {
        return duration;
    }

    public Task getTask() {
        return task;
    }

    public int getState() {
        return state;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalDate getDeadlineDate() {
        return deadlineDate;
    }

    public LocalTime getDeadlineTime() {
        return deadlineTime;
    }

    public LocalDate getPlacedDate() {
        return placedDate;
    }

    public LocalTime getPlacedTime() {
        return placedTime;
    }

    public String getDescription() {
        return description == null ? "" : description;
    }

    public void setDescription(String description) {
        String n = description == null ? "" : description;
        this.description = n.length() > 8000 ? n.substring(0, 8000) : n;
    }

    private static String clip(String s, int max) {
        String n = s == null ? "" : s.trim();
        return n.length() > max ? n.substring(0, max) : n;
    }

    public boolean isDescriptionDone() {
        return descriptionDone;
    }

    public void setDescriptionDone(boolean descriptionDone) {
        this.descriptionDone = descriptionDone;
    }

    public String getParentId() {
        return parentId == null ? "" : parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId == null ? "" : parentId.trim();
    }

    public boolean isFixed() {
        return state == 1;
    }

    public boolean isDeadline() {
        return state == 2;
    }

    public boolean isFlexible() {
        return state == 3;
    }

    @Override
    public String toString() {
        return "FetchPopOutTask{" +
                "taskName='" + taskName + '\'' +
                ", duration=" + duration +
                ", state=" + state +
                ", startDate=" + startDate +
                ", startTime=" + startTime +
                ", deadlineDate=" + deadlineDate +
                ", deadlineTime=" + deadlineTime +
                ", placedDate=" + placedDate +
                ", placedTime=" + placedTime +
                '}';
    }
}