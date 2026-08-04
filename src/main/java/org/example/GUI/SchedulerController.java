package org.example.GUI;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import org.example.AppTimeReference;
import org.example.TaskManagementSystem;

public class SchedulerController {

    private enum ViewMode { WEEK, MONTH }

    @FXML private MenuItem resetAll;
    @FXML private StackPane root;
    @FXML private BorderPane borderPane;
    @FXML private GridPane grid;
    @FXML private Label sidePanel;
    @FXML private Button btnPrev, btnNext, btnUndo, btnRedo, btnAdd, btnMonthView;
    @FXML private MenuItem btnImport, btnRun;
    @FXML private Pane prevPane, nextPane;
    @FXML private ScrollPane gridScroll;

    private TaskManagementSystem system;

    private ViewMode viewMode = ViewMode.WEEK;
    private int weekOffset = 0;
    private int monthOffset = 0;

    private TaskDragDropSupport dragDrop;
    private TaskDialogs dialogs;
    private WeekGridBuilder weekGridBuilder;
    private MonthGridBuilder monthGridBuilder;

    @FXML
    public void initialize() {
        system = TaskManagementSystem.getInstance();
        system.attachGUI(this);

        Runnable bindEdgeWidths = () -> {
            if (root.getScene() == null) return;
            double w = root.getScene().getWidth();
            double edge = Math.max(16, Math.min(40, w * 0.02));
            prevPane.setPrefWidth(edge);
            prevPane.setMinWidth(edge);
            prevPane.setMaxWidth(edge);
            nextPane.setPrefWidth(edge);
            nextPane.setMinWidth(edge);
            nextPane.setMaxWidth(edge);
        };

        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.widthProperty().addListener((o, a, b) -> bindEdgeWidths.run());
                Platform.runLater(bindEdgeWidths);
            }
        });

        dragDrop = new TaskDragDropSupport(system, root, this::refresh);
        dialogs = new TaskDialogs(root, system, this::refresh);
        weekGridBuilder = new WeekGridBuilder(system, dragDrop, dialogs);
        monthGridBuilder = new MonthGridBuilder(system, dialogs);

        btnPrev.setOnAction(e -> step(-1));
        btnNext.setOnAction(e -> step(+1));

        btnMonthView.setOnAction(e -> toggleViewMode());

        btnImport.setOnAction(e -> {
            system.command("addTaskFromFile");
            refresh();
        });

        btnRun.setOnAction(e -> {
            system.command("runScheduler");
            system.command("printAllTask");
            refresh();
        });

        btnUndo.setOnAction(e -> {
            system.command("undo");
            system.command("printAllTask");
            refresh();
        });

        btnRedo.setOnAction(e -> {
            system.command("redo");
            system.command("printAllTask");
            refresh();
        });

        resetAll.setOnAction(e -> {
            system.command("clearAllTask");
            refresh();
        });

        btnAdd.setOnAction(e -> dialogs.showTaskPopup(false, null, -1));

        // Edge panes: only shift week when in week mode (hour drag week-switch)
        dragDrop.setupWeekEdgePanes(prevPane, nextPane, delta -> {
            if (viewMode == ViewMode.WEEK) {
                weekOffset += delta;
                refresh();
            } else {
                monthOffset += delta;
                refresh();
            }
        });

        Label prevPaneLabel = new Label("PREV");
        prevPaneLabel.setRotate(90);
        Label nextPaneLabel = new Label("NEXT");
        nextPaneLabel.setRotate(90);

        StackPane prevStack = new StackPane(prevPaneLabel);
        StackPane nextStack = new StackPane(nextPaneLabel);
        prevStack.prefWidthProperty().bind(prevPane.widthProperty());
        prevStack.prefHeightProperty().bind(prevPane.heightProperty());
        nextStack.prefWidthProperty().bind(nextPane.widthProperty());
        nextStack.prefHeightProperty().bind(nextPane.heightProperty());
        prevPane.getChildren().setAll(prevStack);
        nextPane.getChildren().setAll(nextStack);

        prevPane.setOnMouseClicked(event -> step(-1));
        nextPane.setOnMouseClicked(event -> step(+1));

        updateNavLabels();
        Platform.runLater(this::refresh);
    }

    private void toggleViewMode() {
        if (viewMode == ViewMode.WEEK) {
            viewMode = ViewMode.MONTH;
            // Jump month so current week’s month is visible
            monthOffset = 0;
        } else {
            viewMode = ViewMode.WEEK;
            weekOffset = 0;
        }
        updateNavLabels();
        refresh();
    }

    private void step(int delta) {
        if (viewMode == ViewMode.WEEK) {
            weekOffset += delta;
        } else {
            monthOffset += delta;
        }
        refresh();
    }

    private void updateNavLabels() {
        if (viewMode == ViewMode.WEEK) {
            btnPrev.setText("Prev Week");
            btnNext.setText("Next Week");
            btnMonthView.setText("Monthly View");
        } else {
            btnPrev.setText("Prev Month");
            btnNext.setText("Next Month");
            btnMonthView.setText("Weekly View");
        }
    }

    public void refresh() {
        if (viewMode == ViewMode.WEEK) {
            weekGridBuilder.build(grid, sidePanel, weekOffset);
            Platform.runLater(this::scrollToCurrentTime);
        } else {
            monthGridBuilder.build(grid, sidePanel, monthOffset);
            if (gridScroll != null) {
                gridScroll.setVvalue(0);
            }
        }
    }

    private void scrollToCurrentTime() {
        if (viewMode != ViewMode.WEEK) return;

        ScrollPane scroll = gridScroll;
        if (scroll == null && grid.getParent() instanceof ScrollPane sp) {
            scroll = sp;
        }
        if (scroll == null) return;

        int slot = AppTimeReference.currentSlotIndex();
        int total = AppTimeReference.SLOTS_PER_DAY;
        if (total <= 1) return;

        double v = (slot + 0.5) / total;
        scroll.setVvalue(Math.max(0, Math.min(1, v)));
    }
}