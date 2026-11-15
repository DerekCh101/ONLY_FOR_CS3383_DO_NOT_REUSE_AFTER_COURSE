/**
 * Bulk file import: parser quirks, fixed-task placement, and undo/redo bookkeeping.
 *
 * <p>{@code AddTaskWithFile} hard-codes {@code getResourceAsStream("taskInput1.txt")},
 * so the fixture under {@code src/test/resources/taskInput-test.txt} cannot be
 * swapped in. These tests therefore assert against the shipped resource and pin the
 * parser rules that a differently-shaped input file would hit.
 */
package org.ashoreprospect.Command;

import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Day;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
import org.ashoreprospect.TaskManagementSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("app-clock")
class AddTaskWithFileTest {

    private static final LocalDate MON = LocalDate.of(2026, 7, 20);
    private static final Clock TEST_CLOCK = Clock.fixed(
            Instant.parse("2026-07-20T05:00:00Z"), ZoneId.of("UTC"));

    /** taskInput1.txt holds 26 tasks (9 Fixed, 8 Deadline, 9 Flexible). */
    private static final int EXPECTED_TASKS = 26;
    private static final int EXPECTED_FIXED = 9;

    private TaskManagementSystem system;
    private Clock savedClock;

    @BeforeEach
    void setUp() {
        savedClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(TEST_CLOCK);
        TaskManagementSystem.resetInstance();
        system = TaskManagementSystem.getInstance();
        clearSystem();
    }

    @AfterEach
    void tearDown() {
        if (system != null) {
            clearSystem();
        }
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(savedClock);
    }

    private void clearSystem() {
        system.getPendingTasks().clear();
        system.getPlacedTasks().clear();
        system.getUnscheduledTasks().clear();
        system.getGrid().clear();
        RecordedCommand.getUndoList().clear();
        RecordedCommand.getRedoList().clear();
    }

    private List<Task> allTasks() {
        List<Task> all = new ArrayList<>();
        all.addAll(system.getPlacedTasks());
        all.addAll(system.getPendingTasks());
        all.addAll(system.getUnscheduledTasks());
        return all;
    }

