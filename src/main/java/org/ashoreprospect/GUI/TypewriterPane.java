/** Typewriter tab: diary, lists, pages, HTML editor, line gutter. */
package org.ashoreprospect.GUI;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.concurrent.Worker;
import javafx.scene.paint.Color;
import javafx.scene.web.HTMLEditor;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.ashoreprospect.AppTimeReference;
import java.util.ArrayDeque;

import java.io.File;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

public class TypewriterPane extends BorderPane {

    private enum Mode { DIARY, LISTS, PAGES }
    private enum FindLevel { YEAR, MONTH, DAY }

    public static final class TwNote {
        public final String id;
        public String title;
        public String body = "";
        public LocalDate date;
        public final List<File> images = new ArrayList<>();

        public TwNote(String title, LocalDate date) {
            this(UUID.randomUUID().toString(), title, date);
        }

        public TwNote(String id, String title, LocalDate date) {
            this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
            this.title = title;
            this.date = date;
        }

        @Override
        public String toString() {
            return title == null || title.isBlank() ? "Untitled" : title;
        }
    }

    public static final class TwFolder {
        public final String id;
        public String title;
        public final List<TwFolder> children = new ArrayList<>();
        public final List<TwNote> notes = new ArrayList<>();

        public TwFolder(String title) {
            this(UUID.randomUUID().toString(), title);
        }

