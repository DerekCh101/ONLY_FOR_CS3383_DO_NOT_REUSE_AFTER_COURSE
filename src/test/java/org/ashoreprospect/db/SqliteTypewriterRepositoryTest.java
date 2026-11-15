package org.ashoreprospect.db;

import org.ashoreprospect.GUI.TypewriterPane;
import org.ashoreprospect.GUI.TypewriterStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typewriter SQLite tables, exercised against a temp-dir database.
 *
 * <p>Needs no server: the class takes a JDBC URL, exactly like {@code SqliteTaskRepository}, and
 * the types it moves to the database ({@code TwNote}, {@code TwList}, {@code TwItem},
 * {@code TwFolder}) are plain data holders with no JavaFX dependencies.
 *
 * <p>Two side effects are unavoidable and are asserted around rather than ignored:
 * <ul>
 *   <li>{@code fetchAll()} calls {@code TypewriterStore.openLoaded(data)}, which writes back into
 *       the {@code Data} it returns. Tests therefore assert on the returned object.</li>
 *   <li>{@code TypewriterStore}'s static initialiser creates {@code typewriter-images} and opens
 *       the app's own cache; that is already the case for any test touching this package.</li>
 * </ul>
 */
class SqliteTypewriterRepositoryTest {

    private static final String USER = "tw-user";
    private static final String OTHER = "tw-other";

    @TempDir
    Path tmp;

