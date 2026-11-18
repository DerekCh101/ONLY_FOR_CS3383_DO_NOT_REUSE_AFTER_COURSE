package org.ashoreprospect.db;

import org.ashoreprospect.GUI.TypewriterPane;
import org.ashoreprospect.GUI.TypewriterStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresTypewriterRepositoryTest {

    private static final String USER = "pg-tw-user";
    private static final String OTHER = "pg-tw-other";
    private static Driver testDriver;

    @TempDir
    Path tmp;

    private PostgresTypewriterRepository repo;

    @BeforeAll
    static void registerTestDriver() throws SQLException {
        testDriver = new SQLitePostgresCompatDriver();
        DriverManager.registerDriver(testDriver);
    }

    @AfterAll
    static void deregisterTestDriver() throws SQLException {
        DriverManager.deregisterDriver(testDriver);
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = new PostgresTypewriterRepository(
                "jdbc:postgres-test:" + tmp.resolve("typewriter.db"), "user", "password");
        repo.initialize();
    }

    @Test
    void initialize_isIdempotentAndCreatesAllTables() throws Exception {
        repo.initialize();
        repo.initialize();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String table : new String[]{"tw_diary", "tw_lists", "tw_list_items",
                    "tw_pages", "tw_meta"}) {
                try (ResultSet rows = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
                    assertTrue(rows.next(), table + " should exist");
                }
            }
        }
    }

    @Test
    void blankUsersAreNoOpsAndMissingTimestampIsNull() throws Exception {
        PostgresTypewriterRepository offline = new PostgresTypewriterRepository(
                "jdbc:invalid:", "user", "password");

        assertNull(offline.lastUpdated(null));
        assertNull(offline.lastUpdated(""));
        assertNull(offline.lastUpdated("  "));
        assertTrue(offline.fetchAll(null).diary.isEmpty());
        assertTrue(offline.fetchAll("").lists.isEmpty());
        offline.replaceAll(null, Map.of(), List.of(), new TypewriterPane.TwFolder("Pages"));
        offline.wipeUser(" ");
        assertNull(repo.lastUpdated(USER));
    }

    @Test
    void replaceAllAndFetchAll_roundTripDiaryListsAndNestedPagesPerUser() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 18);
        TypewriterPane.TwNote diaryNote = new TypewriterPane.TwNote("Diary", day);
        diaryNote.body = "encrypted body";
        TypewriterPane.TwList list = new TypewriterPane.TwList("list-1", "Groceries");
        list.items.add(new TypewriterPane.TwItem("milk"));
        TypewriterPane.TwItem done = new TypewriterPane.TwItem("eggs");
        done.done = true;
        list.items.add(done);

        TypewriterPane.TwFolder root = new TypewriterPane.TwFolder("root", "Pages");
        TypewriterPane.TwFolder child = new TypewriterPane.TwFolder("folder-1", "Trips");
        TypewriterPane.TwNote page = new TypewriterPane.TwNote("page-1", "Kyoto", null);
        page.body = "temples";
        File image = Files.createFile(tmp.resolve("kyoto.png")).toFile();
        page.images.add(image);
        child.notes.add(page);
        root.children.add(child);

        repo.replaceAll(USER, Map.of(day, diaryNote), List.of(list), root);
        TypewriterStore.Data loaded = repo.fetchAll(USER);

        assertEquals("Diary", loaded.diary.get(day).title);
        assertEquals("encrypted body", loaded.diary.get(day).body);
        assertEquals(1, loaded.lists.size());
        assertEquals("Groceries", loaded.lists.get(0).title);
        assertEquals(List.of("milk", "eggs"),
                loaded.lists.get(0).items.stream().map(item -> item.text).toList());
        assertFalse(loaded.lists.get(0).items.get(0).done);
        assertTrue(loaded.lists.get(0).items.get(1).done);

        TypewriterPane.TwFolder loadedFolder = loaded.pagesRoot.children.get(0);
        assertEquals("folder-1", loadedFolder.id);
        assertEquals("Trips", loadedFolder.title);
        assertEquals("page-1", loadedFolder.notes.get(0).id);
        assertEquals("temples", loadedFolder.notes.get(0).body);
        assertEquals(image.getAbsolutePath(),
                loadedFolder.notes.get(0).images.get(0).getAbsolutePath());
        assertTrue(repo.fetchAll(OTHER).diary.isEmpty());

    }

    @Test
    void replaceAll_skipsEmptyAndUndatedNotesAndReplacesPreviousRows() throws Exception {
        LocalDate firstDay = LocalDate.of(2026, 11, 17);
        TypewriterPane.TwNote first = new TypewriterPane.TwNote("First", firstDay);
        first.body = "body";
        repo.replaceAll(USER, Map.of(firstDay, first), List.of(),
                new TypewriterPane.TwFolder("Pages"));

        LocalDate secondDay = LocalDate.of(2026, 11, 18);
        TypewriterPane.TwNote second = new TypewriterPane.TwNote("Second", secondDay);
        second.body = "new body";
        TypewriterPane.TwNote undated = new TypewriterPane.TwNote("Undated", null);
        undated.body = "not saved";
        TypewriterPane.TwNote empty = new TypewriterPane.TwNote("Title only", secondDay);
        Map<LocalDate, TypewriterPane.TwNote> diary = new LinkedHashMap<>();
        diary.put(secondDay, second);
        diary.put(LocalDate.of(2026, 11, 19), undated);
        diary.put(LocalDate.of(2026, 11, 20), empty);
        repo.replaceAll(USER, diary, List.of(), new TypewriterPane.TwFolder("Pages"));

        TypewriterStore.Data loaded = repo.fetchAll(USER);
        assertEquals(1, loaded.diary.size());
        assertEquals("Second", loaded.diary.get(secondDay).title);
        assertFalse(loaded.diary.containsKey(firstDay));
    }

    @Test
    void lastUpdated_tracksEachUsersReplaceAndWipe() throws Exception {
        repo.replaceAll(USER, Map.of(), List.of(), new TypewriterPane.TwFolder("Pages"));
        repo.replaceAll(OTHER, Map.of(), List.of(), new TypewriterPane.TwFolder("Pages"));

        assertNotNull(repo.lastUpdated(USER));
        assertNotNull(repo.lastUpdated(OTHER));

        repo.wipeUser(USER);
        assertNull(repo.lastUpdated(USER));
        assertNotNull(repo.lastUpdated(OTHER));
    }

    @Test
    void fetchAll_dropsItemsWithNoMatchingList() throws Exception {
        TypewriterPane.TwList list = new TypewriterPane.TwList("kept", "Kept");
        list.items.add(new TypewriterPane.TwItem("real"));
        repo.replaceAll(USER, Map.of(), List.of(list), new TypewriterPane.TwFolder("Pages"));
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO tw_list_items(list_id,user_id,pos,text,done) VALUES(?,?,?,?,?)")) {
            statement.setString(1, "missing-list");
            statement.setString(2, USER);
            statement.setInt(3, 5);
            statement.setString(4, "orphan");
            statement.setBoolean(5, false);
            statement.executeUpdate();
        }

        TypewriterStore.Data loaded = repo.fetchAll(USER);
        assertEquals(1, loaded.lists.size());
        assertEquals(1, loaded.lists.get(0).items.size());
        assertEquals("real", loaded.lists.get(0).items.get(0).text);
    }

    @Test
    void wipeUser_removesOnlyTheRequestedUsersRows() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 18);
        TypewriterPane.TwNote mine = new TypewriterPane.TwNote("Mine", day);
        mine.body = "mine";
        TypewriterPane.TwNote theirs = new TypewriterPane.TwNote("Theirs", day);
        theirs.body = "theirs";
        repo.replaceAll(USER, Map.of(day, mine), List.of(), new TypewriterPane.TwFolder("Pages"));
        repo.replaceAll(OTHER, Map.of(day, theirs), List.of(), new TypewriterPane.TwFolder("Pages"));

        repo.wipeUser(USER);
        repo.wipeUser("");

        assertTrue(repo.fetchAll(USER).diary.isEmpty());
        assertEquals("Theirs", repo.fetchAll(OTHER).diary.get(day).title);
        assertNull(repo.lastUpdated(USER));
        assertNotNull(repo.lastUpdated(OTHER));
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:postgres-test:" + tmp.resolve("typewriter.db"),
                "user", "password");
    }

    private static final class SQLitePostgresCompatDriver implements Driver {
        private static final String PREFIX = "jdbc:postgres-test:";

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) return null;
            Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + url.substring(PREFIX.length()));
            return proxy(Connection.class, sqlite, (method, args) -> {
                if (method.getName().equals("createStatement")) {
                    Statement statement = (Statement) invoke(sqlite, method, args);
                    return wrapStatement(statement, false);
                }
                if (method.getName().equals("prepareStatement")) {
                    Object[] rewrittenArgs = args.clone();
                    rewrittenArgs[0] = rewriteSql((String) args[0]);
                    PreparedStatement statement =
                            (PreparedStatement) invoke(sqlite, method, rewrittenArgs);
                    return wrapStatement(statement, true);
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

        private static Object wrapStatement(Statement statement, boolean prepared) {
            Class<?> type = prepared ? PreparedStatement.class : Statement.class;
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> {
                        if (!prepared && args != null && args.length > 0
                                && args[0] instanceof String sql) {
                            if (sql.startsWith("CREATE SCHEMA IF NOT EXISTS TASK")) {
                                return method.getName().equals("execute") ? false : 0;
                            }
                            Object[] rewrittenArgs = args.clone();
                            rewrittenArgs[0] = rewriteSql(sql);
                            return invoke(statement, method, rewrittenArgs);
                        }
                        return invoke(statement, method, args);
                    });
        }

        private static String rewriteSql(String sql) {
            return sql.replace("TASK.", "")
                    .replace("TIMESTAMPTZ", "TEXT")
                    .replace("DEFAULT NOW()", "DEFAULT CURRENT_TIMESTAMP")
                    .replace("NOW()", "CURRENT_TIMESTAMP");
        }

        private static <T> T proxy(Class<T> type, Object target, ProxyCall call) {
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
