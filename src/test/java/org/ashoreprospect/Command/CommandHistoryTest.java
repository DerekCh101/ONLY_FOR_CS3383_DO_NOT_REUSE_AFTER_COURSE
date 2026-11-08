package org.ashoreprospect.Command;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.FetchPopOutTask;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.Workflow;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("app-clock")
class CommandHistoryTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private Clock originalClock;
    private TaskManagementSystem system;

    @BeforeEach
    void setUp() {
        originalClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(Clock.fixed(
                Instant.parse("2026-10-12T07:00:00Z"), ZoneId.of("UTC")));
        TaskManagementSystem.resetInstance();
        system = TaskManagementSystem.getInstance();
        system.getPendingTasks().clear();
        system.getPlacedTasks().clear();
        system.getUnscheduledTasks().clear();
        system.getGrid().clear();
        RecordedCommand.getUndoList().clear();
        RecordedCommand.getRedoList().clear();
    }

    @AfterEach
    void tearDown() {
        RecordedCommand.getUndoList().clear();
        RecordedCommand.getRedoList().clear();
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(originalClock);
    }

    @Test
    void systemCommands_exposesRegisteredNamesAndRejectsInvalidNames() throws Exception {
        assertTrue(SystemCommands.known("addTask"));
        assertFalse(SystemCommands.known("does-not-exist"));
        assertFalse(SystemCommands.known(null));
        assertTrue(SystemCommands.names().contains("undo"));

        assertFalse(SystemCommands.run(null, system));
        assertFalse(SystemCommands.run("", system));
        assertFalse(SystemCommands.run("does-not-exist", system));
        assertFalse(SystemCommands.run("addTaskFromCli", system));
    }

        @Test
        void systemCommands_addTaskValidatesPopupBeforeExecuting() throws Exception {
        assertFalse(SystemCommands.run("addTask", system));

        system.fetchPopupTask(new FetchPopOutTask(
            "invalid", 0.0, 1, MON, LocalTime.of(8, 30), null, null));
        assertFalse(SystemCommands.run("addTask", system));

        system.fetchPopupTask(new FetchPopOutTask(
            "from popup", 0.5, 1, MON, LocalTime.of(8, 30), null, null));
        assertTrue(SystemCommands.run("addTask", system));
        assertTrue(system.getPlacedTasks().stream()
            .anyMatch(task -> "from popup".equals(task.getTaskName())));
        assertTrue(new Undo(system).execute());
        }

    @Test
    void exec_rejectsNullAndFailedCommands() throws Exception {
        assertFalse(SystemCommands.exec(system, null, false));
        assertFalse(SystemCommands.exec(system, () -> false, false));
        assertTrue(SystemCommands.exec(system, () -> true, false));
    }

    @Test
    void taskLookup_findsPlacedAndUnscheduledTasksByIdOrName() {
        Task placed = task("Placed task", "placed-1");
        Task pending = task("Pending task", "pending-1");
        Task unscheduled = task("Unscheduled task", "unscheduled-1");
        system.getPlacedTasks().add(placed);
        system.getPendingTasks().add(pending);
        system.getUnscheduledTasks().add(unscheduled);

        assertEquals(placed, TaskLookup.placed(system, "placed-1"));
        assertEquals(placed, TaskLookup.placed(system, "placed task"));
        assertEquals(pending, TaskLookup.anywhere(system, "PENDING TASK"));
        assertEquals(unscheduled, TaskLookup.anywhere(system, "unscheduled-1"));
        assertEquals(null, TaskLookup.placed(system, "pending-1"));
        assertEquals(null, TaskLookup.anywhere(system, "missing"));
        assertEquals(null, TaskLookup.anywhere(null, "missing"));
    }

    @Test
    void inAppCli_handlesHelpBlankUnknownAndMalformedCommands() {
        InAppCli.Result blank = InAppCli.run(system, "  ");
        assertEquals("", blank.message);
        assertFalse(blank.refresh);

        InAppCli.Result help = InAppCli.run(system, "HELP");
        assertTrue(help.message.contains("help"));
        assertTrue(help.message.contains("movetask"));
        assertFalse(help.refresh);

        InAppCli.Result unknown = InAppCli.run(system, "not-a-command");
        assertTrue(unknown.message.contains("unknown"));
        assertFalse(unknown.refresh);

        InAppCli.Result missingMove = InAppCli.run(system, "movetask only-id");
        assertTrue(missingMove.message.contains("movetask id date time"));
        assertFalse(missingMove.refresh);

        InAppCli.Result missingFinish = InAppCli.run(system, "finish");
        assertEquals("finish id", missingFinish.message);
        assertFalse(missingFinish.refresh);

        InAppCli.Result missingTask = InAppCli.run(system, "clear missing");
        assertEquals("task not found", missingTask.message);
        assertFalse(missingTask.refresh);
    }

    @Test
    void undoAndRedo_returnFalseWhenStacksAreEmpty() throws Exception {
        assertTrue(RecordedCommand.getUndoList().isEmpty());
        assertTrue(RecordedCommand.getRedoList().isEmpty());
        assertFalse(new Undo(system).execute());
        assertFalse(new Redo(system).execute());
        assertTrue(RecordedCommand.getUndoList().isEmpty());
        assertTrue(RecordedCommand.getRedoList().isEmpty());
    }

    @Test
    void helpText_isAvailableThroughPublicApi() {
        String help = InAppCli.helpText();
        assertNotNull(help);
        assertTrue(help.contains("finish id"));
        assertTrue(help.contains("clear id"));
    }

    @Test
    void mutatingCommands_rejectInvalidTaskStateAndPlacement() throws Exception {
        Task unplaced = Task.loadTaskFromdb(
                "unplaced-1", "unplaced", 1.0, 3,
                null, null, null, null, null, null);
        assertFalse(new FinishTask(unplaced).execute());
        assertFalse(new UnfinishTask(unplaced).execute());
        assertFalse(new ClearTask(null).execute());

        Task placed = task("placed", "placed-1");
        assertFalse(new MoveTask(placed, MON, 3, MON, 0).execute());
        assertFalse(new MoveTask(placed, MON, 3, MON,
                AppTimeReference.SLOTS_PER_DAY - 1).execute());

        placed.setWorkflow(org.ashoreprospect.Workflow.FINISHED);
        assertFalse(new FinishTask(placed).execute());
        placed.setWorkflow(org.ashoreprospect.Workflow.UNFINISHED);
        assertFalse(new UnfinishTask(placed).execute());

        Task occupied = task("occupied", "occupied-1");
        place(occupied, 5);
        system.getPlacedTasks().add(occupied);
        assertFalse(new AddTask("collision", 0.5, 1,
            MON, LocalTime.of(8, 30), null, null).execute());
    }

        @Test
        void clearTask_pendingRoundTripAndCliErrorsDoNotMutate() throws Exception {
        Task pending = task("pending clear", "pending-clear-1");
        pending.setPlacedDate(null);
        pending.setPlacedTime(null);
        system.getPendingTasks().add(pending);
        ClearTask command = new ClearTask(pending);

        assertTrue(command.execute());
        assertFalse(system.getPendingTasks().contains(pending));
        command.undo();
        assertTrue(system.getPendingTasks().contains(pending));
        command.redo();
        assertFalse(system.getPendingTasks().contains(pending));

        InAppCli.Result badDate = InAppCli.run(system,
            "movetask pending-clear-1 not-a-date 08:30");
        assertTrue(badDate.message.contains("task not found"));
        InAppCli.Result badNumber = InAppCli.run(system,
            "movetask pending-clear-1 2026-10-13 08:30 not-a-number");
        assertTrue(badNumber.message.contains("task not found"));
        }

    @Test
    void clearTask_parentAlsoClearsAndRestoresAffiliatedQuickTasks() throws Exception {
        Task parent = task("parent", "parent-1");
        Task quick = task("quick", "quick-1");
        quick.setParentId(parent.getID());
        place(parent, 3);
        place(quick, 5);
        system.getPlacedTasks().add(parent);
        system.getPlacedTasks().add(quick);

        ClearTask command = new ClearTask(parent);
        assertTrue(command.execute());
        assertFalse(system.getPlacedTasks().contains(parent));
        assertFalse(system.getPlacedTasks().contains(quick));

        command.undo();
        assertTrue(system.getPlacedTasks().contains(parent));
        assertTrue(system.getPlacedTasks().contains(quick));
    }

        @Test
        void cliFinishUnfinishAndMoveCoverValidAndRejectedResults() {
        Task placed = task("cli task", "cli-1");
        place(placed, 3);
        system.getPlacedTasks().add(placed);

        InAppCli.Result moved = InAppCli.run(system,
            "  MOVETASK cli-1 2026-10-13 08:30  ");
        assertEquals("moved", moved.message);
        assertTrue(moved.refresh);

        InAppCli.Result rejected = InAppCli.run(system,
            "movetask cli-1 2026-10-12 06:00");
        assertEquals("move rejected", rejected.message);

        InAppCli.Result finished = InAppCli.run(system, "finish cli-1");
        assertEquals("finished", finished.message);
        assertTrue(finished.refresh);
        InAppCli.Result unfinished = InAppCli.run(system, "unfinish cli-1");
        assertEquals("unfinished", unfinished.message);
        assertTrue(unfinished.refresh);
        }

    @Test
    void moveTask_executeUndoRedo_roundTripsPlacement() throws Exception {
        Task task = task("movable", "move-1");
        place(task, 3);
        MoveTask command = new MoveTask(task, MON, 3, MON, 5);

        assertTrue(command.execute());
        assertEquals(LocalTime.of(8, 30), task.getPlacedTime());
        assertEquals(null, system.getGrid().get(MON).returnDayGrid()[3]);
        assertSame(task, system.getGrid().get(MON).returnDayGrid()[5]);
        assertTrue(RecordedCommand.getUndoList().contains(command));

        command.undo();
        assertEquals(LocalTime.of(7, 30), task.getPlacedTime());
        assertSame(task, system.getGrid().get(MON).returnDayGrid()[3]);
        assertEquals(null, system.getGrid().get(MON).returnDayGrid()[5]);
        assertTrue(RecordedCommand.getRedoList().contains(command));

        command.redo();
        assertEquals(LocalTime.of(8, 30), task.getPlacedTime());
        assertSame(task, system.getGrid().get(MON).returnDayGrid()[5]);
    }

    @Test
    void finishTask_executeUndoRedo_updatesTaskAndGridWorkflow() throws Exception {
        Task task = task("finishable", "finish-1");
        task.setPlacedTime(LocalTime.of(6, 0));
        place(task, 0);
        FinishTask command = new FinishTask(task);

        assertTrue(command.execute());
        assertEquals(Workflow.FINISHED, task.getWorkflow());
        assertEquals(Workflow.FINISHED,
                system.getGrid().get(MON).returnDayGrid()[0].getWorkflow());

        command.undo();
        assertEquals(Workflow.UNFINISHED, task.getWorkflow());
        assertEquals(Workflow.UNFINISHED,
                system.getGrid().get(MON).returnDayGrid()[0].getWorkflow());

        command.redo();
        assertEquals(Workflow.FINISHED, task.getWorkflow());
    }

    @Test
    void unfinishTask_executeUndoRedo_restoresWorkflow() throws Exception {
        Task task = task("unfinishable", "unfinish-1");
        task.setPlacedTime(LocalTime.of(6, 0));
        task.setWorkflow(Workflow.FINISHED);
        place(task, 0);
        UnfinishTask command = new UnfinishTask(task);

        assertTrue(command.execute());
        assertEquals(Workflow.UNFINISHED, task.getWorkflow());
        command.undo();
        assertEquals(Workflow.FINISHED, task.getWorkflow());
        command.redo();
        assertEquals(Workflow.UNFINISHED, task.getWorkflow());
    }

    @Test
    void clearTask_executeUndoRedo_roundTripsTaskMembershipAndPlacement() throws Exception {
        Task task = task("clearable", "clear-1");
        place(task, 3);
        system.getPlacedTasks().add(task);
        ClearTask command = new ClearTask(task);

        assertTrue(command.execute());
        assertFalse(system.getPlacedTasks().contains(task));
        assertEquals(null, system.getGrid().get(MON).returnDayGrid()[3]);

        command.undo();
        assertTrue(system.getPlacedTasks().contains(task));
        assertSame(task, system.getGrid().get(MON).returnDayGrid()[3]);

        command.redo();
        assertFalse(system.getPlacedTasks().contains(task));
        assertEquals(null, system.getGrid().get(MON).returnDayGrid()[3]);
    }

    @Test
    void editTask_executeUndoRedo_roundTripsSnapshotFields() throws Exception {
        Task task = task("editable", "edit-1");
        place(task, 3);
        EditTask.Snapshot next = EditTask.Snapshot.of(task);
        next.name = "edited";
        next.description = "new details";
        next.descriptionDone = true;
        EditTask command = new EditTask(task, next, false);

        assertTrue(command.execute());
        assertEquals("edited", task.getTaskName());
        assertEquals("new details", task.getDescription());
        assertTrue(task.isDescriptionDone());

        command.undo();
        assertEquals("editable", task.getTaskName());
        assertEquals("", task.getDescription());
        assertFalse(task.isDescriptionDone());

        command.redo();
        assertEquals("edited", task.getTaskName());
        assertEquals("new details", task.getDescription());
        assertTrue(task.isDescriptionDone());
    }

    @Test
    void editTask_resizeIntoCollisionRestoresOriginalDuration() throws Exception {
        Task task = org.ashoreprospect.Task.loadTaskFromdb(
                "resize-1", "resize", 1.0, 3,
                null, null, null, null, MON, LocalTime.of(7, 30));
        Task blocker = task("blocker", "blocker-1");
        place(task, 3);
        place(blocker, 5);
        system.getPlacedTasks().add(task);
        system.getPlacedTasks().add(blocker);

        EditTask.Snapshot next = EditTask.Snapshot.of(task);
        next.durationHours = 1.5;
        EditTask command = new EditTask(task, next, false);

        assertTrue(command.execute());
        assertEquals(1.0, task.getDurationHours());
        command.undo();
        assertEquals(1.0, task.getDurationHours());
    }

    @Test
    void addTask_fixedPlacement_executeAndUndoChangesSystemState() throws Exception {
        AddTask command = new AddTask(
                "added", 0.5, 1,
                MON, LocalTime.of(8, 30), null, null);

        assertTrue(command.execute());
        Task added = system.getPlacedTasks().stream()
                .filter(candidate -> "added".equals(candidate.getTaskName()))
                .findFirst()
                .orElseThrow();
        assertSame(added, system.getGrid().get(MON).returnDayGrid()[5]);
        assertTrue(RecordedCommand.getUndoList().contains(command));

        command.undo();
        assertFalse(system.getPlacedTasks().contains(added));
        assertEquals(null, system.getGrid().get(MON).returnDayGrid()[5]);
    }

    @Test
    void addTask_fixedWithoutStart_isRejected() throws Exception {
        AddTask command = new AddTask(
                "invalid", 0.5, 1,
                null, null, null, null);

        assertFalse(command.execute());
        assertTrue(system.getPlacedTasks().isEmpty());
        assertTrue(system.getPendingTasks().isEmpty());
    }

    @Test
    void runScheduler_executeUndoRedo_roundTripsFlexiblePlacement() throws Exception {
        Task pending = task("scheduled", "scheduled-1");
        pending.setPlacedDate(null);
        pending.setPlacedTime(null);
        system.getPendingTasks().add(pending);
        RunSchedulerCommand command = new RunSchedulerCommand(system);

        assertTrue(command.execute());
        assertTrue(system.getPlacedTasks().contains(pending));
        assertEquals(LocalTime.of(7, 30), pending.getPlacedTime());

        command.undo();
        assertFalse(system.getPlacedTasks().contains(pending));
        assertTrue(system.getPendingTasks().contains(pending));
        assertEquals(null, pending.getPlacedTime());

        command.redo();
        assertTrue(system.getPlacedTasks().contains(pending));
        assertEquals(LocalTime.of(7, 30), pending.getPlacedTime());
    }

    @Test
    void clearPastTasks_returnsFalseWhenNoPastTasksExist() throws Exception {
        Task upcoming = task("upcoming", "upcoming-1");
        system.getPendingTasks().add(upcoming);

        ClearPastTasks command = new ClearPastTasks(system);

        assertFalse(command.execute());
        assertTrue(system.getPendingTasks().contains(upcoming));
        assertTrue(RecordedCommand.getUndoList().isEmpty());
    }

        @Test
        void clearPastTasks_executeUndoRedoRemovesAndRestoresPastTask() throws Exception {
        Task past = task("past", "past-1");
        past.setPlacedDate(MON);
        past.setPlacedTime(LocalTime.of(5, 0));
        place(past, 0);
        system.getPlacedTasks().add(past);
        system.getTaskRepo().dbAddTask(past);

        ClearPastTasks command = new ClearPastTasks(system);
        assertTrue(command.execute());
        assertTrue(system.getPlacedTasks().stream()
            .noneMatch(task -> "past-1".equals(task.getID())));

        command.undo();
        assertTrue(RecordedCommand.getRedoList().contains(command));
        command.redo();
        assertTrue(system.getPlacedTasks().stream()
            .noneMatch(task -> "past-1".equals(task.getID())));
        }

        @Test
        void clearAllTask_executeUndoRedoCoversBulkHistory() throws Exception {
        Task placed = task("bulk placed", "bulk-placed-1");
        place(placed, 3);
        Task pending = task("bulk pending", "bulk-pending-1");
        pending.setPlacedDate(null);
        pending.setPlacedTime(null);
        Task unscheduled = task("bulk unscheduled", "bulk-unscheduled-1");
        unscheduled.setPlacedDate(null);
        unscheduled.setPlacedTime(null);
        system.getPlacedTasks().add(placed);
        system.getPendingTasks().add(pending);
        system.getUnscheduledTasks().add(unscheduled);
        system.getTaskRepo().dbAddTask(placed);
        system.getTaskRepo().dbAddTask(pending);
        system.getTaskRepo().dbAddTask(unscheduled);

        ClearAllTask command = new ClearAllTask(system);
        assertTrue(command.execute());
        assertTrue(system.getPlacedTasks().stream()
            .noneMatch(task -> "bulk placed".equals(task.getTaskName())));
        assertTrue(system.getPendingTasks().stream()
            .noneMatch(task -> "bulk pending".equals(task.getTaskName())));

        command.undo();
        assertTrue(RecordedCommand.getRedoList().contains(command));
        command.redo();
        assertTrue(RecordedCommand.getUndoList().contains(command));
        assertTrue(system.getPlacedTasks().stream()
            .noneMatch(task -> "bulk placed".equals(task.getTaskName())));
        }

    @Test
    void printAllTask_outputsPlacedTasks() {
        Task placed = task("printable", "print-1");
        system.getPlacedTasks().add(placed);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));

            assertTrue(new PrintAllTask(system).execute());
        } finally {
            System.setOut(originalOut);
        }

        String output = captured.toString();
        assertTrue(output.contains("Current placed Task:"));
        assertTrue(output.contains("printable"));
        assertTrue(output.contains("print-1"));
    }

    @Test
    void addTaskWithFile_loadsBundledTasksAndUndoRemovesThem() throws Exception {
        AddTaskWithFile command = new AddTaskWithFile(system);

        assertTrue(command.execute());
        int loadedCount = system.getPlacedTasks().size()
                + system.getPendingTasks().size()
                + system.getUnscheduledTasks().size();
        assertTrue(loadedCount > 0, "expected imported tasks, found " + loadedCount);

        command.undo();

        assertTrue(system.getPlacedTasks().isEmpty());
        assertTrue(system.getPendingTasks().isEmpty());
        assertTrue(system.getUnscheduledTasks().isEmpty());
        assertTrue(RecordedCommand.getRedoList().contains(command));
    }

    private void place(Task task, int slot) {
        system.getGrid().putIfAbsent(MON, new org.ashoreprospect.Day(system.getBlockedTime()));
        system.getGrid().get(MON).placeTask(slot, task);
    }

    private static Task task(String name, String id) {
        return Task.loadTaskFromdb(id, name, 1.0, 3,
                null, null, null, null, MON, java.time.LocalTime.of(7, 30));
    }
}