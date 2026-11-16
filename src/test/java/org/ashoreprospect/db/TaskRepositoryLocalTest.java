/**
 * TaskRepository's purely local surface: the cache wipe, the SQLite-only clean/clear/gc paths,
 * the device id, and the timestamp comparison helpers.
 *
 * <p>These are the methods that touch neither Node nor Postgres, so they can be exercised with a
 * real SQLite cache in {@code @TempDir} and nothing else. The repository itself is allocated
 * without running its constructor — which would open the app's own {@code scheduler-cache.db} and
 * probe Postgres — and is then handed the temp-dir store, the same technique
 * {@code TaskRepositoryNodeMergeTest} uses.
 *
 * <p>Signed in where a Node push would otherwise be attempted: the methods here still call
 * {@code nodeDeleteBlocked()} / {@code nodeDeleteTask()} on their way out, and with a blank token
 * {@code nodeReady()} short-circuits to false, which is the state these tests want. The blocked
 * clear path additionally refuses to run without a session (see issue #1), so it is signed in with
 * a file-only session that no live server can answer.
 */
package org.ashoreprospect.db;

import org.ashoreprospect.BlockedTime;
import org.ashoreprospect.Task;
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
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("node-session")
class TaskRepositoryLocalTest {

    private static final String UID = "local-user";
    private static final Instant NOW = Instant.parse("2026-10-12T07:00:00Z");
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    @TempDir
    Path tmp;

    private TaskRepository repo;
    private SqliteTaskRepository sqlite;
    private Path sessionFile;
    private byte[] savedSession;

    // ---------- wiring ----------

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Endpoints.dir();
        sessionFile = dir.resolve("session.txt");
        savedSession = Files.isRegularFile(sessionFile) ? Files.readAllBytes(sessionFile) : null;

        LocalSession.save(UID, "local", "file-only-token");
        assertTrue(Api.INSTANCE.restoreSession(), "the file-only session loads");

        sqlite = new SqliteTaskRepository("jdbc:sqlite:" + tmp.resolve("local.db"));
        sqlite.initialize();

