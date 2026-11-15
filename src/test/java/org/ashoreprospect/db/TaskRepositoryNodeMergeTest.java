/**
 * mergeNodeTasks() / mergeNodeBlocked() driven through a real SQLite cache in @TempDir, plus
 * pullFromNode()'s guard and failure paths.
 *
 * <p>The cache lives in {@code @TempDir}, so nothing touches the app's own
 * {@code scheduler-cache.db}. The merge bodies are private and take plain maps, so they are
 * invoked directly; the repository itself is allocated without running its constructor, which
 * would open the app's database, and is then handed the temp-dir store.
 *
 * <p>Signed in, because {@link SqliteTaskRepository} filters every read on the user id and a
 * blank user turns each query into {@code WHERE 1 = 0}. pullFromNode() is only exercised with
 * the live HTTP client pointed at a closed local port, so the suite never needs a network.
 */
package org.ashoreprospect.db;

import org.ashoreprospect.BlockedTime;
import org.ashoreprospect.Task;
import org.ashoreprospect.net.Api;
import org.ashoreprospect.net.Endpoints;
import org.ashoreprospect.net.LocalSession;
import org.ashoreprospect.net.OsEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("node-session")
class TaskRepositoryNodeMergeTest {

    private static final String UID = "merge-user";
    private static final Instant NOW = Instant.parse("2026-10-12T07:00:00Z");

    @TempDir
    Path tmp;

    private TaskRepository repo;
    private SqliteTaskRepository sqlite;
    private Path sessionFile;
    private Path prefsFile;
    private byte[] savedSession;
    private byte[] savedPrefs;

    // ---------- wiring ----------

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Endpoints.dir();
        sessionFile = dir.resolve("session.txt");
        prefsFile = dir.resolve("login.txt");
        savedSession = Files.isRegularFile(sessionFile) ? Files.readAllBytes(sessionFile) : null;
        savedPrefs = Files.isRegularFile(prefsFile) ? Files.readAllBytes(prefsFile) : null;

        LocalSession.save(UID, "merger", "file-only-token");
        LocalSession.savePrefs(true, "merger");
        assertTrue(Api.INSTANCE.restoreSession(), "the file-only session loads");

        sqlite = new SqliteTaskRepository("jdbc:sqlite:" + tmp.resolve("merge.db"));
        sqlite.initialize();

