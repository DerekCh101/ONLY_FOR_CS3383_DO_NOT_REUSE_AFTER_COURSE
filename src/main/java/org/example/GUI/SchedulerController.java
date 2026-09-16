package org.example.GUI;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.InputEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.example.AppTimeReference;
import org.example.RefreshLifeCycle;
import org.example.SchedulerApp;
import org.example.TaskManagementSystem;
import org.example.net.Api;

public class SchedulerController {

    private static final Duration IDLE_LIMIT = Duration.minutes(100);

    private enum ViewMode { WEEK, MONTH }

    @FXML private MenuItem resetAll;
    @FXML private StackPane root;
    @FXML private BorderPane borderPane;
    @FXML private GridPane grid;
    @FXML private Label sidePanel;
    @FXML private Label lblUser;
    @FXML private Button btnLogout;
    @FXML private Button btnPrev, btnNext, btnUndo, btnRedo, btnAdd, btnBlocked, btnMonthView;
    @FXML private MenuItem btnImport, btnRun;
    @FXML private Pane prevPane, nextPane;
    @FXML private ScrollPane gridScroll;
    @FXML private Button tabScheduler, tabTypewriter;
    @FXML private StackPane mainStack;
    @FXML private javafx.scene.control.SplitPane mainSplit;

    private TaskManagementSystem system;

    private TypewriterPane typewriterPane;
    private boolean typewriterOn;
    private ViewMode viewMode = ViewMode.WEEK;
    private int weekOffset = 0;
    private int monthOffset = 0;

    private TaskDragDropSupport dragDrop;
    private TaskDialogs dialogs;
    private WeekGridBuilder weekGridBuilder;
    private MonthGridBuilder monthGridBuilder;
    private Timeline nowLineClock;
    private PauseTransition idleTimer;
    private boolean loggingOut;

    @FXML
    public void initialize() {
        system = TaskManagementSystem.getInstance();
        system.attachGUI(this);

        String name = Api.INSTANCE.getUsername();
        if (name == null || name.isBlank()) name = "user";
        lblUser.setText("Logged in as " + name);
        btnLogout.setOnAction(e -> logout());

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
        btnBlocked.setOnAction(e -> dialogs.showBlockedEditor());

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
                attachIdleWatch(newScene);
            }
        });

        updateNavLabels();
        startNowLineClock();

        Platform.runLater(() -> {
            try {
                typewriterPane = new TypewriterPane();
                if (mainStack != null) {
                    mainStack.getChildren().add(typewriterPane);
                    typewriterPane.setVisible(false);
                    typewriterPane.setManaged(false);
                }
                styleTab(tabScheduler, true);
                styleTab(tabTypewriter, false);
                if (tabScheduler != null) tabScheduler.setOnAction(ev -> showTypewriter(false));
                if (tabTypewriter != null) tabTypewriter.setOnAction(ev -> showTypewriter(true));
            } catch (Exception ex) {
                ex.printStackTrace();
                typewriterPane = null;
            }
            refreshAfterLoad();
        });
    }

    public void refreshAfterLoad() {
        refresh();
        Platform.runLater(this::scrollToCurrentTime);
        System.out.println("calendar refresh placed=" + system.getPlacedTasks().size()
                + " days=" + system.getGrid().size());
    }

    private void showTypewriter(boolean on) {
        if (typewriterOn && !on && typewriterPane != null) {
            typewriterPane.saveAll();
        }
        typewriterOn = on;
        if (typewriterPane != null) {
            typewriterPane.setVisible(on);
            typewriterPane.setManaged(on);
        }
        if (mainSplit != null) {
            mainSplit.setVisible(!on);
            mainSplit.setManaged(!on);
        }
        setSchedChrome(!on);
        styleTab(tabScheduler, !on);
        styleTab(tabTypewriter, on);
        if (!on) refresh();
    }

    private void setSchedChrome(boolean on) {
        if (btnPrev != null) { btnPrev.setVisible(on); btnPrev.setManaged(on); }
        if (btnMonthView != null) { btnMonthView.setVisible(on); btnMonthView.setManaged(on); }
        if (btnNext != null) { btnNext.setVisible(on); btnNext.setManaged(on); }
        if (btnAdd != null) { btnAdd.setVisible(on); btnAdd.setManaged(on); }
        if (btnBlocked != null) { btnBlocked.setVisible(on); btnBlocked.setManaged(on); }
        if (btnUndo != null) { btnUndo.setVisible(on); btnUndo.setManaged(on); }
        if (btnRedo != null) { btnRedo.setVisible(on); btnRedo.setManaged(on); }
    }

    private void styleTab(Button tab, boolean active) {
        if (tab == null) return;
        tab.setStyle(active
                ? "-fx-background-color: #f3eee4; -fx-text-fill: #1c2430; -fx-background-radius: 8 8 0 0; -fx-padding: 8 18; -fx-font-family: Georgia; -fx-font-size: 13px;"
                : "-fx-background-color: transparent; -fx-text-fill: #d7dde6; -fx-background-radius: 8 8 0 0; -fx-padding: 8 18; -fx-font-family: Georgia; -fx-font-size: 13px;");
    }

    private void attachIdleWatch(javafx.scene.Scene scene) {
        if (idleTimer == null) {
            idleTimer = new PauseTransition(IDLE_LIMIT);
            idleTimer.setOnFinished(e -> Platform.runLater(this::logout));
        }
        scene.addEventFilter(InputEvent.ANY, e -> bumpIdle());
        bumpIdle();
    }

    private void bumpIdle() {
        if (idleTimer != null && !loggingOut) idleTimer.playFromStart();
    }

    private void logout() {
        if (loggingOut) return;
        loggingOut = true;
        if (idleTimer != null) idleTimer.stop();
        if (nowLineClock != null) {
            nowLineClock.stop();
            nowLineClock = null;
        }
        if (typewriterPane != null) typewriterPane.saveAll();
        SchedulerApp.returnToLogin();
    }

    private void startNowLineClock() {
        if (nowLineClock != null) return;
        nowLineClock = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (viewMode != ViewMode.WEEK) return;
            if (weekGridBuilder.nowLineNeedsRowChange()) {
                RefreshLifeCycle.refreshAll(system);
                refresh();
            } else {
                weekGridBuilder.updateNowLinePosition();
            }
        }));
        nowLineClock.setCycleCount(Animation.INDEFINITE);
        nowLineClock.play();
    }

    private void toggleViewMode() {
        if (viewMode == ViewMode.WEEK) {
            viewMode = ViewMode.MONTH;
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
        if (typewriterOn) return;
        if (viewMode == ViewMode.WEEK) {
            weekGridBuilder.build(grid, sidePanel, weekOffset);
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