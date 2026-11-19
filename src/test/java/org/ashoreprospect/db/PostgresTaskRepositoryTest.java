package org.ashoreprospect.db;

import org.ashoreprospect.BlockedTime;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
import org.ashoreprospect.Workflow;
import org.ashoreprospect.net.Api;
import org.ashoreprospect.net.Endpoints;
import org.ashoreprospect.net.LocalSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("node-session")
class PostgresTaskRepositoryTest {

    private static final String USER_ID = "postgres-task-test-user";
    private static Driver testDriver;

    @TempDir
    Path tmp;

    private Path sessionFile;
    private Path prefsFile;
    private byte[] savedSession;
    private byte[] savedPrefs;
    private PostgresTaskRepository repo;

    @BeforeAll
    static void registerTestDriver() throws SQLException {
        testDriver = new PostgreSqliteDriver();
        DriverManager.registerDriver(testDriver);
    }

    @AfterAll
    static void deregisterTestDriver() throws SQLException {
        DriverManager.deregisterDriver(testDriver);
    }

    @BeforeEach
    void setUp() throws Exception {
        Path configDirectory = Endpoints.dir();
        sessionFile = configDirectory.resolve("session.txt");
        prefsFile = configDirectory.resolve("login.txt");
        savedSession = Files.isRegularFile(sessionFile) ? Files.readAllBytes(sessionFile) : null;
        savedPrefs = Files.isRegularFile(prefsFile) ? Files.readAllBytes(prefsFile) : null;

        LocalSession.save(USER_ID, "tester", "file-only-token");
        LocalSession.savePrefs(true, "tester");
        assertTrue(Api.INSTANCE.restoreSession());

        repo = new PostgresTaskRepository(
                "jdbc:postgres-task-test:" + tmp.resolve("tasks.db"), "user", "password");
        repo.initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        Api.INSTANCE.logoutKeepRemembered();
        restore(sessionFile, savedSession);
        restore(prefsFile, savedPrefs);
    }

