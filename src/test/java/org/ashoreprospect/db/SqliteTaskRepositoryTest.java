/** Local SQLite cache: schema, meta, tombstones, and blocked-time round trips. */
package org.ashoreprospect.db;

import org.ashoreprospect.BlockedTime;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
import org.ashoreprospect.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every task-reading method filters on {@code Api.INSTANCE.getUserId()}, which is only
 * set by a live Node login. The tests below therefore exercise the user-independent
 * surface (schema, meta, tombstones, purge, blocked rows) and assert the empty-read
 * guard for tasks directly.
 */
class SqliteTaskRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-12T07:00:00Z");
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    @TempDir
    Path dir;

    private SqliteTaskRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        repo = new SqliteTaskRepository("jdbc:sqlite:" + dir.resolve("test.db").toString().replace('\\', '/'));
        repo.initialize();
    }

    // ---------- schema ----------

    @Test
    void initialize_isIdempotent() throws Exception {
        repo.initialize();
        repo.initialize();

        Set<String> tables = new java.util.TreeSet<>();
        try (Connection conn = repo.connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        assertTrue(tables.contains("tasks"));
        assertTrue(tables.contains("sync_deletes"));
        assertTrue(tables.contains("sync_meta"));
        assertTrue(tables.contains("blocked_times"));
        assertTrue(tables.contains("sync_deletes_blocked"));
    }

    @Test
    void tasksTable_hasTheExtraColumnsTheMigrationsAdd() throws Exception {
        Set<String> columns = new java.util.HashSet<>();
        try (Connection conn = repo.connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(tasks)")) {
            while (rs.next()) columns.add(rs.getString("name"));
        }
        for (String expected : List.of("description", "description_done", "parent_id",
                "user_id", "placement_updated_at", "last_updated_at")) {
            assertTrue(columns.contains(expected), "missing column " + expected);
        }
    }

    @Test
    void replaceBlockedSeen_discardsNullAndBlankIdsAndReplacesThePreviousSet() throws Exception {
        repo.replaceBlockedSeen(java.util.Arrays.asList("block-1", null, "", "  ", "block-2"));

        assertEquals(Set.of("block-1", "block-2"), repo.blockedSeenIds());

        repo.replaceBlockedSeen(null);
        assertTrue(repo.blockedSeenIds().isEmpty());
        repo.replaceBlockedSeen(List.of());
        assertTrue(repo.blockedSeenIds().isEmpty());
    }

    // ---------- meta ----------

    @Test
    void meta_roundTripsOverwritesAndReportsMissingKeys() throws Exception {
        assertNull(repo.getMeta("missing"));

        repo.setMeta("lastSync", "2026-10-12T00:00:00Z");
        assertEquals("2026-10-12T00:00:00Z", repo.getMeta("lastSync"));

        repo.setMeta("lastSync", "2026-10-13T00:00:00Z");
        assertEquals("2026-10-13T00:00:00Z", repo.getMeta("lastSync"));
    }

    // ---------- tombstones ----------

    @Test
    void existsTaskId_and_isDeletedId_handlePresentAbsentAndBlankIds() throws Exception {
        assertFalse(repo.existsTaskId("nope"));
        assertFalse(repo.existsTaskId(""));
        assertFalse(repo.existsTaskId(null));

        repo.recordDelete("gone-1", NOW, "gone");
        assertTrue(repo.isDeletedId("gone-1"));
        assertFalse(repo.isDeletedId("nope"));
        assertFalse(repo.isDeletedId(""));
        assertFalse(repo.isDeletedId(null));

        repo.unrecordDelete("gone-1");
        assertFalse(repo.isDeletedId("gone-1"));
    }

    @Test
    void recordDelete_keepsTheLatestTimestampAndTheFirstTitle() throws Exception {
        repo.recordDelete("t-1", NOW, "original");
        repo.recordDelete("t-1", NOW.minusSeconds(60), null);

        List<String[]> deletes = readDeletesDirectly();
        assertEquals(1, deletes.size());
        assertEquals("t-1", deletes.get(0)[0]);
        assertEquals(NOW.toString(), deletes.get(0)[1]);
        assertEquals("original", deletes.get(0)[2]);
    }

    @Test
    void purgeDeletesBefore_dropsOnlyOlderTombstones() throws Exception {
        repo.recordDelete("old", NOW.minusSeconds(3600), "old");
        repo.recordDelete("new", NOW, "new");

        repo.purgeDeletesBefore(NOW.minusSeconds(60));

        List<String[]> deletes = readDeletesDirectly();
        assertEquals(1, deletes.size());
        assertEquals("new", deletes.get(0)[0]);
    }

    @Test
    void deleteTasksAndLog_withAnEmptyListIsANoOp() throws Exception {
        repo.deleteTasksAndLog(null);
        repo.deleteTasksAndLog(List.of());

        assertTrue(readDeletesDirectly().isEmpty());
    }

    @Test
    void deleteTasksAndLog_skipsNullEntriesAndNullIds() throws Exception {
        Task noId = Task.loadTaskFromdb(null, "no id", 1.0, 3,
                null, null, null, null, null, null);

        repo.deleteTasksAndLog(java.util.Arrays.asList(null, noId));

        assertTrue(readDeletesDirectly().isEmpty());
    }

    @Test
    void restoreTasks_withAnEmptyListIsANoOp() throws Exception {
        repo.restoreTasks(null);
        repo.restoreTasks(List.of());
        assertTrue(repo.fetchAll().isEmpty());
    }

    @Test
    void wipeAllLocalData_clearsEveryCacheTable() throws Exception {
        Task task = Task.loadTaskFromdb("wipe-task", "task", 1, 3,
                null, null, null, null, null, null);
        repo.upsert(task);
        repo.recordDelete("wipe-delete", NOW, "deleted");
        repo.setMeta("user_id", "u-1");

        BlockedTime blocked = BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        blocked.setUserId("u-1");
        repo.upsertBlocked(blocked);
        repo.recordBlockedDelete("wipe-blocked-delete", NOW, "blocked");
        repo.replaceBlockedSeen(Set.of("wipe-seen"));

        repo.wipeAllLocalData();

        assertFalse(rowExistsDirectly("wipe-task"));
        assertTrue(readDeletesDirectly().isEmpty());
        assertTrue(readBlockedDirectly().isEmpty());
        assertTrue(repo.fetchBlockedDeletedIds().isEmpty());
        assertTrue(repo.blockedSeenIds().isEmpty());
        assertNull(repo.getMeta("user_id"));
    }

    // ---------- blocked times ----------

    @Test
    void blockedRows_keepDailyWeeklyAndSpecificDateApart() throws Exception {
        BlockedTime daily = BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        BlockedTime weekly = BlockedTime.weekly("gym", DayOfWeek.MONDAY,
                LocalTime.of(18, 0), LocalTime.of(19, 0));
        BlockedTime once = BlockedTime.once("trip", MON, LocalTime.of(8, 0), LocalTime.of(9, 0));
        for (BlockedTime b : List.of(daily, weekly, once)) {
            b.setUserId("u-1");
            repo.upsertBlocked(b);
        }

        List<BlockedTime> rows = readBlockedDirectly();
        assertEquals(3, rows.size());

        BlockedTime loadedDaily = byId(rows, daily.getId());
        assertNull(loadedDaily.getWeekday());
        assertNull(loadedDaily.getSpecificDate());
        assertTrue(loadedDaily.isDaily());

        BlockedTime loadedWeekly = byId(rows, weekly.getId());
        assertEquals(DayOfWeek.MONDAY, loadedWeekly.getWeekday());
        assertNull(loadedWeekly.getSpecificDate());

        BlockedTime loadedOnce = byId(rows, once.getId());
        assertEquals(MON, loadedOnce.getSpecificDate());
        assertNull(loadedOnce.getWeekday());

        for (BlockedTime row : rows) {
            assertEquals("u-1", row.getUserId());
            assertNotNull(row.getLastUpdatedAt());
        }
    }

    @Test
    void blockedRows_roundTripTheExceptionFlagAndTitle() throws Exception {
        BlockedTime holiday = BlockedTime.weekly("holiday", DayOfWeek.FRIDAY,
                LocalTime.of(8, 0), LocalTime.of(9, 0));
        holiday.setException(true);
        holiday.setUserId("u-1");
        repo.upsertBlocked(holiday);

        BlockedTime loaded = byId(readBlockedDirectly(), holiday.getId());
        assertTrue(loaded.isException());
        assertEquals("holiday", loaded.getTitle());
        assertEquals(LocalTime.of(8, 0), loaded.getStartTime());
        assertEquals(LocalTime.of(9, 0), loaded.getEndTime());
    }

    @Test
    void upsertBlocked_rejectsRowsWithoutTimes() throws Exception {
        repo.upsertBlocked(null);
        repo.upsertBlocked(new BlockedTime("no-times", "bare", null, null, null, null, NOW, "u-1"));

        assertTrue(readBlockedDirectly().isEmpty());
    }

    @Test
    void upsertBlocked_ignoresOlderTimestamps() throws Exception {
        BlockedTime row = BlockedTime.daily("first", LocalTime.of(12, 0), LocalTime.of(13, 0));
        row.setUserId("u-1");
        row.setLastUpdatedAt(NOW);
        repo.upsertBlocked(row);

        row.setTitle("stale");
        row.setLastUpdatedAt(NOW.minusSeconds(3600));
        repo.upsertBlocked(row);

        assertEquals("first", byId(readBlockedDirectly(), row.getId()).getTitle());
    }

    @Test
    void deleteBlockedAndLog_tombstonesTheRow() throws Exception {
        BlockedTime row = BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        row.setUserId("u-1");
        repo.upsertBlocked(row);
        assertTrue(repo.fetchBlockedDeletedIds().isEmpty());

        repo.deleteBlockedAndLog(row);

        // PINNED: deleteBlockedRow() and deleteBlockedAndLog() both filter on the
        // logged-in user id, which is blank here, so the row is NOT deleted. The
        // tombstone is still written, which is exactly the half-done state worth pinning.
        assertTrue(repo.fetchBlockedDeletedIds().contains(row.getId()));
        assertEquals(1, readBlockedDirectly().size());
        assertEquals(1, repo.fetchAllBlockedDeletes().size());
        assertEquals(row.getId(), repo.fetchAllBlockedDeletes().get(0)[0]);
        assertEquals("lunch", repo.fetchAllBlockedDeletes().get(0)[2]);
    }

    @Test
    void purgeBlockedDeletesBefore_dropsOnlyOlderTombstones() throws Exception {
        repo.recordBlockedDelete("old", NOW.minusSeconds(3600), "old");
        repo.recordBlockedDelete("new", NOW, "new");

        repo.purgeBlockedDeletesBefore(NOW.minusSeconds(60));

        assertEquals(Set.of("new"), repo.fetchBlockedDeletedIds());
    }

    // ---------- user-id guard ----------

    @Test
    void taskReads_areEmptyWithoutALoggedInUser() throws Exception {
        Task task = Task.loadTaskFromdb("t-1", "stored", 1.0, 3,
                null, null, null, null, MON, LocalTime.of(9, 0));
        task.setLifeCycle(TaskLifeCycle.ONGOING);
        task.setWorkflow(Workflow.UNFINISHED);
        task.setLastUpdatedAt(NOW);
        repo.upsert(task);

        // The row exists on disk …
        assertTrue(rowExistsDirectly("t-1"));
        // … but the reads are scoped to the logged-in account and stay empty.
        assertTrue(repo.fetchAll().isEmpty());
        assertTrue(repo.fetchUnscheduled().isEmpty());
        assertNull(repo.findById("t-1"));
        assertFalse(repo.containsIdByDateTime(MON, LocalTime.of(9, 0)));
        assertTrue(repo.fetchDeletedIds().isEmpty());
        assertTrue(repo.fetchAllDeletes().isEmpty());
        assertTrue(repo.fetchAllBlocked().isEmpty());
        assertNull(repo.findBlockedById("anything"));
    }

    // ---------- helpers ----------

    private boolean rowExistsDirectly(String id) throws SQLException {
        try (Connection conn = repo.connect();
             var ps = conn.prepareStatement("SELECT 1 FROM tasks WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private List<String[]> readDeletesDirectly() throws SQLException {
        List<String[]> out = new java.util.ArrayList<>();
        try (Connection conn = repo.connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, deleted_at, title FROM sync_deletes ORDER BY id")) {
            while (rs.next()) {
                out.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
            }
        }
        return out;
    }

    /** Bypasses the user filter so a blocked row can be read back without a login. */
    private List<BlockedTime> readBlockedDirectly() throws SQLException {
        List<org.ashoreprospect.BlockedTime> out = new java.util.ArrayList<>();
        try (Connection conn = repo.connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM blocked_times ORDER BY id")) {
            while (rs.next()) {
                int weekday = rs.getInt("weekday");
                Integer wd = rs.wasNull() ? null : weekday;
                BlockedTime b = new BlockedTime(
                        rs.getString("id"),
                        rs.getString("title"),
                        wd == null ? null : DayOfWeek.of(wd),
                        rs.getString("specific_date") == null ? null
                                : LocalDate.parse(rs.getString("specific_date")),
                        LocalTime.parse(rs.getString("start_time")),
                        LocalTime.parse(rs.getString("end_time")),
                        Instant.parse(rs.getString("last_updated_at")),
                        rs.getString("user_id"));
                b.setException(rs.getInt("is_exception") != 0);
                out.add(b);
            }
        }
        return out;
    }

    private static BlockedTime byId(List<BlockedTime> rows, String id) {
        return rows.stream()
                .filter(b -> id.equals(b.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing row " + id));
    }
}