        repo = allocate();
        setField(repo, "sqlite", sqlite);
        // allocateInstance() skips the constructor, so the fields it would have built are null.
        setField(repo, "deviceId", "test-device");
        setField(repo, "historyEpoch", new AtomicLong());
        setField(repo, "historyLock", new ReentrantLock());
    }

    @AfterEach
    void tearDown() throws Exception {
        Api.INSTANCE.logoutKeepRemembered();
        if (savedSession == null) {
            Files.deleteIfExists(sessionFile);
        } else {
            Files.createDirectories(sessionFile.getParent());
            Files.write(sessionFile, savedSession);
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

    private static Task task(String id, String name) {
        Task t = Task.loadTaskFromdb(id, name, 1.0, 3, null, null, null, null, MON, LocalTime.of(9, 0));
        t.setLastUpdatedAt(NOW);
        return t;
    }

    private void store(Task t) throws SQLException {
        sqlite.upsert(t);
    }

    private List<Task> stored() throws SQLException {
        return sqlite.fetchAll();
    }

    // ---------- getDeviceId ----------

    @Test
    void getDeviceIdReturnsTheConfiguredId() {
        assertEquals("test-device", repo.getDeviceId());
    }

    @Test
    void deviceConfigAlwaysYieldsAnId() {
        assertFalse(DeviceConfig.getOrCreateDeviceId().isBlank(),
                "DeviceConfig always yields an id, even when it cannot persist one");
    }

    // ---------- wipeLocalCacheOnly ----------

    @Test
    void wipeLocalCacheOnlyEmptiesEveryLocalTable() throws Exception {
        store(task("t-1", "write the report"));
        sqlite.upsertBlocked(BlockedTime.once("offsite", MON, LocalTime.of(9, 0), LocalTime.of(10, 0)));
        sqlite.setMeta("user_id", UID);
        sqlite.setMeta("last_node_sync_at", "2026-10-12T00:00:00Z");

        repo.wipeLocalCacheOnly();

        assertTrue(stored().isEmpty(), "tasks are gone");
        assertTrue(sqlite.fetchAllBlocked().isEmpty(), "blocked rows are gone");
        assertEquals("", sqlite.getMeta("user_id"), "the cache owner is blanked, not deleted");
        assertEquals("", sqlite.getMeta("last_node_sync_at"), "the sync stamp is blanked");
    }

    @Test
    void wipeLocalCacheOnlySucceedsOnAnAlreadyEmptyCache() throws SQLException {
        repo.wipeLocalCacheOnly();

        assertTrue(stored().isEmpty());
    }

    @Test
    void wipeLocalCacheOnlySwallowsAStoreFailure() throws Exception {
        // Point the repository at a database that cannot be opened (its parent is a regular file,
        // not a directory). wipeAllLocalData() then throws and the method must log and carry on
        // rather than propagate — the typewriter wipe that follows still has to run.
        Path blocker = tmp.resolve("not-a-directory");
        Files.writeString(blocker, "x");
        setField(repo, "sqlite", new SqliteTaskRepository("jdbc:sqlite:" + blocker.resolve("db.sqlite")));

        repo.wipeLocalCacheOnly();
    }

    // ---------- dbCleanAll ----------

    @Test
    void dbCleanAllRemovesEveryTask() throws Exception {
        store(task("t-1", "one"));
        store(task("t-2", "two"));
        assertEquals(2, stored().size());

        repo.dbCleanAll();

        assertTrue(stored().isEmpty(), "every task row is deleted");
    }

    @Test
    void dbCleanAllOnAnEmptyCacheIsANoop() throws SQLException {
        repo.dbCleanAll();

        assertTrue(stored().isEmpty());
    }

    // ---------- isPostgresAvailable ----------

    @Test
    void isPostgresAvailableReflectsTheStoredFlag() throws Exception {
        assertFalse(repo.isPostgresAvailable(), "allocateInstance leaves the flag false");

        setField(repo, "postgresAvailable", true);

        assertTrue(repo.isPostgresAvailable());
    }

    // ---------- guessName ----------

    @Test
    void guessNameAlwaysReturnsSomething() throws Exception {
        java.lang.reflect.Method m = TaskRepository.class.getDeclaredMethod("guessName");
        m.setAccessible(true);

        String name = (String) m.invoke(repo);

        org.junit.jupiter.api.Assertions.assertNotNull(name);
        assertFalse(name.isBlank(), "falls back to \"device\" when the host name is unavailable");
    }

    // ---------- dbClearBlocked ----------

    @Test
    void dbClearBlockedRemovesTheRow() throws SQLException {
        BlockedTime block = BlockedTime.once("lunch", MON, LocalTime.of(12, 0), LocalTime.of(13, 0));
        sqlite.upsertBlocked(block);
        assertEquals(1, sqlite.fetchAllBlocked().size());

        repo.dbClearBlocked(block);

        assertTrue(sqlite.fetchAllBlocked().isEmpty(), "the row is gone");
    }

    @Test
    void dbClearBlockedIgnoresANullBlock() throws SQLException {
        BlockedTime block = BlockedTime.once("lunch", MON, LocalTime.of(12, 0), LocalTime.of(13, 0));
        sqlite.upsertBlocked(block);

        repo.dbClearBlocked(null);

        assertEquals(1, sqlite.fetchAllBlocked().size(), "nothing was touched");
    }

    @Test
    void dbClearBlockedIgnoresABlockWithNoId() throws SQLException {
        BlockedTime block = BlockedTime.once("lunch", MON, LocalTime.of(12, 0), LocalTime.of(13, 0));
        sqlite.upsertBlocked(block);
        BlockedTime noId = new BlockedTime(null, "ghost", null, MON,
                LocalTime.of(12, 0), LocalTime.of(13, 0), NOW, null);

        repo.dbClearBlocked(noId);

        assertEquals(1, sqlite.fetchAllBlocked().size(), "nothing was touched");
    }

    @Test
    void dbClearBlockedRefusesWhenSignedOut() throws Exception {
        BlockedTime block = BlockedTime.once("lunch", MON, LocalTime.of(12, 0), LocalTime.of(13, 0));
        sqlite.upsertBlocked(block);
        Api.INSTANCE.logoutKeepRemembered();

        SQLException e = assertThrows(SQLException.class, () -> repo.dbClearBlocked(block));

        assertTrue(e.getMessage().startsWith("Signed out:"), "got: " + e.getMessage());
        // The refusal happens before the store is touched, so the row is never deleted. It is only
        // unreadable through fetchAllBlocked() because that filters on the now-blank user id.
        Api.INSTANCE.restoreSession();
        assertEquals(1, sqlite.fetchAllBlocked().size(), "the row survives the refusal");
    }

    // ---------- containsIdByDateTime ----------

    @Test
    void containsIdByDateTimeFindsAStoredSlot() throws Exception {
        store(task("t-1", "standup"));

        assertTrue(repo.containsIdByDateTime(MON, LocalTime.of(9, 0)),
                "the slot is occupied as far as the caller is concerned");
    }

    @Test
    void containsIdByDateTimeIsFalseForAnUnusedSlot() throws Exception {
        store(task("t-1", "standup"));

        assertFalse(repo.containsIdByDateTime(MON, LocalTime.of(15, 0)));
    }

    // ---------- local pass-throughs ----------

    @Test
    void fetchAllBlockedPassesThroughToSqlite() throws Exception {
        assertTrue(repo.fetchAllBlocked().isEmpty(), "nothing stored yet");

        BlockedTime block = BlockedTime.once("lunch", MON, LocalTime.of(12, 0), LocalTime.of(13, 0));
        sqlite.upsertBlocked(block);

        List<BlockedTime> all = repo.fetchAllBlocked();
        assertEquals(1, all.size());
        assertEquals("lunch", all.get(0).getTitle());
    }

    @Test
    void fetchUnscheduledTasksPassesThroughToSqlite() throws Exception {
        assertTrue(repo.fetchUnscheduledTasks().isEmpty(), "nothing stored yet");

        Task flexible = Task.loadTaskFromdb("u-1", "report", 2.0, 3,
                null, null, null, null, null, null);
        flexible.setLifeCycle(org.ashoreprospect.TaskLifeCycle.UNSCHEDULED);
        flexible.setLastUpdatedAt(NOW);
        sqlite.upsert(flexible);

        List<Task> unscheduled = repo.fetchUnscheduledTasks();
        assertEquals(1, unscheduled.size());
        assertEquals("report", unscheduled.get(0).getTaskName());
    }

    // ---------- isLocalInstallStale / maybeWarnLocalStaleOffline ----------

    @Test
    void aStaleCacheIsReportedAndAFreshOneIsNot() throws Exception {
        // Both methods are private; they read the last_postgres_sync_at meta stamp. Exercise them
        // through reflection since no public caller reaches them without a live Postgres.
        java.lang.reflect.Method stale = TaskRepository.class.getDeclaredMethod("isLocalInstallStale");
        stale.setAccessible(true);

        assertFalse((boolean) stale.invoke(repo), "no stamp means not stale");

        sqlite.setMeta("last_postgres_sync_at", Instant.now().toString());
        assertFalse((boolean) stale.invoke(repo), "a fresh stamp is not stale");

        sqlite.setMeta("last_postgres_sync_at",
                Instant.now().minus(120, java.time.temporal.ChronoUnit.DAYS).toString());
        assertTrue((boolean) stale.invoke(repo), "120 days exceeds STALE_MONTHS * 30");
    }

    @Test
    void maybeWarnLocalStaleOfflineToleratesBothStates() throws Exception {
        java.lang.reflect.Method warn =
                TaskRepository.class.getDeclaredMethod("maybeWarnLocalStaleOffline");
        warn.setAccessible(true);

        warn.invoke(repo); // no stamp: no warning

        sqlite.setMeta("last_postgres_sync_at",
                Instant.now().minus(120, java.time.temporal.ChronoUnit.DAYS).toString());
        warn.invoke(repo); // stale: prints a warning, must not throw
    }

    // ---------- expirePastBlocked ----------

    @Test
    void expirePastBlockedDeletesDatedBlocksThatAlreadyEnded() throws Exception {
        // A dated block whose end is in the past is deleted. It must also be older than the
        // 5-minute grace window, otherwise a block just saved on another device is spared.
        BlockedTime ended = BlockedTime.once("last month", MON.minusDays(30),
                LocalTime.of(9, 0), LocalTime.of(10, 0));
        ended.setLastUpdatedAt(Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
        sqlite.upsertBlocked(ended);

        BlockedTime upcoming = BlockedTime.once("next month", MON.plusDays(30),
                LocalTime.of(9, 0), LocalTime.of(10, 0));
        sqlite.upsertBlocked(upcoming);
        assertEquals(2, sqlite.fetchAllBlocked().size());

        expirePastBlocked();

        List<BlockedTime> left = sqlite.fetchAllBlocked();
        assertEquals(1, left.size(), "only the future block survives");
        assertEquals("next month", left.get(0).getTitle());
    }

    @Test
    void expirePastBlockedSparesABlockSavedMomentsAgo() throws Exception {
        // The 5-minute grace window keeps a freshly synced past block visible, so the device that
        // just created it does not immediately delete it out from under the others.
        BlockedTime justSaved = BlockedTime.once("this morning", MON.minusDays(3),
                LocalTime.of(9, 0), LocalTime.of(10, 0));
        justSaved.setLastUpdatedAt(Instant.now().minusSeconds(30));
        sqlite.upsertBlocked(justSaved);

        expirePastBlocked();

        assertEquals(1, sqlite.fetchAllBlocked().size(), "the grace window spares it");
    }

    @Test
    void expirePastBlockedLeavesDailyBlocksAlone() throws Exception {
        // Only dated (once) blocks expire; a daily block recurs and never becomes past.
        BlockedTime daily = BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        daily.setLastUpdatedAt(Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS));
        sqlite.upsertBlocked(daily);

        expirePastBlocked();

        assertEquals(1, sqlite.fetchAllBlocked().size(), "the daily block stays");
    }

    private void expirePastBlocked() throws Exception {
        java.lang.reflect.Method m = TaskRepository.class.getDeclaredMethod("expirePastBlocked");
        m.setAccessible(true);
        m.invoke(repo);
    }

    // ---------- compareInstant ----------

    @Test
    void compareInstantOrdersTimestamps() {
        Instant older = Instant.parse("2026-10-11T00:00:00Z");
        Instant newer = Instant.parse("2026-10-13T00:00:00Z");

        assertEquals(-1, TaskRepository.compareInstant(older, newer));
        assertEquals(1, TaskRepository.compareInstant(newer, older));
        assertEquals(0, TaskRepository.compareInstant(older, older));
    }

    @Test
    void compareInstantTreatsNullAsEarliest() {
        Instant some = Instant.parse("2026-10-12T00:00:00Z");

        assertEquals(-1, TaskRepository.compareInstant(null, some), "null sorts first");
        assertEquals(1, TaskRepository.compareInstant(some, null), "a value sorts after null");
        assertEquals(0, TaskRepository.compareInstant(null, null), "two nulls are equal");
    }

    // ---------- preferRemote ----------

    @Test
    void preferRemoteTakesTheNewerTimestampOnEitherRole() {
        Instant older = Instant.parse("2026-10-11T00:00:00Z");
        Instant newer = Instant.parse("2026-10-13T00:00:00Z");

        assertTrue(TaskRepository.preferRemote(older, newer, false));
        assertTrue(TaskRepository.preferRemote(older, newer, true));
        assertFalse(TaskRepository.preferRemote(newer, older, false));
        assertFalse(TaskRepository.preferRemote(newer, older, true));
    }

    @Test
    void preferRemoteOnATieKeepsLocalOnTheHostAndTakesRemoteOnAClient() {
        Instant tie = Instant.parse("2026-10-12T00:00:00Z");

        assertFalse(TaskRepository.preferRemote(tie, tie, true), "the host keeps local");
        assertTrue(TaskRepository.preferRemote(tie, tie, false), "a client takes remote");
    }

    @Test
    void preferRemoteKeepsLocalWhenRemoteIsMissing() {
        Instant local = Instant.parse("2026-10-12T00:00:00Z");

        assertFalse(TaskRepository.preferRemote(local, null, true), "there is no Node clock to prefer");
        assertFalse(TaskRepository.preferRemote(local, null, false));
        assertFalse(TaskRepository.preferRemote(null, null, false), "both null keeps local");
    }

    @Test
    void preferRemoteTakesRemoteWhenLocalIsMissing() {
        Instant remote = Instant.parse("2026-10-12T00:00:00Z");

        assertTrue(TaskRepository.preferRemote(null, remote, true));
        assertTrue(TaskRepository.preferRemote(null, remote, false));
    }

    @Test
    void preferRemoteReadsTheDeviceRoleWhenNotGiven() {
        // The public overload delegates to isHostDevice(); on this machine that resolves from the
        // configured Node URL, so only the role-independent cases are asserted here.
        Instant older = Instant.parse("2026-10-11T00:00:00Z");
        Instant newer = Instant.parse("2026-10-13T00:00:00Z");

        assertTrue(TaskRepository.preferRemote(older, newer), "newer always wins");
        assertFalse(TaskRepository.preferRemote(newer, older), "older never wins");
        assertFalse(TaskRepository.preferRemote(newer, null), "no remote keeps local");
    }

    // ---------- normalizeInstant ----------

    @Test
    void normalizeInstantParsesAnIsoString() {
        assertEquals(NOW, TaskRepository.normalizeInstant("2026-10-12T07:00:00Z"));
    }

    @Test
    void normalizeInstantReturnsNullForBlankOrInvalidInput() {
        assertNull(TaskRepository.normalizeInstant((String) null));
        assertNull(TaskRepository.normalizeInstant(""));
        assertNull(TaskRepository.normalizeInstant("  "));
        assertNull(TaskRepository.normalizeInstant("not-a-timestamp"));
    }

    // ---------- helpers ----------
}
