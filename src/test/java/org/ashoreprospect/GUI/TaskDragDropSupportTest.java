package org.ashoreprospect.GUI;

import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Day;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("app-clock")
@ResourceLock("node-session")
class TaskDragDropSupportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private TaskManagementSystem system;
    private TaskDragDropSupport dragDrop;

    @BeforeEach
    void setUp() {
        AppTimeReference.setClock(Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"), ZoneOffset.UTC));
        TaskManagementSystem.resetInstance();
        system = TaskManagementSystem.getInstance();
        system.getGrid().clear();
        system.getPlacedTasks().clear();
        system.getPendingTasks().clear();
        system.getUnscheduledTasks().clear();
        dragDrop = new TaskDragDropSupport(system, null, null);
    }

    @AfterEach
    void tearDown() {
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(Clock.systemDefaultZone());
    }

    @Test
    void onlyFutureSlotsThatFitTheDayAreValid() {
        Task task = task("moving", 1, 3, null, null);

        assertFalse(dragDrop.canDropOn(TODAY, 7, null));
        assertFalse(dragDrop.canDropOn(TODAY, 6, task));
        assertTrue(dragDrop.canDropOn(TODAY, 7, task));
        assertFalse(dragDrop.canDropOn(TODAY, -1, task));
        assertFalse(dragDrop.canDropOn(TODAY, 47, task));
        assertTrue(dragDrop.canDropOn(TODAY, 46, task));
    }

    @Test
    void rejectsAnotherTaskButAllowsTheMovingTasksOwnSlots() {
        Task moving = task("moving", 1, 3, null, null);
        Task other = task("other", 0.5, 3, null, null);
        Day day = new Day(system.getBlockedTime());
        system.getGrid().put(TODAY, day);
        day.placeTask(8, other);

        assertFalse(dragDrop.canDropOn(TODAY, 7, moving));
        assertTrue(dragDrop.canDropOn(TODAY, 9, moving));

        day.clearAllSlots();
        day.placeTask(7, moving);
        assertTrue(dragDrop.canDropOn(TODAY, 7, moving));
    }

    @Test
    void deadlineMustAllowTheEntireTaskIncludingAfterMidnight() {
        Task deadline = task("deadline", 1, 2, TODAY, LocalTime.of(11, 0));
        assertTrue(dragDrop.canDropOn(TODAY, 8, deadline));
        assertFalse(dragDrop.canDropOn(TODAY, 9, deadline));

        deadline.setDeadLineDate(TODAY.plusDays(1));
        deadline.setDeadLineTime(LocalTime.of(1, 0));
        assertTrue(dragDrop.canDropOn(TODAY, 36, deadline));
        assertFalse(dragDrop.canDropOn(TODAY, 37, deadline));

        deadline.setDeadLineDate(null);
        assertFalse(dragDrop.canDropOn(TODAY, 8, deadline));
    }

    @Test
    void deadlineWithoutTimeAllowsFinishingAtMidnightButNotLater() {
        Task deadline = task("deadline", 1, 2, TODAY, null);

        assertTrue(dragDrop.canDropOn(TODAY, 34, deadline));
        assertFalse(dragDrop.canDropOn(TODAY, 35, deadline));
        assertFalse(dragDrop.canDropOn(TODAY, 36, deadline));
    }

    private static Task task(String id, double hours, int state,
                             LocalDate deadline, LocalTime deadlineTime) {
        return Task.loadTaskFromdb(id, id, hours, state, null, null,
                deadline, deadlineTime, null, null);
    }
}
