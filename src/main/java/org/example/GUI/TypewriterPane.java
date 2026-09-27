package org.example.GUI;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.example.AppTimeReference;

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
            this.id = id;
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
            this.id = id;
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
            this.id = id;
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private Mode mode = Mode.DIARY;

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
    private final VBox listsNav = new VBox(10);
    private final VBox listsEditor = new VBox(12);

    private final TwFolder pagesRoot = new TwFolder("Pages");
    private final TreeView<Object> pageTree = new TreeView<>();
    private final VBox pagesNav = new VBox(10);

    private final TextField titleField = new TextField();
    private final TextArea bodyArea = new TextArea();
    private final Label dateLabel = new Label();
    private final HBox photoStrip = new HBox(8);
    private final VBox noteEditor = new VBox(12);
    private TwNote currentNote;
    private boolean fillingEditor;
    private boolean fillingList;

    private StackPane overlay;
    private javafx.event.EventHandler<KeyEvent> overlayEsc;

    private final PauseTransition saveDebounce = new PauseTransition(Duration.millis(400));

    public TypewriterPane() {
        getStyleClass().add("typewriter-root");
        setStyle("-fx-background-color: #f3eee4;");
        saveDebounce.setOnFinished(e -> saveAll());

        HBox rail = new HBox(8, tabDiary, tabLists, tabPages);
        rail.setPadding(new Insets(14, 16, 8, 16));
        rail.setAlignment(Pos.CENTER_LEFT);
        rail.setStyle("-fx-background-color: #e7dfd0;");
        tabDiary.setOnAction(e -> showMode(Mode.DIARY));
        tabLists.setOnAction(e -> showMode(Mode.LISTS));
        tabPages.setOnAction(e -> showMode(Mode.PAGES));

        buildDiaryNav();
        buildListsNav();
        buildPagesNav();
        buildNoteEditor();
        buildListsEditor();

        leftStack.getChildren().addAll(pagesNav, listsNav, diaryNav);
        centerStack.getChildren().addAll(listsEditor, noteEditor);

        VBox leftWrap = new VBox(leftStack);
        leftWrap.setPrefWidth(280);
        leftWrap.setStyle("-fx-background-color: #e7dfd0;");

        BorderPane body = new BorderPane();
        body.setTop(rail);
        body.setLeft(leftWrap);
        body.setCenter(centerStack);
        layers.getChildren().add(body);
        setCenter(layers);

        TypewriterStore.Data data = TypewriterStore.load();
        if (data.diary.isEmpty() && data.lists.isEmpty()
                && data.pagesRoot.children.isEmpty() && data.pagesRoot.notes.isEmpty()) {
            seedDefaults();
            saveAll();
        } else {
            diaryPages.clear();
            diaryPages.putAll(data.diary);
            lists.clear();
            lists.addAll(data.lists);
            listNames.getItems().setAll(lists);
            rebuildPageTree(data.pagesRoot);
        }
        TypewriterStore.pruneEmptyDiary(diaryPages);
        showMode(Mode.DIARY);
    }

    public void saveAll() {
        TypewriterStore.pruneEmptyDiary(diaryPages);
        TypewriterStore.save(diaryPages, lists, pagesRoot);
        rebuildCalendar();
    }

    private void touch() {
        if (fillingEditor || fillingList) return;
        saveDebounce.playFromStart();
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
        h.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        Label m = new Label(body);
        m.setWrapText(true);
        Button yes = new Button(yesText);
        Button no = new Button(noText);
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
        h.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        Label m = new Label(body);
        m.setWrapText(true);
        Button ok = new Button("Okay");
        box.getChildren().addAll(h, m, ok);
        showOverlayBox(box);
        ok.setOnAction(e -> dismissOverlay());
    }

    private void showPromptOverlay(String header, String seed, Consumer<String> onOk) {
        VBox box = GuiHelper.createWhiteBox();
        Label h = new Label(header);
        h.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        TextField field = new TextField(seed == null ? "" : seed);
        Button save = new Button("Save");
        Button cancel = new Button("Cancel");
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
        return "-fx-background-color: #fbf7ef; -fx-text-fill: #1c2430; -fx-background-radius: 8; "
                + "-fx-padding: 8 16; -fx-font-family: Georgia; -fx-font-size: 13px;";
    }

    private String railOff() {
        return "-fx-background-color: transparent; -fx-text-fill: #6a5c4a; -fx-background-radius: 8; "
                + "-fx-padding: 8 16; -fx-font-family: Georgia; -fx-font-size: 13px;";
    }

    private Button pill(String text) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setStyle("-fx-background-color: #fbf7ef; -fx-border-color: #c9bba6; "
                + "-fx-background-radius: 8; -fx-border-radius: 8; -fx-font-size: 12px;");
        return b;
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
            TreeItem<Object> first = findFirstNote(pageTree.getRoot());
            if (first != null) pageTree.getSelectionModel().select(first);
        }
    }

    private void buildDiaryNav() {
        Label sideTitle = new Label("DIARY");
        sideTitle.setStyle("-fx-font-family: Georgia; -fx-font-size: 13px; -fx-text-fill: #6a5c4a;");

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
        diaryMonthLbl.setStyle("-fx-font-family: Georgia; -fx-font-size: 14px;");
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

        findTitle.setStyle("-fx-font-family: Georgia; -fx-font-size: 13px; -fx-text-fill: #6a5c4a;");
        findList.setPrefHeight(280);
        findList.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 1) onFindPick();
        });
        Button findBack = pill("Back");
        findBack.setOnAction(e -> findBack());
        Button findClose = pill("Calendar");
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
            for (Integer y : idx.keySet()) {
                int n = 0;
                for (List<LocalDate> days : idx.get(y).values()) n += days.size();
                findList.getItems().add(y + "  (" + n + ")");
            }
        } else if (findLevel == FindLevel.MONTH) {
            findTitle.setText(findYear + " — months with entries");
            Map<Month, List<LocalDate>> months = idx.getOrDefault(findYear, Map.of());
            if (months.isEmpty()) {
                findList.getItems().add("(none)");
                return;
            }
            for (Month m : months.keySet()) {
                String name = m.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                findList.getItems().add(name + "  (" + months.get(m).size() + ")");
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
            for (LocalDate d : days) {
                TwNote n = diaryPages.get(d);
                String t = n == null || n.title == null || n.title.isBlank() ? "entry" : n.title;
                findList.getItems().add(d.getDayOfMonth() + "  —  " + t);
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
            h.setStyle("-fx-text-fill: #8a7b66; -fx-font-size: 11px;");
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
            String border = isToday ? "#1c2430" : "#c9bba6";
            String bg = selected ? "#1c2430" : "#fbf7ef";
            String fg = selected ? "#fbf7ef" : "#1c2430";
            String weight = has ? "bold" : "normal";
            cell.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: " + fg
                    + "; -fx-border-color: " + border + "; -fx-font-weight: " + weight
                    + "; -fx-background-radius: 6; -fx-border-radius: 6; -fx-padding: 0;");
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
        sideTitle.setStyle("-fx-font-family: Georgia; -fx-font-size: 13px; -fx-text-fill: #6a5c4a;");
        listNames.setPrefHeight(320);
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
        listTitleField.setStyle("-fx-background-color: transparent; -fx-border-width: 0 0 1 0; "
                + "-fx-border-color: #c9bba6; -fx-font-family: Georgia; -fx-font-size: 22px;");
        listTitleField.textProperty().addListener((o, a, b) -> {
            if (fillingList || currentList == null) return;
            currentList.title = b == null || b.isBlank() ? "Untitled list" : b;
            listNames.refresh();
            touch();
        });

        addItemField.setPromptText("Add item and press Enter");
        addItemField.setStyle("-fx-background-color: #fbf7ef; -fx-font-family: Georgia; -fx-font-size: 14px;");
        Button addRow = pill("Add");
        addRow.setMaxWidth(80);
        Runnable addItem = () -> {
            if (currentList == null) return;
            String text = addItemField.getText() == null ? "" : addItemField.getText().trim();
            currentList.items.add(new TwItem(text));
            addItemField.clear();
            rebuildListRows();
            addItemField.requestFocus();
            touch();
        };
        addRow.setOnAction(e -> addItem.run());
        addItemField.setOnAction(e -> addItem.run());
        HBox addRowBox = new HBox(8, addItemField, addRow);
        addRowBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(addItemField, Priority.ALWAYS);

        ScrollPane rowsScroll = new ScrollPane(listRows);
        rowsScroll.setFitToWidth(true);
        rowsScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(rowsScroll, Priority.ALWAYS);

        listsEditor.getChildren().addAll(listTitleField, addRowBox, rowsScroll);
        listsEditor.setPadding(new Insets(28, 36, 24, 28));
    }

    private void openList(TwList list) {
        currentList = list;
        fillingList = true;
        addItemField.clear();
        if (list == null) {
            listTitleField.setText("");
            listRows.getChildren().clear();
            fillingList = false;
            return;
        }
        listTitleField.setText(list.title);
        fillingList = false;
        rebuildListRows();
    }

    private void rebuildListRows() {
        listRows.getChildren().clear();
        if (currentList == null) return;
        for (TwItem item : new ArrayList<>(currentList.items)) {
            CheckBox box = new CheckBox();
            box.setSelected(item.done);
            box.selectedProperty().addListener((o, a, b) -> {
                item.done = b;
                touch();
            });
            TextField tf = new TextField(item.text);
            tf.setPromptText("Item");
            tf.setStyle("-fx-background-color: #fbf7ef; -fx-font-family: Georgia; -fx-font-size: 14px;");
            tf.textProperty().addListener((o, a, b) -> {
                item.text = b == null ? "" : b;
                touch();
            });
            Button del = new Button("x");
            del.setOnAction(e -> {
                currentList.items.remove(item);
                rebuildListRows();
                touch();
            });
            HBox row = new HBox(8, box, tf, del);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(tf, Priority.ALWAYS);
            listRows.getChildren().add(row);
        }
    }

    private void buildPagesNav() {
        Label sideTitle = new Label("PAGES");
        sideTitle.setStyle("-fx-font-family: Georgia; -fx-font-size: 13px; -fx-text-fill: #6a5c4a;");

        TreeItem<Object> rootItem = new TreeItem<>(pagesRoot);
        rootItem.setExpanded(true);
        pageTree.setRoot(rootItem);
        pageTree.setShowRoot(false);
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
        titleField.setStyle("-fx-background-color: transparent; -fx-border-width: 0 0 1 0; "
                + "-fx-border-color: #c9bba6; -fx-font-family: Georgia; -fx-font-size: 22px; -fx-padding: 6 0;");
        titleField.textProperty().addListener((o, a, b) -> {
            if (fillingEditor || currentNote == null) return;
            currentNote.title = b;
            if (mode == Mode.PAGES) pageTree.refresh();
            if (mode == Mode.DIARY) rebuildCalendar();
            touch();
        });

        dateLabel.setStyle("-fx-text-fill: #8a7b66; -fx-font-size: 12px;");

        bodyArea.setWrapText(true);
        bodyArea.setPromptText("Start typing…");
        bodyArea.setStyle("-fx-background-color: #fbf7ef; -fx-control-inner-background: #fbf7ef; "
                + "-fx-font-family: Georgia; -fx-font-size: 15px; -fx-border-color: transparent;");
        bodyArea.textProperty().addListener((o, a, b) -> {
            if (fillingEditor || currentNote == null) return;
            currentNote.body = b == null ? "" : b;
            if (mode == Mode.DIARY) rebuildCalendar();
            touch();
        });

        Button addPng = pill("Attach PNG");
        addPng.setMaxWidth(140);
        addPng.setOnAction(e -> attachPng());
        photoStrip.setAlignment(Pos.CENTER_LEFT);
        HBox photos = new HBox(12, addPng, photoStrip);
        photos.setAlignment(Pos.CENTER_LEFT);

        noteEditor.getChildren().addAll(titleField, dateLabel, bodyArea, photos);
        noteEditor.setPadding(new Insets(28, 36, 24, 28));
        VBox.setVgrow(bodyArea, Priority.ALWAYS);
    }

    private void bindNote(TwNote note) {
        currentNote = note;
        fillingEditor = true;
        titleField.setText(note == null || note.title == null ? "" : note.title);
        bodyArea.setText(note == null || note.body == null ? "" : note.body);
        if (note == null) {
            dateLabel.setText("");
        } else if (note.date != null) {
            dateLabel.setText(note.date.toString());
        } else {
            dateLabel.setText("Loose page");
        }
        fillingEditor = false;
        rebuildPhotos();
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

    private void attachPng() {
        if (currentNote == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach PNG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;
        currentNote.images.add(file);
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
            photoStrip.getChildren().add(iv);
        }
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