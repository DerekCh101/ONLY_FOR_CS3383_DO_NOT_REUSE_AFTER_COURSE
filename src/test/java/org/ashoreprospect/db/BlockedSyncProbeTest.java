/**
 * Probes for the bug reports - investigations that print their answer, not regression tests.
 *
 * <p>Each probe drives real production code against a temp-dir SQLite store and, where a network
 * call is involved, a loopback HTTP server on 127.0.0.1 handed over through OsEnv's in-process
 * overlay. Nothing leaves the machine, and the developer's user.env is never written.
 *
 * <p>Assertions record what was OBSERVED. A passing assertion therefore documents current
 * behaviour and may describe a bug - read the message.
 * <ul>
 *   <li>A: does a signed-in dbAddBlocked() reach the Node POST?</li>
 *   <li>B/B2: is a blocked period created while signed out ever sent, and is it orphaned?</li>
 *   <li>C: does an older row from Node make every merge look changed?</li>
 *   <li>D: does that row then get swept (deleted locally) when Node omits it once?</li>
 * </ul>
 */
package org.ashoreprospect.db;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.ashoreprospect.BlockedTime;
import org.ashoreprospect.net.Api;
import org.ashoreprospect.net.Endpoints;
import org.ashoreprospect.net.LocalSession;
import org.ashoreprospect.net.OsEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("node-session")
class BlockedSyncProbeTest {

    private static final String UID = "probe-user";
    private static final Instant NOW = Instant.parse("2026-10-12T07:00:00Z");

    @TempDir
    Path tmp;

    private TaskRepository repo;
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

        sqlite = new SqliteTaskRepository("jdbc:sqlite:" + tmp.resolve("probe.db"));
        sqlite.initialize();

        repo = allocate();
        setField(repo, "sqlite", sqlite);
        setField(repo, "historyEpoch", new AtomicLong());
        setField(repo, "historyLock", new ReentrantLock());

