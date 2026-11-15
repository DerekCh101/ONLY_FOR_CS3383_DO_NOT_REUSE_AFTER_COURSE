/**
 * {@code TaskRepository.loadSqliteIntoMemory}, the function that decides what a user sees after a
 * restart.
 *
 * <p>No server needed: the rows come from a temp-dir {@code SqliteTaskRepository} and the target is
 * an in-memory {@code TaskManagementSystem}. The repository is built with
 * {@code Unsafe.allocateInstance} because its real constructor opens the app's own cache.
 *
 * <p>Two rules the class encodes, both asserted here:
 * <ul>
 *   <li>Every SQLite read filters on the signed-in user id, so the session is established
 *       file-only ({@code LocalSession} + {@code restoreSession()}); {@code session.txt} and
 *       {@code login.txt} are snapshotted and restored.</li>
 *   <li>A placed task at 00:00-05:30 belongs to the <em>previous</em> week column
 *       ({@code AppTimeReference.columnDateFor}), not to its own date.</li>
 * </ul>
 */
package org.ashoreprospect.db;

import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.Workflow;
import org.ashoreprospect.net.Api;
import org.ashoreprospect.net.Endpoints;
import org.ashoreprospect.net.LocalSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("app-clock")
class TaskRepositoryLoadTest {

    private static final String UID = "load-user";
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    @TempDir
    Path tmp;

    private TaskRepository repo;
    private SqliteTaskRepository sqlite;
    private TaskManagementSystem system;

    private Clock originalClock;
    private Path sessionFile;
    private Path prefsFile;
    private byte[] savedSession;
    private byte[] savedPrefs;

    @BeforeEach
    void setUp() throws Exception {
        originalClock = Clock.systemDefaultZone();
        // A fixed Monday morning, so lifecycle refresh has a stable "now".
        AppTimeReference.setClock(Clock.fixed(
                Instant.parse("2026-10-12T07:00:00Z"), ZoneId.of("UTC")));

        sessionFile = Endpoints.dir().resolve("session.txt");
        prefsFile = Endpoints.dir().resolve("login.txt");
        savedSession = Files.isRegularFile(sessionFile) ? Files.readAllBytes(sessionFile) : null;
        savedPrefs = Files.isRegularFile(prefsFile) ? Files.readAllBytes(prefsFile) : null;

        LocalSession.save(UID, "loader", "load-token");
        LocalSession.savePrefs(true, "loader");
        assertTrue(Api.INSTANCE.restoreSession(), "the file-only session loads");

        TaskManagementSystem.resetInstance();
        system = TaskManagementSystem.getInstance();
        system.getPendingTasks().clear();
        system.getPlacedTasks().clear();
        system.getUnscheduledTasks().clear();
        system.getGrid().clear();

        sqlite = new SqliteTaskRepository("jdbc:sqlite:" + tmp.resolve("load.db"));
        sqlite.initialize();

        repo = allocate();
        setField(repo, "sqlite", sqlite);
        setField(repo, "postgresAvailable", false);
    }

    @AfterEach
    void tearDown() throws Exception {
        Api.INSTANCE.logoutKeepRemembered();
        restore(sessionFile, savedSession);
        restore(prefsFile, savedPrefs);
        if (savedSession == null && savedPrefs == null) {
            // nothing to put back
            TaskManagementSystem.resetInstance();
        }
        AppTimeReference.setClock(originalClock);
    }

    // ---------- placement ----------

    @Test
    void placedTasksAreRestoredIntoPlacedAndOntoTheGrid() throws Exception {
        sqlite.upsert(placed("mid-morning", MON, LocalTime.of(9, 0)));

        load(true);

        assertEquals(1, system.getPlacedTasks().size());
        assertEquals("mid-morning", system.getPlacedTasks().get(0).getTaskName());
        assertNotNull(system.getGrid().get(MON), "a grid day is created for the column");
        assertTrue(system.getGrid().get(MON).returnDayGrid()[AppTimeReference
                .localTimeToSlot(LocalTime.of(9, 0))] != null, "the task occupies its slot");
    }

    @Test
    void aPlacedTaskBeforeTheGridStartsBelongsToThePreviousColumn() throws Exception {
        // 02:00 is inside the 00:00-05:30 window, which is drawn on the evening before.
        sqlite.upsert(placed("small hours", MON, LocalTime.of(2, 0)));

        load(true);

        assertNotNull(system.getGrid().get(MON.minusDays(1)),
                "the column is the previous day");
        assertFalse(system.getGrid().containsKey(MON),
                "the task's own date is not the column");
    }

