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

import java.time.LocalDate;

public class SchedulerController {

    @FXML private StackPane root;
    @FXML private BorderPane borderPane;
    @FXML private GridPane grid;
    @FXML private Label sidePanel;
    @FXML private Button btnPrev, btnNext, btnUndo, btnRedo, btnAdd;
    @FXML private MenuItem btnImport, btnRun;
    @FXML private Pane prevPane, nextPane;
    @FXML private ScrollPane gridScroll; // add fx:id="gridScroll" on ScrollPane in FXML

    private TaskManagementSystem system;
    private int weekOffset = 0;

    private TaskDragDropSupport dragDrop;
    private TaskDialogs dialogs;
    private WeekGridBuilder gridBuilder;

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
        gridBuilder = new WeekGridBuilder(system, dragDrop, dialogs);

        btnPrev.setOnAction(e -> { weekOffset--; refresh(); });
        btnNext.setOnAction(e -> { weekOffset++; refresh(); });

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

        btnAdd.setOnAction(e -> dialogs.showTaskPopup(false, null, -1));

        dragDrop.setupWeekEdgePanes(prevPane, nextPane, delta -> {
            weekOffset += delta;
            refresh();
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

        prevPane.setOnMouseClicked(event -> {
            weekOffset--;
            refresh();
        });
        nextPane.setOnMouseClicked(event -> {
            weekOffset++;
            refresh();
        });

        Platform.runLater(this::refresh);
    }

    public void refresh() {
        gridBuilder.build(grid, sidePanel, weekOffset);
        Platform.runLater(this::scrollToCurrentTime);
    }

    private void scrollToCurrentTime() {
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