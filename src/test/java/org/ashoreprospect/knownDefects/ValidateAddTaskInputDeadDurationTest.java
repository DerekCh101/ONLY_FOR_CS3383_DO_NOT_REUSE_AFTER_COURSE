package org.ashoreprospect.knownDefects;

import java.io.FileNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.ValidateAddTaskInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins validation and file-import defects.
 *
 * <p>See {@code KNOWN_DEFECTS.md} in this package.
 */
@ResourceLock("app-clock")
class ValidateAddTaskInputDeadDurationTest {

    private Clock originalClock;

    @BeforeEach
    void setUp() {
        originalClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(Clock.fixed(
                Instant.parse("2026-10-12T09:00:00Z"), ZoneId.of("UTC")));
    }

    @AfterEach
    void tearDown() {
        AppTimeReference.setClock(originalClock);
    }

    @Test
    void durationAbove24HoursIsStillAccepted() {
        // DEAD CODE: the `hoursToSlots(duration) > SLOTS_PER_DAY` guard in
        // ValidateAddTaskInput can never fire, because hoursToSlots() delegates to
        // hoursToMinutes(), which already clamps to 1440 minutes. Oversized durations are
        // silently truncated to 24 h instead of being rejected.
        assertTrue(ValidateAddTaskInput.validateAddTaskInput(
                "a", 25, 3, null, null, null, null),
                "PINNED: 25 h passes validation");
        assertTrue(ValidateAddTaskInput.validateAddTaskInput(
                "a", 1000, 3, null, null, null, null),
                "PINNED: 1000 h passes validation");
        assertEquals(AppTimeReference.SLOTS_PER_DAY, AppTimeReference.hoursToSlots(1000),
                "PINNED: and is silently clamped to one day");
    }

    @Test
    void fileImportParserCannotProduceBothStartAndDeadline() throws Exception {
        // UNREACHABLE RULE: README and ADVANCE_BEHAVIOUR.md §8 both say "a file import row
        // that has both a deadline and a fixed start is skipped". The parser reads a start
        // date/time only when state == 1 and a deadline date/time only when state == 2, so a
        // single row can never carry both and the rule can never trigger.
        //
        // The parser also hard-codes taskInput1.txt, so this is proven by reading the bundled
        // file through AddTaskWithFile and checking no imported task has both fields set.
        TaskManagementSystem.resetInstance();
        TaskManagementSystem system = TaskManagementSystem.getInstance();
        try {
            org.ashoreprospect.Command.AddTaskWithFile command =
                    new org.ashoreprospect.Command.AddTaskWithFile(system);
            command.execute();

            java.util.List<org.ashoreprospect.Task> all = new java.util.ArrayList<>();
            all.addAll(system.getPlacedTasks());
            all.addAll(system.getPendingTasks());
            all.addAll(system.getUnscheduledTasks());

            assertFalse(all.isEmpty(), "the bundled fixture should import something");
            for (org.ashoreprospect.Task t : all) {
                boolean hasStart = t.getStartingDate() != null || t.getStartingTime() != null;
                boolean hasDeadline = t.getDeadLineDate() != null || t.getDeadLineTime() != null;
                assertFalse(hasStart && hasDeadline,
                        "PINNED: no import row can ever carry both a start and a deadline ("
                                + t.getTaskName() + ")");
            }
        } catch (FileNotFoundException e) {
            throw new AssertionError("bundled taskInput1.txt is missing", e);
        } finally {
            TaskManagementSystem.resetInstance();
        }
    }
}
