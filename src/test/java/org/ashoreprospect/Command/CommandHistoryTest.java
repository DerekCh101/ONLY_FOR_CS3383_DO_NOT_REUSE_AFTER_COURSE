package org.ashoreprospect.Command;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Day;
import org.ashoreprospect.FetchPopOutTask;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.Workflow;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // ---------------------------------------------------------------- AC: rollback + affiliates

    @Test
    void aRejectedAddTaskLeavesTheTaskInNoListAndOnNoGridSlot() throws Exception {
        // PINNED: with no placed date, AddTask only goes straight to the grid for a *fixed
        // time* task (state 1); a flexible one takes the pending path and is scheduled, so
        // that is the path that can be rejected here.
        //
        // The day is full from 07:15 to 23:45, so no 4-hour window starting at 07:00 fits.
        // The engine rejects the task and AddTask rolls it back.
        Task blocker = placedParent("blocker", "blocker-1", LocalTime.of(7, 0));
        Task[] slots = system.getGrid().get(MON).returnDayGrid();
        for (int i = 1; i < AppTimeReference.SLOTS_PER_DAY; i++) {
            slots[i] = blocker;
        }
        system.getPlacedTasks().remove(blocker);

        AddTask command = new AddTask("impossible", 4.0, 1,
                MON, LocalTime.of(7, 0), null, null, null, null);
        assertFalse(command.execute());

        assertTrue(system.getPlacedTasks().stream()
                .noneMatch(t -> "impossible".equals(t.getTaskName())), "not placed");
        assertTrue(system.getPendingTasks().stream()
                .noneMatch(t -> "impossible".equals(t.getTaskName())), "not pending");
        assertTrue(system.getUnscheduledTasks().stream()
                .noneMatch(t -> "impossible".equals(t.getTaskName())), "not unscheduled");
        assertFalse(system.getGrid().values().stream().anyMatch(day ->
                java.util.Arrays.stream(day.returnDayGrid())
                        .anyMatch(t -> t != null && "impossible".equals(t.getTaskName()))),
                "no grid slot holds the rejected task");
    }

    @Test
    void aRejectedAddTaskLeavesTheListsEmpty() throws Exception {
        // Same rejection as above with the very first slot also taken: the fixed-time path
        // fails its slot check before writing anything, so the three task lists stay empty.
        // PINNED: the reachable rejection is the direct grid check, not rollbackTask() -
        // updateSchedule() skips fixed-time tasks, and a flexible task it refuses is marked
        // unscheduled and then removed by rollbackTask(), which needs a full 5-year horizon.
        Task blocker = placedParent("blocker", "blocker-2", LocalTime.of(7, 0));
        Task[] slots = system.getGrid().get(MON).returnDayGrid();
        for (int i = 0; i < AppTimeReference.SLOTS_PER_DAY; i++) {
            slots[i] = blocker;
        }
        system.getPlacedTasks().remove(blocker);

        AddTask command = new AddTask("impossible", 4.0, 1,
                MON, LocalTime.of(7, 0), null, null, null, null);
        assertFalse(command.execute());
        assertTrue(system.getPlacedTasks().isEmpty());
        assertTrue(system.getPendingTasks().isEmpty());
        assertTrue(system.getUnscheduledTasks().isEmpty());
    }

    @Test
    void finishingAParentFinishesItsQuicksAndUndoUnfinishesExactlyThose() throws Exception {
        Task parent = placedParent("parent", "parent-1", LocalTime.of(9, 0));
        Task openQuick = quickOf(parent, "open-quick", LocalTime.of(9, 30));
        Task openQuick2 = quickOf(parent, "open-quick-2", LocalTime.of(10, 0));

        FinishTask finish = new FinishTask(parent);
        assertTrue(finish.execute());

        assertEquals(Workflow.FINISHED, parent.getWorkflow());
        assertEquals(Workflow.FINISHED, openQuick.getWorkflow());
        assertEquals(Workflow.FINISHED, openQuick2.getWorkflow());

        finish.undo();

        assertEquals(Workflow.UNFINISHED, parent.getWorkflow());
        assertEquals(Workflow.UNFINISHED, openQuick.getWorkflow());
        assertEquals(Workflow.UNFINISHED, openQuick2.getWorkflow());
    }

    @Test
    void finishAffiliatedSkipsAQuickThatWasAlreadyFinished() throws Exception {
        Task parent = placedParent("parent", "parent-2", LocalTime.of(9, 0));
        Task already = quickOf(parent, "already-done", LocalTime.of(9, 30));
        already.setWorkflow(Workflow.FINISHED);
        Task open = quickOf(parent, "still-open", LocalTime.of(10, 0));

        FinishTask finish = new FinishTask(parent);
        assertTrue(finish.execute());
        assertEquals(Workflow.FINISHED, open.getWorkflow());

        finish.undo();

        // PINNED: unfinishAffiliated() only walks the quicks this run finished, so the quick
        // that was already FINISHED stays FINISHED even though its parent is not.
        assertEquals(Workflow.FINISHED, already.getWorkflow());
        assertEquals(Workflow.UNFINISHED, open.getWorkflow());
    }

    // ---------------------------------------------------------------- AC: CLI text

    @Test
    void inAppCli_blankLineIsSilentAndUnknownVerbPointsAtHelp() {
        InAppCli.Result blank = InAppCli.run(system, "  ");
        assertEquals("", blank.message);
        assertFalse(blank.refresh);

        InAppCli.Result unknown = InAppCli.run(system, "nope");
        // U+2014 (em dash), written as an escape so this source stays ASCII-only.
        assertEquals("unknown \u2014 type help", unknown.message);
        assertFalse(unknown.refresh);
    }

    @Test
    void helpText_isPipeSeparatedAndStartsWithHelp() {
        String help = InAppCli.helpText();

        assertTrue(help.startsWith("help"), help);
        String[] parts = help.split(" \\| ");
        assertEquals(7, parts.length, help);
        assertEquals("help", parts[0]);
        assertEquals("movetask id date time [hours]", parts[1]);
        assertEquals("finish id", parts[2]);
        assertEquals("unfinish id", parts[3]);
        assertEquals("clear id", parts[4]);
        assertEquals("undo", parts[5]);
        assertEquals("redo", parts[6]);
    }

    @Test
    void systemCommandNames_areUnmodifiableAndIncludeTheSchedulerEntryPoints() {
        java.util.Set<String> names = SystemCommands.names();

        assertTrue(names.contains("update"));
        assertTrue(names.contains("runScheduler"));
        assertTrue(names.contains("printAll"));
        assertTrue(names.contains("exit"));
        assertThrows(UnsupportedOperationException.class, () -> names.remove("undo"));
        // A failed mutation must not have removed anything.
        assertTrue(SystemCommands.known("undo"));
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

    // =====================================================================
    // Add-task command paths (plan D)
    // =====================================================================

    @Test
    void addTask_flexibleTooBigForOneColumn_isPushedToTheFirstFreeDay() throws Exception {
        // A flexible task can only occupy consecutive free slots inside one day column.
        // Leave a single 30-minute gap today, in the next column and in the column after
        // that, then add a full 24-hour flexible task.
        for (int dayOffset = 0; dayOffset < 3; dayOffset++) {
            LocalDate date = MON.plusDays(dayOffset);
            Day day = new Day(system.getBlockedTime());
            system.getGrid().put(date, day);
            for (int slot = 0; slot < AppTimeReference.SLOTS_PER_DAY; slot++) {
                day.returnDayGrid()[slot] = new Task("filler", 0.5, 3, null, null, null, null);
            }
            day.returnDayGrid()[47] = null;
        }

        AddTask command = new AddTask("huge", 24.0, 3, null, null, null, null);
        assertTrue(command.execute());

        Task added = system.getPlacedTasks().stream()
            .filter(t -> "huge".equals(t.getTaskName()))
            .findFirst()
            .orElseThrow();
        // The first completely free column is three days out, starting at 06:00.
        assertEquals(MON.plusDays(3), added.getPlacedDate());
        assertEquals(LocalTime.of(6, 0), added.getPlacedTime());
        assertEquals(AppTimeReference.SLOTS_PER_DAY, added.getDurationSlots());
        assertSame(added, system.getGrid().get(MON.plusDays(3)).returnDayGrid()[0]);
        assertTrue(RecordedCommand.getUndoList().contains(command));
    }

    @Test
    void addTask_fixedInThePast_isStillPlacedOnTheGrid() throws Exception {
        // AddTask.placeOnGrid only checks collisions and slot bounds; it does not look at
        // the clock. ValidateAddTaskInput is what rejects past starts on the form path.
        // Pinned as-is so the difference between the two entry points stays visible.
        AddTask command = new AddTask(
            "yesterday", 0.5, 1,
            MON.minusDays(1), LocalTime.of(10, 0), null, null);

        assertTrue(command.execute());
        Task added = system.getPlacedTasks().stream()
            .filter(t -> "yesterday".equals(t.getTaskName()))
            .findFirst()
            .orElseThrow();
        assertEquals(MON.minusDays(1), added.getPlacedDate());
        assertEquals(LocalTime.of(10, 0), added.getPlacedTime());
        assertEquals(TaskLifeCycle.PAST, added.getLifeCycle());

        assertFalse(org.ashoreprospect.ValidateAddTaskInput.validateAddTaskInput(
            "yesterday", 0.5, 1,
            MON.minusDays(1), LocalTime.of(10, 0), null, null));
    }

    @Test
    void addTask_quickEventInsideParentWindow_isPlacedAndLinked() throws Exception {
        Task parent = task("parent", "parent-quick-1");
        parent.setPlacedDate(MON);
        parent.setPlacedTime(LocalTime.of(8, 0));
        parent.setDurationHours(2.0);
        place(parent, 4);
        system.getPlacedTasks().add(parent);

        FetchPopOutTask popup = new FetchPopOutTask(
            "coffee", 0.5, 1, null, null, null, null,
            MON, LocalTime.of(9, 0));
        popup.setParentId(parent.getID());
        system.fetchPopupTask(popup);

        assertTrue(new AddTask("coffee", 0.5, 1, null, null, null, null,
            MON, LocalTime.of(9, 0)).execute());

        Task quick = system.getPlacedTasks().stream()
            .filter(t -> "coffee".equals(t.getTaskName()))
            .findFirst()
            .orElseThrow();
        assertTrue(quick.isQuick());
        assertEquals(parent.getID(), quick.getParentId());
        assertEquals(MON, quick.getPlacedDate());
        assertEquals(LocalTime.of(9, 0), quick.getPlacedTime());
        assertEquals(TaskLifeCycle.ONGOING, quick.getLifeCycle());
        assertSame(parent, system.mainTask(quick));
    }

    @Test
    void addTask_quickEventOutsideParentWindow_isRejected() throws Exception {
        Task parent = task("parent", "parent-quick-2");
        parent.setPlacedDate(MON);
        parent.setPlacedTime(LocalTime.of(8, 0));
        parent.setDurationHours(1.0);
        place(parent, 4);
        system.getPlacedTasks().add(parent);

        FetchPopOutTask popup = new FetchPopOutTask(
            "too late", 0.5, 1, null, null, null, null,
            MON, LocalTime.of(11, 0));
        popup.setParentId(parent.getID());
        system.fetchPopupTask(popup);

        assertFalse(new AddTask("too late", 0.5, 1, null, null, null, null,
            MON, LocalTime.of(11, 0)).execute());
        assertTrue(system.getPlacedTasks().stream()
            .noneMatch(t -> "too late".equals(t.getTaskName())));
    }

    @Test
    void addTask_quickEventWithUnplacedParent_isRejected() throws Exception {
        Task ghost = task("ghost", "ghost-parent-1");
        ghost.setPlacedDate(null);
        ghost.setPlacedTime(null);
        system.getPendingTasks().add(ghost);

        FetchPopOutTask popup = new FetchPopOutTask(
            "orphan", 0.5, 1, null, null, null, null,
            MON, LocalTime.of(9, 0));
        popup.setParentId(ghost.getID());
        system.fetchPopupTask(popup);

        assertFalse(new AddTask("orphan", 0.5, 1, null, null, null, null,
            MON, LocalTime.of(9, 0)).execute());
        assertTrue(system.getPlacedTasks().stream()
            .noneMatch(t -> "orphan".equals(t.getTaskName())));
    }

    @Test
    void addTask_autoScheduledUndo_leavesTheTaskOutOfEveryList() throws Exception {
        AddTask command = new AddTask("auto", 0.5, 3, null, null, null, null);
        assertTrue(command.execute());

        Task added = system.getPlacedTasks().stream()
            .filter(t -> "auto".equals(t.getTaskName()))
            .findFirst()
            .orElseThrow();
        LocalTime autoSlot = added.getPlacedTime();
        assertEquals(LocalTime.of(7, 30), autoSlot);

        command.undo();

        assertFalse(system.getPlacedTasks().contains(added));
        assertFalse(system.getPendingTasks().contains(added));
        assertFalse(system.getUnscheduledTasks().contains(added));
        // Pinned current behaviour: undo() clears the grid and the lists and calls
        // updateSchedule(), but it does not clear placedDate/placedTime on the task.
        assertEquals(MON, added.getPlacedDate());
        assertEquals(LocalTime.of(7, 30), added.getPlacedTime());
        assertTrue(RecordedCommand.getRedoList().contains(command));
    }

    @Test
    void addTask_popupExtras_areCopiedOntoTheNewTask() throws Exception {
        // The popup's parentId turns the new task into a quick event, which is only
        // accepted inside its parent's window, so the popup carries no parent here.
        FetchPopOutTask popup = new FetchPopOutTask(
            "with extras", 0.5, 1, null, null, null, null,
            MON, LocalTime.of(13, 30));
        popup.setDescription("bring notes");
        popup.setDescriptionDone(true);
        system.fetchPopupTask(popup);

        assertTrue(new AddTask("with extras", 0.5, 1, null, null, null, null,
            MON, LocalTime.of(13, 30)).execute());

        Task created = system.getPlacedTasks().stream()
            .filter(t -> "with extras".equals(t.getTaskName()))
            .findFirst()
            .orElseThrow();
        assertEquals("bring notes", created.getDescription());
        assertTrue(created.isDescriptionDone());
        assertFalse(created.isQuick());
        assertEquals("", created.getParentId());
        assertEquals(LocalTime.of(13, 30), created.getPlacedTime());
    }

    @Test
    void addTask_subHalfHourPopupDuration_keepsExactMinutes() throws Exception {
        FetchPopOutTask popup = new FetchPopOutTask(
            "twenty", 20.0 / 60.0, 1, null, null, null, null,
            MON, LocalTime.of(10, 0));
        system.fetchPopupTask(popup);

        assertTrue(new AddTask("twenty", 20.0 / 60.0, 1, null, null, null, null,
            MON, LocalTime.of(10, 0)).execute());

        Task created = system.getPlacedTasks().stream()
            .filter(t -> "twenty".equals(t.getTaskName()))
            .findFirst()
            .orElseThrow();
        assertEquals(20, created.getDurationMinutes());
        assertEquals(1, created.getDurationSlots());
    }

    // =====================================================================
    // Run-scheduler command paths (plan E)
    // =====================================================================

    @Test
    void runScheduler_leavesFixedTasksAloneThroughExecuteUndoRedo() throws Exception {
        Task fixed = new Task("fixed", 1.0, 1, MON, LocalTime.of(11, 0), null, null);
        placeFor(system, MON, AppTimeReference.localTimeToSlot(LocalTime.of(11, 0)), fixed);
        fixed.setPlacedDate(MON);
        fixed.setPlacedTime(LocalTime.of(11, 0));
        system.getPlacedTasks().add(fixed);

        RunSchedulerCommand command = new RunSchedulerCommand(system);

        assertTrue(command.execute());
        assertTrue(system.getPlacedTasks().contains(fixed));
        assertEquals(LocalTime.of(11, 0), fixed.getPlacedTime());
        assertSame(fixed, system.getGrid().get(MON).returnDayGrid()[10]);

        command.undo();
        assertTrue(system.getPlacedTasks().contains(fixed));
        assertEquals(LocalTime.of(11, 0), fixed.getPlacedTime());
        assertSame(fixed, system.getGrid().get(MON).returnDayGrid()[10]);

        command.redo();
        assertEquals(LocalTime.of(11, 0), fixed.getPlacedTime());
    }

    @Test
    void runScheduler_deadlineThatNoLongerFits_becomesUnscheduled() throws Exception {
        Task deadline = Task.loadTaskFromdb("dl-1", "deadline", 1.0, 2,
            null, null, MON, LocalTime.of(8, 30), MON, LocalTime.of(7, 30));
        place(deadline, 3);
        system.getPlacedTasks().add(deadline);

        // A fixed task grabs the only slot that could hold the 1-hour deadline task.
        Task blocker = new Task("blocker", 1.0, 1, MON, LocalTime.of(7, 30), null, null);
        blocker.setPlacedDate(MON);
        blocker.setPlacedTime(LocalTime.of(7, 30));
        system.getTaskRepo().dbAddTask(blocker);
        system.getPlacedTasks().add(blocker);
        system.getGrid().get(MON).returnDayGrid()[3] = null;
        system.getGrid().get(MON).placeTask(3, blocker);

        RunSchedulerCommand command = new RunSchedulerCommand(system);
        assertTrue(command.execute());

        assertFalse(system.getPlacedTasks().contains(deadline));
        assertTrue(system.getUnscheduledTasks().contains(deadline));
        assertNull(deadline.getPlacedDate());
        assertNull(deadline.getPlacedTime());
        assertEquals(TaskLifeCycle.UNSCHEDULED, deadline.getLifeCycle());
    }

    @Test
    void runScheduler_undoWithOccupiedOldSlot_dropsTheTaskInsteadOfOverlapping() throws Exception {
        Task flexible = task("flex", "flex-undo-1");
        flexible.setPlacedDate(MON);
        flexible.setPlacedTime(LocalTime.of(7, 30));
        place(flexible, 3);

        RunSchedulerCommand command = new RunSchedulerCommand(system);
        assertTrue(command.execute());

        // Squat on the snapshot slot (07:30) after the run moved the task elsewhere.
        Task squatter = new Task("squatter", 1.0, 1, MON, LocalTime.of(7, 30), null, null);
        system.getGrid().putIfAbsent(MON, new Day(system.getBlockedTime()));
        system.getGrid().get(MON).placeTask(3, squatter);
        assertSame(squatter, system.getGrid().get(MON).returnDayGrid()[3]);

        command.undo();

        // Pinned current behaviour: when the old slot is taken, undo() skips the placement
        // and does NOT move the task to the unscheduled list. It ends up in no list and is
        // off the grid, but it never overlaps the squatter.
        assertFalse(system.getPlacedTasks().contains(flexible));
        assertFalse(system.getUnscheduledTasks().contains(flexible));
        assertFalse(system.getPendingTasks().contains(flexible));
        assertSame(squatter, system.getGrid().get(MON).returnDayGrid()[3]);
        assertTrue(system.getGrid().get(MON).returnDayGrid()[4] != flexible);
    }

    @Test
    void runScheduler_undoRestoresTasksThatWereNeverPlaced() throws Exception {
        Task neverPlaced = task("never", "never-1");
        neverPlaced.setPlacedDate(null);
        neverPlaced.setPlacedTime(null);
        system.getPendingTasks().add(neverPlaced);

        RunSchedulerCommand command = new RunSchedulerCommand(system);
        assertTrue(command.execute());
        assertTrue(system.getPlacedTasks().contains(neverPlaced));

        command.undo();

        assertTrue(system.getPendingTasks().contains(neverPlaced));
        assertFalse(system.getPlacedTasks().contains(neverPlaced));
        assertNull(neverPlaced.getPlacedTime());
    }

    @Test
    void successfulExecute_clearsTheRedoStack() throws Exception {
        Task pending = task("sched", "sched-clear-1");
        pending.setPlacedDate(null);
        pending.setPlacedTime(null);
        system.getPendingTasks().add(pending);

        RunSchedulerCommand first = new RunSchedulerCommand(system);
        assertTrue(first.execute());
        first.undo();
        assertEquals(1, RecordedCommand.getRedoList().size());

        assertTrue(new AddTask("newer", 0.5, 1,
            MON, LocalTime.of(15, 0), null, null).execute());

        assertTrue(RecordedCommand.getRedoList().isEmpty());
    }

    @Test
    void runScheduler_undoRestoresEveryTaskThatWasPlacedInTheSystem() throws Exception {
        Task a = task("a", "multi-a");
        Task b = task("b", "multi-b");
        a.setPlacedDate(MON);
        a.setPlacedTime(LocalTime.of(7, 30));
        b.setPlacedDate(MON);
        b.setPlacedTime(LocalTime.of(8, 30));
        place(a, 3);
        place(b, 5);
        system.getPlacedTasks().add(a);
        system.getPlacedTasks().add(b);

        RunSchedulerCommand command = new RunSchedulerCommand(system);
        assertTrue(command.execute());

        // Pinned current behaviour: updateSchedule() clears the non-fixed tasks off the grid
        // and then runs the engine over the now-empty grid. Both 1-hour tasks therefore come
        // back at their original slots instead of being packed from 06:00.
        assertEquals(LocalTime.of(7, 30), a.getPlacedTime());
        assertEquals(LocalTime.of(8, 30), b.getPlacedTime());

        command.undo();

        assertEquals(LocalTime.of(7, 30), a.getPlacedTime());
        assertEquals(LocalTime.of(8, 30), b.getPlacedTime());
        assertTrue(system.getPlacedTasks().contains(a));
        assertTrue(system.getPlacedTasks().contains(b));
        assertSame(a, system.getGrid().get(MON).returnDayGrid()[3]);
        assertSame(b, system.getGrid().get(MON).returnDayGrid()[5]);
        assertTrue(system.getPendingTasks().isEmpty());
    }

    private void place(Task task, int slot) {
        placeFor(system, MON, slot, task);
    }

    private static void placeFor(TaskManagementSystem sys, LocalDate date, int slot, Task task) {
        sys.getGrid().putIfAbsent(date, new Day(sys.getBlockedTime()));
        sys.getGrid().get(date).placeTask(slot, task);
    }

    /**
     * A placeable parent at {@code time} on {@link #MON}: registered in the placed list and
     * on the grid, which is what {@code quicksOf} and {@code FinishTask} both rely on.
     */
    private Task placedParent(String name, String id, LocalTime time) {
        Task parent = task(name, id);
        system.getPlacedTasks().add(parent);
        placeFor(system, MON, AppTimeReference.localTimeToSlot(time), parent);
        return parent;
    }

    /** A quick child of {@code parent}, placed on the same day at {@code time}. */
    private Task quickOf(Task parent, String name, LocalTime time) {
        Task quick = Task.loadTaskFromdb(name + "-id", name, 0.5, 3,
                null, null, null, null, MON, time);
        quick.setParentId(parent.getID());
        system.getPlacedTasks().add(quick);
        return quick;
    }

    private static Task task(String name, String id) {
        return Task.loadTaskFromdb(id, name, 1.0, 3,
                null, null, null, null, MON, java.time.LocalTime.of(7, 30));
    }
}