        public TwFolder(String id, String title) {
            this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public static final class TwItem {
        public String text;
        public boolean done;

        public TwItem(String text) {
            this.text = text;
        }
    }

    public static final class TwList {
        public final String id;
        public String title;
        public final List<TwItem> items = new ArrayList<>();

        public TwList(String title) {
            this(UUID.randomUUID().toString(), title);
        }

        public TwList(String id, String title) {
            this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private Mode mode = Mode.PAGES;

    private final Button tabDiary = railBtn("Diary");
    private final Button tabLists = railBtn("Lists");
    private final Button tabPages = railBtn("Pages");

    private final StackPane layers = new StackPane();
    private final StackPane leftStack = new StackPane();
    private final StackPane centerStack = new StackPane();

    private final Map<LocalDate, TwNote> diaryPages = new HashMap<>();
    private YearMonth diaryMonth = YearMonth.from(AppTimeReference.getCurrentDate());
    private LocalDate diaryDate = AppTimeReference.getCurrentDate();
    private final GridPane diaryCal = new GridPane();
    private final Label diaryMonthLbl = new Label();
    private final VBox diaryNav = new VBox(10);
    private final VBox calBox = new VBox(10);
    private final VBox findBox = new VBox(10);
    private final ListView<String> findList = new ListView<>();
    private final Label findTitle = new Label();
    private FindLevel findLevel = FindLevel.YEAR;
    private Integer findYear;
    private Month findMonth;
    private boolean findOpen;

    private final List<TwList> lists = new ArrayList<>();
    private TwList currentList;
    private final ListView<TwList> listNames = new ListView<>();
    private final VBox listRows = new VBox(6);
    private final TextField listTitleField = new TextField();
    private final TextField addItemField = new TextField();
    private Button addListItemBtn;
    private final HTMLEditor listHtml = new HTMLEditor();
    private TwItem currentListItem;
    private final VBox listsNav = new VBox(10);
    private final VBox listsEditor = new VBox(12);

    private final TwFolder pagesRoot = new TwFolder("Pages");
    private final TreeView<Object> pageTree = new TreeView<>();
    private final VBox pagesNav = new VBox(10);

    private final TextField titleField = new TextField();
    private final HTMLEditor htmlBody = new HTMLEditor();
    private final TextArea bodyArea = new TextArea();
    private final Label dateLabel = new Label();
    private final Label wordCount = new Label();
    private final Label editorHint = new Label("Select a page, or click New page.");
    private final ArrayDeque<String> twUndo = new ArrayDeque<>();
    private final ArrayDeque<String> twRedo = new ArrayDeque<>();
    private String twBaseline;
    private boolean restoringTw;
    private final HBox photoStrip = new HBox(8);
    private Button attachBtn;
    private final VBox noteEditor = new VBox(12);
    private HBox rail;
    private VBox leftWrap;
    private TwNote currentNote;
    private boolean fillingEditor;
    private boolean fillingList;

    private StackPane overlay;
    private javafx.event.EventHandler<KeyEvent> overlayEsc;

    private final PauseTransition saveDebounce = new PauseTransition(Duration.millis(400));
    private final PauseTransition htmlReady = new PauseTransition(Duration.millis(50));

    public TypewriterPane() {
        getStyleClass().add("typewriter-root");
        saveDebounce.setOnFinished(e -> {
            commitTwHistory();
            saveAll();
        });

        rail = new HBox(8, tabPages, tabDiary, tabLists);
        rail.setPadding(new Insets(14, 16, 8, 16));
        rail.setAlignment(Pos.CENTER_LEFT);
        tabDiary.setOnAction(e -> showMode(Mode.DIARY));
        tabLists.setOnAction(e -> showMode(Mode.LISTS));
        tabPages.setOnAction(e -> showMode(Mode.PAGES));

        buildDiaryNav();
        buildListsNav();
        buildPagesNav();
        buildNoteEditor();
        buildListsEditor();

        leftStack.getChildren().addAll(pagesNav, diaryNav, listsNav);
        centerStack.getChildren().addAll(listsEditor, noteEditor);

        leftWrap = new VBox(leftStack);
        leftWrap.setPrefWidth(280);

        BorderPane body = new BorderPane();
        body.setTop(rail);
        body.setLeft(leftWrap);
        body.setCenter(centerStack);
        layers.getChildren().add(body);
        setCenter(layers);

        applyTheme();
        applyLoaded(TypewriterStore.load(), false);
        TypewriterStore.syncRemoteInBackground(fresh -> Platform.runLater(() -> {
            applyLoaded(fresh, blank(fresh));
        }));
    }

    public void applyTheme() {
        setStyle("-fx-background-color: " + AppTheme.typewriterBg() + ";");
        if (rail != null) rail.setStyle("-fx-background-color: " + AppTheme.typewriterRail() + ";");
        if (leftWrap != null) leftWrap.setStyle("-fx-background-color: " + AppTheme.typewriterRail() + ";");
        diaryMonthLbl.setStyle("-fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif; -fx-font-size: 14px; -fx-text-fill: "
                + AppTheme.ink() + ";");
        dateLabel.setStyle("-fx-text-fill: " + AppTheme.muted() + "; -fx-font-size: 12px;");
        titleField.setStyle(twTitleCss());
        listTitleField.setStyle(twTitleCss());
        bodyArea.setStyle(twBodyCss());
        addItemField.setStyle(twItemCss());
        wordCount.setStyle("-fx-text-fill: " + AppTheme.muted() + "; -fx-font-size: 12px;");
        paintHtmlSurface(htmlBody);
        paintHtmlSurface(listHtml);
        if (!isVisible()) return;
        rebuildCalendar();
        rebuildListRows();
        restylePills(this);
        showMode(mode);
    }

    private static void restylePills(javafx.scene.Node n) {
        if (n instanceof Button b) {
            String s = b.getStyle() == null ? "" : b.getStyle();
            if (s.contains("-fx-border-radius: 8")) b.setStyle(pillCss());
        }
        if (n instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) restylePills(c);
        }
    }

    private static String twTitleCss() {
        return "-fx-background-color: transparent; -fx-border-width: 0 0 1 0; "
                + "-fx-border-color: " + AppTheme.muted() + "; -fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif; "
                + "-fx-font-size: 22px; -fx-text-fill: " + AppTheme.ink() + "; -fx-padding: 6 0;";
    }

    private static String twBodyCss() {
        String paper = AppTheme.paper();
        return "-fx-background-color: " + paper + "; -fx-control-inner-background: " + paper
                + "; -fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif; -fx-font-size: 15px; -fx-border-color: transparent; "
                + "-fx-text-fill: " + AppTheme.ink() + ";";
    }

    private static String twItemCss() {
        String paper = AppTheme.isDark() ? "#1a222c" : "#fbf7ef";
        return "-fx-background-color: " + paper + "; -fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif; -fx-font-size: 14px; "
                + "-fx-text-fill: " + AppTheme.ink() + ";";
    }

    private static void mute(Label l) {
        l.getStyleClass().add("tw-muted");
        l.setStyle("-fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif; -fx-font-size: 13px;");
    }

    private static boolean blank(TypewriterStore.Data data) {
        return data == null
                || (data.diary.isEmpty() && data.lists.isEmpty()
                && data.pagesRoot.children.isEmpty() && data.pagesRoot.notes.isEmpty());
    }

    public void applyFromNode(TypewriterStore.Data data) {
        applyLoaded(data, false);
    }

    private void applyLoaded(TypewriterStore.Data data, boolean seedIfEmpty) {
        if (blank(data)) {
            if (seedIfEmpty) {
                seedDefaults();
                saveAll();
            }
        } else {
            diaryPages.clear();
            diaryPages.putAll(data.diary);
            lists.clear();
            lists.addAll(data.lists);
            listNames.getItems().setAll(lists);
            rebuildPageTree(data.pagesRoot);
        }
        TypewriterStore.pruneEmptyDiary(diaryPages);
        if (mode == Mode.DIARY) {
            rebuildCalendar();
            TwNote day = diaryPages.get(diaryDate);
            if (hasWriting(day)) bindNote(day);
        } else if (mode == Mode.LISTS) {
            listNames.getItems().setAll(lists);
        } else {
            TreeItem<Object> keep = currentNote == null ? null : findNoteById(pageTree.getRoot(), currentNote.id);
            TreeItem<Object> pick = keep != null ? keep : findFirstNote(pageTree.getRoot());
            if (pick != null) pageTree.getSelectionModel().select(pick);
            else bindNote(null);
        }
        if (!restoringTw) {
            twUndo.clear();
            twRedo.clear();
            twBaseline = twSnap();
        }
    }

    public void saveAll() {
        TypewriterStore.pruneEmptyDiary(diaryPages);
        TypewriterStore.save(diaryPages, lists, pagesRoot);
        if (mode == Mode.DIARY) rebuildCalendar();
    }

    public TypewriterStore.Data snapshot() {
        TypewriterStore.Data data = new TypewriterStore.Data();
        data.diary.putAll(diaryPages);
        data.lists.addAll(lists);
        TypewriterStore.copyTree(pagesRoot, data.pagesRoot);
        return data;
    }

    private void touch() {
        if (fillingEditor || fillingList || restoringTw) return;
        saveDebounce.playFromStart();
    }

    public void undo() {
        captureHtml();
        if (twUndo.isEmpty()) return;
        String now = twSnap();
        String prev = twUndo.removeLast();
        twRedo.addLast(now);
        restoreTw(prev);
    }

    public void redo() {
        captureHtml();
        if (twRedo.isEmpty()) return;
        String now = twSnap();
        String next = twRedo.removeLast();
        twUndo.addLast(now);
        restoreTw(next);
    }

    private void commitTwHistory() {
        if (restoringTw) return;
        String now = twSnap();
        if (twBaseline != null && !twBaseline.equals(now)) {
            twUndo.addLast(twBaseline);
            while (twUndo.size() > 40) twUndo.removeFirst();
            twRedo.clear();
        }
        twBaseline = now;
    }

    private String twSnap() {
        return TypewriterStore.snapshotJson(diaryPages, lists, pagesRoot);
    }

    private void restoreTw(String json) {
        restoringTw = true;
        fillingEditor = true;
        fillingList = true;
        try {
            applyLoaded(TypewriterStore.fromSnapshotJson(json), false);
            twBaseline = json;
        } finally {
            fillingEditor = false;
            fillingList = false;
            restoringTw = false;
        }
    }

    private static boolean hasWriting(TwNote n) {
        return TypewriterStore.hasContent(n);
    }

    private void dismissOverlay() {
        Scene scene = getScene();
        if (scene != null && overlayEsc != null) {
            scene.removeEventFilter(KeyEvent.KEY_PRESSED, overlayEsc);
        }
        overlayEsc = null;
        if (overlay != null) {
            layers.getChildren().remove(overlay);
            overlay = null;
        }
    }

    private void showOverlayBox(VBox box) {
        dismissOverlay();
        overlay = GuiHelper.createDimmedOverlay(layers);
        layers.getChildren().add(overlay);
        overlay.getChildren().add(box);
        StackPane.setAlignment(box, Pos.CENTER);
        if (box.getStyleClass().contains("dialog-card")) {
            GuiHelper.paintInk(box);
        }
        Scene scene = getScene();
        if (scene != null) {
            overlayEsc = ev -> {
                if (ev.getCode() == KeyCode.ESCAPE) {
                    ev.consume();
                    dismissOverlay();
                }
            };
            scene.addEventFilter(KeyEvent.KEY_PRESSED, overlayEsc);
        }
    }

    private void showConfirmOverlay(String header, String body, String yesText, String noText,
                                    Runnable onYes) {
        VBox box = GuiHelper.createWhiteBox();
        Label h = new Label(header);
        h.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: " + AppTheme.cardInk() + ";");
        Label m = new Label(body);
        m.setWrapText(true);
        m.setStyle("-fx-text-fill: " + AppTheme.cardInk() + ";");
        Button yes = new Button(yesText);
        Button no = new Button(noText);
        no.setStyle(AppTheme.cardGhostBtnCss());
        yes.setStyle(AppTheme.cardPrimaryBtnCss());
        HBox buttons = new HBox(10, yes, no);
        box.getChildren().addAll(h, m, buttons);
        showOverlayBox(box);
        yes.setOnAction(e -> {
            dismissOverlay();
            if (onYes != null) onYes.run();
        });
        no.setOnAction(e -> dismissOverlay());
    }

    private void showNoticeOverlay(String header, String body) {
        VBox box = GuiHelper.createWhiteBox();
        Label h = new Label(header);
        h.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: " + AppTheme.cardInk() + ";");
        Label m = new Label(body);
        m.setWrapText(true);
        m.setStyle("-fx-text-fill: " + AppTheme.cardInk() + ";");
        Button ok = new Button("Okay");
        ok.setStyle(AppTheme.cardPrimaryBtnCss());
        box.getChildren().addAll(h, m, ok);
        showOverlayBox(box);
        ok.setOnAction(e -> dismissOverlay());
    }

    private void showPromptOverlay(String header, String seed, Consumer<String> onOk) {
        VBox box = GuiHelper.createWhiteBox();
        Label h = new Label(header);
        h.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: " + AppTheme.cardInk() + ";");
        TextField field = new TextField(seed == null ? "" : seed);
        field.setStyle(AppTheme.fieldCss());
        Button save = new Button("Save");
        Button cancel = new Button("Cancel");
        save.setStyle(AppTheme.cardPrimaryBtnCss());
        cancel.setStyle(AppTheme.cardGhostBtnCss());
        HBox buttons = new HBox(10, save, cancel);
        box.getChildren().addAll(h, field, buttons);
        showOverlayBox(box);
        save.setOnAction(e -> {
            String v = field.getText() == null ? "" : field.getText().trim();
            dismissOverlay();
            if (onOk != null) onOk.accept(v);
        });
        cancel.setOnAction(e -> dismissOverlay());
    }

    private void pruneEmptyDays() {
        showConfirmOverlay(
                "Delete empty days?",
                "Days with no text and no photos will be discarded.",
                "Delete empty",
                "Cancel",
                () -> {
                    int n = TypewriterStore.pruneEmptyDiary(diaryPages);
                    if (currentNote != null && !hasWriting(currentNote)) bindNote(null);
                    rebuildCalendar();
                    if (n > 0) touch();
                    showNoticeOverlay("Removed " + n + " empty day(s)",
                            "Days with no text and no photos were discarded.");
                }
        );
    }

    private void rebuildPageTree(TwFolder loaded) {
        pagesRoot.children.clear();
        pagesRoot.notes.clear();
        pagesRoot.children.addAll(loaded.children);
        pagesRoot.notes.addAll(loaded.notes);
        TreeItem<Object> rootItem = pageTree.getRoot();
        rootItem.getChildren().clear();
        for (TwFolder f : pagesRoot.children) rootItem.getChildren().add(wrap(f));
        for (TwNote n : pagesRoot.notes) rootItem.getChildren().add(new TreeItem<>(n));
    }

    private TreeItem<Object> wrap(TwFolder folder) {
        TreeItem<Object> item = new TreeItem<>(folder);
        item.setExpanded(true);
        for (TwFolder child : folder.children) item.getChildren().add(wrap(child));
        for (TwNote note : folder.notes) item.getChildren().add(new TreeItem<>(note));
        return item;
    }

    private Button railBtn(String text) {
        Button b = new Button(text);
        b.setStyle(railOff());
        return b;
    }

    private String railOn() {
        return AppTheme.railOn();
    }

    private String railOff() {
        return AppTheme.railOff();
    }

    private Button pill(String text) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setStyle(pillCss());
        return b;
    }

    private static String pillCss() {
        return "-fx-background-color: " + AppTheme.paper() + "; -fx-border-color: "
                + AppTheme.muted() + "; -fx-background-radius: 8; -fx-border-radius: 8; "
                + "-fx-font-size: 12px; -fx-text-fill: " + AppTheme.ink() + ";";
    }

    private void showMode(Mode next) {
        mode = next;
        tabDiary.setStyle(next == Mode.DIARY ? railOn() : railOff());
        tabLists.setStyle(next == Mode.LISTS ? railOn() : railOff());
        tabPages.setStyle(next == Mode.PAGES ? railOn() : railOff());

        diaryNav.setVisible(next == Mode.DIARY);
        diaryNav.setManaged(next == Mode.DIARY);
        listsNav.setVisible(next == Mode.LISTS);
        listsNav.setManaged(next == Mode.LISTS);
        pagesNav.setVisible(next == Mode.PAGES);
        pagesNav.setManaged(next == Mode.PAGES);

        listsEditor.setVisible(next == Mode.LISTS);
        listsEditor.setManaged(next == Mode.LISTS);
        noteEditor.setVisible(next != Mode.LISTS);
        noteEditor.setManaged(next != Mode.LISTS);

        if (next == Mode.DIARY) {
            diaryDate = AppTimeReference.getCurrentDate();
            diaryMonth = YearMonth.from(diaryDate);
            showFind(false);
            openDiary(diaryDate, false);
        } else if (next == Mode.LISTS) {
            if (currentList == null && !lists.isEmpty()) {
                listNames.getSelectionModel().select(0);
            } else {
                openList(currentList);
            }
        } else {
            TreeItem<Object> keep = currentNote == null ? null : findNoteById(pageTree.getRoot(), currentNote.id);
            TreeItem<Object> pick = keep != null ? keep : findFirstNote(pageTree.getRoot());
            if (pick != null) pageTree.getSelectionModel().select(pick);
            else bindNote(null);
        }
    }

    private void buildDiaryNav() {
        Label sideTitle = new Label("DIARY");
        mute(sideTitle);

        Button prev = pill("<");
        Button next = pill(">");
        prev.setMaxWidth(40);
        next.setMaxWidth(40);
        prev.setOnAction(e -> {
            diaryMonth = diaryMonth.minusMonths(1);
            rebuildCalendar();
        });
        next.setOnAction(e -> {
            diaryMonth = diaryMonth.plusMonths(1);
            rebuildCalendar();
        });
        diaryMonthLbl.setStyle("-fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif; -fx-font-size: 14px; -fx-text-fill: "
                + AppTheme.ink() + ";");
        HBox monthRow = new HBox(8, prev, diaryMonthLbl, next);
        monthRow.setAlignment(Pos.CENTER);

        Button today = pill("Jump to Today");
        today.setOnAction(e -> {
            showFind(false);
            diaryDate = AppTimeReference.getCurrentDate();
            diaryMonth = YearMonth.from(diaryDate);
            openDiary(diaryDate, false);
        });

        Button find = pill("Find entries by date");
        find.setOnAction(e -> showFind(true));

        Button clearDay = pill("Clear this day");
        clearDay.setOnAction(e -> deleteDiaryDay());

        Button prune = pill("Delete empty days");
        prune.setOnAction(e -> pruneEmptyDays());

        diaryCal.setHgap(4);
        diaryCal.setVgap(4);
        diaryCal.setAlignment(Pos.CENTER);

        calBox.getChildren().addAll(monthRow, diaryCal, today, find, clearDay, prune);

        mute(findTitle);
        findList.setPrefHeight(280);
        findList.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 1) onFindPick();
        });
        Button findBack = pill("Up one level");
        findBack.setOnAction(e -> findBack());
        Button findClose = pill("Show calendar");
        findClose.setOnAction(e -> showFind(false));
        findBox.getChildren().addAll(findTitle, findList, findBack, findClose);
        findBox.setVisible(false);
        findBox.setManaged(false);

