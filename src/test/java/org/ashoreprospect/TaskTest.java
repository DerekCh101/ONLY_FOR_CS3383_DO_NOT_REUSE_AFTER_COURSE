package org.ashoreprospect;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class TaskTest {

    @Test
    void loadedTask_preservesIdentityAndSchedulingFields() {
        LocalDate startDate = LocalDate.of(2026, 10, 12);
        LocalTime startTime = LocalTime.of(9, 0);
        LocalDate deadlineDate = LocalDate.of(2026, 10, 13);
        LocalTime deadlineTime = LocalTime.of(17, 0);
        LocalDate placedDate = LocalDate.of(2026, 10, 12);
        LocalTime placedTime = LocalTime.of(10, 0);

        Task task = Task.loadTaskFromdb(
                "task-1", "loaded", 1.5, 2,
                startDate, startTime, deadlineDate, deadlineTime,
                placedDate, placedTime);

        assertEquals("task-1", task.getID());
        assertEquals("loaded", task.getTaskName());
        assertEquals(90, task.getDurationMinutes());
        assertEquals(2, task.getState());
        assertEquals(startDate, task.getStartingDate());
        assertEquals(startTime, task.getStartingTime());
        assertEquals(deadlineDate, task.getDeadLineDate());
        assertEquals(deadlineTime, task.getDeadLineTime());
        assertEquals(placedDate, task.getPlacedDate());
        assertEquals(placedTime, task.getPlacedTime());
        assertEquals(TaskLifeCycle.UNSCHEDULED, task.getLifeCycle());
        assertEquals(Workflow.UNFINISHED, task.getWorkflow());
    }

    @Test
    void taskPredicates_matchSchedulingState() {
        Task fixed = task("fixed", 1);
        fixed.setState(1);
        Task deadline = task("deadline", 2);
        deadline.setState(2);
        Task flexible = task("flexible", 3);
        flexible.setState(3);

        assertTrue(fixed.isFixedTime());
        assertFalse(fixed.canGoUnscheduled());
        assertTrue(deadline.hasDeadline());
        assertTrue(deadline.canGoUnscheduled());
        assertTrue(flexible.isFlexible());
        assertTrue(flexible.canGoUnscheduled());
    }

    @Test
    void durationSlots_usePlacementTimeForCellOccupancy() {
        Task unplaced = task("unplaced", 1.0);
        Task placed = task("placed", 1.0);
        placed.setPlacedTime(LocalTime.of(9, 1));

        assertEquals(2, unplaced.getDurationSlots());
        assertEquals(3, placed.getDurationSlots());

        placed.setDurationMinutes(31);
        assertEquals(2, placed.getDurationSlots());
        placed.setDurationMinutes(1);
        assertEquals(1, placed.getDurationSlots());
    }

    @Test
    void textAndDurationSetters_clipAndNormalizeValues() {
        Task task = task("original", 1.0);
        task.setTaskName("  renamed  ");
        task.setDurationMinutes(0);
        task.setDescription(null);

        assertEquals("renamed", task.getTaskName());
        assertEquals(1, task.getDurationMinutes());
        assertEquals("", task.getDescription());

        task.setDescription("x".repeat(8001));
        assertEquals(8000, task.getDescription().length());
        task.setTaskName("n".repeat(101));
        assertEquals(100, task.getTaskName().length());
    }

    @Test
    void quickTask_parentAndExtras_areNormalizedAndCopied() {
        Task source = task("source", 1.0);
        source.setDescription("details");
        source.setDescriptionDone(true);
        source.setParentId(" parent ");

        Task copy = task("copy", 1.0);
        copy.copyExtrasFrom(source);

        assertEquals("parent", copy.getParentId());
        assertTrue(copy.isQuick());
        assertEquals("details", copy.getDescription());
        assertTrue(copy.isDescriptionDone());

        copy.setParentId(null);
        assertEquals("", copy.getParentId());
        assertFalse(copy.isQuick());
        assertNotSame(source, copy);
    }

    @Test
    void lifecycleWorkflowAndTimestamps_areMutable() {
        Task task = task("state", 1.0);
        Instant placement = Instant.parse("2026-10-12T10:00:00Z");
        Instant updated = Instant.parse("2026-10-12T11:00:00Z");

        task.setLifeCycle(TaskLifeCycle.ONGOING);
        task.setWorkflow(Workflow.FINISHED);
        task.setPlacementUpdatedAt(placement);
        task.setLastUpdatedAt(updated);

        assertEquals(TaskLifeCycle.ONGOING, task.getLifeCycle());
        assertEquals(Workflow.FINISHED, task.getWorkflow());
        assertEquals(placement, task.getPlacementUpdatedAt());
        assertEquals(updated, task.getLastUpdatedAt());
        assertNull(task.getPlacedDate());
    }

    private static Task task(String name, double hours) {
        return Task.loadTaskFromdb(
                name + "-id", name, hours, 3,
                null, null, null, null, null, null);
    }
}