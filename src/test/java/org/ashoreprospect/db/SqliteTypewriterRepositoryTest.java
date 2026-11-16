/**
 * Local SQLite Typewriter cache: schema, meta timestamp, diary/list/page round trips, and wipes.
 *
 * <p>Each test opens its own database file under JUnit's {@code @TempDir}, so nothing is shared
 * between tests. Note that {@code fetchAll} and {@code replaceAll} delegate to
 * {@code TypewriterStore}, whose static initialiser creates {@code scheduler-cache.db} and
 * {@code typewriter-images/} in the process working directory. Those follow the JVM start-up
 * directory, which {@code System.setProperty("user.dir", ...)} cannot change, so this class
 * backs up and restores them around the run instead — see {@link TestRuntimeArtifacts}.
 */
package org.ashoreprospect.db;

import org.ashoreprospect.GUI.TypewriterPane;
import org.ashoreprospect.GUI.TypewriterStore;
import org.ashoreprospect.TestRuntimeArtifacts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteTypewriterRepositoryTest {

    private static final String UID = "user-1";
    private static final String OTHER = "user-2";
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final LocalDate TUE = LocalDate.of(2026, 10, 13);
    private static final Instant STAMP = Instant.parse("2026-10-12T07:00:00Z");

    @TempDir
    Path dir;

    private SqliteTypewriterRepository repo;

    @BeforeAll
    static void snapshotRuntimeFiles() {
        // Must run before anything loads TypewriterStore, whose static initialiser creates them.
        TestRuntimeArtifacts.snapshot();
    }

    @AfterAll
    static void removeRuntimeFiles() {
        TestRuntimeArtifacts.cleanUp();
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = newRepository("test.db");
    }

    private SqliteTypewriterRepository newRepository(String file) throws Exception {
        SqliteTypewriterRepository r = new SqliteTypewriterRepository(
                "jdbc:sqlite:" + dir.resolve(file).toString().replace('\\', '/'));
        r.initialize();
        return r;
    }

    private static TypewriterPane.TwNote note(String id, String title, LocalDate day, String body) {
        TypewriterPane.TwNote n = new TypewriterPane.TwNote(id, title, day);
        n.body = body;
        return n;
    }

    private static Map<LocalDate, TypewriterPane.TwNote> diaryOf(TypewriterPane.TwNote... notes) {
        Map<LocalDate, TypewriterPane.TwNote> m = new LinkedHashMap<>();
        for (TypewriterPane.TwNote n : notes) m.put(n.date, n);
        return m;
    }

    private static TypewriterPane.TwFolder pages() {
        return new TypewriterPane.TwFolder("root", "Pages");
    }

    // ==================================================================
    // schema
    // ==================================================================

    @Test
    void initialize_createsAllFiveTables() throws Exception {
        TreeSet<String> tables = new TreeSet<>();
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:" + dir.resolve("test.db").toString().replace('\\', '/'));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) tables.add(rs.getString(1));
        }

        assertTrue(tables.containsAll(
                List.of("tw_diary", "tw_lists", "tw_list_items", "tw_pages", "tw_meta")),
                "missing table; got " + tables);
    }

    @Test
    void initialize_isIdempotent() throws Exception {
        repo.initialize();
        repo.initialize();
        repo.initialize();

        // Still usable afterwards, and no duplicate-table error was raised.
        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "body")), List.of(), pages(), STAMP);
        assertEquals(1, repo.fetchAll(UID).diary.size());
    }

    @Test
    void initialize_leavesExistingRowsAlone() throws Exception {
        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "body")), List.of(), pages(), STAMP);

        repo.initialize();

        assertEquals(1, repo.fetchAll(UID).diary.size(), "re-initialising must not drop data");
    }

    // ==================================================================
    // lastUpdated
    // ==================================================================

    @Test
    void lastUpdated_isNullBeforeAnyWrite() throws Exception {
        assertNull(repo.lastUpdated(UID));
    }

    @Test
    void lastUpdated_nullOrBlankUserIsNull() throws Exception {
        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "x")), List.of(), pages(), STAMP);

        assertNull(repo.lastUpdated(null));
        assertNull(repo.lastUpdated(""));
        assertNull(repo.lastUpdated("   "));
    }

    @Test
    void lastUpdated_returnsTheWrittenInstant() throws Exception {
        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "x")), List.of(), pages(), STAMP);

        assertEquals(STAMP, repo.lastUpdated(UID));
    }

    @Test
    void lastUpdated_overwritesOnSecondWrite() throws Exception {
        Instant later = STAMP.plusSeconds(3600);
        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "x")), List.of(), pages(), STAMP);
        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "x")), List.of(), pages(), later);

        assertEquals(later, repo.lastUpdated(UID));
    }

    @Test
    void lastUpdated_defaultsToNowWhenStampIsNull() throws Exception {
        Instant before = Instant.now().minusSeconds(5);
        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "x")), List.of(), pages(), null);

        Instant got = repo.lastUpdated(UID);
        assertNotNull(got);
        assertTrue(got.isAfter(before), "null stamp should fall back to the current instant");
    }

    @Test
    void lastUpdated_isScopedPerUser() throws Exception {
        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "x")), List.of(), pages(), STAMP);

        assertNull(repo.lastUpdated(OTHER), "another account must not see this stamp");
    }

    @Test
    void lastUpdated_ignoresUnparseableStoredValue() throws Exception {
        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "x")), List.of(), pages(), STAMP);
        // Corrupt the stored string behind the repository's back.
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:" + dir.resolve("test.db").toString().replace('\\', '/'));
             Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE tw_meta SET last_updated_at = 'not-a-timestamp' WHERE user_id = '" + UID + "'");
        }

        assertNull(repo.lastUpdated(UID));
    }

    @Test
    void lastUpdated_treatsBlankStoredValueAsMissing() throws Exception {
        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "x")), List.of(), pages(), STAMP);
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:" + dir.resolve("test.db").toString().replace('\\', '/'));
             Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE tw_meta SET last_updated_at = '' WHERE user_id = '" + UID + "'");
        }

        assertNull(repo.lastUpdated(UID));
    }

    @Test
    void lastUpdated_acceptsAnOffsetTimestamp() throws Exception {
        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "x")), List.of(), pages(), STAMP);
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:" + dir.resolve("test.db").toString().replace('\\', '/'));
             Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE tw_meta SET last_updated_at = '2026-10-12T15:00:00+08:00' WHERE user_id = '" + UID + "'");
        }

        // Same instant as STAMP, expressed in +08:00.
        assertEquals(STAMP, repo.lastUpdated(UID));
    }

    // ==================================================================
    // fetchAll — diary
    // ==================================================================

    @Test
    void fetchAll_nullOrBlankUserIsEmpty() throws Exception {
        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "x")), List.of(), pages(), STAMP);

        assertTrue(repo.fetchAll(null).diary.isEmpty());
        assertTrue(repo.fetchAll("").diary.isEmpty());
        assertTrue(repo.fetchAll("  ").diary.isEmpty());
    }

    @Test
    void fetchAll_unknownUserIsEmpty() throws Exception {
        TypewriterStore.Data d = repo.fetchAll("nobody");

        assertTrue(d.diary.isEmpty());
        assertTrue(d.lists.isEmpty());
        assertTrue(d.pagesRoot.children.isEmpty());
        assertTrue(d.pagesRoot.notes.isEmpty());
    }

    @Test
    void fetchAll_roundTripsDiaryBodyAndDate() throws Exception {
        repo.replaceAll(UID, diaryOf(note("id-1", "day one", MON, "hello world")),
                List.of(), pages(), STAMP);

        TypewriterStore.Data d = repo.fetchAll(UID);

        assertEquals(1, d.diary.size());
        TypewriterPane.TwNote n = d.diary.get(MON);
        assertNotNull(n);
        assertEquals("hello world", n.body);
        assertEquals("day one", n.title);
    }

    @Test
    void fetchAll_keepsMultipleDiaryDaysDistinct() throws Exception {
        repo.replaceAll(UID,
                diaryOf(note("a", "mon", MON, "monday"), note("b", "tue", TUE, "tuesday")),
                List.of(), pages(), STAMP);

        TypewriterStore.Data d = repo.fetchAll(UID);

        assertEquals(2, d.diary.size());
        assertEquals("monday", d.diary.get(MON).body);
        assertEquals("tuesday", d.diary.get(TUE).body);
    }

    @Test
    void fetchAll_dropsEmptyDiaryDays() throws Exception {
        // A row with no body, no images, and (only) a title is not worth returning.
        repo.replaceAll(UID, diaryOf(note("a", "title only", MON, ""),
                note("b", "real", TUE, "content")), List.of(), pages(), STAMP);

        TypewriterStore.Data d = repo.fetchAll(UID);

        assertEquals(1, d.diary.size());
        assertTrue(d.diary.containsKey(TUE));
    }

    @Test
    void replaceAll_skipsDiaryNotesWithoutADate() throws Exception {
        Map<LocalDate, TypewriterPane.TwNote> diary = new LinkedHashMap<>();
        diary.put(MON, note("a", "dated", MON, "kept"));
        diary.put(TUE, note("b", "undated", null, "dropped"));

        repo.replaceAll(UID, diary, List.of(), pages(), STAMP);

        TypewriterStore.Data d = repo.fetchAll(UID);
        assertEquals(1, d.diary.size());
        assertTrue(d.diary.containsKey(MON));
    }

    @Test
    void fetchAll_preservesRowCountThroughDuplicateWrite() throws Exception {
        repo.replaceAll(UID, diaryOf(note("a", "first", MON, "v1")), List.of(), pages(), STAMP);
        repo.replaceAll(UID, diaryOf(note("b", "second", MON, "v2")), List.of(), pages(), STAMP);

        TypewriterStore.Data d = repo.fetchAll(UID);

        assertEquals(1, d.diary.size(), "same day must be replaced, not duplicated");
        assertEquals("v2", d.diary.get(MON).body);
    }

    // ==================================================================
    // fetchAll — lists
    // ==================================================================

    @Test
    void fetchAll_roundTripsListsAndItemsInOrder() throws Exception {
        TypewriterPane.TwList list = new TypewriterPane.TwList("list-1", "Groceries");
        list.items.add(marked(new TypewriterPane.TwItem("milk"), true));
        list.items.add(marked(new TypewriterPane.TwItem("bread"), false));
        list.items.add(marked(new TypewriterPane.TwItem("eggs"), true));

        repo.replaceAll(UID, new LinkedHashMap<>(), List.of(list), pages(), STAMP);

        TypewriterStore.Data d = repo.fetchAll(UID);
        assertEquals(1, d.lists.size());
        TypewriterPane.TwList round = d.lists.get(0);
        assertEquals("list-1", round.id);
        assertEquals("Groceries", round.title);
        assertEquals(3, round.items.size());
        assertEquals("milk", round.items.get(0).text);
        assertTrue(round.items.get(0).done);
        assertFalse(round.items.get(1).done);
        assertEquals("eggs", round.items.get(2).text);
    }

    @Test
    void fetchAll_keepsListOrderAcrossSeveralLists() throws Exception {
        List<TypewriterPane.TwList> lists = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            TypewriterPane.TwList l = new TypewriterPane.TwList("list-" + i, "List " + i);
            l.items.add(new TypewriterPane.TwItem("item-" + i));
            lists.add(l);
        }

        repo.replaceAll(UID, new LinkedHashMap<>(), lists, pages(), STAMP);

        List<TypewriterPane.TwList> back = repo.fetchAll(UID).lists;
        assertEquals(4, back.size());
        for (int i = 0; i < 4; i++) {
            assertEquals("list-" + i, back.get(i).id, "position " + i);
            assertEquals("List " + i, back.get(i).title);
            assertEquals("item-" + i, back.get(i).items.get(0).text);
        }
    }

    @Test
    void fetchAll_handlesAListWithNoItems() throws Exception {
        repo.replaceAll(UID, new LinkedHashMap<>(),
                List.of(new TypewriterPane.TwList("empty", "Empty")), pages(), STAMP);

        TypewriterStore.Data d = repo.fetchAll(UID);
        assertEquals(1, d.lists.size());
        assertTrue(d.lists.get(0).items.isEmpty());
    }

    private static TypewriterPane.TwItem marked(TypewriterPane.TwItem item, boolean done) {
        item.done = done;
        return item;
    }

    // ==================================================================
    // fetchAll — pages
    // ==================================================================

    @Test
    void fetchAll_roundTripsRootPageNotes() throws Exception {
        TypewriterPane.TwFolder root = pages();
        root.notes.add(note("p1", "root note", null, "root body"));

        repo.replaceAll(UID, new LinkedHashMap<>(), List.of(), root, STAMP);

        TypewriterPane.TwFolder back = repo.fetchAll(UID).pagesRoot;
        assertEquals(1, back.notes.size());
        assertEquals("p1", back.notes.get(0).id);
        assertEquals("root body", back.notes.get(0).body);
    }

    @Test
    void fetchAll_roundTripsNestedFolders() throws Exception {
        TypewriterPane.TwFolder root = pages();
        TypewriterPane.TwFolder projects = new TypewriterPane.TwFolder("f-proj", "Projects");
        TypewriterPane.TwFolder deep = new TypewriterPane.TwFolder("f-deep", "Deep");
        deep.notes.add(note("p-deep", "deep note", null, "deep body"));
        projects.children.add(deep);
        projects.notes.add(note("p-proj", "project note", null, "project body"));
        root.children.add(projects);

        repo.replaceAll(UID, new LinkedHashMap<>(), List.of(), root, STAMP);

        TypewriterPane.TwFolder back = repo.fetchAll(UID).pagesRoot;
        assertEquals(1, back.children.size());
        TypewriterPane.TwFolder proj = back.children.get(0);
        assertEquals("Projects", proj.title);
        assertEquals(1, proj.notes.size());
        assertEquals(1, proj.children.size());
        assertEquals("Deep", proj.children.get(0).title);
        assertEquals(1, proj.children.get(0).notes.size());
        assertEquals("deep body", proj.children.get(0).notes.get(0).body);
    }

    // ==================================================================
    // replaceAll semantics
    // ==================================================================

    @Test
    void replaceAll_nullOrBlankUserWritesNothing() throws Exception {
        repo.replaceAll(null, diaryOf(note("n", "t", MON, "body")), List.of(), pages(), STAMP);
        repo.replaceAll("", diaryOf(note("n", "t", MON, "body")), List.of(), pages(), STAMP);
        repo.replaceAll("   ", diaryOf(note("n", "t", MON, "body")), List.of(), pages(), STAMP);

        assertNull(repo.lastUpdated(UID));
        assertTrue(repo.fetchAll(UID).diary.isEmpty());
    }

    @Test
    void replaceAll_replacesTheWholeUserPayload() throws Exception {
        TypewriterPane.TwList first = new TypewriterPane.TwList("l1", "First");
        first.items.add(new TypewriterPane.TwItem("a"));
        repo.replaceAll(UID, diaryOf(note("n1", "mon", MON, "old")), List.of(first), pages(), STAMP);

        repo.replaceAll(UID, diaryOf(note("n2", "tue", TUE, "new")), List.of(), pages(), STAMP);

        TypewriterStore.Data d = repo.fetchAll(UID);
        assertEquals(1, d.diary.size());
        assertTrue(d.diary.containsKey(TUE), "the old day must be gone");
        assertTrue(d.lists.isEmpty(), "old lists must be gone");
    }

    @Test
    void replaceAll_isolatesUsers() throws Exception {
        repo.replaceAll(UID, diaryOf(note("a", "mine", MON, "user one")), List.of(), pages(), STAMP);
        repo.replaceAll(OTHER, diaryOf(note("b", "theirs", MON, "user two")), List.of(), pages(), STAMP);

        assertEquals("user one", repo.fetchAll(UID).diary.get(MON).body);
        assertEquals("user two", repo.fetchAll(OTHER).diary.get(MON).body);

        repo.replaceAll(OTHER, new LinkedHashMap<>(), List.of(), pages(), STAMP);
        assertEquals(1, repo.fetchAll(UID).diary.size(), "wiping one user must spare the other");
    }

    @Test
    void replaceAll_emptyPayloadStillStampsMeta() throws Exception {
        repo.replaceAll(UID, new LinkedHashMap<>(), List.of(), pages(), STAMP);

        TypewriterStore.Data d = repo.fetchAll(UID);
        assertTrue(d.diary.isEmpty());
        assertTrue(d.lists.isEmpty());
        assertTrue(d.pagesRoot.notes.isEmpty());
        assertEquals(STAMP, repo.lastUpdated(UID));
    }

    @Test
    void replaceAll_overloadDefaultsTheStamp() throws Exception {
        Instant before = Instant.now().minusSeconds(5);
        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "x")), List.of(), pages());

        Instant got = repo.lastUpdated(UID);
        assertNotNull(got);
        assertTrue(got.isAfter(before));
    }

    @Test
    void replaceAll_rollsBackWhenTheSchemaIsMissing() throws Exception {
        SqliteTypewriterRepository bare =
                new SqliteTypewriterRepository("jdbc:sqlite:" + dir.resolve("bare.db").toString().replace('\\', '/'));
        // Deliberately never call initialize().

        assertThrows(Exception.class, () ->
                bare.replaceAll(UID, diaryOf(note("n", "t", MON, "x")), List.of(), pages(), STAMP));
    }

    // ==================================================================
    // wipeUser / wipeAll
    // ==================================================================

    @Test
    void wipeUser_removesOnlyThatUsersRows() throws Exception {
        repo.replaceAll(UID, diaryOf(note("a", "mine", MON, "one")), List.of(), pages(), STAMP);
        repo.replaceAll(OTHER, diaryOf(note("b", "theirs", MON, "two")), List.of(), pages(), STAMP);

        repo.wipeUser(UID);

        assertTrue(repo.fetchAll(UID).diary.isEmpty());
        assertNull(repo.lastUpdated(UID));
        assertEquals(1, repo.fetchAll(OTHER).diary.size());
        assertNotNull(repo.lastUpdated(OTHER));
    }

    @Test
    void wipeUser_nullOrBlankIsANoOp() throws Exception {
        repo.replaceAll(UID, diaryOf(note("a", "mine", MON, "one")), List.of(), pages(), STAMP);

        repo.wipeUser(null);
        repo.wipeUser("");
        repo.wipeUser("   ");

        assertEquals(1, repo.fetchAll(UID).diary.size());
    }

    @Test
    void wipeUser_isIdempotent() throws Exception {
        repo.replaceAll(UID, diaryOf(note("a", "mine", MON, "one")), List.of(), pages(), STAMP);

        repo.wipeUser(UID);
        repo.wipeUser(UID);

        assertTrue(repo.fetchAll(UID).diary.isEmpty());
    }

    @Test
    void wipeUser_clearsListsAndPagesToo() throws Exception {
        TypewriterPane.TwList list = new TypewriterPane.TwList("l1", "L");
        list.items.add(new TypewriterPane.TwItem("i"));
        TypewriterPane.TwFolder root = pages();
        root.notes.add(note("p1", "p", null, "page body"));

        repo.replaceAll(UID, diaryOf(note("d1", "d", MON, "diary")), List.of(list), root, STAMP);
        repo.wipeUser(UID);

        TypewriterStore.Data d = repo.fetchAll(UID);
        assertTrue(d.diary.isEmpty());
        assertTrue(d.lists.isEmpty());
        assertTrue(d.pagesRoot.notes.isEmpty());
        assertTrue(d.pagesRoot.children.isEmpty());
    }

    @Test
    void wipeAll_clearsEveryUser() throws Exception {
        repo.replaceAll(UID, diaryOf(note("a", "mine", MON, "one")), List.of(), pages(), STAMP);
        repo.replaceAll(OTHER, diaryOf(note("b", "theirs", MON, "two")), List.of(), pages(), STAMP);

        repo.wipeAll();

        assertTrue(repo.fetchAll(UID).diary.isEmpty());
        assertTrue(repo.fetchAll(OTHER).diary.isEmpty());
        assertNull(repo.lastUpdated(UID));
        assertNull(repo.lastUpdated(OTHER));
    }

    @Test
    void wipeAll_onAnEmptyDatabaseIsFine() throws Exception {
        repo.wipeAll();
        repo.wipeAll();

        assertTrue(repo.fetchAll(UID).diary.isEmpty());
    }

    @Test
    void wipeAll_leavesTheSchemaUsable() throws Exception {
        repo.wipeAll();

        repo.replaceAll(UID, diaryOf(note("n", "t", MON, "after wipe")), List.of(), pages(), STAMP);

        assertEquals("after wipe", repo.fetchAll(UID).diary.get(MON).body);
    }

    // ==================================================================
    // persistence across instances
    // ==================================================================

    @Test
    void dataSurvivesANewRepositoryInstanceOnTheSameFile() throws Exception {
        repo.replaceAll(UID, diaryOf(note("n", "persist", MON, "written")), List.of(), pages(), STAMP);

        SqliteTypewriterRepository reopened = newRepository("test.db");

        assertEquals("written", reopened.fetchAll(UID).diary.get(MON).body);
        assertEquals(STAMP, reopened.lastUpdated(UID));
    }

    @Test
    void separateFilesAreIndependent() throws Exception {
        SqliteTypewriterRepository other = newRepository("other.db");
        repo.replaceAll(UID, diaryOf(note("n", "one", MON, "a")), List.of(), pages(), STAMP);

        assertTrue(other.fetchAll(UID).diary.isEmpty());
    }
}