        diaryNav.getChildren().addAll(sideTitle, calBox, findBox);
        diaryNav.setPadding(new Insets(16, 12, 16, 16));
        rebuildCalendar();
    }

    private void showFind(boolean on) {
        findOpen = on;
        calBox.setVisible(!on);
        calBox.setManaged(!on);
        findBox.setVisible(on);
        findBox.setManaged(on);
        if (on) {
            findLevel = FindLevel.YEAR;
            findYear = null;
            findMonth = null;
            rebuildFind();
        }
    }

    private Map<Integer, Map<Month, List<LocalDate>>> indexDiary() {
        Map<Integer, Map<Month, List<LocalDate>>> idx = new TreeMap<>(Comparator.reverseOrder());
        for (TwNote n : diaryPages.values()) {
            if (n.date == null || !hasWriting(n)) continue;
            idx.computeIfAbsent(n.date.getYear(), y -> new TreeMap<>())
                    .computeIfAbsent(n.date.getMonth(), m -> new ArrayList<>())
                    .add(n.date);
        }
        for (Map<Month, List<LocalDate>> months : idx.values()) {
            for (List<LocalDate> days : months.values()) days.sort(Comparator.naturalOrder());
        }
        return idx;
    }

    private void rebuildFind() {
        findList.getItems().clear();
        Map<Integer, Map<Month, List<LocalDate>>> idx = indexDiary();
        if (findLevel == FindLevel.YEAR) {
            findTitle.setText("Years with entries");
            if (idx.isEmpty()) {
                findList.getItems().add("(none yet)");
                return;
            }
            int thisYear = AppTimeReference.getCurrentDate().getYear();
            for (Integer y : idx.keySet()) {
                int n = 0;
                for (List<LocalDate> days : idx.get(y).values()) n += days.size();
                findList.getItems().add(y + "  (" + n + ")"
                        + (y != null && y == thisYear ? "  · this year" : ""));
            }
        } else if (findLevel == FindLevel.MONTH) {
            findTitle.setText(findYear + " — months with entries");
            Map<Month, List<LocalDate>> months = idx.getOrDefault(findYear, Map.of());
            if (months.isEmpty()) {
                findList.getItems().add("(none)");
                return;
            }
            LocalDate today = AppTimeReference.getCurrentDate();
            for (Month m : months.keySet()) {
                String name = m.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                boolean here = findYear != null && findYear == today.getYear() && m == today.getMonth();
                findList.getItems().add(name + "  (" + months.get(m).size() + ")"
                        + (here ? "  · this month" : ""));
            }
        } else {
            String monthName = findMonth.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            findTitle.setText(monthName + " " + findYear);
            List<LocalDate> days = idx.getOrDefault(findYear, Map.of())
                    .getOrDefault(findMonth, List.of());
            if (days.isEmpty()) {
                findList.getItems().add("(none)");
                return;
            }
            LocalDate today = AppTimeReference.getCurrentDate();
            for (LocalDate d : days) {
                TwNote n = diaryPages.get(d);
                String t = n == null || n.title == null || n.title.isBlank() ? "entry" : n.title;
                int imgs = n == null || n.images == null ? 0 : n.images.size();
                String line = d.getDayOfMonth() + "  —  " + t;
                if (imgs > 0) line += "  (" + imgs + " img)";
                if (d.equals(today)) line += "  · today";
                findList.getItems().add(line);
            }
        }
    }

    private void onFindPick() {
        String sel = findList.getSelectionModel().getSelectedItem();
        if (sel == null || sel.startsWith("(")) return;
        Map<Integer, Map<Month, List<LocalDate>>> idx = indexDiary();
        if (findLevel == FindLevel.YEAR) {
            findYear = Integer.parseInt(sel.split("\\s+")[0]);
            findLevel = FindLevel.MONTH;
            rebuildFind();
        } else if (findLevel == FindLevel.MONTH) {
            String name = sel.split("  ")[0];
            for (Month m : idx.getOrDefault(findYear, Map.of()).keySet()) {
                if (m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).equals(name)) {
                    findMonth = m;
                    findLevel = FindLevel.DAY;
                    rebuildFind();
                    return;
                }
            }
        } else {
            int day = Integer.parseInt(sel.split("\\s+")[0]);
            LocalDate date = LocalDate.of(findYear, findMonth, day);
            showFind(false);
            openDiary(date, false);
        }
    }

    private void findBack() {
        if (findLevel == FindLevel.DAY) {
            findLevel = FindLevel.MONTH;
            findMonth = null;
        } else if (findLevel == FindLevel.MONTH) {
            findLevel = FindLevel.YEAR;
            findYear = null;
        } else {
            showFind(false);
            return;
        }
        rebuildFind();
    }

    private void deleteDiaryDay() {
        TwNote n = diaryPages.get(diaryDate);
        if (n == null || !hasWriting(n)) return;
        showConfirmOverlay(
                "Clear " + diaryDate + "?",
                "The text and photos for this day will be removed.",
                "Clear",
                "Cancel",
                () -> {
                    n.body = "";
                    n.images.clear();
                    diaryPages.remove(diaryDate);
                    bindNote(null);
                    dateLabel.setText(diaryDate + "  (not created)");
                    rebuildCalendar();
                    touch();
                }
        );
    }

    private void rebuildCalendar() {
        diaryCal.getChildren().clear();
        diaryMonthLbl.setText(diaryMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + diaryMonth.getYear());

        String[] heads = {"M", "T", "W", "T", "F", "S", "S"};
        for (int i = 0; i < 7; i++) {
            Label h = new Label(heads[i]);
            h.getStyleClass().add("tw-muted");
            h.setStyle("-fx-font-size: 11px;");
            diaryCal.add(h, i, 0);
        }

        LocalDate first = diaryMonth.atDay(1);
        int shift = first.getDayOfWeek().getValue() - 1;
        int days = diaryMonth.lengthOfMonth();
        LocalDate today = AppTimeReference.getCurrentDate();

        for (int d = 1; d <= days; d++) {
            LocalDate date = diaryMonth.atDay(d);
            Button cell = new Button(String.valueOf(d));
            cell.setPrefSize(30, 28);
            boolean selected = date.equals(diaryDate);
            boolean isToday = date.equals(today);
            boolean has = hasWriting(diaryPages.get(date));
            String border = isToday ? AppTheme.ink() : AppTheme.muted();
            String bg = selected ? "#1c2430" : AppTheme.paper();
            String fg = selected ? "#fbf7ef" : AppTheme.ink();
            String mark = has
                    ? "-fx-underline: true; -fx-font-weight: bold; -fx-border-width: 0 0 2 0; "
                    + "-fx-border-color: transparent transparent " + fg + " transparent;"
                    : "-fx-underline: false; -fx-font-weight: normal; -fx-border-width: 1; "
                    + "-fx-border-color: " + border + ";";
            cell.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: " + fg
                    + "; " + mark
                    + " -fx-background-radius: 6; -fx-border-radius: 6; -fx-padding: 0;");
            cell.setOnAction(e -> openDiary(date, true));
            int row = (shift + d - 1) / 7 + 1;
            int col = (shift + d - 1) % 7;
            diaryCal.add(cell, col, row);
        }
    }

    private TwNote newDay(LocalDate date) {
        return new TwNote(
                date.getDayOfMonth() + " "
                        + date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                date);
    }

    private void openDiary(LocalDate date, boolean askIfMissing) {
        diaryDate = date;
        diaryMonth = YearMonth.from(date);
        rebuildCalendar();
        TwNote existing = diaryPages.get(date);
        if (hasWriting(existing)) {
            bindNote(existing);
            return;
        }
        if (existing != null) diaryPages.remove(date);
        bindNote(null);
        dateLabel.setText(date + "  (not created)");
        if (!askIfMissing) return;
        showConfirmOverlay(
                "No entry for " + date,
                "Create this day?",
                "Create",
                "Not now",
                () -> {
                    TwNote created = newDay(date);
                    diaryPages.put(date, created);
                    bindNote(created);
                    rebuildCalendar();
                }
        );
    }

    private void buildListsNav() {
        Label sideTitle = new Label("LISTS");
        mute(sideTitle);
        listNames.setPrefHeight(320);
        listNames.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(TwList list, boolean empty) {
                super.updateItem(list, empty);
                if (empty || list == null) {
                    setText(null);
                    return;
                }
                int n = list.items == null ? 0 : list.items.size();
                setText((list.title == null || list.title.isBlank() ? "Untitled list" : list.title)
                        + " (" + n + ")");
            }
        });
        listNames.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> openList(b));
        Button add = pill("New list");
        add.setOnAction(e -> {
            TwList created = new TwList("Untitled list");
            lists.add(created);
            listNames.getItems().add(created);
            listNames.getSelectionModel().select(created);
            touch();
        });
        Button del = pill("Delete list");
        del.setOnAction(e -> deleteList());
        listsNav.getChildren().addAll(sideTitle, listNames, add, del);
        listsNav.setPadding(new Insets(16, 12, 16, 16));
        VBox.setVgrow(listNames, Priority.ALWAYS);
    }

    private void deleteList() {
        TwList list = currentList;
        if (list == null) return;
        showConfirmOverlay("Delete list?", list.title, "Delete", "Cancel", () -> {
            lists.remove(list);
            listNames.getItems().remove(list);
            currentList = listNames.getItems().isEmpty() ? null : listNames.getItems().get(0);
            if (currentList != null) listNames.getSelectionModel().select(currentList);
            openList(currentList);
            touch();
        });
    }

    private void buildListsEditor() {
        listTitleField.setPromptText("List name");
        listTitleField.setStyle(twTitleCss());
        listTitleField.textProperty().addListener((o, a, b) -> {
            if (fillingList || currentList == null) return;
            if (b != null && b.length() > 200) {
                listTitleField.setText(b.substring(0, 200));
                return;
            }
            currentList.title = b == null || b.isBlank() ? "Untitled list" : b;
            listNames.refresh();
            touch();
        });

        addItemField.setPromptText("Add item and press Enter");
        addItemField.setStyle(twItemCss());
        addListItemBtn = pill("Add");
        addListItemBtn.setMaxWidth(80);
        Runnable addItem = () -> {
            if (currentList == null) return;
            if (currentList.items.size() >= 500) {
                new Alert(Alert.AlertType.INFORMATION,
                        "A list can have at most 500 items.").showAndWait();
                return;
            }
            String text = addItemField.getText() == null ? "" : addItemField.getText().trim();
            if (text.length() > 8000) text = text.substring(0, 8000);
            currentList.items.add(new TwItem(text));
            addItemField.clear();
            rebuildListRows();
            addItemField.requestFocus();
            touch();
        };
        addListItemBtn.setOnAction(e -> addItem.run());
        addItemField.setOnAction(e -> addItem.run());
        HBox addRowBox = new HBox(8, addItemField, addListItemBtn);
        addRowBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(addItemField, Priority.ALWAYS);

        ScrollPane rowsScroll = new ScrollPane(listRows);
        rowsScroll.setFitToWidth(true);
        rowsScroll.setPrefHeight(160);
        rowsScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        listHtml.setPrefHeight(200);
        listHtml.setOnKeyReleased(e -> captureListHtml());
        listHtml.setOnMouseReleased(e -> captureListHtml());
        HBox.setHgrow(listHtml, Priority.ALWAYS);
        wireHtmlEditor(listHtml);
        VBox.setVgrow(listHtml, Priority.ALWAYS);

        listsEditor.getChildren().addAll(listTitleField, addRowBox, rowsScroll,
                new Label("Selected item (bold / size / color / heading)"), listHtml);
        listsEditor.setPadding(new Insets(28, 36, 24, 28));
    }

    private void openList(TwList list) {
        currentList = list;
        fillingList = true;
        addItemField.clear();
        listTitleField.setDisable(list == null);
        addItemField.setDisable(list == null);
        if (addListItemBtn != null) addListItemBtn.setDisable(list == null);
        if (list == null) {
            listTitleField.setText("");
            listRows.getChildren().clear();
            bindListItem(null);
            fillingList = false;
            return;
        }
        listTitleField.setText(list.title);
        fillingList = false;
        rebuildListRows();
        bindListItem(list.items.isEmpty() ? null : list.items.get(0));
    }

    private void bindListItem(TwItem item) {
        currentListItem = item;
        fillingList = true;
        String html = item == null || item.text == null ? "" : item.text;
        putHtml(listHtml, html);
        refreshCounts(html);
        fillingList = false;
        listHtml.setFocusTraversable(item != null);
    }

    private void captureListHtml() {
        if (fillingList || currentListItem == null) return;
        currentListItem.text = TypewriterHtml.storedText(listHtml.getHtmlText());
        refreshCounts(currentListItem.text);
        touch();
    }

    private void refreshCounts(String html) {
        String plain = TypewriterHtml.plainText(html);
        int lines = Math.max(1, plain.isEmpty() ? 1 : plain.split("\\R", -1).length);
        int words = 0;
        String t = plain.trim();
        if (!t.isEmpty()) words = t.split("\\s+").length;
        int letters = 0;
        for (int i = 0; i < plain.length(); i++) {
            if (Character.isLetter(plain.charAt(i))) letters++;
        }
        wordCount.setText(lines + (lines == 1 ? " line" : " lines")
                + " · " + words + (words == 1 ? " word" : " words")
                + " · " + letters + (letters == 1 ? " letter" : " letters"));
    }

    private void wireHtmlEditor(HTMLEditor editor) {
        if (editor == null) return;
        Runnable hook = () -> paintHtmlSurface(editor);
        editor.skinProperty().addListener((o, a, b) -> Platform.runLater(hook));
        editor.sceneProperty().addListener((o, a, b) -> {
            if (b != null) Platform.runLater(hook);
        });
        Platform.runLater(hook);
    }

    private void paintHtmlSurface(HTMLEditor editor) {
        if (editor == null) return;
        WebView wv = findWebView(editor);
        if (wv == null) return;
        String bg = AppTheme.paper();
        String ink = AppTheme.ink();
        try {
            wv.setPageFill(Color.web(bg));
        } catch (Exception ignored) {}
        wv.setStyle("-fx-background-color: " + bg + ";");
        injectPageTheme(wv.getEngine(), bg, ink, AppTheme.muted());
        ensureBodyEditable(editor);
        watchPage(wv);
    }

    private void watchPage(WebView wv) {
        if (wv == null || wv.getProperties().containsKey("ashore-tw-watch")) return;
        wv.getProperties().put("ashore-tw-watch", Boolean.TRUE);
        WebEngine eng = wv.getEngine();
        eng.getLoadWorker().stateProperty().addListener((o, a, st) -> {
            if (st == Worker.State.SUCCEEDED) {
                injectPageTheme(eng, AppTheme.paper(), AppTheme.ink(), AppTheme.muted());
                ensureBodyEditable(wv);
                restoreDocScroll(wv);
            }
        });
        eng.documentProperty().addListener((o, a, b) -> {
            injectPageTheme(eng, AppTheme.paper(), AppTheme.ink(), AppTheme.muted());
            restoreDocScroll(wv);
        });
    }

    private static String jsQuote(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static void injectPageTheme(WebEngine eng, String bg, String ink, String muted) {
        if (eng == null) return;
        try {
            eng.executeScript(
                    "try{"
                            + "var bg='" + jsQuote(bg) + "',ink='" + jsQuote(ink)
                            + "',muted='" + jsQuote(muted) + "';"
                            + "if(document.documentElement)document.documentElement.style.backgroundColor=bg;"
                            + "if(document.body){document.body.contentEditable='true';"
                            + "document.body.setAttribute('contenteditable','true');"
                            + "document.body.style.backgroundColor=bg;document.body.style.color=ink;"
                            + "document.body.style.fontSize='15px';document.body.style.lineHeight='1.45';"
                            + "document.body.style.fontFamily='Segoe UI, Helvetica Neue, Arial, sans-serif';"
                            + "document.body.style.margin='0';document.body.style.padding='8px 12px 8px 44px';"
                            + "document.body.style.position='relative';}"
                            + "var s=document.getElementById('ashore-tw-theme');"
                            + "if(!s && document.head){s=document.createElement('style');s.id='ashore-tw-theme';document.head.appendChild(s);}"
                            + "if(s)s.textContent='html,body{background:'+bg+' !important;color:'+ink+' !important;}'"
                            + "+'body{font-size:15px;line-height:1.45;margin:0;padding:8px 12px 8px 44px;position:relative;}'"
                            + "+'p,div,h1,h2,h3,h4,h5,h6,li,blockquote,pre{margin:0 !important;padding:0 !important;"
                            + "line-height:normal !important;min-height:1.45em;}'"
                            + "+'#ashore-ln{position:absolute;left:0;top:0;width:36px;z-index:5;pointer-events:none;"
                            + "user-select:none;font:13px Consolas,Courier New,monospace;color:'+muted"
                            + "+' !important;text-align:right;}'"
                            + "+'#ashore-ln span{position:absolute;right:8px;left:0;padding-top:1px;}';"
                            + "window.ashorePaintLn=function(){"
                            + "if(!document.body)return 1;"
                            + "var g=document.getElementById('ashore-ln');"
                            + "if(!g){g=document.createElement('div');g.id='ashore-ln';"
                            + "g.setAttribute('contenteditable','false');document.body.appendChild(g);}"
                            + "var bodyRect=document.body.getBoundingClientRect();"
                            + "var lines=[];"
                            + "function addRect(r){if(!r||r.height<2)return;"
                            + "var top=Math.max(0,r.top-bodyRect.top);var i;"
                            + "for(i=0;i<lines.length;i++){if(Math.abs(lines[i].top-top)<4)return;}"
                            + "lines.push({top:top,h:Math.max(16,r.height)});}"
                            + "function walk(node){if(!node||node.id==='ashore-ln'||node.id==='ashore-tw-theme')return;"
                            + "if(node.nodeType===3){if(node.nodeValue&&node.nodeValue.replace(/\\s/g,'').length){"
                            + "var rg=document.createRange();rg.selectNodeContents(node);var rs=rg.getClientRects();"
                            + "for(var i=0;i<rs.length;i++)addRect(rs[i]);}return;}"
                            + "if(node.nodeType!==1)return;"
                            + "var tag=node.tagName;if(tag==='STYLE'||tag==='SCRIPT')return;"
                            + "if(tag==='BR'){addRect(node.getBoundingClientRect());return;}"
                            + "var hasBlock=false;for(var c=node.firstChild;c;c=c.nextSibling){"
                            + "if(c.nodeType===1&&/^(P|DIV|H[1-6]|LI|BLOCKQUOTE|PRE|UL|OL)$/.test(c.tagName)){hasBlock=true;break;}}"
                            + "if(!hasBlock){var range=document.createRange();range.selectNodeContents(node);"
                            + "var rs=range.getClientRects();if(rs.length){for(var i=0;i<rs.length;i++)addRect(rs[i]);}"
                            + "else addRect(node.getBoundingClientRect());return;}"
                            + "for(var c=node.firstChild;c;c=c.nextSibling)walk(c);}"
                            + "for(var c=document.body.firstChild;c;c=c.nextSibling)walk(c);"
                            + "lines.sort(function(a,b){return a.top-b.top;});"
                            + "var html='';var n=lines.length;"
                            + "if(!n){n=1;html='<span style=\"top:8px;height:22px;\">1</span>';}"
                            + "else{for(var i=0;i<n;i++)html+='<span style=\"top:'+lines[i].top+'px;height:'"
                            + "+lines[i].h+'px;\">'+(i+1)+'</span>';}"
                            + "g.style.height=Math.max(document.body.scrollHeight,1)+'px';"
                            + "if(g.innerHTML!==html)g.innerHTML=html;"
                            + "window.__ashoreLineCount=n;return n;};"
            + "window.ashorePaintLnSoon=function(){if(window.__ashoreLnT)clearTimeout(window.__ashoreLnT);"
            + "window.__ashoreLnT=setTimeout(window.ashorePaintLn,50);};"
            + "window.ashorePaintLn();"
            + "if(!window.__ashoreLnWatch){window.__ashoreLnWatch=true;"
            + "document.addEventListener('input',window.ashorePaintLnSoon);"
            + "document.addEventListener('keyup',window.ashorePaintLnSoon);"
            + "document.addEventListener('keydown',function(e){"
            + "if(e&&(e.key==='Enter'||e.keyCode===13))window.ashorePaintLnSoon();});"
            + "new MutationObserver(function(ms){var paint=false;"
            + "for(var i=0;i<ms.length;i++){var t=ms[i].target;"
            + "if(t&&(t.id==='ashore-ln'||(t.parentElement&&t.parentElement.id==='ashore-ln')))continue;"
            + "paint=true;break;}"
            + "if(paint)window.ashorePaintLnSoon();"
            + "}).observe(document.documentElement,{childList:true,subtree:true,characterData:true});}"
                            + "}catch(e){}"
            );
        } catch (Exception ignored) {}
    }

    private static WebView findWebView(javafx.scene.Parent root) {
        if (root == null) return null;
        for (javafx.scene.Node n : root.getChildrenUnmodifiable()) {
            if (n instanceof WebView wv) return wv;
            if (n instanceof javafx.scene.Parent p) {
                WebView found = findWebView(p);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void rebuildListRows() {
        listRows.getChildren().clear();
        if (currentList == null) return;
        int n = 1;
        for (TwItem item : new ArrayList<>(currentList.items)) {
            CheckBox box = new CheckBox();
            box.setSelected(item.done);
            box.selectedProperty().addListener((o, a, b) -> {
                item.done = b;
                touch();
            });
            TextField tf = new TextField(item.text);
            tf.setPromptText("Item");
            tf.setStyle(twItemCss());
            tf.textProperty().addListener((o, a, b) -> {
                if (b != null && b.length() > 8000) {
                    tf.setText(b.substring(0, 8000));
                    return;
                }
                item.text = b == null ? "" : b;
                touch();
            });
            Button del = new Button("x");
            del.setOnAction(e -> {
                currentList.items.remove(item);
                rebuildListRows();
                touch();
            });
            Label num = new Label(String.valueOf(n++));
            num.setMinWidth(22);
            num.setStyle("-fx-text-fill: " + AppTheme.muted() + "; -fx-font-family: Consolas, 'Courier New', monospace;");
            HBox row = new HBox(8, num, box, tf, del);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(tf, Priority.ALWAYS);
            row.setOnMouseClicked(e -> bindListItem(item));
            tf.setOnMouseClicked(e -> bindListItem(item));
            listRows.getChildren().add(row);
        }
    }

    private void buildPagesNav() {
        Label sideTitle = new Label("PAGES");
        mute(sideTitle);

        TreeItem<Object> rootItem = new TreeItem<>(pagesRoot);
        rootItem.setExpanded(true);
        pageTree.setRoot(rootItem);
        pageTree.setShowRoot(false);
        pageTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Object value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                    return;
                }
                if (value instanceof TwNote note) {
                    int n = note.images == null ? 0 : note.images.size();
                    String title = note.title == null || note.title.isBlank() ? "Untitled" : note.title;
                    setText(n > 0 ? title + " (" + n + " img)" : title);
                    return;
                }
                setText(String.valueOf(value));
            }
        });
        pageTree.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (mode != Mode.PAGES) return;
            if (b != null && b.getValue() instanceof TwNote note) bindNote(note);
        });

        Button addNote = pill("New page");
        Button addFolder = pill("New folder");
        Button del = pill("Delete");
        addNote.setOnAction(e -> addPageNote());
        addFolder.setOnAction(e -> addPageFolder());
        del.setOnAction(e -> deleteSelectedPage());

        pagesNav.getChildren().addAll(sideTitle, pageTree, addNote, addFolder, del);
        pagesNav.setPadding(new Insets(16, 12, 16, 16));
        VBox.setVgrow(pageTree, Priority.ALWAYS);
    }

    private void deleteSelectedPage() {
        TreeItem<Object> sel = pageTree.getSelectionModel().getSelectedItem();
        if (sel == null || sel == pageTree.getRoot()) return;
        Object v = sel.getValue();
        String label = String.valueOf(v);
        if (v instanceof TwFolder) {
            showConfirmOverlay("Delete folder?",
                    label + " and everything inside will be removed.",
                    "Delete", "Cancel", () -> {
                        TwFolder folder = (TwFolder) v;
                        TwFolder parent = folderOf(sel.getParent());
                        parent.children.remove(folder);
                        TreeItem<Object> parentItem = sel.getParent();
                        parentItem.getChildren().remove(sel);
                        touch();
                    });
        } else if (v instanceof TwNote) {
            showConfirmOverlay("Delete page?", label, "Delete", "Cancel", () -> {
                TwNote note = (TwNote) v;
                TwFolder parent = folderOf(sel.getParent());
                parent.notes.remove(note);
                if (currentNote == note) bindNote(null);
                TreeItem<Object> parentItem = sel.getParent();
                parentItem.getChildren().remove(sel);
                touch();
            });
        }
    }

    private void buildNoteEditor() {
        titleField.setPromptText("Title");
        titleField.setStyle(twTitleCss());
        titleField.textProperty().addListener((o, a, b) -> {
            if (fillingEditor || currentNote == null) return;
            if (b != null && b.length() > 200) {
                titleField.setText(b.substring(0, 200));
                return;
            }
            currentNote.title = b;
            if (mode == Mode.DIARY) rebuildCalendar();
            touch();
        });

        dateLabel.setStyle("-fx-text-fill: " + AppTheme.muted() + "; -fx-font-size: 12px;");

        bodyArea.setWrapText(true);
        bodyArea.setPromptText("Start typing…");
        bodyArea.setStyle(twBodyCss());
        bodyArea.setVisible(false);
        bodyArea.setManaged(false);
        htmlBody.setPrefHeight(280);
        htmlBody.setOnKeyReleased(e -> captureHtml());
        htmlBody.setOnMouseReleased(e -> captureHtml());

        attachBtn = pill("Attach images");
        attachBtn.setMaxWidth(200);
        attachBtn.setOnAction(e -> attachImages());
        photoStrip.setAlignment(Pos.CENTER_LEFT);
        HBox photos = new HBox(12, attachBtn, photoStrip);
        photos.setAlignment(Pos.CENTER_LEFT);

        HBox.setHgrow(htmlBody, Priority.ALWAYS);
        wireHtmlEditor(htmlBody);
        VBox.setVgrow(htmlBody, Priority.ALWAYS);
        editorHint.setStyle("-fx-text-fill: " + AppTheme.muted() + "; -fx-font-size: 13px;");
        editorHint.setWrapText(true);
        StackPane pageBox = new StackPane(htmlBody, editorHint);
        StackPane.setAlignment(editorHint, Pos.CENTER);
        VBox.setVgrow(pageBox, Priority.ALWAYS);
        Label fmtHint = new Label("Toolbar: bold, italic, size, color, headings. Line numbers on the left of the page.");
        fmtHint.setStyle("-fx-text-fill: " + AppTheme.muted() + "; -fx-font-size: 11px;");
        wordCount.setText("0 lines · 0 words · 0 letters");
        wordCount.setStyle("-fx-text-fill: " + AppTheme.muted() + "; -fx-font-size: 12px;");
        noteEditor.getChildren().addAll(titleField, dateLabel, fmtHint, pageBox, wordCount, photos);
        noteEditor.setPadding(new Insets(28, 36, 24, 28));
    }

    private void bindNote(TwNote note) {
        if (note != null && currentNote == note) {
            syncEditorChrome();
            ensureBodyEditable(htmlBody);
            return;
        }
        boolean sameId = note != null && currentNote != null
                && note.id != null && note.id.equals(currentNote.id);
        if (currentNote != null && !fillingEditor) captureHtml();
        String html = note == null || note.body == null ? "" : note.body;
        if (note != null) note.body = TypewriterHtml.storedText(html);
        String body = note == null ? "" : note.body;
        // Host pull replaces TwNote instances; skip full reload when markup is unchanged.
        if (sameId && TypewriterHtml.storedText(currentNote.body)
                .equals(TypewriterHtml.storedText(body))) {
            currentNote = note;
            fillingEditor = true;
            titleField.setText(note.title == null ? "" : note.title);
            if (note.date != null) dateLabel.setText(note.date.toString());
            else dateLabel.setText("Loose page");
            fillingEditor = false;
            syncEditorChrome();
            rebuildPhotos();
            return;
        }
        currentNote = note;
        fillingEditor = true;
        titleField.setText(note == null || note.title == null ? "" : note.title);
        putHtml(htmlBody, body, sameId);
        refreshCounts(body);
        if (note == null) {
            dateLabel.setText("");
        } else if (note.date != null) {
            dateLabel.setText(note.date.toString());
        } else {
            dateLabel.setText("Loose page");
        }
        fillingEditor = false;
        syncEditorChrome();
        rebuildPhotos();
    }

    private void syncEditorChrome() {
        boolean on = currentNote != null;
        titleField.setDisable(!on);
        editorHint.setText(mode == Mode.DIARY
                ? "Pick a written day, or create this date."
                : "Select a page, or click New page.");
        editorHint.setVisible(!on);
        editorHint.setManaged(!on);
        editorHint.setMouseTransparent(on);
        htmlBody.setDisable(false);
        htmlBody.setMouseTransparent(false);
        htmlBody.setFocusTraversable(true);
        if (attachBtn != null) attachBtn.setDisable(!on);
    }

    private void putHtml(HTMLEditor editor, String html) {
        putHtml(editor, html, false);
    }

    private void putHtml(HTMLEditor editor, String html, boolean keepScroll) {
        if (editor == null) return;
        WebView wv = findWebView(editor);
        if (keepScroll && wv != null) {
            wv.getProperties().put("ashore-keep-sx", readDocScroll(wv.getEngine(), false));
            wv.getProperties().put("ashore-keep-sy", readDocScroll(wv.getEngine(), true));
        } else if (wv != null) {
            wv.getProperties().remove("ashore-keep-sx");
            wv.getProperties().remove("ashore-keep-sy");
        }
        String inner = TypewriterHtml.htmlForEditor(html);
        editor.setDisable(false);
        editor.setMouseTransparent(false);
        editor.setHtmlText("<html><head></head><body contenteditable=\"true\">" + inner + "</body></html>");
        Runnable ready = () -> {
            paintHtmlSurface(editor);
            ensureBodyEditable(editor);
            restoreDocScroll(findWebView(editor));
        };
        Platform.runLater(ready);
        htmlReady.stop();
        htmlReady.setOnFinished(e -> ready.run());
        htmlReady.playFromStart();
        if (keepScroll) {
            PauseTransition again = new PauseTransition(Duration.millis(140));
            again.setOnFinished(e -> restoreDocScroll(findWebView(editor)));
            again.playFromStart();
        }
    }

    private static int readDocScroll(WebEngine eng, boolean vertical) {
        if (eng == null) return 0;
        try {
            String expr = vertical
                    ? "(window.pageYOffset||document.documentElement.scrollTop||document.body.scrollTop||0)|0"
                    : "(window.pageXOffset||document.documentElement.scrollLeft||document.body.scrollLeft||0)|0";
            Object v = eng.executeScript(expr);
            if (v instanceof Number n) return n.intValue();
        } catch (Exception ignored) {}
        return 0;
    }

    private static void restoreDocScroll(WebView wv) {
        if (wv == null) return;
        Object ox = wv.getProperties().get("ashore-keep-sx");
        Object oy = wv.getProperties().get("ashore-keep-sy");
        if (!(ox instanceof Number) || !(oy instanceof Number)) return;
        int x = ((Number) ox).intValue();
        int y = ((Number) oy).intValue();
        if (x <= 0 && y <= 0) return;
        try {
            wv.getEngine().executeScript("try{window.scrollTo(" + x + "," + y + ");}catch(e){}");
        } catch (Exception ignored) {}
    }

    private void ensureBodyEditable(HTMLEditor editor) {
        if (editor == null) return;
        editor.setDisable(false);
        editor.setMouseTransparent(false);
        ensureBodyEditable(findWebView(editor));
    }

    private void ensureBodyEditable(WebView wv) {
        if (wv == null) return;
        wv.setDisable(false);
        wv.setMouseTransparent(false);
        try {
            wv.getEngine().executeScript(
                    "if(document.body){"
                            + "document.body.contentEditable='true';"
                            + "document.body.setAttribute('contenteditable','true');"
                            + "var kids=document.body.childNodes,has=false;"
                            + "for(var i=0;i<kids.length;i++){"
                            + "var n=kids[i];if(n.id==='ashore-ln')continue;"
                            + "if(n.nodeType===3&&n.nodeValue&&n.nodeValue.replace(/\\s/g,'').length){has=true;break;}"
                            + "if(n.nodeType===1){has=true;break;}}"
                            + "if(!has){var p=document.createElement('p');p.innerHTML='<br>';document.body.appendChild(p);}"
                            + "}"
            );
        } catch (Exception ignored) {}
    }

    private void captureHtml() {
        if (fillingEditor || currentNote == null) return;
        String raw = TypewriterHtml.storedText(htmlBody.getHtmlText());
        currentNote.body = raw.length() > 100000 ? raw.substring(0, 100000) : raw;
        refreshCounts(currentNote.body);
        touch();
    }

    private TwFolder folderOf(TreeItem<Object> item) {
        while (item != null) {
            if (item.getValue() instanceof TwFolder f) return f;
            item = item.getParent();
        }
        return pagesRoot;
    }

    private void addPageNote() {
        TreeItem<Object> sel = pageTree.getSelectionModel().getSelectedItem();
        TwFolder folder = folderOf(sel);
        TwNote note = new TwNote("Untitled", null);
        folder.notes.add(note);
        TreeItem<Object> parent = (sel != null && sel.getValue() instanceof TwFolder) ? sel
                : (sel == null ? pageTree.getRoot() : sel.getParent());
        if (parent == null) parent = pageTree.getRoot();
        TreeItem<Object> created = new TreeItem<>(note);
        parent.getChildren().add(created);
        parent.setExpanded(true);
        pageTree.getSelectionModel().select(created);
        Platform.runLater(() -> {
            bindNote(note);
            titleField.requestFocus();
        });
        touch();
    }

    private void addPageFolder() {
        TreeItem<Object> sel = pageTree.getSelectionModel().getSelectedItem();
        TreeItem<Object> parentItem = pageTree.getRoot();
        TwFolder parentFolder = pagesRoot;
        if (sel != null && sel.getValue() instanceof TwFolder) {
            parentItem = sel;
            parentFolder = (TwFolder) sel.getValue();
        } else if (sel != null && sel.getParent() != null && sel.getParent().getValue() instanceof TwFolder) {
            parentItem = sel.getParent();
            parentFolder = (TwFolder) sel.getParent().getValue();
        }
        final TreeItem<Object> destItem = parentItem;
        final TwFolder destFolder = parentFolder;
        showPromptOverlay("Folder name", "New folder", name -> {
            TwFolder child = new TwFolder(name == null || name.isBlank() ? "Folder" : name);
            destFolder.children.add(child);
            TreeItem<Object> created = new TreeItem<>(child);
            destItem.getChildren().add(created);
            destItem.setExpanded(true);
            pageTree.getSelectionModel().select(created);
            touch();
        });
    }

    private void attachImages() {
        if (currentNote == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach images");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"));
        List<File> files = chooser.showOpenMultipleDialog(getScene() == null ? null : getScene().getWindow());
        if (files == null || files.isEmpty()) return;
        for (File file : files) {
            if (file == null) continue;
            String name = file.getName() == null ? "" : file.getName().toLowerCase(Locale.ROOT);
            if (!(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".gif") || name.endsWith(".webp"))) {
                continue;
            }
            if (currentNote.images.size() >= 20) {
                new Alert(Alert.AlertType.INFORMATION,
                        "This note already has 20 images.").showAndWait();
                break;
            }
            try {
                currentNote.images.add(ImageMetaStrip.copyStripped(file));
            } catch (Exception ex) {
                new Alert(Alert.AlertType.WARNING,
                        "Could not attach “" + file.getName() + "”: metadata wipe failed.")
                        .showAndWait();
            }
        }
        rebuildPhotos();
        touch();
    }

    private void rebuildPhotos() {
        photoStrip.getChildren().clear();
        if (currentNote == null) return;
        for (File file : currentNote.images) {
            ImageView iv = new ImageView(new Image(file.toURI().toString(), 72, 72, true, true));
            iv.setFitWidth(72);
            iv.setFitHeight(72);
            iv.setPreserveRatio(true);
            iv.setCursor(Cursor.HAND);
            iv.setOnMouseClicked(e -> showPhotoFull(file));
            String added = java.time.Instant.ofEpochMilli(file.lastModified())
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                    .toString();
            Label meta = new Label(file.getName() + "\nadded " + added);
            meta.setWrapText(true);
            meta.setStyle("-fx-font-size: 10px; -fx-text-fill: " + AppTheme.muted() + ";");
            Button remove = new Button("Remove");
            remove.setOnAction(e -> {
                currentNote.images.remove(file);
                rebuildPhotos();
                touch();
            });
            VBox one = new VBox(4, iv, meta, remove);
            photoStrip.getChildren().add(one);
        }
        syncAttachBtn();
        if (mode == Mode.PAGES) pageTree.refresh();
    }

    private void syncAttachBtn() {
        if (attachBtn == null) return;
        int n = currentNote == null ? 0 : Math.min(20, currentNote.images.size());
        attachBtn.setText("Attach images (" + n + "/20)");
        attachBtn.setDisable(currentNote == null || n >= 20);
    }

    private void showPhotoFull(File file) {
        if (file == null || !file.isFile()) return;
        Image full = new Image(file.toURI().toString(), false);
        ImageView iv = new ImageView(full);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        iv.setCursor(Cursor.HAND);
        ScrollPane scroll = new ScrollPane(iv);
        scroll.setPannable(true);
        scroll.setFitToWidth(false);
        scroll.setFitToHeight(false);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scroll.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        final boolean[] actualPixels = {false};
        Runnable applySize = () -> {
            if (overlay == null) return;
            if (actualPixels[0]) {
                iv.setFitWidth(0);
                iv.setFitHeight(0);
                if (full.getWidth() > 0) {
                    iv.setFitWidth(full.getWidth());
                    iv.setFitHeight(full.getHeight());
                }
            } else {
                double w = Math.max(200, overlay.getWidth() * 0.92);
                double h = Math.max(200, overlay.getHeight() * 0.88);
                iv.setFitWidth(w);
                iv.setFitHeight(h);
            }
        };
        iv.setOnMouseClicked(e -> {
            actualPixels[0] = !actualPixels[0];
            applySize.run();
        });
        Label hint = new Label("Click photo: full pixels  ·  Esc or dim area: close");
        hint.setStyle("-fx-text-fill: #f3eee4; -fx-font-size: 12px; -fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif;");
        VBox box = new VBox(8, hint, scroll);
        box.setAlignment(Pos.CENTER);
        box.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        showOverlayBox(box);
        overlay.setOnMouseClicked(e -> {
            if (e.getTarget() == overlay) dismissOverlay();
        });
        overlay.widthProperty().addListener((o, a, b) -> applySize.run());
        overlay.heightProperty().addListener((o, a, b) -> applySize.run());
        Platform.runLater(applySize);
    }

    private TreeItem<Object> findNoteById(TreeItem<Object> item, String id) {
        if (item == null || id == null) return null;
        if (item.getValue() instanceof TwNote n && id.equals(n.id)) return item;
        for (TreeItem<Object> c : item.getChildren()) {
            TreeItem<Object> found = findNoteById(c, id);
            if (found != null) return found;
        }
        return null;
    }

    private TreeItem<Object> findFirstNote(TreeItem<Object> item) {
        if (item == null) return null;
        if (item.getValue() instanceof TwNote) return item;
        for (TreeItem<Object> c : item.getChildren()) {
            TreeItem<Object> found = findFirstNote(c);
            if (found != null) return found;
        }
        return null;
    }

    private void seedDefaults() {
        TwList groceries = new TwList("Groceries");
        groceries.items.add(new TwItem("Milk"));
        groceries.items.add(new TwItem("Eggs"));
        lists.add(groceries);
        listNames.getItems().setAll(lists);
        listNames.getSelectionModel().select(groceries);

        TwFolder novel = new TwFolder("Novel");
        TwNote opening = new TwNote("Opening", null);
        novel.notes.add(opening);
        pagesRoot.children.add(novel);
        TreeItem<Object> novelItem = new TreeItem<>(novel);
        novelItem.getChildren().add(new TreeItem<>(opening));
        pageTree.getRoot().getChildren().add(novelItem);

        TwNote scrap = new TwNote("Scrap", null);
        pagesRoot.notes.add(scrap);
        pageTree.getRoot().getChildren().add(new TreeItem<>(scrap));
    }
}