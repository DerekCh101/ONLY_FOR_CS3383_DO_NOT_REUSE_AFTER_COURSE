/**
 * Offline, toolkit-free parts of the Typewriter store: content detection, empty-day pruning,
 * the JSON bundle round trip, folder-tree copying, and the image path list.
 *
 * <p>Everything here runs against plain objects. {@code TypewriterPane.TwNote} and friends are
 * static nested classes, so loading them does not start a JavaFX toolkit. The SQLite / Postgres /
 * Node arms of the store need a live database or HTTP server and are not covered here.
 */
package org.ashoreprospect.GUI;

import org.ashoreprospect.TestRuntimeArtifacts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypewriterStoreTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final LocalDate TUE = LocalDate.of(2026, 10, 13);

    @BeforeAll
    static void snapshotRuntimeFiles() {
        // Loading TypewriterStore runs its static initialiser, which creates these files.
        TestRuntimeArtifacts.snapshot();
    }

    @AfterAll
    static void removeRuntimeFiles() {
        TestRuntimeArtifacts.cleanUp();
    }

    private static TypewriterPane.TwNote note(String id, String title, LocalDate date, String body) {
        TypewriterPane.TwNote n = new TypewriterPane.TwNote(id, title, date);
        n.body = body;
        return n;
    }

    // ==================================================================
    // hasContent
    // ==================================================================

    @Test
    void hasContent_nullNoteIsFalse() {
        assertFalse(TypewriterStore.hasContent(null));
    }

    @Test
    void hasContent_plainTextBodyCounts() {
        assertTrue(TypewriterStore.hasContent(note("n1", "t", MON, "hello")));
    }

    @Test
    void hasContent_markupOnlyBodyIsStillText() {
        assertTrue(TypewriterStore.hasContent(note("n1", "t", MON, "<p>hello</p>")));
    }

    @Test
    void hasContent_blankBodyDoesNotCount() {
        assertFalse(TypewriterStore.hasContent(note("n1", "t", MON, "")));
        assertFalse(TypewriterStore.hasContent(note("n1", "t", MON, "   ")));
        assertFalse(TypewriterStore.hasContent(note("n1", "t", MON, "<p><br></p>")));
    }

    @Test
    void hasContent_nullBodyFallsThroughToImages() {
        assertFalse(TypewriterStore.hasContent(note("n1", "t", MON, null)));
    }

    @Test
    void hasContent_imageOnlyNoteCounts() {
        TypewriterPane.TwNote n = note("n1", "t", MON, "");
        n.images.add(new File("a.png"));
        assertTrue(TypewriterStore.hasContent(n));
    }

    @Test
    void hasContent_titleAloneIsNotContent() {
        // A title with no body and no images is not worth keeping.
        assertFalse(TypewriterStore.hasContent(note("n1", "only a title", MON, "")));
    }

    // ==================================================================
    // pruneEmptyDiary
    // ==================================================================

    @Test
    void pruneEmptyDiary_nullIsZero() {
        assertEquals(0, TypewriterStore.pruneEmptyDiary(null));
    }

    @Test
    void pruneEmptyDiary_emptyMapIsZero() {
        assertEquals(0, TypewriterStore.pruneEmptyDiary(new LinkedHashMap<>()));
    }

    @Test
    void pruneEmptyDiary_removesOnlyEmptyDays() {
        Map<LocalDate, TypewriterPane.TwNote> diary = new LinkedHashMap<>();
        diary.put(MON, note("a", "mon", MON, "written"));
        diary.put(TUE, note("b", "tue", TUE, ""));
        diary.put(MON.plusDays(2), note("c", "wed", MON.plusDays(2), "   "));

        assertEquals(2, TypewriterStore.pruneEmptyDiary(diary));
        assertEquals(1, diary.size());
        assertTrue(diary.containsKey(MON));
    }

    @Test
    void pruneEmptyDiary_keepsImageOnlyDay() {
        Map<LocalDate, TypewriterPane.TwNote> diary = new LinkedHashMap<>();
        TypewriterPane.TwNote n = note("a", "pic", MON, "");
        n.images.add(new File("x.png"));
        diary.put(MON, n);
        diary.put(TUE, note("b", "empty", TUE, ""));

        assertEquals(1, TypewriterStore.pruneEmptyDiary(diary));
        assertTrue(diary.containsKey(MON));
    }

    // ==================================================================
    // copyTree / copyFolder
    // ==================================================================

    @Test
    void copyTree_copiesNestedFoldersAndNotes() {
        TypewriterPane.TwFolder from = new TypewriterPane.TwFolder("root-id", "Pages");
        TypewriterPane.TwFolder child = new TypewriterPane.TwFolder("child-id", "Child");
        TypewriterPane.TwNote grandChildNote = note("note-id", "deep", null, "deep body");
        child.notes.add(grandChildNote);
        from.children.add(child);
        from.notes.add(note("top-note", "top", null, "top body"));

        TypewriterPane.TwFolder to = new TypewriterPane.TwFolder("target-id", "old title");
        TypewriterStore.copyTree(from, to);

        assertEquals("Pages", to.title);
        assertEquals(1, to.children.size());
        assertEquals(1, to.notes.size());
        assertEquals("Child", to.children.get(0).title);
        assertEquals(1, to.children.get(0).notes.size());
        assertEquals("deep", to.children.get(0).notes.get(0).title);
    }

    @Test
    void copyTree_replacesExistingTargetContent() {
        TypewriterPane.TwFolder from = new TypewriterPane.TwFolder("a", "Source");
        TypewriterPane.TwFolder to = new TypewriterPane.TwFolder("b", "Target");
        to.children.add(new TypewriterPane.TwFolder("stale", "Stale"));
        to.notes.add(note("stale-note", "stale", null, "stale"));

        TypewriterStore.copyTree(from, to);

        assertTrue(to.children.isEmpty());
        assertTrue(to.notes.isEmpty());
    }

    // ==================================================================
    // joinImages
    // ==================================================================

    @Test
    void joinImages_nullOrEmptyIsBlank() {
        assertEquals("", TypewriterStore.joinImages(null));
        assertEquals("", TypewriterStore.joinImages(note("n", "t", MON, "b")));
    }

    @Test
    void joinImages_joinsAbsolutePathsWithPipe() {
        TypewriterPane.TwNote n = note("n", "t", MON, "");
        n.images.add(new File("a.png"));
        n.images.add(new File("b.png"));

        String joined = TypewriterStore.joinImages(n);
        String[] parts = joined.split("\\|");
        assertEquals(2, parts.length);
        assertEquals(new File("a.png").getAbsolutePath(), parts[0]);
        assertEquals(new File("b.png").getAbsolutePath(), parts[1]);
    }

    @Test
    void joinImages_singleImageHasNoSeparator() {
        TypewriterPane.TwNote n = note("n", "t", MON, "");
        n.images.add(new File("only.png"));
        assertFalse(TypewriterStore.joinImages(n).contains("|"));
    }

    // ==================================================================
    // addImages — the pipe-separated (legacy) branch
    // ==================================================================

    @Test
    void addImages_ignoresNullAndBlankInput() {
        TypewriterPane.TwNote n = note("n", "t", MON, "");
        TypewriterStore.addImages(null, "x");
        TypewriterStore.addImages(n, null);
        TypewriterStore.addImages(n, "   ");
        assertTrue(n.images.isEmpty());
    }

    @Test
    void addImages_pipeListSkipsMissingFiles() throws Exception {
        File real = File.createTempFile("tw-test", ".png");
        real.deleteOnExit();
        TypewriterPane.TwNote n = note("n", "t", MON, "");

        TypewriterStore.addImages(n, real.getAbsolutePath() + "|/definitely/not/here.png||");

        assertEquals(1, n.images.size());
        assertEquals(real.getAbsoluteFile(), n.images.get(0).getAbsoluteFile());
    }

    // ==================================================================
    // snapshotJson / fromSnapshotJson (bundle round trip)
    // ==================================================================

    @Test
    void snapshotJson_emptyDataHasAllThreeArrays() {
        String json = TypewriterStore.snapshotJson(new LinkedHashMap<>(), new ArrayList<>(),
                new TypewriterPane.TwFolder("root", "Pages"));

        assertTrue(json.contains("\"diary\":["));
        assertTrue(json.contains("\"lists\":["));
        assertTrue(json.contains("\"pages\":["));
    }

    @Test
    void snapshotJson_dropsEmptyDiaryDays() {
        Map<LocalDate, TypewriterPane.TwNote> diary = new LinkedHashMap<>();
        diary.put(MON, note("a", "written", MON, "body"));
        diary.put(TUE, note("b", "empty", TUE, ""));

        String json = TypewriterStore.snapshotJson(diary, new ArrayList<>(),
                new TypewriterPane.TwFolder("root", "Pages"));

        assertTrue(json.contains(MON.toString()));
        assertFalse(json.contains(TUE.toString()));
    }

    @Test
    void bundleRoundTrip_keepsDiaryNoteBodyAndDate() {
        Map<LocalDate, TypewriterPane.TwNote> diary = new LinkedHashMap<>();
        diary.put(MON, note("id-1", null, MON, "line one\nline two"));

        String json = TypewriterStore.snapshotJson(diary, new ArrayList<>(),
                new TypewriterPane.TwFolder("root", "Pages"));
        TypewriterStore.Data back = TypewriterStore.fromSnapshotJson(json);

        assertEquals(1, back.diary.size());
        TypewriterPane.TwNote n = back.diary.get(MON);
        assertNotNull(n);
        assertEquals("id-1", n.id);
        assertTrue(n.body.contains("line one"));
        assertTrue(n.body.contains("line two"));
    }

    @Test
    void bundleRoundTrip_keepsListItemsAndDoneFlags() {
        List<TypewriterPane.TwList> lists = new ArrayList<>();
        TypewriterPane.TwList list = new TypewriterPane.TwList("list-1", "Groceries");
        TypewriterPane.TwItem done = new TypewriterPane.TwItem("milk");
        done.done = true;
        TypewriterPane.TwItem todo = new TypewriterPane.TwItem("bread");
        list.items.add(done);
        list.items.add(todo);
        lists.add(list);

        String json = TypewriterStore.snapshotJson(new LinkedHashMap<>(), lists,
                new TypewriterPane.TwFolder("root", "Pages"));
        TypewriterStore.Data back = TypewriterStore.fromSnapshotJson(json);

        assertEquals(1, back.lists.size());
        TypewriterPane.TwList round = back.lists.get(0);
        assertEquals("list-1", round.id);
        assertEquals(2, round.items.size());
        assertTrue(round.items.get(0).done);
        assertFalse(round.items.get(1).done);
    }

    @Test
    void bundleRoundTrip_keepsPageFolderTree() {
        TypewriterPane.TwFolder root = new TypewriterPane.TwFolder("root", "Pages");
        TypewriterPane.TwFolder child = new TypewriterPane.TwFolder("f1", "Projects");
        child.notes.add(note("p1", null, null, "inside folder"));
        root.children.add(child);
        root.notes.add(note("p2", null, null, "at root"));

        String json = TypewriterStore.snapshotJson(new LinkedHashMap<>(), new ArrayList<>(), root);
        TypewriterStore.Data back = TypewriterStore.fromSnapshotJson(json);

        assertEquals(1, back.pagesRoot.children.size(), "child folder restored");
        assertEquals(1, back.pagesRoot.notes.size(), "root note restored");
        assertEquals("Projects", back.pagesRoot.children.get(0).title);
        assertEquals(1, back.pagesRoot.children.get(0).notes.size());
    }

    @Test
    void bundleRoundTrip_preservesNestedFolderDepth() {
        TypewriterPane.TwFolder root = new TypewriterPane.TwFolder("root", "Pages");
        TypewriterPane.TwFolder a = new TypewriterPane.TwFolder("a", "A");
        TypewriterPane.TwFolder b = new TypewriterPane.TwFolder("b", "B");
        a.children.add(b);
        root.children.add(a);

        String json = TypewriterStore.snapshotJson(new LinkedHashMap<>(), new ArrayList<>(), root);
        TypewriterStore.Data back = TypewriterStore.fromSnapshotJson(json);

        assertEquals(1, back.pagesRoot.children.size());
        assertEquals("A", back.pagesRoot.children.get(0).title);
        assertEquals(1, back.pagesRoot.children.get(0).children.size());
        assertEquals("B", back.pagesRoot.children.get(0).children.get(0).title);
    }

    @Test
    void bundleRoundTrip_escapesQuotesAndBackslashes() {
        List<TypewriterPane.TwList> lists = new ArrayList<>();
        TypewriterPane.TwList list = new TypewriterPane.TwList("l", "quoted");
        list.items.add(new TypewriterPane.TwItem("say \"hi\" \\ bye"));
        lists.add(list);

        String json = TypewriterStore.snapshotJson(new LinkedHashMap<>(), lists,
                new TypewriterPane.TwFolder("root", "Pages"));
        TypewriterStore.Data back = TypewriterStore.fromSnapshotJson(json);

        assertEquals("say \"hi\" \\ bye", back.lists.get(0).items.get(0).text);
    }

    // ==================================================================
    // fromSnapshotJson robustness
    // ==================================================================

    @Test
    void fromSnapshotJson_blankInputYieldsEmptyData() {
        for (String in : new String[]{null, "", "   "}) {
            TypewriterStore.Data d = TypewriterStore.fromSnapshotJson(in);
            assertTrue(d.diary.isEmpty());
            assertTrue(d.lists.isEmpty());
            assertTrue(d.pagesRoot.children.isEmpty());
            assertTrue(d.pagesRoot.notes.isEmpty());
        }
    }

    @Test
    void fromSnapshotJson_garbageDoesNotThrow() {
        TypewriterStore.Data d = TypewriterStore.fromSnapshotJson("not json at all");
        assertNotNull(d);
        assertTrue(d.diary.isEmpty());
    }

    @Test
    void fromSnapshotJson_skipsDiaryRowsWithoutUsableDate() {
        String json = "{\"diary\":["
                + "{\"id\":\"a\",\"date\":\"\",\"title\":null,\"body\":\"x\",\"images\":[]},"
                + "{\"id\":\"b\",\"date\":\"garbage\",\"title\":null,\"body\":\"y\",\"images\":[]},"
                + "{\"id\":\"c\",\"date\":\"2026-10-12\",\"title\":null,\"body\":\"z\",\"images\":[]}"
                + "],\"lists\":[],\"pages\":[]}";

        TypewriterStore.Data d = TypewriterStore.fromSnapshotJson(json);

        assertEquals(1, d.diary.size());
        assertTrue(d.diary.containsKey(MON));
    }

    @Test
    void fromSnapshotJson_skipsEmptyDiaryRows() {
        String json = "{\"diary\":["
                + "{\"id\":\"a\",\"date\":\"2026-10-12\",\"title\":\"t\",\"body\":\"\",\"images\":[]}"
                + "],\"lists\":[],\"pages\":[]}";

        assertTrue(TypewriterStore.fromSnapshotJson(json).diary.isEmpty());
    }

    // ==================================================================
    // openLoaded — locks in the plain-text invariant for stored bodies
    // ==================================================================

    @Test
    void openLoaded_nullIsNoOp() {
        TypewriterStore.openLoaded(null);
    }

    @Test
    void openLoaded_unwrapsEditorHtmlIntoStoredText() {
        TypewriterStore.Data data = new TypewriterStore.Data();
        TypewriterPane.TwNote n = note("n", "title", MON, "");
        n.body = "<html><body><p>Hello</p></body></html>";
        data.diary.put(MON, n);

        TypewriterStore.openLoaded(data);

        assertFalse(data.diary.get(MON).body.toLowerCase().contains("<html"));
        assertTrue(data.diary.get(MON).body.contains("Hello"));
    }

    @Test
    void openLoaded_leavesAnEmptyDataUntouched() {
        TypewriterStore.Data data = new TypewriterStore.Data();

        TypewriterStore.openLoaded(data);

        assertTrue(data.diary.isEmpty());
        assertTrue(data.lists.isEmpty());
        assertTrue(data.pagesRoot.notes.isEmpty());
        assertEquals("Pages", data.pagesRoot.title);
    }

    @Test
    void openLoaded_walksIntoNestedPagesFolders() {
        TypewriterStore.Data data = new TypewriterStore.Data();
        TypewriterPane.TwFolder child = new TypewriterPane.TwFolder("f", "Folder");
        child.notes.add(note("p", "t", null, "<html><body><p>deep</p></body></html>"));
        data.pagesRoot.children.add(child);

        TypewriterStore.openLoaded(data);

        String body = data.pagesRoot.children.get(0).notes.get(0).body;
        assertFalse(body.toLowerCase().contains("<html"));
        assertTrue(body.contains("deep"));
    }

    // ==================================================================
    // resetPostgres — the only testable lifecycle hook
    // ==================================================================

    @Test
    void resetPostgres_isIdempotent() {
        TypewriterStore.resetPostgres();
        TypewriterStore.resetPostgres();
        // No exception and no state leak; the next pg() call re-probes.
    }

    // ==================================================================
    // Data holder shape
    // ==================================================================

    @Test
    void data_startsEmptyWithPagesRoot() {
        TypewriterStore.Data d = new TypewriterStore.Data();
        assertTrue(d.diary.isEmpty());
        assertTrue(d.lists.isEmpty());
        assertNotNull(d.pagesRoot);
        assertEquals("Pages", d.pagesRoot.title);
    }

    @Test
    void data_eachInstanceIsIndependent() {
        TypewriterStore.Data a = new TypewriterStore.Data();
        TypewriterStore.Data b = new TypewriterStore.Data();
        a.diary.put(MON, note("n", "t", MON, "x"));

        assertTrue(b.diary.isEmpty());
        assertNotSame(a.pagesRoot, b.pagesRoot);
    }
}
