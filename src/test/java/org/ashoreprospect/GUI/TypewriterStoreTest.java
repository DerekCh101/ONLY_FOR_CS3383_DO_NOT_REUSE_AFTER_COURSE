package org.ashoreprospect.GUI;

import org.ashoreprospect.crypto.Vault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("node-session")
@ResourceLock("vault")
class TypewriterStoreTest {

    @AfterEach
    void lockVault() {
        Vault.lock();
    }

    @Test
    void pruneEmptyDiaryKeepsWrittenAndImageOnlyDays() {
        TypewriterPane.TwNote empty = new TypewriterPane.TwNote("empty", null);
        empty.body = "<p><br></p>";
        TypewriterPane.TwNote written = new TypewriterPane.TwNote("written", null);
        written.body = "<p>text</p>";
        TypewriterPane.TwNote imageOnly = new TypewriterPane.TwNote("photo", null);
        imageOnly.images.add(Path.of("photo.png").toFile());

        Map<LocalDate, TypewriterPane.TwNote> diary = new LinkedHashMap<>();
        diary.put(LocalDate.of(2026, 1, 1), empty);
        diary.put(LocalDate.of(2026, 1, 2), written);
        diary.put(LocalDate.of(2026, 1, 3), imageOnly);
        diary.put(LocalDate.of(2026, 1, 4), null);

        assertEquals(2, TypewriterStore.pruneEmptyDiary(diary));
        assertEquals(List.of(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 3)),
                new ArrayList<>(diary.keySet()));
        assertFalse(TypewriterStore.hasContent(empty));
        assertTrue(TypewriterStore.hasContent(imageOnly));
        assertEquals(0, TypewriterStore.pruneEmptyDiary(null));
    }

    @Test
    void snapshotRoundTripsDiaryListsAndNestedPages() {
        Vault.lock();
        LocalDate day = LocalDate.of(2026, 10, 12);
        TypewriterPane.TwNote diaryNote = new TypewriterPane.TwNote("diary-id", "Diary \"title\"", day);
        diaryNote.body = "<p>First line</p><p>Second \\ line</p>";
        TypewriterPane.TwNote empty = new TypewriterPane.TwNote("empty", day.plusDays(1));
        Map<LocalDate, TypewriterPane.TwNote> diary = new LinkedHashMap<>();
        diary.put(day, diaryNote);
        diary.put(day.plusDays(1), empty);

        TypewriterPane.TwList list = new TypewriterPane.TwList("list-id", "Shopping");
        list.items.add(new TypewriterPane.TwItem("milk"));
        TypewriterPane.TwItem done = new TypewriterPane.TwItem("bread \"whole\"");
        done.done = true;
        list.items.add(done);

        TypewriterPane.TwFolder root = new TypewriterPane.TwFolder("Pages");
        TypewriterPane.TwFolder folder = new TypewriterPane.TwFolder("folder-id", "Travel");
        TypewriterPane.TwNote page = new TypewriterPane.TwNote("page-id", "Kyoto", null);
        page.body = "<p>Plans</p>";
        folder.notes.add(page);
        root.children.add(folder);

        String json = TypewriterStore.snapshotJson(diary, List.of(list), root);
        TypewriterStore.Data restored = TypewriterStore.fromSnapshotJson(json);

        assertEquals(1, restored.diary.size());
        assertEquals("Diary \"title\"", restored.diary.get(day).title);
        assertEquals(diaryNote.body, restored.diary.get(day).body);
        assertEquals(1, restored.lists.size());
        assertEquals("Shopping", restored.lists.get(0).title);
        assertEquals("bread \"whole\"", restored.lists.get(0).items.get(1).text);
        assertTrue(restored.lists.get(0).items.get(1).done);
        assertFalse(restored.lists.get(0).items.get(0).done);
        assertEquals("Travel", restored.pagesRoot.children.get(0).title);
        assertEquals("Kyoto", restored.pagesRoot.children.get(0).notes.get(0).title);
        assertEquals("<p>Plans</p>", restored.pagesRoot.children.get(0).notes.get(0).body);
    }

    @Test
    void copyTreeSeparatesFolderStructure() {
        TypewriterPane.TwFolder source = new TypewriterPane.TwFolder("Pages");
        TypewriterPane.TwFolder child = new TypewriterPane.TwFolder("child", "Folder");
        child.notes.add(new TypewriterPane.TwNote("note", "Note", null));
        source.children.add(child);
        TypewriterPane.TwFolder target = new TypewriterPane.TwFolder("old");
        target.notes.add(new TypewriterPane.TwNote("old", null));

        TypewriterStore.copyTree(source, target);
        target.children.get(0).title = "Renamed";
        target.children.get(0).notes.clear();

        assertNotSame(child, target.children.get(0));
        assertEquals("Folder", child.title);
        assertEquals(1, child.notes.size());
        assertTrue(target.notes.isEmpty());
    }

    @Test
    void legacyImagePathsIgnoreMissingFilesAndStopAtTwenty(@TempDir Path temp) throws Exception {
        List<Path> images = new ArrayList<>();
        for (int i = 0; i < 21; i++) images.add(Files.createFile(temp.resolve("image" + i + ".png")));
        StringBuilder paths = new StringBuilder(temp.resolve("missing.png").toString());
        for (Path image : images) paths.append('|').append(image);
        TypewriterPane.TwNote note = new TypewriterPane.TwNote("note", null);

        TypewriterStore.addImages(note, paths.toString());

        assertEquals(20, note.images.size());
        assertEquals(images.get(0).toFile(), note.images.get(0));
        assertEquals(images.get(19).toFile(), note.images.get(19));
        assertFalse(TypewriterStore.joinImages(note).contains("missing.png"));
    }

    @Test
    void sqlitePagesPreserveNestedFoldersAndNotes() throws Exception {
        Vault.lock();
        TypewriterPane.TwFolder root = new TypewriterPane.TwFolder("Pages");
        TypewriterPane.TwFolder folder = new TypewriterPane.TwFolder("f1", "Trips");
        TypewriterPane.TwFolder nested = new TypewriterPane.TwFolder("f2", "Japan");
        TypewriterPane.TwNote note = new TypewriterPane.TwNote("n1", "Kyoto", null);
        note.body = "<p>Tea</p>";
        nested.notes.add(note);
        folder.children.add(nested);
        root.children.add(folder);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().execute("CREATE TABLE tw_pages (id TEXT, user_id TEXT, "
                    + "parent_id TEXT, kind TEXT, title TEXT, body TEXT, images TEXT, pos INTEGER)");
            TypewriterStore.savePagesSqlite(connection, "user", root, null, new int[]{0});
            TypewriterPane.TwFolder restored = new TypewriterPane.TwFolder("Pages");
            TypewriterStore.rebuildPages(connection, "user", restored, false);

            TypewriterPane.TwFolder restoredNested = restored.children.get(0).children.get(0);
            assertEquals("Japan", restoredNested.title);
            assertEquals("Kyoto", restoredNested.notes.get(0).title);
            assertEquals("<p>Tea</p>", restoredNested.notes.get(0).body);
        }
    }

    @Test
    void snapshotImportSkipsEmptyOrInvalidDaysAndRetainsOrphanPages() {
        String json = """
                {"diary":[
                  {"id":"bad","date":"not-a-date","title":"Bad","body":"writing","images":[]},
                  {"id":"empty","date":"2026-10-12","title":"Empty","body":"<p><br></p>","images":[]},
                  {"id":"good","date":"2026-10-13","title":"Written","body":"<p>Text</p>","images":[]}
                ],"lists":[{"id":"l","title":"Tasks","items":[
                  {"text":"One","done":1},{"text":"Two","done":false}
                ]}],"pages":[
                  {"id":"orphan","parent_id":"missing","kind":"note","title":"Loose","body":"Note","images":[]},
                  {"id":"child","parent_id":"parent","kind":"folder","title":"Child"},
                  {"id":"parent","parent_id":null,"kind":"folder","title":"Parent"},
                  {"id":"page","parent_id":"child","kind":"note","title":"Nested","body":"Text","images":[]}
                ]}
                """;

        TypewriterStore.Data restored = TypewriterStore.fromSnapshotJson(json);

        assertEquals(List.of(LocalDate.of(2026, 10, 13)), new ArrayList<>(restored.diary.keySet()));
        assertEquals("<p>Text</p>", restored.diary.get(LocalDate.of(2026, 10, 13)).body);
        assertTrue(restored.lists.get(0).items.get(0).done);
        assertFalse(restored.lists.get(0).items.get(1).done);
        assertEquals("Loose", restored.pagesRoot.notes.get(0).title);
        TypewriterPane.TwFolder child = restored.pagesRoot.children.get(0).children.get(0);
        assertEquals("Child", child.title);
        assertEquals("Nested", child.notes.get(0).title);
    }

    @Test
    void openLoadedNormalizesDiaryListAndNestedPageContent() {
        TypewriterStore.Data data = new TypewriterStore.Data();
        TypewriterPane.TwNote diary = new TypewriterPane.TwNote("Diary", LocalDate.of(2026, 10, 12));
        diary.body = "<html><body><p>Diary text</p></body></html>";
        data.diary.put(diary.date, diary);
        TypewriterPane.TwList list = new TypewriterPane.TwList("List");
        list.items.add(new TypewriterPane.TwItem("<html><body><b>Item</b></body></html>"));
        data.lists.add(list);
        TypewriterPane.TwFolder child = new TypewriterPane.TwFolder("Folder");
        TypewriterPane.TwNote page = new TypewriterPane.TwNote("Page", null);
        page.body = "<html><body><p>Page text</p></body></html>";
        child.notes.add(page);
        data.pagesRoot.children.add(child);

        TypewriterStore.openLoaded(data);

        assertEquals("<p>Diary text</p>", diary.body);
        assertEquals("<b>Item</b>", list.items.get(0).text);
        assertEquals("<p>Page text</p>", page.body);
    }

    @Test
    void embeddedImageIsRestoredToTheIsolatedCache() throws Exception {
        String id = "test-" + UUID.randomUUID();
        Path cached = Path.of("typewriter-images", id + ".jpg");
        byte[] image = {1, 2, 3, 4};
        String added = "2024-01-02T03:04:05Z";
        String json = "[{\"id\":\"" + id + "\",\"name\":\"photo.jpeg\",\"data\":\""
                + Base64.getEncoder().encodeToString(image) + "\",\"added_at\":\"" + added + "\"}]";
        TypewriterPane.TwNote note = new TypewriterPane.TwNote("Page", null);

        try {
            TypewriterStore.addImages(note, json);

            assertEquals(1, note.images.size());
            assertEquals(cached.toAbsolutePath(), note.images.get(0).toPath().toAbsolutePath());
            assertArrayEquals(image, Files.readAllBytes(cached));
            assertEquals(Instant.parse(added).toEpochMilli(), note.images.get(0).lastModified());
        } finally {
            Files.deleteIfExists(cached);
        }
    }

    @Test
    void sqlitePageLoadingSeparatesUsersAndKeepsOrphansAtRoot() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().execute("CREATE TABLE tw_pages (id TEXT, user_id TEXT, "
                    + "parent_id TEXT, kind TEXT, title TEXT, body TEXT, images TEXT, pos INTEGER)");
            connection.createStatement().execute("INSERT INTO tw_pages VALUES "
                    + "('n1','one','missing','note','Orphan','content','',0),"
                    + "('n2','two',NULL,'note','Other user','private','',1)");
            TypewriterPane.TwFolder root = new TypewriterPane.TwFolder("Pages");

            TypewriterStore.rebuildPages(connection, "one", root, false);

            assertEquals(1, root.notes.size());
            assertEquals("Orphan", root.notes.get(0).title);
            assertEquals("content", root.notes.get(0).body);
        }
    }
}
