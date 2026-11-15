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

    // ---------- plan K: durations, clipping, ids ----------

    @Test
    void durationSlots_roundUpHalfHourCellsAndClampToOneDay() {
        assertEquals(1, taskWithMinutes("a", 1).getDurationSlots());
        assertEquals(1, taskWithMinutes("b", 30).getDurationSlots());
        assertEquals(2, taskWithMinutes("c", 31).getDurationSlots());
        assertEquals(2, taskWithMinutes("d", 60).getDurationSlots());
        assertEquals(3, taskWithMinutes("e", 61).getDurationSlots());
        assertEquals(AppTimeReference.SLOTS_PER_DAY, taskWithMinutes("f", 1440).getDurationSlots());
        assertEquals(AppTimeReference.SLOTS_PER_DAY, taskWithMinutes("g", 1441).getDurationSlots());
    }

    @Test
    void setDurationHours_clampsNonPositiveAndNaNToOneMinute() {
        Task task = task("clamp", 1.0);

        task.setDurationHours(0);
        assertEquals(1, task.getDurationMinutes());

        task.setDurationHours(-5);
        assertEquals(1, task.getDurationMinutes());

        task.setDurationHours(Double.NaN);
        assertEquals(1, task.getDurationMinutes());

        task.setDurationHours(25);
        assertEquals(AppTimeReference.MINUTES_PER_DAY, task.getDurationMinutes());
    }

    @Test
    void setDurationMinutes_clampsToTheSameRange() {
        Task task = task("minutes", 1.0);

        task.setDurationMinutes(0);
        assertEquals(1, task.getDurationMinutes());

        task.setDurationMinutes(1440);
        assertEquals(1440, task.getDurationMinutes());

        task.setDurationMinutes(2000);
        assertEquals(1440, task.getDurationMinutes());
    }

    @Test
    void sealedName_isNotClippedButAPlainNameIs() {
        String sealed = "AP1." + "x".repeat(200);
        Task sealedTask = task(sealed, 1.0);
        assertEquals(sealed, sealedTask.getTaskName());

        // Pinned current behaviour: the private load constructor stores the name verbatim
        // (the id comes from the DB), so clipping only happens on the public constructor
        // and on setTaskName().
        Task loadedPlain = task("y".repeat(150), 1.0);
        assertEquals(150, loadedPlain.getTaskName().length());

        Task fresh = new Task("y".repeat(150), 1.0, 3, null, null, null, null);
        assertEquals(100, fresh.getTaskName().length());

        fresh.setTaskName("y".repeat(150));
        assertEquals(100, fresh.getTaskName().length());
    }

    @Test
    void description_extrasAreCappedButSealedTextIsNot() {
        Task task = task("desc", 1.0);

        task.copyExtrasFrom(null);
        assertEquals("", task.getDescription());
        assertFalse(task.isDescriptionDone());

        task.setDescription("z".repeat(9000));
        assertEquals(8000, task.getDescription().length());

        String sealed = "AP1." + "q".repeat(9000);
        task.setDescription(sealed);
        assertEquals(sealed, task.getDescription());

        task.setDescription(null);
        assertEquals("", task.getDescription());
    }

    @Test
    void parentId_isTrimmedAndBlankMeansNotQuick() {
        Task task = task("quick", 1.0);

        task.setParentId("  parent-1  ");
        assertEquals("parent-1", task.getParentId());
        assertTrue(task.isQuick());

        task.setParentId("   ");
        assertEquals("", task.getParentId());
        assertFalse(task.isQuick());
    }

    @Test
    void toString_containsIdentityStateLifecycleAndWorkflow() {
        Task task = task("shown", 1.5);
        task.setPlacedDate(LocalDate.of(2026, 10, 12));
        task.setPlacedTime(LocalTime.of(9, 0));

        String text = task.toString();

        assertTrue(text.contains(task.getID()));
        assertTrue(text.contains("shown"));
        assertTrue(text.contains("durationHours=1.5"));
        assertTrue(text.contains("state=3"));
        assertTrue(text.contains("lifeCycle=UNSCHEDULED"));
        assertTrue(text.contains("workflow=UNFINISHED"));
    }

    @Test
    void twoNewTasks_getDistinctIds() {
        Task first = new Task("a", 1.0, 3, null, null, null, null);
        Task second = new Task("b", 1.0, 3, null, null, null, null);

        assertFalse(first.getID().isBlank());
        assertFalse(first.getID().equals(second.getID()));
    }

    @Test
    void typePredicates_areMutuallyExclusiveOnTheStoredState() {
        Task fixed = task("fixed", 1.0);
        fixed.setState(1);
        assertTrue(fixed.isFixedTime());
        assertFalse(fixed.hasDeadline());
        assertFalse(fixed.canGoUnscheduled());

        Task deadline = task("deadline", 1.0);
        deadline.setState(2);
        assertTrue(deadline.hasDeadline());
        assertTrue(deadline.canGoUnscheduled());

        Task flexible = task("flexible", 1.0);
        assertTrue(flexible.isFlexible());
        assertTrue(flexible.canGoUnscheduled());
    }

    private static Task taskWithMinutes(String name, int minutes) {
        Task task = task(name, 1.0);
        task.setDurationMinutes(minutes);
        return task;
    }

    private static Task task(String name, double hours) {
        return Task.loadTaskFromdb(
                name + "-id", name, hours, 3,
                null, null, null, null, null, null);
    }
}