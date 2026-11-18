/**
 * {@code TaskRepository}'s node writers, driven through a loopback fake Node.
 *
 * <p>Needs no real server: {@code com.sun.net.httpserver.HttpServer} on {@code 127.0.0.1:0}
 * answers every request and records it, so the assertions can be about the exact verb, path,
 * headers, and body that leave the machine. This is the sync path where a silent failure loses a
 * user's edit, so it is worth testing precisely.
 *
 * <p>Wiring is the same as {@code TaskRepositoryNodeMergeTest}: the object is built with
 * {@code Unsafe.allocateInstance} (the real constructor opens the app's own cache database) and
 * handed a temp-dir {@code SqliteTaskRepository}. The session is file-only
 * ({@code LocalSession} + {@code restoreSession()}), so {@code Api.INSTANCE.getToken()} is
 * non-blank and {@code nodeReady()} is true. {@code .scheduler/session.txt} and
 * {@code login.txt} are snapshotted and restored.
 *
 * <p><b>Encryption:</b> {@code taskToJson} / {@code blockedToJson} seal {@code title} and
 * {@code description} through {@code Vault.seal}. When the vault is unlocked the body carries
 * ciphertext, so the assertions compare against {@code Vault.seal(...)} rather than against the
 * plaintext.
 */
package org.ashoreprospect.db;

