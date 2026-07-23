package org.example.GUI;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
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

    private TaskManagementSystem system;
    private int weekOffset = 0;
    private LocalDate baseDate;

    private TaskDragDropSupport dragDrop;
    private TaskDialogs dialogs;
    private WeekGridBuilder gridBuilder;

    @FXML
    public void initialize() {
        system = TaskManagementSystem.getInstance();
        system.attachGUI(this);
        baseDate = LocalDate.now();

        // ~2% of window width for each edge strip, min 16px, max 40px
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



        Platform.runLater(this::refresh);
    }

    public void refresh() {
        gridBuilder.build(grid, sidePanel, weekOffset, baseDate);
    }
}