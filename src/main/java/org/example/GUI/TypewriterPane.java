package org.example.GUI;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TypewriterPane extends BorderPane {

    public static final class TwNote {
        final String id = UUID.randomUUID().toString();
        String title;
        String body = "";
        LocalDate date;
        final List<File> images = new ArrayList<>();

        TwNote(String title, LocalDate date) {
            this.title = title;
            this.date = date;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public static final class TwFolder {
        final String id = UUID.randomUUID().toString();
        String title;
        final List<TwFolder> children = new ArrayList<>();
        final List<TwNote> notes = new ArrayList<>();

        TwFolder(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private final TreeView<Object> tree = new TreeView<>();
    private final TextField titleField = new TextField();
    private final TextArea bodyArea = new TextArea();
    private final Label dateLabel = new Label();
    private final HBox photoStrip = new HBox(8);
    private TwNote current;

    public TypewriterPane() {
        getStyleClass().add("typewriter-root");
        setStyle("-fx-background-color: #f3eee4;");

        TreeItem<Object> rootItem = new TreeItem<>(new TwFolder("Typewriter"));
        rootItem.setExpanded(true);
        seed(rootItem);
        tree.setRoot(rootItem);
        tree.setShowRoot(false);
        tree.setPrefWidth(260);
        tree.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> open(b));

        Label sideTitle = new Label("TYPEWRITER");
        sideTitle.setStyle("-fx-font-family: 'Georgia'; -fx-font-size: 13px; -fx-letter-spacing: 2px; -fx-text-fill: #6a5c4a;");

        Button addNote = pill("New page");
        Button addFolder = pill("New folder");
        Button addDiary = pill("This month");
        addNote.setOnAction(e -> addNote());
        addFolder.setOnAction(e -> addFolder());
        addDiary.setOnAction(e -> revealDiaryMonth(YearMonth.now()));

        VBox side = new VBox(10, sideTitle, tree, addNote, addFolder, addDiary);
        side.setPadding(new Insets(16, 12, 16, 16));
        side.setPrefWidth(280);
        side.setStyle("-fx-background-color: #e7dfd0;");
        VBox.setVgrow(tree, Priority.ALWAYS);
        setLeft(side);

        titleField.setPromptText("Title");
        titleField.setStyle("-fx-background-color: transparent; -fx-border-width: 0 0 1 0; -fx-border-color: #c9bba6; -fx-font-family: 'Georgia'; -fx-font-size: 22px; -fx-padding: 6 0;");
        titleField.textProperty().addListener((o, a, b) -> {
            if (current != null) {
                current.title = b;
                tree.refresh();
            }
        });

        dateLabel.setStyle("-fx-text-fill: #8a7b66; -fx-font-size: 12px;");

        bodyArea.setWrapText(true);
        bodyArea.setPromptText("Start typing…");
        bodyArea.setStyle("-fx-background-color: #fbf7ef; -fx-control-inner-background: #fbf7ef; -fx-font-family: 'Georgia'; -fx-font-size: 15px; -fx-border-color: transparent;");
        bodyArea.textProperty().addListener((o, a, b) -> {
            if (current != null) current.body = b == null ? "" : b;
        });

        Button addPng = pill("Attach PNG");
        addPng.setOnAction(e -> attachPng());
        photoStrip.setAlignment(Pos.CENTER_LEFT);

        HBox photos = new HBox(12, addPng, photoStrip);
        photos.setAlignment(Pos.CENTER_LEFT);

        VBox editor = new VBox(12, titleField, dateLabel, bodyArea, photos);
        editor.setPadding(new Insets(28, 36, 24, 28));
        VBox.setVgrow(bodyArea, Priority.ALWAYS);
        setCenter(editor);

        TreeItem<Object> first = findFirstNote(rootItem);
        if (first != null) tree.getSelectionModel().select(first);
    }

    private Button pill(String text) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setStyle("-fx-background-color: #fbf7ef; -fx-border-color: #c9bba6; -fx-background-radius: 8; -fx-border-radius: 8; -fx-font-size: 12px;");
        return b;
    }

    private void seed(TreeItem<Object> rootItem) {
        TwFolder random = new TwFolder("Random notes");
        random.notes.add(new TwNote("Scrap", null));

        TwFolder groceries = new TwFolder("Groceries");
        groceries.notes.add(new TwNote("This week", LocalDate.now()));

        TwFolder novel = new TwFolder("Novel");
        TwFolder ch1 = new TwFolder("Chapter 1");
        ch1.notes.add(new TwNote("Opening", null));
        novel.children.add(ch1);

        TwFolder diary = new TwFolder("Diary");
        YearMonth ym = YearMonth.now();
        TwFolder year = new TwFolder(String.valueOf(ym.getYear()));
        TwFolder month = new TwFolder(ym.getMonth().name().substring(0, 1)
                + ym.getMonth().name().substring(1).toLowerCase());
        TwNote today = new TwNote("Today", LocalDate.now());
        month.notes.add(today);
        year.children.add(month);
        diary.children.add(year);

        rootItem.getChildren().addAll(wrap(random), wrap(groceries), wrap(novel), wrap(diary));
    }

    private TreeItem<Object> wrap(TwFolder folder) {
        TreeItem<Object> item = new TreeItem<>(folder);
        item.setExpanded(true);
        for (TwFolder child : folder.children) item.getChildren().add(wrap(child));
        for (TwNote note : folder.notes) item.getChildren().add(new TreeItem<>(note));
        return item;
    }

    private TreeItem<Object> selected() {
        return tree.getSelectionModel().getSelectedItem();
    }

    private TwFolder folderOf(TreeItem<Object> item) {
        while (item != null) {
            if (item.getValue() instanceof TwFolder f) return f;
            item = item.getParent();
        }
        return null;
    }

    private void open(TreeItem<Object> item) {
        if (item == null || !(item.getValue() instanceof TwNote note)) {
            return;
        }
        current = note;
        titleField.setText(note.title == null ? "" : note.title);
        bodyArea.setText(note.body == null ? "" : note.body);
        dateLabel.setText(note.date == null ? "Loose page" : note.date.toString());
        rebuildPhotos();
    }

    private void addNote() {
        TreeItem<Object> sel = selected();
        TwFolder folder = folderOf(sel);
        if (folder == null) return;
        TwNote note = new TwNote("Untitled", null);
        folder.notes.add(note);
        TreeItem<Object> parent = (sel.getValue() instanceof TwFolder) ? sel : sel.getParent();
        TreeItem<Object> created = new TreeItem<>(note);
        parent.getChildren().add(created);
        parent.setExpanded(true);
        tree.getSelectionModel().select(created);
    }

    private void addFolder() {
        TreeItem<Object> sel = selected();
        TwFolder parentFolder = folderOf(sel);
        if (parentFolder == null) return;
        TextInputDialog dlg = new TextInputDialog("New folder");
        dlg.setHeaderText("Folder name");
        dlg.showAndWait().ifPresent(name -> {
            TwFolder child = new TwFolder(name.trim().isEmpty() ? "Folder" : name.trim());
            parentFolder.children.add(child);
            TreeItem<Object> parent = (sel.getValue() instanceof TwFolder) ? sel : sel.getParent();
            TreeItem<Object> created = wrap(child);
            parent.getChildren().add(created);
            parent.setExpanded(true);
            tree.getSelectionModel().select(created);
        });
    }

    private void revealDiaryMonth(YearMonth ym) {
        TreeItem<Object> diary = null;
        for (TreeItem<Object> c : tree.getRoot().getChildren()) {
            if (c.getValue() instanceof TwFolder f && "Diary".equals(f.title)) diary = c;
        }
        if (diary == null) return;
        TwFolder diaryFolder = (TwFolder) diary.getValue();
        TreeItem<Object> yearItem = null;
        TwFolder yearFolder = null;
        for (TreeItem<Object> c : diary.getChildren()) {
            if (c.getValue() instanceof TwFolder f && f.title.equals(String.valueOf(ym.getYear()))) {
                yearItem = c;
                yearFolder = f;
            }
        }
        if (yearItem == null) {
            yearFolder = new TwFolder(String.valueOf(ym.getYear()));
            diaryFolder.children.add(yearFolder);
            yearItem = wrap(yearFolder);
            diary.getChildren().add(yearItem);
        }
        String monthName = ym.getMonth().name().substring(0, 1) + ym.getMonth().name().substring(1).toLowerCase();
        for (TreeItem<Object> c : yearItem.getChildren()) {
            if (c.getValue() instanceof TwFolder f && f.title.equals(monthName)) {
                tree.getSelectionModel().select(c);
                return;
            }
        }
        TwFolder month = new TwFolder(monthName);
        yearFolder.children.add(month);
        TreeItem<Object> monthItem = wrap(month);
        yearItem.getChildren().add(monthItem);
        yearItem.setExpanded(true);
        tree.getSelectionModel().select(monthItem);
    }

    private void attachPng() {
        if (current == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach PNG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;
        current.images.add(file);
        rebuildPhotos();
    }

    private void rebuildPhotos() {
        photoStrip.getChildren().clear();
        if (current == null) return;
        for (File file : current.images) {
            ImageView iv = new ImageView(new Image(file.toURI().toString(), 72, 72, true, true));
            iv.setFitWidth(72);
            iv.setFitHeight(72);
            photoStrip.getChildren().add(iv);
        }
    }

    private TreeItem<Object> findFirstNote(TreeItem<Object> item) {
        if (item.getValue() instanceof TwNote) return item;
        for (TreeItem<Object> c : item.getChildren()) {
            TreeItem<Object> found = findFirstNote(c);
            if (found != null) return found;
        }
        return null;
    }
}