    @Test
    void aQuickTaskIsPlacedButTakesNoSlot() throws Exception {
        Task quick = placed("quick note", MON, LocalTime.of(10, 0));
        quick.setParentId("some-parent");
        assertTrue(quick.isQuick(), "a task with a parent id is quick");
        sqlite.upsert(quick);
        assertEquals("some-parent", sqlite.findById(quick.getID()).getParentId(),
                "parent_id survives the round trip, so the quick branch is reachable");

        load(true);

        assertTrue(system.getPlacedTasks().stream()
                        .anyMatch(t -> "quick note".equals(t.getTaskName())),
                "a quick task is listed as placed");
        assertFalse(system.getGrid().containsKey(MON),
                "and it creates no grid column at all - a quick task takes no slot");
    }

    // ---------- deletes ----------

    @Test
    void aTombstonedTaskIsNotLoaded() throws Exception {
        Task live = placed("still here", MON, LocalTime.of(11, 0));
        Task gone = placed("deleted", MON, LocalTime.of(12, 0));
        sqlite.upsert(live);
        sqlite.upsert(gone);
        sqlite.deleteTaskAndLog(gone);

        load(true);

        assertTrue(system.getPlacedTasks().stream()
                .anyMatch(t -> "still here".equals(t.getTaskName())));
        assertFalse(system.getPlacedTasks().stream()
                        .anyMatch(t -> "deleted".equals(t.getTaskName())),
                "a tombstoned id is skipped before it reaches the grid");
    }

    // ---------- unplaced ----------

    @Test
    void anUnscheduledCapableTaskGoesToUnscheduled() throws Exception {
        Task unplaced = unplaced("parked", TaskLifeCycle.UNSCHEDULED);
        sqlite.upsert(unplaced);

        load(true);

        assertEquals(1, system.getUnscheduledTasks().size());
        assertTrue(system.getPendingTasks().isEmpty());
        assertTrue(system.getPlacedTasks().isEmpty());
    }

    @Test
    void anUnplacedTaskThatCannotGoUnscheduledGoesToPending() throws Exception {
        // PINNED: the branch is `lifeFromDb == UNSCHEDULED && task.canGoUnscheduled()`; a task
        // stored as ONGOING never reaches it, so this exercises the else arm.
        Task unplaced = unplaced("waiting", TaskLifeCycle.ONGOING);
        sqlite.upsert(unplaced);

        load(true);

        assertEquals(1, system.getPendingTasks().size());
        assertTrue(system.getUnscheduledTasks().isEmpty());
    }

    @Test
    void aPlacedTaskIsNeverAlsoPendingOrUnscheduled() throws Exception {
        sqlite.upsert(placed("only once", MON, LocalTime.of(13, 0)));

        load(true);

        assertEquals(1, system.getPlacedTasks().size());
        assertTrue(system.getPendingTasks().isEmpty());
        assertTrue(system.getUnscheduledTasks().isEmpty());
    }

    @Test
    void loadingAnEmptyStoreLeavesEveryListEmpty() throws Exception {
        load(true);

        assertTrue(system.getPlacedTasks().isEmpty());
        assertTrue(system.getPendingTasks().isEmpty());
        assertTrue(system.getUnscheduledTasks().isEmpty());
        assertTrue(system.getGrid().isEmpty());
    }

    @Test
    void anotherUsersRowsAreNotLoaded() throws Exception {
        sqlite.upsert(placed("mine", MON, LocalTime.of(9, 0)));
        Api.INSTANCE.logoutKeepRemembered();

        // With no session every read is `WHERE 1 = 0`, so nothing comes back at all.
        load(true);

        assertTrue(system.getPlacedTasks().isEmpty(),
                "rows written by one user are invisible to a signed-out load");
    }

    // ---------- wiring ----------

    private void load(boolean persistLifecycle) throws Exception {
        Method m = TaskRepository.class.getDeclaredMethod("loadSqliteIntoMemory",
                TaskManagementSystem.class, boolean.class);
        m.setAccessible(true);
        try {
            m.invoke(repo, system, persistLifecycle);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private static Task placed(String name, LocalDate date, LocalTime time) {
        // loadTaskFromdb(id, name, hours, state, startDate, startTime, deadlineDate,
        // deadlineTime, placedDate, placedTime) - placement is the last pair.
        Task t = Task.loadTaskFromdb("placed-" + name, name, 1.0, 3, null, null,
                null, null, date, time);
        t.setLifeCycle(TaskLifeCycle.ONGOING);
        t.setWorkflow(Workflow.UNFINISHED);
        return t;
    }

    private static Task unplaced(String name, TaskLifeCycle life) {
        Task t = Task.loadTaskFromdb("unplaced-" + name, name, 1.0, 3, null, null,
                null, null, null, null);
        t.setLifeCycle(life);
        t.setWorkflow(Workflow.UNFINISHED);
        return t;
    }

    private static void restore(Path file, byte[] content) throws Exception {
        if (content == null) {
            Files.deleteIfExists(file);
        } else {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        }
    }

    private static TaskRepository allocate() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
        return (TaskRepository) unsafe.allocateInstance(TaskRepository.class);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = TaskRepository.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Set<String> none() {
        return Set.of();
    }
}
