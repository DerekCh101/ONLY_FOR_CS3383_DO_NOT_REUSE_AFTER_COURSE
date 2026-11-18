/**
 * The read side of SqliteTaskRepository when a user is signed in.
 *
 * <p>Every task/blocked read filters on the signed-in user id, so the cache tests in this file
 * sign in first. A file-only remember-me session (no server round trip) is installed for the
 * duration of a test and the developer's .scheduler files are put back afterwards.
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("node-session")
class SqliteTaskRepositorySignedInTest {

    private static final String UID = "signed-in-user";

    @TempDir
    Path tmp;

    private SqliteTaskRepository sqlite;
    private Path sessionFile;
    private Path prefsFile;
    private byte[] savedSession;
    private byte[] savedPrefs;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Endpoints.dir();
        sessionFile = dir.resolve("session.txt");
        prefsFile = dir.resolve("login.txt");
        savedSession = Files.isRegularFile(sessionFile) ? Files.readAllBytes(sessionFile) : null;
        savedPrefs = Files.isRegularFile(prefsFile) ? Files.readAllBytes(prefsFile) : null;

        // A file-only remember-me session: restoreSession() only reads the two files.
        LocalSession.save(UID, "tester", "file-only-token");
        LocalSession.savePrefs(true, "tester");
        assertTrue(Api.INSTANCE.restoreSession(), "the file-only session loads");
        assertEquals(UID, Api.INSTANCE.getUserId());

        sqlite = new SqliteTaskRepository("jdbc:sqlite:" + tmp.resolve("signed-in.db"));
        sqlite.initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        // drop the RAM session without touching the remembered file, then restore both files
        Api.INSTANCE.logoutKeepRemembered();
        restore(sessionFile, savedSession);
        restore(prefsFile, savedPrefs);
    }

    private static void restore(Path file, byte[] content) throws Exception {
        if (content == null) {
            Files.deleteIfExists(file);
        } else {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        }
    }

    private static Task task(String id, String name) {
        return Task.loadTaskFromdb(id, name, 1.0, 3, null, null, null, null, null, null);
    }

    // ---------- tasks ----------

    @Test
    void fetchAll_returnsTheRowsWrittenUnderTheSameUser() throws Exception {
        Task t = task("t-1", "write a test");
        t.setLastUpdatedAt(Instant.parse("2026-10-12T07:00:00Z"));
        sqlite.upsert(t);

        List<Task> all = sqlite.fetchAll();
        assertEquals(1, all.size());
        assertEquals("t-1", all.get(0).getID());
        assertEquals("write a test", all.get(0).getTaskName());
    }

    @Test
    void findById_roundTripsTheColumnsItWrites() throws Exception {
        Task t = Task.loadTaskFromdb("t-2", "fixed", 2.5, 1, null, null, null, null,
                LocalDate.of(2026, 10, 12), LocalTime.of(9, 0));
        t.setLastUpdatedAt(Instant.parse("2026-10-12T07:00:00Z"));
        sqlite.upsert(t);

        Task back = sqlite.findById("t-2");
        assertNotNull(back);
        assertEquals(1, back.getState(), "Fixed");
        assertEquals(2.5, back.getDurationHours(), 1e-9);
        assertEquals(LocalDate.of(2026, 10, 12), back.getPlacedDate());
        assertEquals(LocalTime.of(9, 0), back.getPlacedTime());
    }

    @Test
    void findById_aDeadlineRowKeepsItsDeadlineOnlyWhenTheStateIsDeadline() throws Exception {
        Task deadline = Task.loadTaskFromdb("t-3", "deadline", 1.0, 2, null, null,
                LocalDate.of(2026, 10, 20), LocalTime.of(17, 0), null, null);
        deadline.setLastUpdatedAt(Instant.parse("2026-10-12T07:00:00Z"));
        sqlite.upsert(deadline);

        // A flexible task that happens to carry a deadline date: bind() nulls the columns.
        Task flexible = Task.loadTaskFromdb("t-4", "flexible", 1.0, 3, null, null,
                LocalDate.of(2026, 10, 21), LocalTime.of(18, 0), null, null);
        flexible.setLastUpdatedAt(Instant.parse("2026-10-12T07:00:00Z"));
        sqlite.upsert(flexible);

        assertEquals(LocalDate.of(2026, 10, 20), sqlite.findById("t-3").getDeadLineDate());
        assertNull(sqlite.findById("t-4").getDeadLineDate(),
                "PINNED: a non-deadline task loses its deadline on a round trip");
    }

    @Test
    void findById_anUnknownIdIsNull() throws Exception {
        assertNull(sqlite.findById("nope"));
    }

    @Test
    void upsert_anOlderStampDoesNotOverwriteANewerRow() throws Exception {
        Task newer = task("t-5", "newer title");
        newer.setLastUpdatedAt(Instant.parse("2026-10-12T08:00:00Z"));
        sqlite.upsert(newer);

        Task older = task("t-5", "older title");
        older.setLastUpdatedAt(Instant.parse("2026-10-12T07:00:00Z"));
        sqlite.upsert(older);

        assertEquals("newer title", sqlite.findById("t-5").getTaskName(),
                "the last-write-wins guard compares last_updated_at");
    }

    @Test
    void deleteTaskRow_removesTheRowButLeavesNoTombstone() throws Exception {
        Task t = task("t-6", "delete me");
        t.setLastUpdatedAt(Instant.parse("2026-10-12T07:00:00Z"));
        sqlite.upsert(t);

        sqlite.deleteTaskRow("t-6");

        assertNull(sqlite.findById("t-6"));
        assertTrue(sqlite.fetchDeletedIds().isEmpty(), "recordDelete() is the tombstone call");
    }

    @Test
    void recordDelete_writesATombstoneThatFetchedDeletedIdsReports() throws Exception {
        Instant at = Instant.parse("2026-10-12T07:00:00Z");
        sqlite.recordDelete("t-7", at, "gone");

        assertTrue(sqlite.fetchDeletedIds().contains("t-7"));
        sqlite.unrecordDelete("t-7");
        assertFalse(sqlite.fetchDeletedIds().contains("t-7"));
    }

    @Test
    void recordDelete_keepsTheLatestTimestampOnARepeat() throws Exception {
        // PINNED: deleted_at only ever moves forward, so a later call with an older stamp is
        // ignored and the tombstone still disappears in one unrecordDelete().
        sqlite.recordDelete("t-8", Instant.parse("2026-10-12T09:00:00Z"), "first");
        sqlite.recordDelete("t-8", Instant.parse("2026-10-12T07:00:00Z"), "second");

        assertTrue(sqlite.fetchDeletedIds().contains("t-8"));
        sqlite.unrecordDelete("t-8");
        assertTrue(sqlite.fetchDeletedIds().isEmpty());
    }

    @Test
    void fetchUnscheduled_returnsOnlyUnplacedRows() throws Exception {
        Task placed = Task.loadTaskFromdb("u-1", "placed", 1.0, 1, null, null, null, null,
                LocalDate.of(2026, 10, 12), LocalTime.of(9, 0));
        placed.setLastUpdatedAt(Instant.parse("2026-10-12T07:00:00Z"));
        sqlite.upsert(placed);

        Task loose = task("u-2", "loose");
        loose.setLastUpdatedAt(Instant.parse("2026-10-12T07:00:00Z"));
        sqlite.upsert(loose);

        List<Task> unscheduled = sqlite.fetchUnscheduled();
        assertEquals(1, unscheduled.size(), "unscheduled = not placed");
        assertEquals("u-2", unscheduled.get(0).getID());
    }

    @Test
    void existsTaskId_isUserAgnosticAndFalseForBlank() throws Exception {
        Task t = task("e-1", "exists");
        t.setLastUpdatedAt(Instant.parse("2026-10-12T07:00:00Z"));
        sqlite.upsert(t);

        assertTrue(sqlite.existsTaskId("e-1"));
        assertFalse(sqlite.existsTaskId("e-2"));
        assertFalse(sqlite.existsTaskId(null));
        assertFalse(sqlite.existsTaskId("  "));
    }

    @Test
    void containsIdByDateTime_matchesOnlyTheSignedInUsersPlacement() throws Exception {
        Task placed = Task.loadTaskFromdb("slot-1", "placed", 1, 1, null, null,
                null, null, LocalDate.of(2026, 10, 12), LocalTime.of(9, 0));
        placed.setLastUpdatedAt(Instant.parse("2026-10-12T07:00:00Z"));
        sqlite.upsert(placed);

        assertTrue(sqlite.containsIdByDateTime(LocalDate.of(2026, 10, 12), LocalTime.of(9, 0)));
        assertFalse(sqlite.containsIdByDateTime(LocalDate.of(2026, 10, 12), LocalTime.of(10, 0)));
    }

    @Test
    void deleteAllTasksAndLogEach_writesATombstoneForEverySignedInTask() throws Exception {
        Task first = task("bulk-1", "first");
        Task second = task("bulk-2", "second");
        sqlite.upsert(first);
        sqlite.upsert(second);

        sqlite.deleteAllTasksAndLogEach();

        assertTrue(sqlite.fetchAll().isEmpty());
        List<String[]> deletes = sqlite.fetchAllDeletes();
        assertEquals(2, deletes.size());
        assertTrue(deletes.stream().anyMatch(row -> row[0].equals("bulk-1")));
        assertTrue(deletes.stream().anyMatch(row -> row[0].equals("bulk-2")));
    }

    // ---------- blocked times ----------

    @Test
    void blockedTimes_roundTripDailyWeeklyAndOneOff() throws Exception {
        Instant at = Instant.parse("2026-10-12T07:00:00Z");
        BlockedTime daily = BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        daily.setLastUpdatedAt(at);
        BlockedTime weekly = BlockedTime.weekly("gym", DayOfWeek.WEDNESDAY,
                LocalTime.of(18, 0), LocalTime.of(19, 0));
        weekly.setLastUpdatedAt(at);
        BlockedTime once = BlockedTime.once("dentist", LocalDate.of(2026, 10, 14),
                LocalTime.of(9, 0), LocalTime.of(10, 0));
        once.setLastUpdatedAt(at);
        sqlite.upsertBlocked(daily);
        sqlite.upsertBlocked(weekly);
        sqlite.upsertBlocked(once);

        List<BlockedTime> all = sqlite.fetchAllBlocked();
        assertEquals(3, all.size());
        assertEquals(LocalTime.of(12, 0), sqlite.findBlockedById(daily.getId()).getStartTime());
        assertEquals(DayOfWeek.WEDNESDAY, sqlite.findBlockedById(weekly.getId()).getWeekday());
        assertEquals(LocalDate.of(2026, 10, 14), sqlite.findBlockedById(once.getId()).getSpecificDate());
    }

    @Test
    void findBlockedById_anUnknownIdIsNull() throws Exception {
        assertNull(sqlite.findBlockedById("no-such-block"));
    }

    @Test
    void blockedDeletes_areVisibleRegardlessOfTheSignedInUser() throws Exception {
        // PINNED: fetchBlockedDeletedIds() has no user filter, unlike the task tombstones.
        sqlite.recordBlockedDelete("b-1", Instant.parse("2026-10-12T07:00:00Z"), "lunch");

        assertTrue(sqlite.fetchBlockedDeletedIds().contains("b-1"));
        sqlite.purgeBlockedDeletesBefore(Instant.parse("2026-10-13T00:00:00Z"));
        assertTrue(sqlite.fetchBlockedDeletedIds().isEmpty());
    }

    @Test
    void blockedTimes_startAndEndTimeAreNotNullColumns() throws Exception {
        // A block with a null start cannot be written even through raw SQL, which is why
        // mergeNodeBlocked()'s missing-time guard only ever fires for Node rows.
        assertThrows(SQLException.class, () -> {
            try (var conn = sqlite.connect();
                 var ps = conn.prepareStatement(
                         "INSERT INTO blocked_times (id, title, last_updated_at) VALUES (?, ?, ?)")) {
                ps.setString(1, "half");
                ps.setString(2, "half");
                ps.setString(3, Instant.now().toString());
                ps.executeUpdate();
            }
        });
    }

    @Test
    void deleteBlockedRow_removesTheBlockButLeavesNoTombstone() throws Exception {
        BlockedTime b = BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        b.setLastUpdatedAt(Instant.parse("2026-10-12T07:00:00Z"));
        sqlite.upsertBlocked(b);

        sqlite.deleteBlockedRow(b.getId());

        assertNull(sqlite.findBlockedById(b.getId()));
        assertTrue(sqlite.fetchBlockedDeletedIds().isEmpty(),
                "recordBlockedDelete() is the tombstone call");
    }

    // ---------- meta ----------

    @Test
    void meta_isACaseSensitiveKeyValueStore() throws Exception {
        assertNull(sqlite.getMeta("last_node_sync_at"));
        sqlite.setMeta("last_node_sync_at", "2026-10-12T07:00:00Z");
        assertEquals("2026-10-12T07:00:00Z", sqlite.getMeta("last_node_sync_at"));

        sqlite.setMeta("last_node_sync_at", "2026-10-12T08:00:00Z");
        assertEquals("2026-10-12T08:00:00Z", sqlite.getMeta("last_node_sync_at"));
        sqlite.setMeta("LAST_NODE_SYNC_AT", "upper");
        assertEquals("2026-10-12T08:00:00Z", sqlite.getMeta("last_node_sync_at"),
                "meta keys are case-sensitive");
    }
}