    @Test
    void initialize_isIdempotentAndCreatesTaskAndBlockedTables() throws Exception {
        repo.initialize();

        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table'")) {
            java.util.Set<String> tables = new java.util.HashSet<>();
            while (rows.next()) tables.add(rows.getString(1));
            assertTrue(tables.containsAll(List.of("tasks", "sync_deletes", "devices",
                    "blocked_times", "sync_deletes_blocked")));
        }
    }

    @Test
    void taskRoundTrip_mapsDatesLifecycleWorkflowAndExtras() throws Exception {
        Task task = Task.loadTaskFromdb(
                "deadline-1", "Write tests", 1.5, 2, null, null,
                LocalDate.of(2026, 12, 4), LocalTime.of(17, 0),
                LocalDate.of(2026, 12, 3), LocalTime.of(9, 30));
        task.setLifeCycle(TaskLifeCycle.ONGOING);
        task.setWorkflow(Workflow.FINISHED);
        task.setPlacementUpdatedAt(Instant.parse("2026-12-01T10:00:00Z"));
        task.setLastUpdatedAt(Instant.parse("2026-12-02T10:00:00Z"));
        task.setDescription("include database tests");
        task.setDescriptionDone(true);
        task.setParentId("parent-task");

        repo.upsert(task);

        assertTrue(repo.existsTaskId("deadline-1"));
        List<Task> loadedTasks = repo.fetchAll();
        assertEquals(1, loadedTasks.size());
        Task loaded = loadedTasks.get(0);
        assertEquals("deadline-1", loaded.getID());
        assertEquals("Write tests", loaded.getTaskName());
        assertEquals(2, loaded.getState());
        assertEquals(LocalDate.of(2026, 12, 3), loaded.getPlacedDate());
        assertEquals(LocalTime.of(9, 30), loaded.getPlacedTime());
        assertEquals(LocalDate.of(2026, 12, 4), loaded.getDeadLineDate());
        assertEquals(LocalTime.of(17, 0), loaded.getDeadLineTime());
        assertEquals(TaskLifeCycle.ONGOING, loaded.getLifeCycle());
        assertEquals(Workflow.FINISHED, loaded.getWorkflow());
        assertEquals("include database tests", loaded.getDescription());
        assertTrue(loaded.isDescriptionDone());
        assertEquals("parent-task", loaded.getParentId());
        assertEquals(task.getPlacementUpdatedAt(), loaded.getPlacementUpdatedAt());
        assertEquals(task.getLastUpdatedAt(), loaded.getLastUpdatedAt());

        task.setWorkflow(Workflow.UNFINISHED);
        task.setLastUpdatedAt(Instant.parse("2026-12-03T10:00:00Z"));
        repo.updateWorkflow(task);
        assertEquals(Workflow.UNFINISHED, repo.fetchAll().get(0).getWorkflow());
    }

    @Test
    void deleteLogging_fetchingUnrecordingAndPurgingAreConsistent() throws Exception {
        Instant firstDelete = Instant.parse("2026-11-18T10:00:00Z");
        repo.recordDelete("deleted-1", firstDelete, "old title");
        repo.recordDelete("deleted-1", firstDelete.plusSeconds(60), null);

        assertTrue(repo.isDeletedId("deleted-1"));
        List<Object[]> rows = repo.fetchAllDeletes();
        assertEquals(1, rows.size());
        assertEquals("deleted-1", rows.get(0)[0]);
        assertEquals(firstDelete.plusSeconds(60), rows.get(0)[1]);
        assertEquals("old title", rows.get(0)[2]);

        repo.recordDelete("expired", firstDelete.minusSeconds(3600), "expired");
        repo.purgeDeletesBefore(firstDelete);
        assertEquals(1, repo.fetchAllDeletes().size());

        repo.unrecordDelete("deleted-1");
        assertFalse(repo.isDeletedId("deleted-1"));
        assertTrue(repo.fetchAllDeletes().isEmpty());
    }

    @Test
    void deleteAllTasksAndLog_removesOnlyCurrentUsersTasksAndRecordsTitles() throws Exception {
        Task first = task("task-1", "first");
        Task second = task("task-2", "second");
        repo.upsert(first);
        repo.upsert(second);

        repo.deleteAllTasksAndLog();

        assertTrue(repo.fetchAll().isEmpty());
        List<Object[]> deletes = repo.fetchAllDeletes();
        assertEquals(2, deletes.size());
        assertTrue(deletes.stream().anyMatch(row -> row[0].equals("task-1") && row[2].equals("first")));
        assertTrue(deletes.stream().anyMatch(row -> row[0].equals("task-2") && row[2].equals("second")));
    }

    @Test
    void blockedTimeRoundTrip_handlesDailyWeeklyAndSpecificDates() throws Exception {
        Instant at = Instant.parse("2026-11-18T10:00:00Z");
        BlockedTime daily = BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        daily.setLastUpdatedAt(at);
        BlockedTime weekly = BlockedTime.weekly("exercise", DayOfWeek.WEDNESDAY,
                LocalTime.of(18, 0), LocalTime.of(19, 0));
        weekly.setLastUpdatedAt(at);
        BlockedTime once = BlockedTime.once("appointment", LocalDate.of(2026, 12, 4),
                LocalTime.of(8, 0), LocalTime.of(9, 0));
        once.setLastUpdatedAt(at);
        once.setException(true);

        repo.upsertBlocked(daily);
        repo.upsertBlocked(weekly);
        repo.upsertBlocked(once);

        List<BlockedTime> loaded = repo.fetchAllBlocked();
        assertEquals(3, loaded.size());
        BlockedTime loadedDaily = byId(loaded, daily.getId());
        assertTrue(loadedDaily.isDaily());
        assertEquals("lunch", loadedDaily.getTitle());
        assertEquals(12, loadedDaily.getStartTime().getHour());

        BlockedTime loadedWeekly = byId(loaded, weekly.getId());
        assertEquals(DayOfWeek.WEDNESDAY, loadedWeekly.getWeekday());
        BlockedTime loadedOnce = byId(loaded, once.getId());
        assertEquals(LocalDate.of(2026, 12, 4), loadedOnce.getSpecificDate());
        assertTrue(loadedOnce.isException());
    }

    @Test
    void blockedDeleteLoggingAndPurgeTrackDeletedRows() throws Exception {
        BlockedTime blocked = BlockedTime.daily("lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));
        repo.upsertBlocked(blocked);

        repo.deleteBlockedAndLog(blocked);

        assertTrue(repo.fetchAllBlocked().isEmpty());
        assertEquals(java.util.Set.of(blocked.getId()), repo.fetchBlockedDeletedIds());
        List<Object[]> deletes = repo.fetchAllBlockedDeletes();
        assertEquals(1, deletes.size());
        assertEquals("lunch", deletes.get(0)[2]);

        repo.purgeBlockedDeletesBefore(Instant.now().plusSeconds(60));
        assertTrue(repo.fetchBlockedDeletedIds().isEmpty());
    }

    @Test
    void deleteAcknowledgementsRequireEveryActiveDevice() throws Exception {
        repo.upsertDevice("first", "First", "test");
        repo.upsertDevice("second", "Second", "test");
        repo.touchDeleteSync("first");
        repo.touchTaskSync("first");

        assertNull(repo.minDeleteAckAmongActive(6));

        repo.touchDeleteSync("second");
        assertNotNull(repo.minDeleteAckAmongActive(6));
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT last_task_sync_at FROM devices WHERE device_id = 'first'")) {
            assertTrue(rows.next());
            assertNotNull(rows.getString(1));
        }
    }

    @Test
    void staleDevicesAreRevokedAndAbandonedDataIsWiped() throws Exception {
        repo.upsertDevice("stale", "Old device", "test");
        repo.upsert(task("old-task", "old task"));
        assertFalse(repo.wipeIfAbandoned(6));

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE devices SET last_seen_at = datetime('now', '-12 months')");
        }
        repo.revokeStaleDevices(6);
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT revoked_at FROM devices")) {
            assertTrue(rows.next());
            assertNotNull(rows.getString(1));
        }

        assertTrue(repo.wipeIfAbandoned(6));
        assertTrue(repo.fetchAll().isEmpty());
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM devices")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgres-task-test:" + tmp.resolve("tasks.db"), "user", "password");
    }

    private static Task task(String id, String title) {
        Task task = Task.loadTaskFromdb(id, title, 1, 3,
                null, null, null, null, null, null);
        task.setLastUpdatedAt(Instant.parse("2026-11-18T10:00:00Z"));
        return task;
    }

    private static BlockedTime byId(List<BlockedTime> rows, String id) {
        return rows.stream().filter(row -> id.equals(row.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("missing row " + id));
    }

    private static void restore(Path file, byte[] content) throws Exception {
        if (content == null) {
            Files.deleteIfExists(file);
        } else {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        }
    }

    private static final class PostgreSqliteDriver implements Driver {
        private static final String PREFIX = "jdbc:postgres-task-test:";

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) return null;
            Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + url.substring(PREFIX.length()));
            return proxy(Connection.class, (method, args) -> {
                if (method.getName().equals("createStatement")) {
                    Statement statement = (Statement) invoke(sqlite, method, args);
                    return wrapStatement(sqlite, statement);
                }
                if (method.getName().equals("prepareStatement")) {
                    Object[] rewrittenArgs = args.clone();
                    rewrittenArgs[0] = rewriteSql((String) args[0]);
                    PreparedStatement statement =
                            (PreparedStatement) invoke(sqlite, method, rewrittenArgs);
                    return proxy(PreparedStatement.class,
                            (statementMethod, statementArgs) -> {
                                if (statementMethod.getName().equals("setTimestamp")) {
                                    statement.setString((Integer) statementArgs[0],
                                            ((java.sql.Timestamp) statementArgs[1]).toInstant().toString());
                                    return null;
                                }
                                if (statementMethod.getName().equals("setDate")) {
                                    statement.setString((Integer) statementArgs[0],
                                            ((java.sql.Date) statementArgs[1]).toLocalDate().toString());
                                    return null;
                                }
                                if (statementMethod.getName().equals("setTime")) {
                                    statement.setString((Integer) statementArgs[0],
                                            ((java.sql.Time) statementArgs[1]).toLocalTime().toString());
                                    return null;
                                }
                                Object result = invoke(statement, statementMethod, statementArgs);
                                if (statementMethod.getName().equals("executeQuery")) {
                                    return wrapResultSet((ResultSet) result);
                                }
                                return result;
                            });
                }
                return invoke(sqlite, method, args);
            });
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith(PREFIX);
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new java.sql.DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        private static Statement wrapStatement(Connection connection, Statement statement) {
            return (Statement) proxy(Statement.class, (method, args) -> {
                if (args != null && args.length > 0 && args[0] instanceof String sql) {
                    String normalized = sql.trim();
                    String upper = normalized.toUpperCase(java.util.Locale.ROOT);
                    if (upper.startsWith("CREATE SCHEMA IF NOT EXISTS TASK")) {
                        return method.getName().equals("execute") ? false : 0;
                    }
                    if (upper.startsWith("ALTER TABLE") && upper.contains(" ALTER COLUMN ")) {
                        return 0;
                    }
                    String addColumn = normalized.replace("TASK.", "");
                    java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                            "(?is)^ALTER TABLE\\s+(\\w+)\\s+ADD COLUMN IF NOT EXISTS\\s+(\\w+)\\s+(.+)$")
                            .matcher(addColumn.trim());
                    if (match.matches()) {
                        if (columnExists(connection, match.group(1), match.group(2))) return 0;
                        addColumn = "ALTER TABLE " + match.group(1) + " ADD COLUMN "
                                + match.group(2) + " " + match.group(3);
                    }
                    Object[] rewrittenArgs = args.clone();
                    rewrittenArgs[0] = rewriteSql(addColumn);
                    return invoke(statement, method, rewrittenArgs);
                }
                return invoke(statement, method, args);
            });
        }

        private static boolean columnExists(Connection connection, String table, String column)
                throws SQLException {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
                while (rows.next()) {
                    if (column.equalsIgnoreCase(rows.getString("name"))) return true;
                }
                return false;
            }
        }

        private static ResultSet wrapResultSet(ResultSet rows) {
            return (ResultSet) proxy(ResultSet.class, (method, args) -> {
                if (args != null && args.length == 1) {
                    if (method.getName().equals("getTimestamp")) {
                        String value = getString(rows, args[0]);
                        if (value == null) return null;
                        if (!value.contains("T")) {
                            value = value.replace(' ', 'T') + "Z";
                        }
                        return java.sql.Timestamp.from(Instant.parse(value));
                    }
                    if (method.getName().equals("getDate")) {
                        String value = getString(rows, args[0]);
                        return value == null ? null : java.sql.Date.valueOf(value);
                    }
                    if (method.getName().equals("getTime")) {
                        String value = getString(rows, args[0]);
                        return value == null ? null : java.sql.Time.valueOf(LocalTime.parse(value));
                    }
                }
                return invoke(rows, method, args);
            });
        }

        private static String getString(ResultSet rows, Object column) throws SQLException {
            return column instanceof Integer index
                    ? rows.getString(index)
                    : rows.getString((String) column);
        }

        private static String rewriteSql(String sql) {
            return sql.replace("TASK.", "")
                    .replace("TIMESTAMPTZ", "TEXT")
                    .replace("NOW() - (? || ' months')::INTERVAL",
                            "datetime('now', '-' || ? || ' months')")
                    .replace("NOW()", "CURRENT_TIMESTAMP")
                    .replace("GREATEST(", "MAX(");
        }

        private static <T> T proxy(Class<T> type, ProxyCall call) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> call.invoke(method, args)));
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }

        @FunctionalInterface
        private interface ProxyCall {
            Object invoke(Method method, Object[] args) throws Throwable;
        }
    }
}
