package org.ashoreprospect;

import org.ashoreprospect.Command.EditTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class EditTaskSnapshotBugTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 1);
    private TaskManagementSystem system;

    @BeforeEach
    void setUp() {
        AppTimeReference.setClock(Clock.fixed(
                TODAY.atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault()));
        TaskManagementSystem.resetInstance();
        system = TaskManagementSystem.getInstance();
        system.getPlacedTasks().clear();
        system.getPendingTasks().clear();
        system.getUnscheduledTasks().clear();
        system.getGrid().clear();
    }

    @AfterEach
    void tearDown() {
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(Clock.systemDefaultZone());
    }

    @Test
    void snapshotCopiesEveryEditFieldAndOriginalPlacement() {
        Task task = task("Snapshot task", 1.5, 3);
        task.setDeadLineDate(TODAY.plusDays(5));
        task.setDeadLineTime(LocalTime.of(17, 0));
        task.setDescription("Original description");
        task.setDescriptionDone(true);
        task.setPlacedDate(TODAY.plusDays(2));
        task.setPlacedTime(LocalTime.of(9, 30));

        EditTask.Snapshot snapshot = EditTask.Snapshot.of(task);

        assertAll(
                () -> assertEquals(task.getTaskName(), snapshot.name),
                () -> assertEquals(task.getDurationHours(), snapshot.durationHours),
                () -> assertEquals(task.getDeadLineDate(), snapshot.deadlineDate),
                () -> assertEquals(task.getDeadLineTime(), snapshot.deadlineTime),
                () -> assertEquals(task.getDescription(), snapshot.description),
                () -> assertEquals(task.isDescriptionDone(), snapshot.descriptionDone),
                () -> assertEquals(task.getPlacedDate(), snapshot.placedDate),
                () -> assertEquals(task.getPlacedTime(), snapshot.placedTime)
        );
    }

    @Test
    void undoAndRedoRestorePlacementAfterReschedulingMovesTask() throws Exception {
        LocalDate originalDate = TODAY.plusDays(30);
        LocalTime originalTime = LocalTime.of(10, 0);
        Task task = task("Resize task", 1.0, 3);
        Task blocker = task("Blocking task", 1.0, 3);
        place(task, originalDate, originalTime);
        place(blocker, originalDate, originalTime.plusHours(1));

        EditTask.Snapshot edited = EditTask.Snapshot.of(task);
        edited.durationHours = 2.0;
        EditTask command = new EditTask(task, edited, true);

        command.execute();
        LocalDate editedDate = task.getPlacedDate();
        LocalTime editedTime = task.getPlacedTime();
        assertNotNull(editedDate);
        assertNotEquals(originalDate, editedDate);

        command.undo();
        assertEquals(1.0, task.getDurationHours());
        assertEquals(originalDate, task.getPlacedDate());
        assertEquals(originalTime, task.getPlacedTime());
        assertSlotContains(task, originalDate, originalTime);

        command.redo();
        assertEquals(2.0, task.getDurationHours());
        assertEquals(editedDate, task.getPlacedDate());
        assertEquals(editedTime, task.getPlacedTime());
        assertSlotContains(task, editedDate, editedTime);
    }

    @Test
    void placedTaskWithEnoughRoomIsRelayoutInPlace() throws Exception {
        LocalDate date = TODAY.plusDays(2);
        LocalTime time = LocalTime.of(9, 0);
        Task task = task("Fits task", 1.0, 3);
        place(task, date, time);

        EditTask.Snapshot edited = EditTask.Snapshot.of(task);
        edited.durationHours = 2.0;
        new EditTask(task, edited, false).execute();

        assertEquals(2.0, task.getDurationHours());
        assertEquals(date, task.getPlacedDate());
        assertEquals(time, task.getPlacedTime());
        assertSlotContains(task, date, time);
        assertSlotContains(task, date, time.plusHours(1));
    }

    @Test
    void alreadyStartedTaskCanGrowIntoFreeSlots() throws Exception {
        LocalDate date = TODAY.minusDays(1);
        LocalTime time = LocalTime.of(9, 0);
        Task task = task("Started task", 1.0, 3);
        place(task, date, time);

        EditTask.Snapshot edited = EditTask.Snapshot.of(task);
        edited.durationHours = 2.0;
        new EditTask(task, edited, false).execute();

        assertEquals(2.0, task.getDurationHours());
        assertEquals(date, task.getPlacedDate());
        assertSlotContains(task, date, time);
        assertSlotContains(task, date, time.plusHours(1));
    }

    @Test
    void placedTaskWithoutRoomKeepsOldDurationWhenReschedulingIsDisabled() throws Exception {
        LocalDate date = TODAY.minusDays(1);
        LocalTime time = LocalTime.of(9, 0);
        Task task = task("Blocked resize", 1.0, 3);
        Task blocker = task("Blocker", 1.0, 3);
        place(task, date, time);
        place(blocker, date, time.plusHours(1));

        EditTask.Snapshot edited = EditTask.Snapshot.of(task);
        edited.durationHours = 2.0;
        new EditTask(task, edited, false).execute();

        assertEquals(1.0, task.getDurationHours());
        assertEquals(date, task.getPlacedDate());
        assertEquals(time, task.getPlacedTime());
        assertSlotContains(task, date, time);
        assertSlotContains(blocker, date, time.plusHours(1));
    }

    @Test
    void unplacedFlexibleTaskIsScheduledOnlyWhenReschedulingIsEnabled() throws Exception {
        Task task = task("Unplaced flexible task", 1.0, 3);
        system.getUnscheduledTasks().add(task);
        EditTask.Snapshot edited = EditTask.Snapshot.of(task);
        edited.name = "Edited flexible task";

        new EditTask(task, edited, true).execute();

        assertNotNull(task.getPlacedDate());
        assertNotNull(task.getPlacedTime());
        assertTrue(system.getPlacedTasks().contains(task));
    }

    @Test
    void unplacedFlexibleTaskStaysUnscheduledWhenReschedulingIsDisabled() throws Exception {
        Task task = task("Unscheduled flexible task", 1.0, 3);
        system.getUnscheduledTasks().add(task);
        EditTask.Snapshot edited = EditTask.Snapshot.of(task);
        edited.name = "Edited flexible task";

        new EditTask(task, edited, false).execute();

        assertNull(task.getPlacedDate());
        assertNull(task.getPlacedTime());
        assertTrue(system.getUnscheduledTasks().contains(task));
    }

    @Test
    void fixedTaskCannotEnterTheUnscheduledReschedulePath() throws Exception {
        Task task = task("Unplaced fixed task", 1.0, 1);
        system.getPendingTasks().add(task);
        EditTask.Snapshot edited = EditTask.Snapshot.of(task);
        edited.name = "Edited fixed task";

        new EditTask(task, edited, true).execute();

        assertNull(task.getPlacedDate());
        assertNull(task.getPlacedTime());
        assertFalse(system.getPlacedTasks().contains(task));
    }

    private Task task(String name, double durationHours, int state) {
        return new Task(name, durationHours, state, null, null, null, null);
    }

    private void place(Task task, LocalDate date, LocalTime time) {
        task.setPlacedDate(date);
        task.setPlacedTime(time);
        system.getPlacedTasks().add(task);
        LocalDate column = AppTimeReference.columnDateFor(date, time);
        Day day = system.getGrid().computeIfAbsent(
                column, ignored -> new Day(system.getBlockedTime()));
        day.placeTask(AppTimeReference.localTimeToSlot(time), task);
    }

    private void assertSlotContains(Task task, LocalDate date, LocalTime time) {
        LocalDate column = AppTimeReference.columnDateFor(date, time);
        Day day = system.getGrid().get(column);
        assertNotNull(day, "Expected a calendar column for " + date + " " + time);
        assertSame(task, day.returnDayGrid()[AppTimeReference.localTimeToSlot(time)]);
    }
}
