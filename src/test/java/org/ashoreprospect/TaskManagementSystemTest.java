/** Singleton wiring: mainTask/quicksOf lookups, schedule rebuild, and popup storage. */
package org.ashoreprospect;

import org.ashoreprospect.db.SqliteTaskRepository;
import org.ashoreprospect.db.TaskRepository;
import org.ashoreprospect.net.Api;
import org.ashoreprospect.net.Endpoints;
import org.ashoreprospect.net.LocalSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
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
@ResourceLock("node-session")
class TaskManagementSystemTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final Clock TEST_CLOCK = Clock.fixed(
            Instant.parse("2026-10-12T07:00:00Z"), ZoneId.of("UTC"));
    private static final String SIGNED_IN_UID = "tms-test-user";

    private TaskManagementSystem system;
    private Clock savedClock;
    private Path sessionFile;
    private Path prefsFile;
    private byte[] savedSession;
    private byte[] savedPrefs;
    private boolean signedIn;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() throws Exception {
        savedClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(TEST_CLOCK);

        Path dir = Endpoints.dir();
        sessionFile = dir.resolve("session.txt");
        prefsFile = dir.resolve("login.txt");
        savedSession = Files.isRegularFile(sessionFile) ? Files.readAllBytes(sessionFile) : null;
        savedPrefs = Files.isRegularFile(prefsFile) ? Files.readAllBytes(prefsFile) : null;

        TaskManagementSystem.resetInstance();
        system = TaskManagementSystem.getInstance();
        clear();
    }

    /**
     * A file-only remember-me session, plus a repository pointed at a temp database.
     *
     * <p>Signing in is needed because rewriteEncryptedStores() is only reached from the account
     * pane, and a blocked write with no session is refused since #1. The repository must also be
     * swapped, though: TaskRepository's own constructor opens the real scheduler-cache.db, so a
     * session would otherwise make this test read the developer's data and collide with it.
     */
    private void signInWithIsolatedDatabase() throws Exception {
        LocalSession.save(SIGNED_IN_UID, "tester", "file-only-token");
        LocalSession.savePrefs(true, "tester");
        assertTrue(Api.INSTANCE.restoreSession(), "the file-only session loads");

        SqliteTaskRepository sqlite = new SqliteTaskRepository("jdbc:sqlite:" + tmp.resolve("tms.db"));
        sqlite.initialize();
        Field sqliteField = TaskRepository.class.getDeclaredField("sqlite");
        sqliteField.setAccessible(true);
        sqliteField.set(system.getTaskRepo(), sqlite);
        signedIn = true;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (system != null) {
            clear();
        }
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(savedClock);
        if (signedIn) {
            Api.INSTANCE.logoutKeepRemembered();
            restore(sessionFile, savedSession);
            restore(prefsFile, savedPrefs);
        }
    }

    private static void restore(Path file, byte[] content) throws Exception {
        if (content == null) Files.deleteIfExists(file);
        else Files.write(file, content);
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

    // ---------------------------------------------------------------- command error arms

    /**
     * Runs {@code name} through {@code TaskManagementSystem.command} after adding it to the
     * command table, capturing stdout and stderr. The table is put back to exactly what it was,
     * so a test action can never leak into another test.
     */
    private String[] commandCapturingOutput(String name,
                                            org.ashoreprospect.Command.SystemCommands.Action body) {
        java.io.PrintStream originalOut = System.out;
        java.io.PrintStream originalErr = System.err;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8));
            org.ashoreprospect.Command.SystemCommands.put(name, body);
            system.command(name);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            org.ashoreprospect.Command.SystemCommands.put(name, sys -> {
                throw new IllegalStateException("test action " + name + " leaked out of its test");
            });
        }
        return new String[]{out.toString(java.nio.charset.StandardCharsets.UTF_8),
                err.toString(java.nio.charset.StandardCharsets.UTF_8)};
    }

    @Test
    void commandReportsADatabaseErrorAndReturnsFalse() {
        String[] io = commandCapturingOutput("testDbFailure", sys -> {
            throw new java.sql.SQLException("disk full");
        });

        assertTrue(io[0].contains("Database error: disk full"),
                "the SQLException arm logs to stdout: " + io[0]);
    }

    @Test
    void commandReportsAnyOtherFailureAndReturnsFalse() {
        String[] io = commandCapturingOutput("testOtherFailure", sys -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(io[0].contains("Command failed: boom"),
                "the generic arm uses a different prefix: " + io[0]);
    }

    @Test
    void commandCatchesACheckedFailureToo() {
        // FileNotFoundException is what addTaskFromFile surfaces when the file is missing.
        String[] io = commandCapturingOutput("testCheckedFailure", sys -> {
            throw new java.io.FileNotFoundException("no such file");
        });

        assertTrue(io[0].contains("Command failed: no such file"), io[0]);
    }

    @Test
    void commandSwallowsTheFailureRatherThanReturningTrue() {
        // The return value is the thing a caller acts on; an exception must not look like success.
        assertFalse(runWithAction("testDeclines", sys -> {
            throw new RuntimeException("nope");
        }));
        assertTrue(runWithAction("testSucceeds", sys -> true));
    }

    private boolean runWithAction(String name, org.ashoreprospect.Command.SystemCommands.Action body) {
        java.io.PrintStream originalOut = System.out;
        java.io.PrintStream originalErr = System.err;
        try {
            System.setOut(new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true,
                    java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true,
                    java.nio.charset.StandardCharsets.UTF_8));
            org.ashoreprospect.Command.SystemCommands.put(name, body);
            return system.command(name);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            org.ashoreprospect.Command.SystemCommands.put(name, sys -> {
                throw new IllegalStateException("test action " + name + " leaked out of its test");
            });
        }
    }

    @Test
    void rewriteEncryptedStores_resavesEveryTaskAndBlockedRowAndAdvancesTheirTimestamps()
            throws Exception {
        // The real callers sit in the account pane, which only exists with a session.
        signInWithIsolatedDatabase();
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

    // ---------- stage 3: branch hardening ----------

    @Test
    void mainTask_returnsNullAndUnknownForABlankParentId() throws Exception {
        // L95: `pid == null || pid.isBlank()` - a task whose parentId is blank is not quick,
        // so mainTask() returns it unchanged without ever looking it up.
        Task blank = task("blank-parent", 1.0, 3);
        blank.setParentId("   ");
        assertFalse(blank.isQuick());
        assertSame(blank, system.mainTask(blank));
    }

    @Test
    void quicksOf_returnsEmptyForANonQuickParentWithNoId() {
        // L104: `parent.getID() == null` short-circuits before the placed scan. The id is
        // final, so a null id has to come from the DB loader.
        Task parent = Task.loadTaskFromdb(null, "no-id", 1.0, 3,
                null, null, null, null, null, null);
        assertNull(parent.getID());
        assertTrue(system.quicksOf(parent).isEmpty());
    }

    @Test
    void quicksOf_skipsATaskWhoseParentIdIsBlank() {
        // L107: only tasks whose parentId actually equals the parent's id are collected, so
        // an ordinary placed task alongside a real quick is ignored.
        Task parent = task("parent", 1.0, 3);
        system.getPlacedTasks().add(parent);
        Task quick = quickOf(parent, "quick");
        Task plain = task("plain", 0.5, 3);
        system.getPlacedTasks().add(quick);
        system.getPlacedTasks().add(plain);

        List<Task> found = system.quicksOf(parent);

        assertEquals(1, found.size());
        assertSame(quick, found.get(0));
    }

    @Test
    void findById_returnsNullForANullListOrANullId() {
        // L121: the guard against either side being absent. findById is private, so it is
        // driven through mainTask, which passes the three system lists in turn.
        Task quick = task("quick", 0.5, 3);
        quick.setParentId("  ");
        assertSame(quick, system.mainTask(quick), "a blank id is not resolved");

        Task quickWithId = task("quick-id", 0.5, 3);
        quickWithId.setParentId("never-stored");
        assertSame(quickWithId, system.mainTask(quickWithId),
                "an unknown id falls back to the quick itself");
    }

    @Test
    void findById_skipsNullEntriesAndMatchesOnId() {
        // L123: a null entry in the list is skipped rather than dereferenced, and a quick
        // whose parent id matches resolves to that parent.
        Task parent = task("parent", 0.5, 3);
        system.getPlacedTasks().add(null);
        system.getPlacedTasks().add(parent);
        Task quick = quickOf(parent, "quick");
        assertTrue(quick.isQuick());

        assertSame(parent, system.mainTask(quick));
    }

    @Test
    void rewriteEncryptedStores_skipsNullTaskEntries() throws Exception {
        // L152: a null in any of the three task lists is skipped, not dereferenced, and the
        // null entry is preserved rather than silently removed.
        Task real = task("real", 0.5, 3);
        system.getPlacedTasks().add(null);
        system.getPlacedTasks().add(real);
        system.getUnscheduledTasks().add(null);

        system.rewriteEncryptedStores();

        assertEquals(2, system.getPlacedTasks().size(),
                "the null entry survives, it is only skipped");
        assertSame(real, system.getPlacedTasks().get(1));
        assertEquals(1, system.getUnscheduledTasks().size(),
                "the null in the unscheduled list survives too");
    }

    @Test
    void rewriteEncryptedStores_nullBlockedGuardIsUnreachableBecauseRebuildCrashesFirst()
            throws Exception {
        // BUG (found in stage 3): rewriteEncryptedStores() has `if (b == null) continue;`
        // at L159, but that guard can never run. Putting a null into blockedPeriod goes
        // through replaceBlockedPeriods(), which calls rebuildPeriods(); that loop does
        // `b.isDaily()` with no null check and throws first. So the rewrite's null handling
        // for blocked rows is dead, and a null blocked row is a crash either way.
        NullPointerException thrown =
                org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                        () -> system.getBlockedTime().replaceBlockedPeriods(
                                new ArrayList<>(java.util.Arrays.asList(
                                        (BlockedTime) null,
                                        BlockedTime.daily("custom",
                                                LocalTime.of(12, 0), LocalTime.of(13, 0))))));
        assertTrue(thrown.getMessage().contains("isDaily"),
                "PINNED: the crash is in rebuildPeriods(), before rewriteEncryptedStores: "
                + thrown.getMessage());
    }

    @Test
    void rewriteEncryptedStores_copesWithNoBlockedPeriodsAtAll() throws Exception {
        // L157: the outer `blockedTime != null && blockedPeriod != null` guard is already
        // satisfied with an empty list; the loop simply never runs.
        system.getBlockedTime().replaceBlockedPeriods(new ArrayList<>());
        system.rewriteEncryptedStores();
        assertTrue(system.getBlockedTime().blockedPeriod.isEmpty());
    }

    @Test
    void startNodePoll_isNotRestartedWhenAlreadyRunning() throws Exception {
        // L168: the re-entrancy guard. resetInstance() flips pollRunning to false, so this
        // pins the live flag rather than guessing at the thread.
        java.lang.reflect.Field running =
                TaskManagementSystem.class.getDeclaredField("pollRunning");
        running.setAccessible(true);
        assertTrue((boolean) running.get(system),
                "the constructor starts the poll thread");

        java.lang.reflect.Method start =
                TaskManagementSystem.class.getDeclaredMethod("startNodePoll");
        start.setAccessible(true);
        start.invoke(system);

        assertTrue((boolean) running.get(system), "a second call leaves it running");
    }

    @Test
    void attachGUI_nullControllerIsAllowedAndLeavesTheSystemHeadless() {
        // L252/L258: both guards are `guiController != null`. Attaching nothing keeps
        // refreshGUI()/applyIdleLimit() as no-ops that never touch the JavaFX toolkit.
        system.attachGUI(null);
        system.refreshGUI();
        system.applyIdleLimit();
        assertNull(system.getPopupTask(), "no GUI wiring implies no popup either");
    }

    @Test
    void updateSchedule_skipsNullQuickAndFixedEntriesInEveryList() {
        // L217/L222: the three-way filter. Null entries are dropped, quick and fixed tasks
        // are never handed to the engine, and a flexible task is scheduled once.
        Task fixed = task("fixed", 1.0, 1);
        Task parent = task("parent", 1.0, 3);
        Task quick = quickOf(parent, "quick");
        Task flexible = task("flexible", 0.5, 3);
        system.getPlacedTasks().add(null);
        system.getPlacedTasks().add(fixed);
        system.getPendingTasks().add(null);
        system.getPendingTasks().add(quick);
        system.getUnscheduledTasks().add(null);
        system.getUnscheduledTasks().add(flexible);
        system.getUnscheduledTasks().add(flexible);

        system.updateSchedule();

        assertFalse(system.getPendingTasks().contains(null),
                "null entries are filtered out of the pending list");
        assertEquals(1, system.getPlacedTasks().stream().filter(t -> t == flexible).count(),
                "a duplicated flexible task is scheduled exactly once");
        assertFalse(system.getPlacedTasks().contains(quick),
                "quick tasks are not handed to the engine");
        // PINNED: a state-1 task is filtered out by updateSchedule() but never re-added, so
        // it survives in whatever list it started in. It is not auto-placed either.
        assertEquals(1, system.getPlacedTasks().stream().filter(t -> t == fixed).count(),
                "the fixed task stays listed exactly once");
    }
}