        signIn();
    }

    @AfterEach
    void tearDown() throws Exception {
        Api.INSTANCE.logout();
        restore(sessionFile, savedSession);
        restore(prefsFile, savedPrefs);
        overrideNodeEnv(null);
    }

    private void signIn() throws Exception {
        LocalSession.save(UID, "probe", "file-only-token");
        LocalSession.savePrefs(true, "probe");
        assertTrue(Api.INSTANCE.restoreSession(), "the file-only session loads");
    }

    // ---------- Probe A: does dbAddBlocked() POST to Node? ----------

    @Test
    void probeA_dbAddBlockedPostsToNodeWhenSignedIn() throws Exception {
        try (CapturingNode node = new CapturingNode()) {
            overrideNodeEnv("http://127.0.0.1:" + node.port());

            BlockedTime b = BlockedTime.daily("Dentist", LocalTime.of(9, 0), LocalTime.of(10, 0));
            repo.dbAddBlocked(b);

            List<String> writes = node.writes();
            System.out.println("PROBE-A requests: " + node.all());
            assertFalse(writes.isEmpty(),
                    "PROBE-A RESULT: no write reached Node with a token present");
            assertTrue(writes.get(0).startsWith("POST /blocked"),
                    "PROBE-A RESULT: unexpected write: " + writes.get(0));
            assertTrue(writes.get(0).contains("Dentist"),
                    "PROBE-A RESULT: body missing the title: " + writes.get(0));
            System.out.println("PROBE-A RESULT: the signed-in push path WORKS");
        }
    }

    // ---------- Probe B: is a signed-out block ever sent? ----------

    @Test
    void probeB_aBlockCreatedWhileSignedOutIsNeverSentToNode() throws Exception {
        Api.INSTANCE.logout();
        assertNull(Api.INSTANCE.getToken(), "precondition: no token");

        BlockedTime b = BlockedTime.daily("Offline", LocalTime.of(9, 0), LocalTime.of(10, 0));
        repo.dbAddBlocked(b);

        // Sign in, then pull. Nothing has pushed the row in between.
        signIn();
        try (CapturingNode node = new CapturingNode()) {
            overrideNodeEnv("http://127.0.0.1:" + node.port());
            repo.pullFromNode();

            List<String> writes = node.writes();
            System.out.println("PROBE-B requests: " + node.all());
            assertTrue(writes.isEmpty(),
                    "PROBE-B RESULT: a block created while signed out WAS re-sent: " + writes);
            System.out.println("PROBE-B RESULT: no backlog flush exists - the row is never sent");
        }
    }

    @Test
    void probeB2_aBlockWrittenWhileSignedOutIsOrphanedForever() throws Exception {
        Api.INSTANCE.logout();
        BlockedTime b = BlockedTime.daily("Offline", LocalTime.of(9, 0), LocalTime.of(10, 0));
        repo.dbAddBlocked(b);
        boolean whileOut = sqlite.findBlockedById(b.getId()) != null;

        signIn();
        boolean whileIn = sqlite.findBlockedById(b.getId()) != null;
        String owner = userIdOf(b.getId());
        boolean rawRowExists = rowExists(b.getId());

        System.out.println("PROBE-B2 rowExists=" + rawRowExists + " user_id=" + owner
                + " visibleWhileSignedOut=" + whileOut + " visibleWhileSignedIn=" + whileIn);

        assertTrue(rawRowExists, "the row is physically in the table");
        assertNull(owner, "user_id is NULL, because it was written with no session");
        assertFalse(whileOut, "hidden while signed out (user-id filter -> WHERE 1 = 0)");
        // PINNED BUG: blocked_times reads use "user_id = ?" with no "OR user_id IS NULL", so the
        // row can never be matched again, by any user, at any point.
        assertFalse(whileIn,
                "PROBE-B2 RESULT: CONFIRMED - the row stays invisible even after signing in, "
                        + "because blocked reads never match a NULL user_id");
    }

    // ---------- Probe C: does an older row keep the merge "changed"? ----------

    @Test
    void probeC_anOlderRowFromNodeIsReMergedEveryTime() throws Exception {
        BlockedTime local = BlockedTime.daily("mine", LocalTime.of(9, 0), LocalTime.of(10, 0));
        local.setLastUpdatedAt(NOW);
        sqlite.upsertBlocked(local);

        Map<String, String> older = row("id", local.getId(), "title", "mine",
                "start_time", "09:00", "end_time", "10:00",
                "last_updated_at", NOW.minusSeconds(43_200).toString());

        boolean first = mergeBlocked(List.of(older));
        boolean second = mergeBlocked(List.of(older));
        Instant stored = sqlite.findBlockedById(local.getId()).getLastUpdatedAt();

        System.out.println("PROBE-C first=" + first + " second=" + second + " storedStamp=" + stored);
        assertTrue(first, "PROBE-C RESULT: the first merge reports a change");
        // PINNED BUG: the identical payload never settles. Because mergeBlocked returns the local
        // object unchanged, the stored stamp stays at NOW and can never equal the remote one, so
        // sameBlockedStamp at L629 never matches and every merge writes again.
        assertTrue(second,
                "PROBE-C RESULT: CONFIRMED - a repeated identical payload is re-merged");
        assertTrue(NOW.equals(stored), "the stored stamp never moved to the remote value");
    }

    // ---------- Probe D: is that row swept when Node omits it? ----------

    @Test
    void probeD_aStillPresentRowIsSweptWhenNodeOmitsItOnce() throws Exception {
        BlockedTime local = BlockedTime.daily("mine", LocalTime.of(9, 0), LocalTime.of(10, 0));
        local.setLastUpdatedAt(NOW);
        sqlite.upsertBlocked(local);

        Map<String, String> older = row("id", local.getId(), "title", "mine",
                "start_time", "09:00", "end_time", "10:00",
                "last_updated_at", NOW.minusSeconds(43_200).toString());
        mergeBlocked(List.of(older));                        // pull A
        sqlite.setMeta("last_node_sync_at", NOW.plusSeconds(60).toString());

        boolean changed = mergeBlocked(List.of());           // pull B: Node omits the row
        boolean stillLocal = sqlite.findBlockedById(local.getId()) != null;

        System.out.println("PROBE-D changed=" + changed + " stillLocal=" + stillLocal
                + " tombstones=" + sqlite.fetchBlockedDeletedIds());
        assertTrue(changed, "PROBE-D RESULT: the sweep fired");
        // PINNED BUG: a block Node still has is deleted and tombstoned because its local stamp
        // fell behind the pull stamp. One partial response is enough.
        assertFalse(stillLocal,
                "PROBE-D RESULT: CONFIRMED - a still-present block was deleted locally");
        assertTrue(sqlite.fetchBlockedDeletedIds().contains(local.getId()),
                "PROBE-D RESULT: and tombstoned, so it cannot be re-added");
    }

    // ---------- helpers ----------

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private boolean mergeBlocked(List<Map<String, String>> rows) throws Exception {
        Method m = TaskRepository.class.getDeclaredMethod("mergeNodeBlocked", List.class);
        m.setAccessible(true);
        return (boolean) m.invoke(repo, rows);
    }

    /** Reads the raw user_id column, bypassing the DAO's user filter. */
    private String userIdOf(String id) throws java.sql.SQLException {
        try (var conn = sqlite.connect();
             var ps = conn.prepareStatement("SELECT user_id FROM blocked_times WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "(no row)";
            }
        }
    }

    private boolean rowExists(String id) throws java.sql.SQLException {
        try (var conn = sqlite.connect();
             var ps = conn.prepareStatement("SELECT 1 FROM blocked_times WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
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

    private static void restore(Path file, byte[] content) throws Exception {
        if (content == null) {
            Files.deleteIfExists(file);
        } else {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        }
    }

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

    /** Records "METHOD /path body" for every request, and answers everything with 200 {}. */
    private static final class CapturingNode implements AutoCloseable {

        private final HttpServer http;
        private final List<String> log = new CopyOnWriteArrayList<>();

        CapturingNode() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", this::handle);
            http.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            log.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + (body.isEmpty() ? "" : " " + body));
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (var os = exchange.getResponseBody()) {
                os.write(out);
            }
        }

        int port() {
            return http.getAddress().getPort();
        }

        List<String> all() {
            return List.copyOf(log);
        }

        List<String> writes() {
            return log.stream().filter(s -> !s.startsWith("GET ")).toList();
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }
}