        repo = allocate();
        setField(repo, "sqlite", sqlite);
        // final fields are skipped by allocateInstance(); pullFromNode() needs both.
        setField(repo, "historyEpoch", new AtomicLong());
        setField(repo, "historyLock", new ReentrantLock());
    }

    @AfterEach
    void tearDown() throws Exception {
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

    private static TaskRepository allocate() throws Exception {
        // TaskRepository's only constructor opens the app's own database. Unsafe lets the test
        // skip it and hand the object a temp-dir database instead.
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

    private static Object getField(Object target, String name) throws Exception {
        Field f = TaskRepository.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static Task task(String id, String name) {
        return Task.loadTaskFromdb(id, name, 1.0, 3, null, null, null, null, null, null);
    }

    private void store(Task t) throws SQLException {
        sqlite.upsert(t);
    }

    private boolean mergeTasks(List<Map<String, String>> rows) throws Exception {
        Method m = TaskRepository.class.getDeclaredMethod("mergeNodeTasks", List.class);
        m.setAccessible(true);
        return (boolean) m.invoke(repo, rows);
    }

    private boolean mergeBlocked(List<Map<String, String>> rows) throws Exception {
        Method m = TaskRepository.class.getDeclaredMethod("mergeNodeBlocked", List.class);
        m.setAccessible(true);
        return (boolean) m.invoke(repo, rows);
    }

    private List<Map<String, String>> rows(Map<String, String>... items) {
        return new ArrayList<>(List.of(items));
    }

    // ---------- mergeNodeTasks ----------

    @Test
    void mergeNodeTasks_aNullListIsTreatedAsEmpty() throws Exception {
        assertFalse(mergeTasks(null));
        assertTrue(sqlite.fetchAll().isEmpty());
    }

    @Test
    void mergeNodeTasks_rowsWithoutAnIdAreSkipped() throws Exception {
        assertFalse(mergeTasks(rows(row("title", "orphan"))));
        assertTrue(sqlite.fetchAll().isEmpty());
    }

    @Test
    void mergeNodeTasks_insertsAnUnknownRemoteRow() throws Exception {
        boolean changed = mergeTasks(rows(row("id", "r-1", "title", "from-node", "duration", "2.5",
                "state", "Deadline", "deadline_date", "2026-10-20")));

        assertTrue(changed);
        Task stored = sqlite.findById("r-1");
        assertNotNull(stored);
        assertEquals("from-node", stored.getTaskName());
        assertEquals(2, stored.getState());
        assertEquals(2.5, stored.getDurationHours(), 1e-9);
        assertEquals(LocalDate.of(2026, 10, 20), stored.getDeadLineDate());
    }

    @Test
    void mergeNodeTasks_theSameStampLeavesTheLocalRowAlone() throws Exception {
        Task local = task("same-1", "local-title");
        local.setLastUpdatedAt(NOW);
        store(local);

        assertFalse(mergeTasks(rows(row("id", "same-1", "title", "remote-title",
                "last_updated_at", NOW.toString()))));
        assertEquals("local-title", sqlite.findById("same-1").getTaskName());
    }

    @Test
    void mergeNodeTasks_aNewerRemoteStampOverwritesTheLocalRow() throws Exception {
        Task local = task("newer-1", "local-title");
        local.setLastUpdatedAt(NOW);
        store(local);

        assertTrue(mergeTasks(rows(row("id", "newer-1", "title", "remote-title",
                "last_updated_at", NOW.plusSeconds(60).toString()))));
        assertEquals("remote-title", sqlite.findById("newer-1").getTaskName());
    }

    @Test
    void mergeNodeTasks_aRemoteRowForALocallyDeletedTaskIsRestored() throws Exception {
        sqlite.recordDelete("undel-1", NOW, "back-again");

        assertTrue(mergeTasks(rows(row("id", "undel-1", "title", "back-again",
                "last_updated_at", NOW.toString()))));
        assertTrue(sqlite.fetchDeletedIds().isEmpty(), "the tombstone is dropped");
        assertNotNull(sqlite.findById("undel-1"));
    }

    @Test
    void mergeNodeTasks_theSecondIdenticalPullIsANoOp() throws Exception {
        Map<String, String> r = row("id", "twice-1", "title", "once",
                "last_updated_at", NOW.toString());
        List<Map<String, String>> pull = rows(r);

        assertTrue(mergeTasks(pull), "the first pull applies");
        assertFalse(mergeTasks(pull), "the same stamp is skipped");
        assertNotNull(sqlite.findById("twice-1"));
    }

    @Test
    void mergeNodeTasks_aLocalRowMissingFromNodeAndNotNewer_isDeleted() throws Exception {
        // PINNED: a row Node no longer has is deleted once it is no newer than the recorded
        // pull, and the delete is recorded as a tombstone so it is not re-uploaded.
        Task local = task("gone-1", "gone");
        local.setLastUpdatedAt(NOW);
        store(local);
        sqlite.setMeta("last_node_sync_at", NOW.plusSeconds(60).toString());

        assertTrue(mergeTasks(List.of()));
        assertNull(sqlite.findById("gone-1"));
        assertTrue(sqlite.fetchDeletedIds().contains("gone-1"));
    }

    @Test
    void mergeNodeTasks_aLocalRowEditedAfterTheLastPull_isKept() throws Exception {
        Task local = task("kept-1", "kept");
        local.setLastUpdatedAt(NOW.plusSeconds(120));
        store(local);
        sqlite.setMeta("last_node_sync_at", NOW.toString());

        assertFalse(mergeTasks(List.of()));
        assertNotNull(sqlite.findById("kept-1"), "a newer local edit survives an empty pull");
    }

    @Test
    void mergeNodeTasks_withoutAPullStampNothingIsEverDeleted() throws Exception {
        Task local = task("nostamp-1", "nostamp");
        local.setLastUpdatedAt(NOW);
        store(local);

        assertFalse(mergeTasks(List.of()));
        assertNotNull(sqlite.findById("nostamp-1"));
    }

    @Test
    void mergeNodeTasks_aLocalRowWithNoStampAtAll_isKept() throws Exception {
        // PINNED: lastSync is set but the local row has neither a last stamp nor a placement
        // stamp, so the delete rule cannot fire.
        Task local = task("nostamp-2", "nostamp-2");
        local.setLastUpdatedAt(null);
        local.setPlacementUpdatedAt(null);
        store(local);
        sqlite.setMeta("last_node_sync_at", NOW.toString());

        assertFalse(mergeTasks(List.of()));
        assertNotNull(sqlite.findById("nostamp-2"));
    }

    @Test
    void mergeNodeTasks_aRowAlreadyInTheDeleteLogIsNotDeletedTwice() throws Exception {
        Task local = task("del-2", "del-2");
        local.setLastUpdatedAt(NOW);
        store(local);
        sqlite.recordDelete("del-2", NOW.plusSeconds(5), "del-2");
        sqlite.setMeta("last_node_sync_at", NOW.plusSeconds(60).toString());

        assertFalse(mergeTasks(List.of()), "the tombstoned row is skipped");
        assertNotNull(sqlite.findById("del-2"), "and left on disk for the sweep to remove");
    }

    @Test
    void mergeNodeTasks_anUnparseableSyncStampDisablesTheDeleteRule() throws Exception {
        // PINNED: a corrupt last_node_sync_at is read as "never pulled", so nothing is deleted.
        Task local = task("corrupt-1", "corrupt");
        local.setLastUpdatedAt(NOW);
        store(local);
        sqlite.setMeta("last_node_sync_at", "not-a-timestamp");

        assertFalse(mergeTasks(List.of()));
        assertNotNull(sqlite.findById("corrupt-1"));
    }

    @Test
    void mergeNodeTasks_aRemoteRowWithAnUnparseableStamp_isApplied() throws Exception {
        // A Node stamp that is not ISO-8601 is unusable, but it must not be read as "older than
        // local": the local row always has a stamp, so that silently dropped the remote edit
        // while still reporting a change (#4).
        Task local = task("bad-stamp", "local-title");
        local.setLastUpdatedAt(null);
        store(local);
        assertNotNull(sqlite.findById("bad-stamp").getLastUpdatedAt(), "upsert() stamps it");

        assertTrue(mergeTasks(rows(row("id", "bad-stamp", "title", "remote-title",
                "last_updated_at", "yesterday"))), "reported as a change");
        assertEquals("remote-title", sqlite.findById("bad-stamp").getTaskName(),
                "and the remote edit is applied instead of dropped");
    }

    // ---------- mergeNodeBlocked ----------

    @Test
    void mergeNodeBlocked_aNullListIsTreatedAsEmpty() throws Exception {
        assertFalse(mergeBlocked(null));
    }

    @Test
    void mergeNodeBlocked_rowsWithoutAnIdAreSkipped() throws Exception {
        assertFalse(mergeBlocked(rows(row("title", "orphan", "start_time", "09:00",
                "end_time", "10:00"))));
        assertTrue(sqlite.fetchAllBlocked().isEmpty());
    }

    @Test
    void mergeNodeBlocked_rowsWithoutTimesAreDropped() throws Exception {
        assertFalse(mergeBlocked(rows(row("id", "blk-1", "title", "no times"))));
        assertTrue(sqlite.fetchAllBlocked().isEmpty());
    }

    @Test
    void mergeNodeBlocked_insertsAPlainDailyBlock() throws Exception {
        assertTrue(mergeBlocked(rows(row("id", "blk-2", "title", "Lunch",
                "start_time", "12:00", "end_time", "13:00", "is_exception", "false"))));

        List<BlockedTime> all = sqlite.fetchAllBlocked();
        assertEquals(1, all.size());
        assertEquals(LocalTime.of(12, 0), all.get(0).getStartTime());
        assertEquals(LocalTime.of(13, 0), all.get(0).getEndTime());
        assertEquals("Lunch", all.get(0).getTitle());
    }

    @Test
    void mergeNodeBlocked_aNewerRemoteBlockOverwritesTheLocalOne() throws Exception {
        BlockedTime local = BlockedTime.daily("old", LocalTime.of(9, 0), LocalTime.of(10, 0));
        local.setLastUpdatedAt(NOW);
        sqlite.upsertBlocked(local);

        assertTrue(mergeBlocked(rows(row("id", local.getId(), "title", "new",
                "start_time", "09:00", "end_time", "10:00",
                "last_updated_at", NOW.plusSeconds(60).toString()))));
        assertEquals("new", sqlite.fetchAllBlocked().get(0).getTitle());
    }

    @Test
    void mergeNodeBlocked_theSameStampLeavesTheLocalBlockAlone() throws Exception {
        BlockedTime local = BlockedTime.daily("old", LocalTime.of(9, 0), LocalTime.of(10, 0));
        local.setLastUpdatedAt(NOW);
        sqlite.upsertBlocked(local);

        assertFalse(mergeBlocked(rows(row("id", local.getId(), "title", "new",
                "start_time", "09:00", "end_time", "10:00",
                "last_updated_at", NOW.toString()))));
        assertEquals("old", sqlite.fetchAllBlocked().get(0).getTitle());
    }

    @Test
    void mergeNodeBlocked_aBareLocalStampDoesNotAdvanceTheRemoteOne() throws Exception {
        // PINNED: mergeBlocked() is all-or-nothing - it returns either the local or the remote
        // block. When the local side is newer the merged block is the local object itself, so
        // the remote stamp is discarded instead of being carried forward. The next pull then
        // fires the "missing from Node" rule and deletes a block that only just arrived.
        BlockedTime local = BlockedTime.daily("mine", LocalTime.of(9, 0), LocalTime.of(10, 0));
        local.setLastUpdatedAt(NOW.plusSeconds(300));
        sqlite.upsertBlocked(local);
        // A pull happened before the local edit, so the remote row is "new" to this device.
        sqlite.setMeta("last_node_sync_at", NOW.minusSeconds(600).toString());

        assertEquals("mine", sqlite.findBlockedById(local.getId()).getTitle(), "local before");
        assertTrue(mergeBlocked(rows(row("id", local.getId(), "title", "theirs",
                "start_time", "09:00", "end_time", "10:00",
                "last_updated_at", NOW.toString()))), "reported as a change");
        assertEquals("mine", sqlite.findBlockedById(local.getId()).getTitle(),
                "but the remote title is thrown away");
        assertEquals(NOW.plusSeconds(300), sqlite.findBlockedById(local.getId()).getLastUpdatedAt(),
                "and the local stamp survives untouched");
    }

    @Test
    void mergeNodeBlocked_codeMergesTheRemoteStampButNodeReSendsTheRow() throws Exception {
        // mergeNodeBlocked() only writes when the incoming stamp is newer than the stored one,
        // so a local edit stays local until Node sends a newer version back.
        BlockedTime local = BlockedTime.daily("mine", LocalTime.of(9, 0), LocalTime.of(10, 0));
        local.setLastUpdatedAt(NOW.plusSeconds(300));
        sqlite.upsertBlocked(local);

        assertTrue(mergeBlocked(rows(row("id", local.getId(), "title", "theirs",
                "start_time", "09:00", "end_time", "10:00",
                "last_updated_at", NOW.plusSeconds(400).toString()))));
        assertEquals("theirs", sqlite.findBlockedById(local.getId()).getTitle(),
                "a newer remote row does overwrite");
    }

    @Test
    void mergeBlocked_keepsTheLocalEditAndItsStamp() throws Exception {
        // The block is picked whole, so a newer local stamp wins on every device. The local stamp
        // is kept rather than advanced: upsertBlocked() refuses a write whose stamp is older than
        // the stored one, so a merged copy carrying Node's stamp could never be saved. Convergence
        // is decided by mergeNodeBlocked()'s served-row record instead (#2).
        BlockedTime local = BlockedTime.daily("mine", LocalTime.of(9, 0), LocalTime.of(10, 0));
        local.setLastUpdatedAt(NOW.plusSeconds(300));
        BlockedTime remote = BlockedTime.daily("theirs", LocalTime.of(9, 0), LocalTime.of(10, 0));
        remote.setLastUpdatedAt(NOW);

        BlockedTime host = TaskRepository.mergeBlocked(local, remote, true);
        BlockedTime client = TaskRepository.mergeBlocked(local, remote, false);

        assertEquals("mine", host.getTitle(), "host keeps local fields");
        assertEquals("mine", client.getTitle(), "client too");
        assertEquals(local.getId(), host.getId(), "and it is the same block");
        assertEquals(NOW.plusSeconds(300), host.getLastUpdatedAt(), "keeping the local stamp");
        assertEquals(NOW.plusSeconds(300), client.getLastUpdatedAt());

        BlockedTime newer = BlockedTime.daily("newer", LocalTime.of(9, 0), LocalTime.of(10, 0));
        newer.setLastUpdatedAt(NOW.plusSeconds(600));
        assertSame(newer, TaskRepository.mergeBlocked(local, newer, true),
                "a newer remote block replaces the local one wholesale");
    }

    @Test
    void mergeNodeBlocked_aTombstonedBlockThatNodeStillHasComesBack() throws Exception {
        sqlite.recordBlockedDelete("blk-3", NOW, "Lunch");
        assertTrue(sqlite.fetchBlockedDeletedIds().contains("blk-3"), "tombstoned first");

        assertTrue(mergeBlocked(rows(row("id", "blk-3", "title", "Lunch",
                "start_time", "12:00", "end_time", "13:00"))), "the block is stored again");
        assertEquals(1, sqlite.fetchAllBlocked().size());
        // Node still has the block, so the local delete is superseded and the tombstone row goes
        // with it - otherwise dbAddBlocked() refuses this id forever (#3).
        assertFalse(sqlite.fetchBlockedDeletedIds().contains("blk-3"),
                "the tombstone is cleared, like the task branch does");
    }

    @Test
    void mergeNodeBlocked_aLocalBlockMissingFromNodeAndNotNewer_isDeleted() throws Exception {
        BlockedTime local = BlockedTime.daily("gone", LocalTime.of(9, 0), LocalTime.of(10, 0));
        local.setLastUpdatedAt(NOW);
        sqlite.upsertBlocked(local);
        sqlite.setMeta("last_node_sync_at", NOW.plusSeconds(60).toString());

        assertTrue(mergeBlocked(List.of()));
        assertTrue(sqlite.fetchAllBlocked().isEmpty());
        assertTrue(sqlite.fetchBlockedDeletedIds().contains(local.getId()));
    }

    @Test
    void mergeNodeBlocked_aRemoteDeleteOnlyTakesEffectAfterTwoPullsThatOmitTheRow() throws Exception {
        // A row Node served once is not deleted by the very next response that omits it: one
        // response is not proof that Node dropped the block (#2).
        BlockedTime local = BlockedTime.daily("keep", LocalTime.of(9, 0), LocalTime.of(10, 0));
        local.setLastUpdatedAt(NOW);
        sqlite.upsertBlocked(local);
        mergeBlocked(rows(row("id", local.getId(), "title", "keep",
                "start_time", "09:00", "end_time", "10:00",
                "last_updated_at", NOW.toString())));
        sqlite.setMeta("last_node_sync_at", NOW.plusSeconds(60).toString());

        mergeBlocked(List.of());                      // first pull without the row
        assertNotNull(sqlite.findBlockedById(local.getId()),
                "one omission is not a remote delete");

        mergeBlocked(List.of());                      // second pull without it
        assertNull(sqlite.findBlockedById(local.getId()),
                "a confirmed remote delete still happens");
        assertTrue(sqlite.fetchBlockedDeletedIds().contains(local.getId()));
    }

    @Test
    void mergeNodeBlocked_aLocalBlockEditedAfterTheLastPull_isKept() throws Exception {
        BlockedTime local = BlockedTime.daily("kept", LocalTime.of(9, 0), LocalTime.of(10, 0));
        local.setLastUpdatedAt(NOW.plusSeconds(120));
        sqlite.upsertBlocked(local);
        sqlite.setMeta("last_node_sync_at", NOW.toString());

        assertFalse(mergeBlocked(List.of()));
        assertEquals(1, sqlite.fetchAllBlocked().size());
    }

    @Test
    void mergeNodeBlocked_aBlockAlreadyInTheDeleteLogIsNotDeletedTwice() throws Exception {
        BlockedTime local = BlockedTime.daily("del", LocalTime.of(9, 0), LocalTime.of(10, 0));
        local.setLastUpdatedAt(NOW);
        sqlite.upsertBlocked(local);
        sqlite.recordBlockedDelete(local.getId(), NOW.plusSeconds(5), "del");
        sqlite.setMeta("last_node_sync_at", NOW.plusSeconds(60).toString());

        assertFalse(mergeBlocked(List.of()));
        assertEquals(1, sqlite.fetchAllBlocked().size());
    }

    @Test
    void mergeNodeBlocked_aBlockWithNoStampAtAll_isKept() throws Exception {
        // PINNED: a blank last_updated_at is not a column default, so this row can only come
        // from mapBlocked() ignoring the NOT NULL column. The delete rule needs a stamp, so a
        // block whose stamp is missing survives every pull.
        BlockedTime local = BlockedTime.daily("nostamp", LocalTime.of(9, 0), LocalTime.of(10, 0));
        local.setLastUpdatedAt(null);
        sqlite.upsertBlocked(local);
        sqlite.setMeta("last_node_sync_at", NOW.toString());

        assertFalse(mergeBlocked(List.of()));
        assertEquals(1, sqlite.fetchAllBlocked().size(), "the upsert stamped it with now()");
    }

    // ---------- pullFromNode ----------

    @Test
    void pullFromNode_withoutATokenResetsTheFingerprintAndReturnsFalse() throws Exception {        setField(repo, "lastNodeFingerprint", "stale");
        Api.INSTANCE.logoutKeepRemembered();
        assertNull(Api.INSTANCE.getToken());

        assertFalse(repo.pullFromNode());
        assertNull(getField(repo, "lastNodeFingerprint"),
                "a token-less pull clears the cached fingerprint");
    }

    @Test
    void pullFromNode_anUnreachableNodeFailsClosedAndKeepsTheFingerprint() throws Exception {
        setField(repo, "lastNodeFingerprint", "previous-fingerprint");
        overrideNodeEnv("http://127.0.0.1:" + closedPort());
        try {
            assertFalse(repo.pullFromNode(), "an unreachable Node is not a change");
            assertEquals("previous-fingerprint", getField(repo, "lastNodeFingerprint"),
                    "a failed pull leaves the fingerprint alone");
            assertNull(sqlite.getMeta("last_node_sync_at"), "and records no sync time");
            assertTrue(sqlite.fetchAll().isEmpty());
        } finally {
            overrideNodeEnv(null);
        }
    }

    @Test
    void pullFromNode_aTokenIsNotEnoughWithoutAReachableNode() throws Exception {
        // Every guard passes except the request itself: token set, Node pointed at a dead port.
        assertNotNull(Api.INSTANCE.getToken());
        setField(repo, "lastNodeFingerprint", "still-here");
        overrideNodeEnv("http://127.0.0.1:" + closedPort());
        try {
            assertFalse(repo.pullFromNode());
            assertEquals("still-here", getField(repo, "lastNodeFingerprint"));
        } finally {
            overrideNodeEnv(null);
        }
    }

    /** Binds a port, then releases it, so a connect there is refused immediately. */
    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void pullFromNode_succeedsAgainstAScriptedNodeAndRecordsThePull() throws Exception {
        ScriptedNode server = new ScriptedNode(
                rows(row("id", "pull-1", "title", "from-node", "last_updated_at", NOW.toString())),
                rows(row("id", "pull-b1", "title", "Focus", "start_time", "09:00",
                        "end_time", "10:00")));
        serve(server);
        try {
            assertTrue(repo.pullFromNode(), "the first pull applies both lists");
            assertEquals("from-node", sqlite.findById("pull-1").getTaskName());
            assertEquals(1, sqlite.fetchAllBlocked().size());
            assertNotNull(sqlite.getMeta("last_node_sync_at"),
                    "a completed pull records when it happened");
            assertFalse(repo.pullFromNode(), "the identical second pull is skipped");
            assertTrue(server.calls >= 4, "the top-level GETs run twice");
        } finally {
            stopServer(server);
        }
    }

    /** Serves the scripted payload over the loopback address, pointed at by SCHEDULER_NODE. */
    private void serve(ScriptedNode server) throws Exception {
        server.start();
        overrideNodeEnv("http://127.0.0.1:" + server.port());
    }

    private static void stopServer(ScriptedNode server) throws Exception {
        overrideNodeEnv(null);
        server.close();
    }

    /**
     * A throwaway HTTP server that answers the two read endpoints NodeApi uses. It listens on
     * the loopback interface only, so no traffic can leave this machine.
     */
    private static final class ScriptedNode implements AutoCloseable {

        private final com.sun.net.httpserver.HttpServer http;
        private final String tasksJson;
        private final String blockedJson;
        volatile int calls;

        ScriptedNode(List<Map<String, String>> tasks, List<Map<String, String>> blocked)
                throws Exception {
            this.tasksJson = jsonObject("tasks", tasks);
            this.blockedJson = jsonObject("blocked", blocked);
            this.http = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/tasks", exchange -> reply(exchange, tasksJson));
            http.createContext("/blocked", exchange -> reply(exchange, blockedJson));
        }

        private void reply(com.sun.net.httpserver.HttpExchange exchange, String body)
                throws java.io.IOException {
            calls++;
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        void start() {
            http.start();
        }

        int port() {
            return http.getAddress().getPort();
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }

    private static String jsonObject(String key, List<Map<String, String>> rows) {
        StringBuilder sb = new StringBuilder("{\"").append(key).append("\":[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('{');
            int j = 0;
            for (Map.Entry<String, String> e : rows.get(i).entrySet()) {
                if (j++ > 0) sb.append(',');
                sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    /**
     * Sets or clears (with null) the in-process copy of SCHEDULER_NODE. This is OsEnv's own
     * overlay, so the developer's user.env and the OS environment are never written.
     */
    private static void overrideNodeEnv(String value) throws Exception {
        Field overlay = OsEnv.class.getDeclaredField("OVERLAY");
        overlay.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) overlay.get(null);
        if (value == null) {
            map.remove(Endpoints.NODE_ENV);
        } else {
            map.put(Endpoints.NODE_ENV, value);
        }
    }
}