    private SqliteTypewriterRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        repo = new SqliteTypewriterRepository("jdbc:sqlite:" + tmp.resolve("typewriter.db"));
        repo.initialize();
    }

    // ---------- schema ----------

    @Test
    void initialize_createsEveryTableAndIsIdempotent() throws Exception {
        repo.initialize();
        repo.initialize();

        try (Connection c = connect(); Statement st = c.createStatement()) {
            for (String table : new String[]{"tw_diary", "tw_lists", "tw_list_items",
                    "tw_pages", "tw_meta"}) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
                    assertTrue(rs.next(), table + " should exist");
                }
            }
        }
    }

    // ---------- lastUpdated ----------

    @Test
    void lastUpdated_blankOrMissingUserIsNull() throws Exception {
        assertNull(repo.lastUpdated(null));
        assertNull(repo.lastUpdated(""));
        assertNull(repo.lastUpdated("   "));
        assertNull(repo.lastUpdated(USER), "no row yet");
    }

    @Test
    void lastUpdated_roundTripsTheStampStoredByReplaceAll() throws Exception {
        Instant at = Instant.parse("2026-11-14T09:30:00Z");
        repo.replaceAll(USER, Map.of(), List.of(), new TypewriterPane.TwFolder("Pages"), at);

        assertEquals(at, repo.lastUpdated(USER));
    }

    @Test
    void lastUpdated_fallsBackToInstantParseAndThenToNull() throws Exception {
        writeMeta(USER, "2026-11-14T09:30:00Z");
        assertEquals(Instant.parse("2026-11-14T09:30:00Z"), repo.lastUpdated(USER),
                "a plain ISO stamp goes through the fallback parser");

        writeMeta(USER, "not a timestamp");
        assertNull(repo.lastUpdated(USER));

        writeMeta(USER, "   ");
        assertNull(repo.lastUpdated(USER));
    }

    @Test
    void lastUpdated_isPerUser() throws Exception {
        Instant mine = Instant.parse("2026-11-14T09:30:00Z");
        Instant theirs = Instant.parse("2026-11-01T00:00:00Z");
        repo.replaceAll(USER, Map.of(), List.of(), new TypewriterPane.TwFolder("Pages"), mine);
        repo.replaceAll(OTHER, Map.of(), List.of(), new TypewriterPane.TwFolder("Pages"), theirs);

        assertEquals(mine, repo.lastUpdated(USER));
        assertEquals(theirs, repo.lastUpdated(OTHER));
    }

    // ---------- fetchAll ----------

    @Test
    void fetchAll_roundTripsDiaryListsAndItems() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 14);
        TypewriterPane.TwNote note = new TypewriterPane.TwNote("Diary", day);
        note.body = "a day in the life";

        TypewriterPane.TwList list = new TypewriterPane.TwList("list-1", "Groceries");
        TypewriterPane.TwItem first = new TypewriterPane.TwItem("milk");
        TypewriterPane.TwItem second = new TypewriterPane.TwItem("eggs");
        second.done = true;
        list.items.add(first);
        list.items.add(second);

        Map<LocalDate, TypewriterPane.TwNote> diary = new LinkedHashMap<>();
        diary.put(day, note);
        repo.replaceAll(USER, diary, List.of(list), new TypewriterPane.TwFolder("Pages"));

        TypewriterStore.Data loaded = repo.fetchAll(USER);

        assertEquals(1, loaded.diary.size());
        TypewriterPane.TwNote readNote = loaded.diary.get(day);
        assertEquals("Diary", readNote.title);
        assertEquals("a day in the life", readNote.body);

        assertEquals(1, loaded.lists.size());
        TypewriterPane.TwList readList = loaded.lists.get(0);
        assertEquals("list-1", readList.id);
        assertEquals("Groceries", readList.title);
        assertEquals(2, readList.items.size());
        assertEquals("milk", readList.items.get(0).text);
        assertFalse(readList.items.get(0).done);
        assertEquals("eggs", readList.items.get(1).text);
        assertTrue(readList.items.get(1).done);
    }

    @Test
    void fetchAll_keepsListOrderByPosition() throws Exception {
        TypewriterPane.TwList first = new TypewriterPane.TwList("l1", "first");
        TypewriterPane.TwList second = new TypewriterPane.TwList("l2", "second");
        TypewriterPane.TwList third = new TypewriterPane.TwList("l3", "third");
        repo.replaceAll(USER, Map.of(), List.of(first, second, third),
                new TypewriterPane.TwFolder("Pages"));

        List<String> ids = new ArrayList<>();
        for (TypewriterPane.TwList list : repo.fetchAll(USER).lists) ids.add(list.id);

        assertEquals(List.of("l1", "l2", "l3"), ids, "lists read back in insertion order");
    }

    @Test
    void fetchAll_dropsNotesWithNoContentAndNoDate() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 14);
        TypewriterPane.TwNote empty = new TypewriterPane.TwNote("only a title", day);
        TypewriterPane.TwNote undated = new TypewriterPane.TwNote("dated note", null);
        undated.body = "has a body but no date";

        Map<LocalDate, TypewriterPane.TwNote> diary = new LinkedHashMap<>();
        diary.put(day, empty);
        diary.put(LocalDate.of(2026, 11, 15), undated);
        repo.replaceAll(USER, diary, List.of(), new TypewriterPane.TwFolder("Pages"));

        assertTrue(repo.fetchAll(USER).diary.isEmpty(),
                "a note with no body and no images is not stored at all");
    }

    @Test
    void fetchAll_skipsAnItemWhoseListIsMissing() throws Exception {
        TypewriterPane.TwList list = new TypewriterPane.TwList("kept", "Kept");
        list.items.add(new TypewriterPane.TwItem("real"));
        repo.replaceAll(USER, Map.of(), List.of(list), new TypewriterPane.TwFolder("Pages"));
        insertOrphanItem(USER, "ghost-list", "orphan");

        TypewriterStore.Data loaded = repo.fetchAll(USER);

        assertEquals(1, loaded.lists.size());
        assertEquals(1, loaded.lists.get(0).items.size(),
                "the orphan item belongs to no list and is dropped");
    }

    @Test
    void fetchAll_blankUserReturnsEmptyDataWithoutReading() throws Exception {
        TypewriterStore.Data nothing = new TypewriterStore.Data();
        assertEquals(nothing.diary.size(), repo.fetchAll(null).diary.size());
        assertEquals(nothing.lists.size(), repo.fetchAll("").lists.size());
        assertTrue(repo.fetchAll("   ").diary.isEmpty());
    }

    @Test
    void fetchAll_isPerUser() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 14);
        TypewriterPane.TwNote note = new TypewriterPane.TwNote("Mine", day);
        note.body = "private";
        repo.replaceAll(USER, Map.of(day, note), List.of(), new TypewriterPane.TwFolder("Pages"));

        assertTrue(repo.fetchAll(OTHER).diary.isEmpty());
        assertEquals(1, repo.fetchAll(USER).diary.size());
    }

    // ---------- replaceAll ----------

    @Test
    void replaceAll_replacesRatherThanAppends() throws Exception {
        LocalDate first = LocalDate.of(2026, 11, 14);
        TypewriterPane.TwNote a = new TypewriterPane.TwNote("A", first);
        a.body = "first write";
        repo.replaceAll(USER, Map.of(first, a), List.of(), new TypewriterPane.TwFolder("Pages"));

        LocalDate second = LocalDate.of(2026, 11, 15);
        TypewriterPane.TwNote b = new TypewriterPane.TwNote("B", second);
        b.body = "second write";
        repo.replaceAll(USER, Map.of(second, b), List.of(), new TypewriterPane.TwFolder("Pages"));

        TypewriterStore.Data loaded = repo.fetchAll(USER);
        assertEquals(1, loaded.diary.size(), "the earlier day is wiped, not kept");
        assertTrue(loaded.diary.containsKey(second));
        assertFalse(loaded.diary.containsKey(first));
    }

    @Test
    void replaceAll_onlyTouchesItsOwnUser() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 14);
        TypewriterPane.TwNote mine = new TypewriterPane.TwNote("Mine", day);
        mine.body = "mine";
        repo.replaceAll(USER, Map.of(day, mine), List.of(), new TypewriterPane.TwFolder("Pages"));

        TypewriterPane.TwNote theirs = new TypewriterPane.TwNote("Theirs", day);
        theirs.body = "theirs";
        repo.replaceAll(OTHER, Map.of(day, theirs), List.of(), new TypewriterPane.TwFolder("Pages"));

        assertEquals("mine", repo.fetchAll(USER).diary.get(day).body);
        assertEquals("theirs", repo.fetchAll(OTHER).diary.get(day).body);
    }

    @Test
    void replaceAll_withABlankUserWritesNothing() throws Exception {
        repo.replaceAll("", Map.of(), List.of(), new TypewriterPane.TwFolder("Pages"));
        repo.replaceAll(null, Map.of(), List.of(), new TypewriterPane.TwFolder("Pages"));

        assertEquals(0, count("tw_meta"));
    }

    @Test
    void replaceAll_defaultsTheStampToNowWhenNull() throws Exception {
        Instant before = Instant.now().minusSeconds(2);
        repo.replaceAll(USER, Map.of(), List.of(), new TypewriterPane.TwFolder("Pages"), null);

        Instant stored = repo.lastUpdated(USER);
        assertTrue(stored.isAfter(before), "a null stamp becomes the current time");
    }

    @Test
    void replaceAll_storesPagesAsFoldersAndNotes() throws Exception {
        TypewriterPane.TwFolder root = new TypewriterPane.TwFolder("root", "Pages");
        TypewriterPane.TwFolder child = new TypewriterPane.TwFolder("folder-1", "Trips");
        TypewriterPane.TwNote page = new TypewriterPane.TwNote("note-1", "Kyoto", null);
        page.body = "the temples";
        child.notes.add(page);
        root.children.add(child);

        repo.replaceAll(USER, Map.of(), List.of(), root);

        TypewriterStore.Data loaded = repo.fetchAll(USER);
        assertEquals(1, loaded.pagesRoot.children.size());
        TypewriterPane.TwFolder readChild = loaded.pagesRoot.children.get(0);
        assertEquals("folder-1", readChild.id);
        assertEquals("Trips", readChild.title);
        assertEquals(1, readChild.notes.size());
        assertEquals("note-1", readChild.notes.get(0).id);
        assertEquals("Kyoto", readChild.notes.get(0).title);
        assertEquals("the temples", readChild.notes.get(0).body);
    }

    // ---------- wipes ----------

    @Test
    void wipeUser_leavesOtherUsersAlone() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 14);
        TypewriterPane.TwNote mine = new TypewriterPane.TwNote("Mine", day);
        mine.body = "mine";
        repo.replaceAll(USER, Map.of(day, mine), List.of(), new TypewriterPane.TwFolder("Pages"));
        TypewriterPane.TwNote theirs = new TypewriterPane.TwNote("Theirs", day);
        theirs.body = "theirs";
        repo.replaceAll(OTHER, Map.of(day, theirs), List.of(), new TypewriterPane.TwFolder("Pages"));

        repo.wipeUser(USER);

        assertTrue(repo.fetchAll(USER).diary.isEmpty());
        assertNull(repo.lastUpdated(USER));
        assertEquals(1, repo.fetchAll(OTHER).diary.size());
    }

    @Test
    void wipeUser_withABlankUserIsANoOp() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 14);
        TypewriterPane.TwNote note = new TypewriterPane.TwNote("Mine", day);
        note.body = "mine";
        repo.replaceAll(USER, Map.of(day, note), List.of(), new TypewriterPane.TwFolder("Pages"));

        repo.wipeUser(null);
        repo.wipeUser("");

        assertEquals(1, repo.fetchAll(USER).diary.size());
    }

    @Test
    void wipeAll_emptiesEveryTable() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 14);
        TypewriterPane.TwNote note = new TypewriterPane.TwNote("Mine", day);
        note.body = "mine";
        TypewriterPane.TwList list = new TypewriterPane.TwList("l1", "list");
        list.items.add(new TypewriterPane.TwItem("one"));
        TypewriterPane.TwFolder root = new TypewriterPane.TwFolder("root", "Pages");
        root.children.add(new TypewriterPane.TwFolder("f1", "folder"));
        repo.replaceAll(USER, Map.of(day, note), List.of(list), root);

        repo.wipeAll();

        for (String table : new String[]{"tw_diary", "tw_lists", "tw_list_items",
                "tw_pages", "tw_meta"}) {
            assertEquals(0, count(table), table + " should be empty");
        }
    }

    // ---------- helpers ----------

    private Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + tmp.resolve("typewriter.db"));
    }

    private int count(String table) throws Exception {
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void writeMeta(String uid, String stamp) throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO tw_meta(user_id,last_updated_at) VALUES(?,?) "
                             + "ON CONFLICT(user_id) DO UPDATE SET last_updated_at = excluded.last_updated_at")) {
            ps.setString(1, uid);
            ps.setString(2, stamp);
            ps.executeUpdate();
        }
    }

    private void insertOrphanItem(String uid, String listId, String text) throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO tw_list_items(list_id,user_id,pos,text,done) VALUES(?,?,?,?,0)")) {
            ps.setString(1, listId);
            ps.setString(2, uid);
            ps.setInt(3, 0);
            ps.setString(4, text);
            ps.executeUpdate();
        }
    }
}