    private static Task named(List<Task> tasks, String name) {
        return tasks.stream()
                .filter(t -> name.equals(t.getTaskName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing task " + name));
    }

    @Test
    void constructor_parsesEveryCompleteRecordInTheShippedFile() throws Exception {
        List<Task> parsed = addedTasks(new AddTaskWithFile(system));

        assertEquals(EXPECTED_TASKS, parsed.size());
        for (Task task : parsed) {
            assertFalse(task.getTaskName().isBlank(), "blank name in parsed list");
            assertTrue(task.getState() == 1 || task.getState() == 2 || task.getState() == 3,
                    "unexpected state " + task.getState() + " for " + task.getTaskName());
            assertTrue(task.getDurationMinutes() > 0);
        }
    }

    @Test
    void constructor_mapsTheThreeFieldsPerTaskType() throws Exception {
        List<Task> parsed = addedTasks(new AddTaskWithFile(system));

        Task fixed = named(parsed, "Morning Standup");
        assertEquals(1, fixed.getState());
        assertEquals(1.0, fixed.getDurationHours());
        assertEquals(LocalDate.of(2026, 7, 20), fixed.getStartingDate());
        assertEquals(LocalTime.of(9, 0), fixed.getStartingTime());
        assertNull(fixed.getDeadLineDate());
        assertNull(fixed.getDeadLineTime());

        Task deadline = named(parsed, "Write Project Proposal");
        assertEquals(2, deadline.getState());
        assertNull(deadline.getStartingDate());
        assertNull(deadline.getStartingTime());
        assertEquals(LocalDate.of(2026, 7, 22), deadline.getDeadLineDate());
        assertEquals(LocalTime.of(18, 0), deadline.getDeadLineTime());

        Task flexible = named(parsed, "Buy Groceries");
        assertEquals(3, flexible.getState());
        assertNull(flexible.getStartingDate());
        assertNull(flexible.getStartingTime());
        assertNull(flexible.getDeadLineDate());
        assertNull(flexible.getDeadLineTime());

        // A second Fixed row starts on a different day and lands in its own column.
        Task review = named(parsed, "Code Review Session");
        assertEquals(LocalDate.of(2026, 7, 21), review.getStartingDate());
        assertEquals(LocalTime.of(10, 0), review.getStartingTime());
    }

    @Test
    void constructor_preparesTasksWithoutTouchingTheSystem() throws Exception {
        AddTaskWithFile command = new AddTaskWithFile(system);

        assertEquals(EXPECTED_TASKS, addedTasks(command).size());
        assertTrue(system.getPendingTasks().isEmpty());
        assertTrue(system.getPlacedTasks().isEmpty());
        assertTrue(system.getUnscheduledTasks().isEmpty());
        assertTrue(system.getGrid().isEmpty());
        assertTrue(RecordedCommand.getUndoList().isEmpty());
    }

    @Test
    void execute_placesFixedTasksOnTheGridAndLeavesTheRestToTheEngine() throws Exception {
        AddTaskWithFile command = new AddTaskWithFile(system);
        List<Task> parsed = addedTasks(command);

        assertTrue(command.execute());

        long fixedCount = parsed.stream().filter(Task::isFixedTime).count();
        assertEquals(EXPECTED_FIXED, fixedCount);
        assertEquals(fixedCount, system.getPlacedTasks().stream()
                .filter(Task::isFixedTime).count());

        Task standup = named(system.getPlacedTasks(), "Morning Standup");
        assertEquals(LocalDate.of(2026, 7, 20), standup.getPlacedDate());
        assertEquals(LocalTime.of(9, 0), standup.getPlacedTime());
        Day day = system.getGrid().get(MON);
        assertNotNull(day);
        assertEquals(standup, day.returnDayGrid()[
                AppTimeReference.localTimeToSlot(LocalTime.of(9, 0))]);

        // Every parsed task ends up in exactly one list after updateSchedule().
        for (Task task : parsed) {
            long present = system.getPlacedTasks().stream().filter(t -> t == task).count()
                    + system.getPendingTasks().stream().filter(t -> t == task).count()
                    + system.getUnscheduledTasks().stream().filter(t -> t == task).count();
            assertEquals(1, present, "task in " + present + " lists: " + task.getTaskName());
        }

        assertTrue(RecordedCommand.getUndoList().contains(command));
        assertTrue(RecordedCommand.getRedoList().isEmpty());
    }

    @Test
    void execute_refreshesLifecycleForEveryPreparedTask() throws Exception {
        AddTaskWithFile command = new AddTaskWithFile(system);
        List<Task> parsed = addedTasks(command);

        command.execute();

        // The command refreshes lifecycle only for Fixed rows; updateSchedule() has already
        // placed everyone, so no task is left UNSCHEDULED.
        for (Task task : parsed) {
            assertNotNull(task.getLifeCycle(), "lifecycle unset for " + task.getTaskName());
            if (!task.isFixedTime()) {
                assertEquals(TaskLifeCycle.ONGOING, task.getLifeCycle(),
                        "unexpected lifecycle for " + task.getTaskName());
            }
        }
        // Fixed rows are compared against the frozen clock at 05:00 on 20-07-2026, so
        // the 09:00 standup is still ahead and the 21-07 lunch is ahead too.
        assertEquals(TaskLifeCycle.ONGOING, named(parsed, "Morning Standup").getLifeCycle());
        assertEquals(TaskLifeCycle.ONGOING, named(parsed, "Lunch with Mentor").getLifeCycle());
    }

    @Test
    void execute_twiceIsIdempotentAndRecordsTheCommandOnce() throws Exception {
        AddTaskWithFile command = new AddTaskWithFile(system);
        command.execute();
        int placedAfterFirst = system.getPlacedTasks().size();

        // A second execute() re-runs applyToSystem() on the same Task objects. The Fixed
        // rows find their own task already in the slot, so they are appended to
        // pendingTasks; updateSchedule() then removes every non-PAST pending task again,
        // so the observable state is unchanged.
        command.execute();

        assertEquals(placedAfterFirst, system.getPlacedTasks().size());
        assertTrue(system.getPendingTasks().isEmpty());
        assertTrue(system.getUnscheduledTasks().isEmpty());

        // PINNED: execute() appends `this` to the undo list unconditionally, so a second
        // call leaves the same command recorded twice and undo() runs the removal twice.
        assertEquals(2, RecordedCommand.getUndoList().stream()
                .filter(c -> c == command).count());

        command.undo();
        command.undo();
        assertTrue(allTasks().isEmpty());
    }

    @Test
    void undo_removesEveryTaskFromListsAndGrid() throws Exception {
        AddTaskWithFile command = new AddTaskWithFile(system);
        List<Task> parsed = addedTasks(command);
        command.execute();

        command.undo();

        assertEquals(0, allTasks().size());
        for (Day day : system.getGrid().values()) {
            for (Task slot : day.returnDayGrid()) {
                assertNull(slot);
            }
        }
        for (Task task : parsed) {
            assertNull(task.getPlacedDate(), "placedDate survived undo for " + task.getTaskName());
            assertNull(task.getPlacedTime(), "placedTime survived undo for " + task.getTaskName());
        }
        assertTrue(RecordedCommand.getRedoList().contains(command));
        assertFalse(RecordedCommand.getUndoList().contains(command));
    }

    @Test
    void redo_restoresTheSameTaskObjectsAndSwapsTheLists() throws Exception {
        AddTaskWithFile command = new AddTaskWithFile(system);
        List<Task> parsed = addedTasks(command);
        command.execute();
        command.undo();

        command.redo();

        for (Task task : parsed) {
            assertTrue(allTasks().contains(task), "missing after redo: " + task.getTaskName());
        }
        assertTrue(RecordedCommand.getUndoList().contains(command));
        assertFalse(RecordedCommand.getRedoList().contains(command));
    }

    @Test
    void undo_clearsAManuallyPlacedTaskAndRefreshesTheDay() throws Exception {
        AddTaskWithFile command = new AddTaskWithFile(system);
        command.execute();

        Task flexible = named(system.getPlacedTasks(), "Buy Groceries");
        Day day = new Day(system.getBlockedTime());
        system.getGrid().put(MON.plusDays(30), day);
        int freeSlotsBefore = day.getAvailableSlots();
        day.placeTask(10, flexible);
        system.getPlacedTasks().remove(flexible);
        system.getPlacedTasks().add(flexible);

        command.undo();

        assertNull(day.returnDayGrid()[10]);
        assertEquals(freeSlotsBefore, day.getAvailableSlots());
        assertFalse(system.getPlacedTasks().contains(flexible));
        assertNull(flexible.getPlacedTime());
    }

    /** The parsed list is private and not exposed; mirror it reflectively. */
    @SuppressWarnings("unchecked")
    private static List<Task> addedTasks(AddTaskWithFile command) throws Exception {
        List<Task> parsed = new ArrayList<>();
        for (java.lang.reflect.Field field : AddTaskWithFile.class.getDeclaredFields()) {
            if (field.getName().equals("addedTaskList")) {
                field.setAccessible(true);
                parsed.addAll((List<Task>) field.get(command));
            }
        }
        return parsed;
    }
}
