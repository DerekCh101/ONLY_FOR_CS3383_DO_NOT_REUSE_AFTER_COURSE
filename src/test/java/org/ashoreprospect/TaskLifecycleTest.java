package org.ashoreprospect;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskLifecycleTest {

    @Test
    void lifecycleAndWorkflowTrackSchedulingState() {
        Task task = Task.loadTaskFromdb(
                "task-1", "Example", 1.0, 3,
                LocalDate.of(2026, 10, 12), LocalTime.of(9, 0),
                null, null,
                null, null);

        assertEquals(TaskLifeCycle.UNSCHEDULED, task.getLifeCycle());
        assertEquals(Workflow.UNFINISHED, task.getWorkflow());

        task.setLifeCycle(TaskLifeCycle.ONGOING);
        task.setWorkflow(Workflow.FINISHED);

        assertEquals(TaskLifeCycle.ONGOING, task.getLifeCycle());
        assertEquals(Workflow.FINISHED, task.getWorkflow());
    }

    @Test
    void fixedDeadlineAndFlexibleTasksHaveDifferentUnscheduledRules() {
        Task fixed = Task.loadTaskFromdb(
                "task-fixed", "Fixed", 1.0, 1,
                LocalDate.of(2026, 10, 12), LocalTime.of(9, 0),
                null, null,
                null, null);
        Task deadline = Task.loadTaskFromdb(
                "task-deadline", "Deadline", 1.0, 2,
                null, null,
                LocalDate.of(2026, 10, 14), LocalTime.of(18, 0),
                null, null);
        Task flexible = Task.loadTaskFromdb(
                "task-flex", "Flexible", 1.0, 3,
                null, null,
                null, null,
                null, null);

        assertTrue(fixed.isFixedTime());
        assertTrue(deadline.hasDeadline());
        assertTrue(flexible.isFlexible());

        assertFalse(fixed.canGoUnscheduled());
        assertTrue(deadline.canGoUnscheduled());
        assertTrue(flexible.canGoUnscheduled());
    }

    @Test
    void timestampsAreUpdatedWhenTouchingLifecycleState() {
        Task task = Task.loadTaskFromdb(
                "task-ts", "Timestamped", 1.0, 3,
                null, null,
                null, null,
                null, null);

        task.touchLastUpdated();
        task.touchPlacementUpdated();

        assertNotNull(task.getLastUpdatedAt());
        assertNotNull(task.getPlacementUpdatedAt());
        assertTrue(task.getLastUpdatedAt().isAfter(Instant.EPOCH));
        assertTrue(task.getPlacementUpdatedAt().isAfter(Instant.EPOCH));
    }
}
