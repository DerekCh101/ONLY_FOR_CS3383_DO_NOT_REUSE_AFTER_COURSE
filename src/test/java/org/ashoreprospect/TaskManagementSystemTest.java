/** Singleton wiring: mainTask/quicksOf lookups, schedule rebuild, and popup storage. */
package org.ashoreprospect;

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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code getInstance()} opens the real SQLite cache and starts a daemon poll thread, so
 * every test resets the singleton and clears its lists/grid afterwards.
 */
@ResourceLock("app-clock")
class TaskManagementSystemTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final Clock TEST_CLOCK = Clock.fixed(
            Instant.parse("2026-10-12T07:00:00Z"), ZoneId.of("UTC"));

    private TaskManagementSystem system;
    private Clock savedClock;

    @BeforeEach
    void setUp() {
        savedClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(TEST_CLOCK);
        TaskManagementSystem.resetInstance();
        system = TaskManagementSystem.getInstance();
        clear();
    }

    @AfterEach
    void tearDown() {
        if (system != null) {
            clear();
        }
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(savedClock);
    }

    private void clear() {
        system.getPendingTasks().clear();
        system.getPlacedTasks().clear();
        system.getUnscheduledTasks().clear();
        system.getGrid().clear();
    }

    private static Task task(String name, double hours, int state) {
        return new Task(name, hours, state, null, null, null, null);
    }

    private static Task quickOf(Task parent, String name) {
        Task quick = task(name, 0.5, 3);
        quick.setParentId(parent.getID());
        return quick;
    }

    // ---------- singleton ----------

    @Test
    void getInstance_returnsTheSameInstanceUntilReset() {
        assertSame(system, TaskManagementSystem.getInstance());

        TaskManagementSystem.resetInstance();
        TaskManagementSystem fresh = TaskManagementSystem.getInstance();

        assertNotSame(system, fresh);
        assertNotNull(fresh.getEngine());
        assertNotNull(fresh.getTaskRepo());
        assertNotNull(fresh.getBlockedTime());
        assertNotNull(fresh.getGrid());
        system = fresh;
    }

    @Test
    void aFreshSystem_startsWithTheBaseDateAsTheCurrentColumn() {
        TaskManagementSystem.resetInstance();
        TaskManagementSystem fresh = TaskManagementSystem.getInstance();
        system = fresh;

        // The clock is 07:00, which belongs to the 12-10-2026 column.
        assertEquals(MON, AppTimeReference.currentColumnDate());
        fresh.updateSchedule();
        assertTrue(fresh.getPendingTasks().isEmpty());
        assertTrue(fresh.getPlacedTasks().isEmpty());
        assertTrue(fresh.getUnscheduledTasks().isEmpty());
    }

    // ---------- mainTask ----------

    @Test
    void mainTask_returnsTheArgumentForNullAndNonQuickTasks() {
        Task parent = task("parent", 1.0, 3);

        assertNull(system.mainTask(null));
        assertSame(parent, system.mainTask(parent));
    }

    @Test
    void mainTask_findsTheParentInAnyOfTheThreeLists() {
        Task placed = task("placed", 1.0, 3);
        Task pending = task("pending", 1.0, 3);
        Task unscheduled = task("unscheduled", 1.0, 3);
        system.getPlacedTasks().add(placed);
        system.getPendingTasks().add(pending);
        system.getUnscheduledTasks().add(unscheduled);

        assertSame(placed, system.mainTask(quickOf(placed, "q1")));
        assertSame(pending, system.mainTask(quickOf(pending, "q2")));
        assertSame(unscheduled, system.mainTask(quickOf(unscheduled, "q3")));
    }

    @Test
    void mainTask_returnsTheQuickItselfWhenTheParentIsGone() {
        Task orphan = quickOf(task("missing-parent", 1.0, 3), "orphan");

        assertSame(orphan, system.mainTask(orphan));
    }

    @Test
    void mainTask_returnsAQuickWhoseParentIsItselfQuick() {
        // A quick task can never be a parent: mainTask() returns it unchanged.
        Task grandparent = task("grandparent", 1.0, 3);
        Task child = quickOf(grandparent, "child");
        Task grandchild = quickOf(child, "grandchild");
        system.getPlacedTasks().add(child);

        assertSame(child, system.mainTask(child));
        assertSame(child, system.mainTask(grandchild));
    }

    // ---------- quicksOf ----------

    @Test
    void quicksOf_isEmptyForNullQuickAndUnknownParents() {
        Task parent = task("parent", 1.0, 3);
        Task quick = quickOf(parent, "quick");
        system.getPlacedTasks().add(quick);

        assertTrue(system.quicksOf(null).isEmpty());
        assertTrue(system.quicksOf(quick).isEmpty());
        // The parent does not need to be in a list itself; only the quick's parentId
        // has to match.
        assertEquals(List.of(quick), system.quicksOf(parent));
    }

    @Test
    void quicksOf_sortsByPlacedTimeWithUntimedLast() {
        Task parent = task("parent", 1.0, 3);
        system.getPlacedTasks().add(parent);

        Task late = quickOf(parent, "late");
        late.setPlacedTime(LocalTime.of(14, 0));
        Task early = quickOf(parent, "early");
        early.setPlacedTime(LocalTime.of(8, 0));
        Task untimed = quickOf(parent, "untimed");
        Task alsoUntimed = quickOf(parent, "also-untimed");
        Task other = quickOf(task("other", 1.0, 3), "other");

        system.getPlacedTasks().addAll(List.of(late, untimed, early, alsoUntimed, other));

        List<Task> quicks = system.quicksOf(parent);

        assertEquals(4, quicks.size());
        assertEquals("early", quicks.get(0).getTaskName());
        assertEquals("late", quicks.get(1).getTaskName());
        assertEquals("untimed", quicks.get(2).getTaskName());
        assertEquals("also-untimed", quicks.get(3).getTaskName());
        assertFalse(quicks.contains(other));
    }

    @Test
    void quicksOf_onlyLooksAtPlacedTasks() {
        Task parent = task("parent", 1.0, 3);
        Task quick = quickOf(parent, "quick");
        system.getPendingTasks().add(quick);

        assertTrue(system.quicksOf(parent).isEmpty());

        system.getPendingTasks().remove(quick);
        system.getPlacedTasks().add(quick);
        assertEquals(List.of(quick), system.quicksOf(parent));
    }

    // ---------- updateSchedule ----------

    @Test
    void updateSchedule_neverMovesFixedOrQuickTasks() {
        Task fixed = new Task("fixed", 1.0, 1, MON, LocalTime.of(9, 0), null, null);
        fixed.setPlacedDate(MON);
        fixed.setPlacedTime(LocalTime.of(9, 0));
        Day day = new Day(system.getBlockedTime());
        day.placeTask(AppTimeReference.localTimeToSlot(LocalTime.of(9, 0)), fixed);
        system.getGrid().put(MON, day);
        system.getPlacedTasks().add(fixed);

        Task parent = task("parent", 1.0, 3);
        Task quick = quickOf(parent, "quick");
        quick.setPlacedDate(MON);
        quick.setPlacedTime(LocalTime.of(11, 0));
        system.getPlacedTasks().addAll(List.of(parent, quick));

        system.updateSchedule();

        assertEquals(LocalTime.of(9, 0), fixed.getPlacedTime());
        assertEquals(LocalTime.of(11, 0), quick.getPlacedTime());
        assertSame(fixed, system.getGrid().get(MON)
                .returnDayGrid()[AppTimeReference.localTimeToSlot(LocalTime.of(9, 0))]);
        // The flexible parent was placed by the engine instead of being dropped.
        assertTrue(system.getPlacedTasks().contains(parent));
        assertNotNull(parent.getPlacedDate());
    }

    @Test
    void updateSchedule_rebuildsFromScratchEachTime() {
        Task flexible = task("flexible", 1.0, 3);
        system.getPendingTasks().add(flexible);

        system.updateSchedule();
        LocalDate firstDate = flexible.getPlacedDate();
        LocalTime firstTime = flexible.getPlacedTime();
        assertNotNull(firstDate);
        assertNotNull(firstTime);

        // Clearing all lists and re-adding the task places it in the same slot because
        // updateSchedule() schedules onto a freshly emptied grid.
        system.getPlacedTasks().clear();
        system.getPendingTasks().add(flexible);
        system.getGrid().clear();
        system.updateSchedule();

        assertEquals(firstDate, flexible.getPlacedDate());
        assertEquals(firstTime, flexible.getPlacedTime());
    }

    @Test
    void updateSchedule_keepsNullEntriesInThePlacedList() {
        system.getPendingTasks().addAll(new ArrayList<>(java.util.Arrays.asList(null, null)));
        system.getPlacedTasks().add(null);

        system.updateSchedule();

        // PINNED: updateSchedule() never rebuilds placedTasks; it only removes the tasks
        // it decided to reschedule, so a null element survives the pass untouched. The
        // pending nulls are not rescheduled either and are left where they are.
        assertEquals(1, system.getPlacedTasks().size());
        assertNull(system.getPlacedTasks().get(0));
        assertEquals(2, system.getPendingTasks().size());
    }

    // ---------------------------------------------------------------- AC: command + stores

    @Test
    void commandWithAnUnknownNameReturnsFalseWithoutThrowing() {
        // SystemCommands.run prints one line and returns false for an unregistered name.
        java.io.PrintStream originalOut = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        boolean result;
        try {
            System.setOut(new java.io.PrintStream(captured, true,
                    java.nio.charset.StandardCharsets.UTF_8));
            result = system.command("notACommand");
        } finally {
            System.setOut(originalOut);
        }

        assertFalse(result);
        assertEquals("Invalid Command." + System.lineSeparator(),
                captured.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void rewriteEncryptedStores_resavesEveryTaskAndBlockedRowAndAdvancesTheirTimestamps()
            throws Exception {
        Task placed = task("placed", 1.0, 3);
        placed.setPlacedDate(MON);
        placed.setPlacedTime(LocalTime.of(9, 0));
        Task unscheduled = task("unscheduled", 1.0, 3);
        Task pending = task("pending", 1.0, 3);
        system.getPlacedTasks().add(placed);
        system.getUnscheduledTasks().add(unscheduled);
        system.getPendingTasks().add(pending);

        org.ashoreprospect.BlockedTime blocked = org.ashoreprospect.BlockedTime.daily(
                "lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        system.getBlockedTime().blockedPeriod.add(blocked);

        // The store is re-saved, not replaced: list sizes must survive the pass.
        int placedCount = system.getPlacedTasks().size();
        int unscheduledCount = system.getUnscheduledTasks().size();
        int pendingCount = system.getPendingTasks().size();
        int blockedCount = system.getBlockedTime().blockedPeriod.size();

        // Stamped well in the past so "advanced" cannot be a clock-resolution artefact.
        Instant then = Instant.parse("2000-01-01T00:00:00Z");
        for (Task t : List.of(placed, unscheduled, pending)) t.setLastUpdatedAt(then);
        blocked.setLastUpdatedAt(then);

        system.rewriteEncryptedStores();

        assertEquals(placedCount, system.getPlacedTasks().size());
        assertEquals(unscheduledCount, system.getUnscheduledTasks().size());
        assertEquals(pendingCount, system.getPendingTasks().size());
        assertEquals(blockedCount, system.getBlockedTime().blockedPeriod.size());
        for (Task t : List.of(placed, unscheduled, pending)) {
            assertTrue(t.getLastUpdatedAt().isAfter(then), t.getTaskName() + " stamped");
        }
        assertTrue(blocked.getLastUpdatedAt().isAfter(then), "blocked row stamped");
        // PINNED: all rows share one `now`, so the four stamps are identical.
        assertEquals(placed.getLastUpdatedAt(), pending.getLastUpdatedAt());
        assertEquals(placed.getLastUpdatedAt(), blocked.getLastUpdatedAt());
    }

    // ---------- popup / misc ----------

    @Test
    void popupTask_roundTripsAndNullClearsIt() {
        assertNull(system.getPopupTask());

        FetchPopOutTask popup = new FetchPopOutTask(task("popup", 1.0, 3));
        assertSame(popup, system.fetchPopupTask(popup));
        assertSame(popup, system.getPopupTask());

        system.fetchPopupTask(null);
        assertNull(system.getPopupTask());
    }

    @Test
    void guiAndCliAccessors_areHeadlessSafe() {
        // No controller has been attached, so refreshGUI()/applyIdleLimit() are no-ops
        // that must not touch the JavaFX toolkit.
        system.refreshGUI();
        system.applyIdleLimit();

        assertFalse(system.getCliFlag());
        assertNotNull(system.getCliMode());
        assertNotSame(system.getCliMode(), system.getCliMode());
    }
}