import org.ashoreprospect.BlockedTime;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
import org.ashoreprospect.Workflow;
import org.ashoreprospect.crypto.Vault;
import org.ashoreprospect.net.Api;
import org.ashoreprospect.net.Endpoints;
import org.ashoreprospect.net.LocalSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRepositoryNodeWriterTest {

    private static final String UID = "writer-user";
    private static final String TOKEN = "writer-token";

    @TempDir
    Path tmp;

    private TaskRepository repo;
    private SqliteTaskRepository sqlite;
    private FakeNode node;

    private Path sessionFile;
    private Path prefsFile;
    private byte[] savedSession;
    private byte[] savedPrefs;

    @BeforeEach
    void setUp() throws Exception {
        sessionFile = Endpoints.dir().resolve("session.txt");
        prefsFile = Endpoints.dir().resolve("login.txt");
        savedSession = Files.isRegularFile(sessionFile) ? Files.readAllBytes(sessionFile) : null;
        savedPrefs = Files.isRegularFile(prefsFile) ? Files.readAllBytes(prefsFile) : null;

        LocalSession.save(UID, "writer", TOKEN);
        LocalSession.savePrefs(true, "writer");
        assertTrue(Api.INSTANCE.restoreSession(), "the file-only session loads");

        sqlite = new SqliteTaskRepository("jdbc:sqlite:" + tmp.resolve("writer.db"));
        sqlite.initialize();

        repo = allocate();
        setField(repo, "sqlite", sqlite);
        setField(repo, "historyEpoch", new java.util.concurrent.atomic.AtomicLong());
        setField(repo, "historyLock", new java.util.concurrent.locks.ReentrantLock());
        setField(repo, "postgresAvailable", false);

        node = new FakeNode();
        node.pointNodeAt();
    }

    @AfterEach
    void tearDown() throws Exception {
        node.close();
        Api.INSTANCE.logoutKeepRemembered();
        restore(sessionFile, savedSession);
        restore(prefsFile, savedPrefs);
    }

    // ---------- add ----------

    @Test
    void dbAddTask_postsOneJsonBodyToTasks() throws Exception {
        Task task = task("task-1", "Buy milk");
        task.setDurationHours(1.5);
        task.setState(1);
        task.setLifeCycle(TaskLifeCycle.ONGOING);
        task.setWorkflow(Workflow.FINISHED);

        repo.dbAddTask(task);

        assertEquals(1, node.callCount(), "exactly one push: " + node.calls());
        FakeNode.Call call = node.only();
        assertEquals("POST", call.method);
        assertEquals("/tasks", call.path);
        assertTrue(call.body.contains("\"id\":\"task-1\""), call.body);
        assertTrue(call.body.contains("\"duration\":1.5"), call.body);
        assertTrue(call.body.contains("\"state\":\"Fixed\""), call.body);
        assertTrue(call.body.contains("\"lifecycle\":\"ONGOING\""), call.body);
        assertTrue(call.body.contains("\"workflow\":\"FINISHED\""), call.body);
    }

    @Test
    void dbAddTask_sealsTheTitleSoTheBodyCarriesCiphertext() throws Exception {
        Task task = task("task-seal", "Secret errand");

        repo.dbAddTask(task);

        String body = node.only().body;
        String sealed = Vault.seal("Secret errand");
        assertTrue(body.contains(quoted("title", sealed)), body);
        if (Vault.enabled()) {
            assertFalse(body.contains("Secret errand"),
                    "an unlocked vault must not put the plaintext on the wire");
            assertTrue(sealed.startsWith(Vault.PREFIX), sealed);
        }
    }

    @Test
    void dbAddTask_sendsTheBearerTokenAndContentType() throws Exception {
        repo.dbAddTask(task("task-headers", "headers"));

        FakeNode.Call call = node.only();
        assertEquals("Bearer " + TOKEN, call.header("Authorization"));
        assertTrue(call.header("Content-Type").contains("application/json"),
                call.header("Content-Type"));
        assertEquals("application/json", call.header("Accept"));
        assertTrue(call.header("User-Agent") != null && !call.header("User-Agent").isBlank(),
                "every request identifies the device");
    }

    @Test
    void dbAddTask_stillWritesLocallyAndPushesNothingWhenSignedOut() throws Exception {
        Api.INSTANCE.logoutKeepRemembered();

        Task task = task("task-offline", "no session");
        repo.dbAddTask(task);

        assertEquals(0, node.callCount(), "no token means no push");
        try (java.sql.Connection c = sqlite.connect();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id FROM tasks WHERE id = ?")) {
            ps.setString(1, "task-offline");
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the local row is still written");
                // PINNED: with no session the row is stored with a NULL user_id, and every
                // read filters on the signed-in id, so it is invisible until claimBlockedRows
                // style ownership back-fill runs - and tasks have no such back-fill. The task
                // is written and then cannot be read back. This is the invariant
                // requireSessionForBlockedWrite() exists to stop for blocked times; tasks have
                // no equivalent guard.
                assertNull(rs.getString(1), "the row has no owner");
            }
        }
        assertNull(sqlite.findById("task-offline"),
                "PINNED: the unsigned-in write is not readable through findById");
    }

    @Test
    void dbAddTask_survivesAnUnreachableNode() throws Exception {
        node.close();

        Task task = task("task-unreachable", "node is down");
        // The whole point: the local write succeeds and nothing escapes.
        repo.dbAddTask(task);

        assertTrue(sqlite.findById("task-unreachable") != null);
    }

    @Test
    void dbAddTask_skipsThePushForATombstonedId() throws Exception {
        Task task = task("task-tombstone", "already deleted");
        sqlite.deleteTaskAndLog(task);

        repo.dbAddTask(task);

        assertEquals(0, node.callCount(), "a tombstoned id is not resurrected on Node");
        assertTrue(sqlite.findById("task-tombstone") == null);
    }

    @Test
    void idIsTaken_checksBlankTaskAndTombstoneIds() throws Exception {
        assertTrue(repo.idIsTaken(null));
        assertTrue(repo.idIsTaken("  "));

        sqlite.upsert(task("taken-task", "task"));
        sqlite.recordDelete("taken-delete", java.time.Instant.now(), "deleted");

        assertTrue(repo.idIsTaken("taken-task"));
        assertTrue(repo.idIsTaken("taken-delete"));
        assertFalse(repo.idIsTaken("unused"));
    }

    // ---------- update / delete ----------

    @Test
    void dbUpdateWorkflow_postsTheUpdatedRow() throws Exception {
        Task task = task("task-wf", "workflow");
        repo.dbAddTask(task);
        node.clear();

        task.setWorkflow(Workflow.FINISHED);
        repo.dbUpdateWorkflow(task);

        FakeNode.Call call = node.only();
        assertEquals("POST", call.method);
        assertEquals("/tasks", call.path);
        assertTrue(call.body.contains("\"workflow\":\"FINISHED\""), call.body);
    }

    @Test
    void dbClearTask_sendsDeleteWithTheId() throws Exception {
        Task task = task("task-del", "to delete");
        repo.dbAddTask(task);
        node.clear();

        repo.dbClearTask(task);

        FakeNode.Call call = node.only();
        assertEquals("DELETE", call.method);
        assertEquals("/tasks/task-del", call.path);
        assertTrue(call.body == null || call.body.isEmpty(), "a DELETE carries no body");
        assertTrue(sqlite.findById("task-del") == null);
    }

    @Test
    void dbClearTask_withANullIdPushesNothing() throws Exception {
        repo.dbClearTask(null);
        assertEquals(0, node.callCount());
    }

    @Test
    void dbClearTasks_pushesOneDeletePerTask() throws Exception {
        Task first = task("batch-1", "one");
        Task second = task("batch-2", "two");
        repo.dbAddTask(first);
        repo.dbAddTask(second);
        node.clear();

        List<Task> batch = new ArrayList<>();
        batch.add(first);
        batch.add(second);
        batch.add(null);
        repo.dbClearTasks(batch);

        List<String> paths = new ArrayList<>();
        for (FakeNode.Call c : node.calls()) paths.add(c.method + " " + c.path);
        assertEquals(List.of("DELETE /tasks/batch-1", "DELETE /tasks/batch-2"), paths,
                "a null entry in the batch is skipped, not pushed");
    }

    @Test
    void dbCleanAll_deletesEveryLocalTaskAndPushesEachDelete() throws Exception {
        repo.dbAddTask(task("clean-a", "one"));
        repo.dbAddTask(task("clean-b", "two"));
        node.clear();

        repo.dbCleanAll();

        assertTrue(sqlite.fetchAll().isEmpty());
        assertEquals(2, node.callCount());
        assertTrue(node.calls().stream().allMatch(call -> call.method.equals("DELETE")));
    }

    @Test
    void dbRestoreTasks_pushesOnePostPerRow() throws Exception {
        Task task = task("restore-1", "back again");
        repo.dbRestoreTasks(List.of(task), true);

        FakeNode.Call call = node.only();
        assertEquals("POST", call.method);
        assertEquals("/tasks", call.path);
        assertTrue(call.body.contains("\"id\":\"restore-1\""), call.body);
    }

    // ---------- blocked times ----------

    @Test
    void dbAddBlocked_postsToBlocked() throws Exception {
        BlockedTime block = BlockedTime.daily("Lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));

        repo.dbAddBlocked(block);

        FakeNode.Call call = node.only();
        assertEquals("POST", call.method);
        assertEquals("/blocked", call.path);
        assertTrue(call.body.contains("\"start_time\":\"12:00\""), call.body);
        assertTrue(call.body.contains("\"end_time\":\"13:00\""), call.body);
        assertTrue(call.body.contains("\"is_exception\":false"), call.body);
        assertTrue(call.body.contains("\"weekday\":"), call.body);
    }

    @Test
    void dbAddBlocked_refusesToWriteWithoutASession() {
        // The local row would be stored with no owner and could never be pushed, so the write is
        // refused rather than silently lost (issue #1).
        Api.INSTANCE.logoutKeepRemembered();
        BlockedTime block = BlockedTime.daily("Lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));

        java.sql.SQLException thrown =
                assertThrows(java.sql.SQLException.class, () -> repo.dbAddBlocked(block));

        assertTrue(thrown.getMessage().toLowerCase().contains("sign in"), thrown.getMessage());
        assertEquals(0, node.callCount());
    }

    @Test
    void dbClearBlocked_sendsDeleteWithTheId() throws Exception {
        BlockedTime block = BlockedTime.daily("Lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        repo.dbAddBlocked(block);
        node.clear();

        repo.dbClearBlocked(block);

        FakeNode.Call call = node.only();
        assertEquals("DELETE", call.method);
        assertEquals("/blocked/" + block.getId(), call.path);
    }

    @Test
    void dbClearBlocked_refusesToWriteWithoutASession() {
        BlockedTime block = BlockedTime.daily("Lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        Api.INSTANCE.logoutKeepRemembered();

        assertThrows(java.sql.SQLException.class, () -> repo.dbClearBlocked(block));
    }

    @Test
    void aBlockedTimeWithASpecificDateCarriesItInsteadOfAWeekday() throws Exception {
        BlockedTime once = BlockedTime.once("Trip", LocalDate.of(2026, 11, 20),
                LocalTime.of(9, 0), LocalTime.of(17, 0));

        repo.dbAddBlocked(once);

        String body = node.only().body;
        assertTrue(body.contains("\"specific_date\":\"2026-11-20\""), body);
        assertTrue(body.contains("\"weekday\":null"), body);
    }

    // ---------- wiring ----------

    private static Task task(String id, String name) {
        return Task.loadTaskFromdb(id, name, 1.0, 3, null, null, null, null, null, null);
    }

    private static String quoted(String key, String value) {
        return "\"" + key + "\":\"" + value + "\"";
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

    /**
     * Records every request and answers 200 with a small JSON object.
     *
     * <p>Kept local rather than shared with {@code TaskRepositoryNodeMergeTest}: that one only
     * answers GETs with fixed payloads, and a writer test needs the request itself.
     */
    private static final class FakeNode implements AutoCloseable {

        private final HttpServer http;
        private final List<Call> calls = new CopyOnWriteArrayList<>();

        record Call(String method, String path, Map<String, List<String>> headers, String body) {
            String header(String name) {
                for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                        List<String> values = e.getValue();
                        return values == null || values.isEmpty() ? null : values.get(0);
                    }
                }
                return null;
            }
        }

        FakeNode() throws Exception {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", exchange -> {
                byte[] raw = exchange.getRequestBody().readAllBytes();
                Map<String, List<String>> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
                calls.add(new Call(exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(), headers,
                        new String(raw, StandardCharsets.UTF_8)));
                byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            });
            http.start();
        }

        void pointNodeAt() throws Exception {
            overrideNodeEnv("http://127.0.0.1:" + http.getAddress().getPort());
        }

        List<Call> calls() {
            return List.copyOf(calls);
        }

        int callCount() {
            return calls.size();
        }

        Call only() {
            if (calls.size() != 1) {
                StringBuilder sb = new StringBuilder("expected exactly one request, got:\n");
                for (Call c : calls) sb.append("  ").append(c.method()).append(' ')
                        .append(c.path()).append('\n');
                throw new AssertionError(sb.toString());
            }
            return calls.get(0);
        }

        void clear() {
            calls.clear();
        }

        @Override
        public void close() throws Exception {
            overrideNodeEnv(null);
            http.stop(0);
        }
    }

    /** Sets or clears the in-process copy of SCHEDULER_NODE; the user's user.env is untouched. */
    @SuppressWarnings("unchecked")
    private static void overrideNodeEnv(String value) throws Exception {
        Field overlay = org.ashoreprospect.net.OsEnv.class.getDeclaredField("OVERLAY");
        overlay.setAccessible(true);
        Map<String, String> map = (Map<String, String>) overlay.get(null);
        if (value == null) {
            map.remove(Endpoints.NODE_ENV);
        } else {
            map.put(Endpoints.NODE_ENV, value);
        }
    }
}
