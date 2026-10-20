/** Calendar window controller: week/day/month grid, side description, tabs, and the in-app CLI bar. */
package org.ashoreprospect.GUI;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.InputEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import java.util.ArrayList;
import java.util.List;
import javafx.util.Duration;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Command.FinishTask;
import org.ashoreprospect.Command.UnfinishTask;
import org.ashoreprospect.RefreshLifeCycle;
import org.ashoreprospect.SchedulerApp;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.Workflow;
import org.ashoreprospect.net.Api;
import org.ashoreprospect.net.LocalSession;
import org.ashoreprospect.net.NetworkWatch;

public class SchedulerController {

    private Duration idleLimit() {
        int mins = LocalSession.idleMinutes();
        if (mins <= 0) return null;
        return Duration.minutes(mins);
    }

    private enum ViewMode { WEEK, DAY, MONTH }

    @FXML private MenuItem resetAll;
    @FXML private MenuItem clearPast;
    @FXML private StackPane root;
    @FXML private BorderPane borderPane;
    @FXML private GridPane grid;
    @FXML private Label sidePanel;
    @FXML private Label descMeta;
    @FXML private VBox descList;
    @FXML private Label descListHead;
    @FXML private Label descQuicksHead;
    @FXML private VBox descQuicks;
    @FXML private TextField descAddField;
    @FXML private Button descAddBtn;
    @FXML private Button descSave;
    @FXML private Button descFinish;
    @FXML private Button cliShow, cliRun;
    @FXML private javafx.scene.control.TextField cliInput;
    @FXML private Label cliOut;
    @FXML private HBox cliBar;
    @FXML private javafx.scene.layout.VBox sidePanelContainer;
    @FXML private Label lblUser;
    @FXML private Label lblNet;
    @FXML private Button btnPrev, btnNext, btnUndo, btnRedo, btnDayView, btnMonthView, btnJumpNow;
    @FXML private Button btnFab, btnFabPrev, btnFabNext;
    @FXML private Button btnAction;
    @FXML private MenuButton menuView;
    @FXML private MenuItem btnImport, btnRun, menuAddTask, menuBlocked, menuUnscheduled;
    @FXML private MenuItem menuWeek, menuDay, menuMonth, menuThisWeek, menuToday, menuThisMonth, menuUndo, menuRedo;
    @FXML private MenuItem menuAccount, menuUsername, menuEmail, menuPassword, menuSessions, menuLogout, menuAbout;
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
    private int dayOffset = 0;

    private TaskDragDropSupport dragDrop;
    private TaskDialogs dialogs;
    private WeekGridBuilder weekGridBuilder;
    private MonthGridBuilder monthGridBuilder;
    private DayGridBuilder dayGridBuilder;
    private Timeline nowLineClock;
    private PauseTransition refreshSoon;
    private PauseTransition idleTimer;
    private boolean loggingOut;
    private boolean skipSaveOnLogout;
    private AccountPane accountPane;
    private boolean accountOn;
    private org.ashoreprospect.Task selectedTask;
    private boolean fillingDesc;

    @FXML
    public void initialize() {
        system = TaskManagementSystem.getInstance();
        system.attachGUI(this);

        paintUser();
        paintNet(NetworkWatch.isOnline());
        NetworkWatch.addListener(online -> Platform.runLater(() -> {
            paintNet(online);
            if (online && SchedulerApp.wipeIfAccountDeleted()) logout();
        }));
        Platform.runLater(() -> {
            if (SchedulerApp.wipeIfAccountDeleted()) logout();
        });
        AppTheme.listen(() -> Platform.runLater(this::onThemeChanged));
        if (lblUser != null) {
            lblUser.setOnMouseClicked(e -> showAccount(AccountPane.Page.HOME));
        }

        dragDrop = new TaskDragDropSupport(system, root, this::refresh);
        dialogs = new TaskDialogs(root, system, this::refresh);
        weekGridBuilder = new WeekGridBuilder(system, dragDrop, dialogs);
        weekGridBuilder.setOnSelectTask(this::showTaskDescription);
        weekGridBuilder.setRealtimeOverlay(LocalSession.wantRealtimeOverlay());
        if (gridScroll != null) {
            gridScroll.setFitToHeight(false);
            PauseTransition scrollIdle = new PauseTransition(Duration.millis(90));
            scrollIdle.setOnFinished(e -> {
                weekGridBuilder.setScrolling(false);
                if (grid != null) grid.setCache(false);
            });
            javafx.beans.value.ChangeListener<Number> onScroll = (o, a, b) -> {
                weekGridBuilder.setScrolling(true);
                if (grid != null) {
                    grid.setCache(true);
                    grid.setCacheHint(javafx.scene.CacheHint.SPEED);
                }
                scrollIdle.playFromStart();
            };
            gridScroll.vvalueProperty().addListener(onScroll);
            gridScroll.hvalueProperty().addListener(onScroll);
        }
        monthGridBuilder = new MonthGridBuilder(system, dialogs);
        monthGridBuilder.setOnSelectTask(this::showTaskDescription);
        dayGridBuilder = new DayGridBuilder(system);
        dayGridBuilder.setOnSelectTask(this::showTaskDescription);
        showTaskDescription(null);
        if (descAddBtn != null) descAddBtn.setOnAction(e -> addDescItem());
        if (descAddField != null) {
            descAddField.setOnAction(e -> addDescItem());
        }

        btnPrev.setOnAction(e -> step(-1));
        btnNext.setOnAction(e -> step(+1));
        if (btnDayView != null) btnDayView.setOnAction(e ->
                setViewMode(viewMode == ViewMode.DAY ? ViewMode.WEEK : ViewMode.DAY));
        btnMonthView.setOnAction(e ->
                setViewMode(viewMode == ViewMode.MONTH ? ViewMode.WEEK : ViewMode.MONTH));
        btnImport.setOnAction(e -> {
            system.command("addTaskFromFile");
            refresh();
        });
        btnRun.setOnAction(e -> {
            system.command("runScheduler");
            system.command("printAllTask");
            refresh();
        });
        btnUndo.setOnAction(e -> runUndo());
        btnRedo.setOnAction(e -> runRedo());
        resetAll.setOnAction(e -> {
            if (!confirmYes("Clear all tasks", "Delete every task?",
                    "Undo can restore them in this session.")) return;
            system.command("clearAllTask");
            refresh();
        });
        if (clearPast != null) {
            clearPast.setOnAction(e -> {
                if (!confirmYes("Clear past tasks", "Delete every past task?",
                        "Undo can restore them in this session.")) return;
                system.command("clearPastTasks");
                refresh();
            });
        }
        if (btnFab != null) btnFab.setOnAction(e -> dialogs.showTaskPopup(false, null, -1));
        if (btnFabPrev != null) btnFabPrev.setOnAction(e -> step(-1));
        if (btnFabNext != null) btnFabNext.setOnAction(e -> step(+1));
        if (btnJumpNow != null) btnJumpNow.setOnAction(e -> jumpNow());
        if (btnAction != null) {
            btnAction.setOnAction(e -> {
                if (btnAction.getContextMenu() != null) {
                    btnAction.getContextMenu().show(btnAction, Side.BOTTOM, 0, 0);
                }
            });
        }
        if (menuAddTask != null) menuAddTask.setOnAction(e -> dialogs.showTaskPopup(false, null, -1));
        if (menuUnscheduled != null) menuUnscheduled.setOnAction(e -> dialogs.showUnscheduledPopup());
        if (menuBlocked != null) menuBlocked.setOnAction(e -> dialogs.showBlockedEditor());
        if (descSave != null) descSave.setOnAction(e -> saveTaskDescription());
        if (descFinish != null) descFinish.setOnAction(e -> toggleTaskFinish());
        if (cliShow != null) cliShow.setOnAction(e -> toggleCli());
        if (cliRun != null) cliRun.setOnAction(e -> runCli());
        if (cliInput != null) cliInput.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) runCli();
        });
        wireMenus();

        dragDrop.setupWeekEdgePanes(prevPane, nextPane, delta -> {
            if (viewMode == ViewMode.DAY) return;
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
        prevPaneLabel.setStyle("-fx-text-fill: " + AppTheme.ink() + "; " + AppTheme.font());
        Label nextPaneLabel = new Label("NEXT");
        nextPaneLabel.setRotate(90);
        nextPaneLabel.setStyle("-fx-text-fill: " + AppTheme.ink() + "; " + AppTheme.font());
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
                AppTheme.apply(newScene);
            }
        });

        updateNavLabels();
        startNowLineClock();
        TaskReminders.start();

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
                if (tabScheduler != null) tabScheduler.setOnAction(ev -> showMain(false));
                if (tabTypewriter != null) tabTypewriter.setOnAction(ev -> showMain(true));
            } catch (Exception ex) {
                ex.printStackTrace();
                typewriterPane = null;
            }
            accountPane = new AccountPane(this::logout, this::flushThenWipeCacheAndLogout, name -> paintUser(),
                    () -> typewriterPane == null ? TypewriterStore.load() : typewriterPane.snapshot());
            accountPane.setVisible(false);
            accountPane.setManaged(false);
            if (mainStack != null) mainStack.getChildren().add(accountPane);
            refreshAfterLoad();
            maybeRememberChallenge();
        });
    }

    public void applyTypewriterFromNode(TypewriterStore.Data data) {
        if (data == null || typewriterPane == null || !typewriterPane.isVisible()) return;
        typewriterPane.applyFromNode(data);
    }

    public void refreshAfterLoad() {
        if (refreshSoon != null) refreshSoon.stop();
        refreshNow();
        Platform.runLater(() -> {
            scrollToCurrentTime();
            if (dialogs != null) dialogs.prewarmAddTask();
        });
        System.out.println("calendar refresh placed=" + system.getPlacedTasks().size()
                + " days=" + system.getGrid().size());
    }

    private void showMain(boolean typewriter) {
        if (typewriterOn && !typewriter && typewriterPane != null) {
            typewriterPane.saveAll();
        }
        accountOn = false;
        typewriterOn = typewriter;
        if (accountPane != null) {
            accountPane.setVisible(false);
            accountPane.setManaged(false);
        }
        if (typewriterPane != null) {
            typewriterPane.setVisible(typewriter);
            typewriterPane.setManaged(typewriter);
        }
        if (mainSplit != null) {
            mainSplit.setVisible(!typewriter);
            mainSplit.setManaged(!typewriter);
        }
        setSchedChrome(!typewriter);
        styleTab(tabScheduler, !typewriter);
        styleTab(tabTypewriter, typewriter);
        if (typewriter && typewriterPane != null) typewriterPane.applyTheme();
        if (!typewriter) refresh();
    }

    private void showAccount(AccountPane.Page page) {
        if (typewriterOn && typewriterPane != null) typewriterPane.saveAll();
        typewriterOn = false;
        accountOn = true;
        if (typewriterPane != null) {
            typewriterPane.setVisible(false);
            typewriterPane.setManaged(false);
        }
        if (mainSplit != null) {
            mainSplit.setVisible(false);
            mainSplit.setManaged(false);
        }
        if (accountPane != null) {
            accountPane.setVisible(true);
            accountPane.setManaged(true);
            accountPane.show(page);
        }
        setSchedChrome(false);
        styleTab(tabScheduler, false);
        styleTab(tabTypewriter, false);
    }

    private void wireMenus() {
        if (menuWeek != null) menuWeek.setOnAction(e -> {
            showMain(false);
            setViewMode(ViewMode.WEEK);
        });
        if (menuDay != null) menuDay.setOnAction(e -> {
            showMain(false);
            setViewMode(ViewMode.DAY);
        });
        if (menuMonth != null) menuMonth.setOnAction(e -> {
            showMain(false);
            setViewMode(ViewMode.MONTH);
        });
        if (menuThisWeek != null) menuThisWeek.setOnAction(e -> {
            showMain(false);
            setViewMode(ViewMode.WEEK);
            weekOffset = 0;
            refresh();
        });
        if (menuToday != null) menuToday.setOnAction(e -> {
            showMain(false);
            setViewMode(ViewMode.DAY);
            dayOffset = 0;
            refresh();
        });
        if (menuThisMonth != null) menuThisMonth.setOnAction(e -> {
            showMain(false);
            setViewMode(ViewMode.MONTH);
            monthOffset = 0;
            refresh();
        });
        addRealtimeOverlayToViewMenu();
        addThemeToViewMenu();
        AppTheme.hookPopups(root);
        if (btnAction != null && btnAction.getContextMenu() != null) {
            javafx.scene.control.ContextMenu actions = btnAction.getContextMenu();
            actions.setOnShowing(e -> AppTheme.applyPopup(actions));
        }
        if (menuUndo != null) menuUndo.setOnAction(e -> runUndo());
        if (menuRedo != null) menuRedo.setOnAction(e -> runRedo());
        if (menuAccount != null) menuAccount.setOnAction(e -> showAccount(AccountPane.Page.HOME));
        if (menuUsername != null) menuUsername.setOnAction(e -> showAccount(AccountPane.Page.USERNAME));
        if (menuEmail != null) menuEmail.setOnAction(e -> showAccount(AccountPane.Page.EMAIL));
        if (menuPassword != null) menuPassword.setOnAction(e -> showAccount(AccountPane.Page.PASSWORD));
        if (menuSessions != null) menuSessions.setOnAction(e -> showAccount(AccountPane.Page.SESSIONS));
        if (menuLogout != null) menuLogout.setOnAction(e -> logout());
        if (menuAbout != null) menuAbout.setOnAction(e -> {
            Alert a = new Alert(Alert.AlertType.INFORMATION, "Ashore Prospect — calendar and typewriter.", ButtonType.OK);
            a.setHeaderText("About");
            a.setTitle("About");
            a.showAndWait();
        });
    }

    private void paintUser() {
        String name = Api.INSTANCE.getUsername();
        if (name == null || name.isBlank()) name = "user";
        if (lblUser != null) lblUser.setText("Logged in as " + name);
    }

    private void runUndo() {
        if (typewriterOn && typewriterPane != null) {
            typewriterPane.undo();
            return;
        }
        system.command("undo");
        system.command("printAllTask");
        refresh();
    }

    private void runRedo() {
        if (typewriterOn && typewriterPane != null) {
            typewriterPane.redo();
            return;
        }
        system.command("redo");
        system.command("printAllTask");
        refresh();
    }

    private void setSchedChrome(boolean on) {
        if (btnPrev != null) { btnPrev.setVisible(on); btnPrev.setManaged(on); }
        if (btnDayView != null) { btnDayView.setVisible(on); btnDayView.setManaged(on); }
        if (btnMonthView != null) { btnMonthView.setVisible(on); btnMonthView.setManaged(on); }
        if (btnNext != null) { btnNext.setVisible(on); btnNext.setManaged(on); }
        if (btnUndo != null) { btnUndo.setVisible(true); btnUndo.setManaged(true); }
        if (btnRedo != null) { btnRedo.setVisible(true); btnRedo.setManaged(true); }
        if (btnAction != null) { btnAction.setVisible(on); btnAction.setManaged(on); }
        if (btnFab != null) { btnFab.setVisible(on); btnFab.setManaged(on); }
        if (btnFabPrev != null) { btnFabPrev.setVisible(on); btnFabPrev.setManaged(on); }
        if (btnFabNext != null) { btnFabNext.setVisible(on); btnFabNext.setManaged(on); }
        if (btnJumpNow != null) { btnJumpNow.setVisible(on); btnJumpNow.setManaged(on); }
    }

    private void addRealtimeOverlayToViewMenu() {
        if (menuView == null) return;
        CheckMenuItem item = new CheckMenuItem("Real-time overlay");
        item.setSelected(LocalSession.wantRealtimeOverlay());
        item.setOnAction(e -> {
            LocalSession.setRealtimeOverlay(item.isSelected());
            if (weekGridBuilder != null) {
                weekGridBuilder.setRealtimeOverlay(item.isSelected());
                refresh();
            }
        });
        menuView.getItems().add(item);
    }

    private void addThemeToViewMenu() {
        if (menuView == null) return;
        Label name = new Label("Dark theme");
        Runnable paintName = () -> name.setStyle("-fx-text-fill: " + AppTheme.ink()
                + "; " + AppTheme.font());
        paintName.run();
        AppTheme.listen(() -> Platform.runLater(paintName));
        HBox row = new HBox(12, name, new ThemeSwitch());
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new javafx.geometry.Insets(4, 8, 4, 8));
        CustomMenuItem item = new CustomMenuItem(row, false);
        menuView.getItems().addAll(new SeparatorMenuItem(), item);
        if (menuView.getContextMenu() != null) {
            javafx.scene.control.ContextMenu viewMen = menuView.getContextMenu();
            viewMen.setOnShowing(e -> AppTheme.applyPopup(viewMen));
        }
    }

    private void jumpNow() {
        showMain(false);
        if (viewMode == ViewMode.WEEK) weekOffset = 0;
        else if (viewMode == ViewMode.DAY) dayOffset = 0;
        else monthOffset = 0;
        refresh();
        if (viewMode == ViewMode.WEEK) Platform.runLater(this::scrollToCurrentTime);
    }

    private void showTaskDescription(org.ashoreprospect.Task task) {
        if (system != null) task = system.mainTask(task);
        if (task != null && task.isQuick()) task = null;
        selectedTask = task;
        boolean on = task != null;
        if (sidePanel != null) sidePanel.setText(on ? "Task description" : "Task description");
        if (descMeta != null) {
            if (!on) {
                descMeta.setText("Left-click a task to see it here.");
            } else {
                String kind = task.isFixedTime() ? "Fixed" : task.hasDeadline() ? "Deadline" : "Flexible";
                String placed = (task.getPlacedDate() == null || task.getPlacedTime() == null)
                        ? "not placed" : task.getPlacedDate() + " " + task.getPlacedTime();
                String deadline = !task.hasDeadline() ? ""
                        : "\nDeadline " + task.getDeadLineDate()
                        + (task.getDeadLineTime() == null ? "" : " " + task.getDeadLineTime());
                int mins = (int) Math.round(task.getDurationMinutes());
                String dur = mins + " min"
                        + (mins % 30 == 0 ? " · " + task.getDurationHours() + "h grid" : "");
                descMeta.setText(task.getTaskName()
                        + "\n" + kind + " · " + dur
                        + " · " + (task.getWorkflow() == null ? "" : task.getWorkflow().name())
                        + "\nPlaced " + placed
                        + deadline);
            }
        }
        if (descAddField != null) {
            descAddField.setDisable(!on);
            if (!on) descAddField.clear();
        }
        if (descAddBtn != null) descAddBtn.setDisable(!on);
        if (descSave != null) descSave.setDisable(!on);
        if (descListHead != null) {
            descListHead.setVisible(on);
            descListHead.setManaged(on);
            if (on) {
                descListHead.setText("Checklist");
                descListHead.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: "
                        + AppTheme.ink() + ";");
            }
        }
        boolean placed = on && task.getPlacedDate() != null && task.getPlacedTime() != null;
        if (descFinish != null) {
            descFinish.setDisable(!placed);
            descFinish.setText(placed && task.getWorkflow() == Workflow.FINISHED ? "Unfinish" : "Finish");
        }
        fillingDesc = true;
        try {
            fillDescList(on ? task.getDescription() : null);
            fillQuicks(on ? task : null);
        } finally {
            fillingDesc = false;
        }
    }

    private void fillQuicks(org.ashoreprospect.Task task) {
        if (descQuicks != null) descQuicks.getChildren().clear();
        if (descQuicksHead != null) {
            descQuicksHead.setVisible(false);
            descQuicksHead.setManaged(false);
        }
        if (task == null || system == null || descQuicks == null) return;
        if (descQuicksHead != null) {
            descQuicksHead.setText("Quick time events");
            descQuicksHead.setVisible(true);
            descQuicksHead.setManaged(true);
            descQuicksHead.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: "
                    + AppTheme.ink() + ";");
        }
        List<org.ashoreprospect.Task> nested = system.quicksOf(task);
        if (nested.isEmpty()) {
            Label empty = new Label("None on this task.");
            empty.setWrapText(true);
            empty.setStyle("-fx-font-size: 12px; -fx-text-fill: " + AppTheme.muted() + ";");
            descQuicks.getChildren().add(empty);
            return;
        }
        boolean parentDone = task.getWorkflow() == Workflow.FINISHED;
        for (org.ashoreprospect.Task q : nested) {
            if (q == null) continue;
            String clock = "";
            if (q.getPlacedTime() != null) {
                clock = String.format("%02d:%02d  ", q.getPlacedTime().getHour(), q.getPlacedTime().getMinute());
            }
            int mins = (int) Math.round(q.getDurationMinutes());
            boolean done = parentDone || q.getWorkflow() == Workflow.FINISHED;
            Label row = new Label((done ? "done  ·  " : "") + clock + q.getTaskName() + "  ·  " + mins + " min");
            row.setWrapText(true);
            row.setStyle("-fx-font-size: 12px; -fx-text-fill: " + AppTheme.ink() + ";");
            descQuicks.getChildren().add(row);
        }
    }

    private void toggleTaskFinish() {
        if (selectedTask == null) return;
        if (selectedTask.getPlacedDate() == null || selectedTask.getPlacedTime() == null) {
            new Alert(Alert.AlertType.INFORMATION, "Finish is only for placed tasks.").showAndWait();
            return;
        }
        try {
            boolean finished = selectedTask.getWorkflow() == Workflow.FINISHED;
            boolean ok = finished
                    ? new UnfinishTask(selectedTask).execute()
                    : new FinishTask(selectedTask).execute();
            if (!ok) {
                new Alert(Alert.AlertType.ERROR, "Could not change finish state.").showAndWait();
                return;
            }
            refresh();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Could not change finish state: " + ex.getMessage()).showAndWait();
        }
    }

    private void fillDescList(String raw) {
        if (descList == null) return;
        descList.getChildren().clear();
        if (raw == null) return;
        for (DescLine line : parseDescLines(raw)) {
            descList.getChildren().add(descRow(line.done, line.text));
        }
    }

    private void addDescItem() {
        if (descAddField == null || descList == null || selectedTask == null) return;
        String t = descAddField.getText() == null ? "" : descAddField.getText().trim();
        if (t.isEmpty()) return;
        if (t.length() > 500) t = t.substring(0, 500);
        descList.getChildren().add(descRow(false, t));
        descAddField.clear();
        persistDesc();
    }

    private HBox descRow(boolean done, String text) {
        CheckBox box = new CheckBox();
        box.setSelected(done);
        box.setOnAction(e -> persistDesc());
        TextField tf = new TextField(text == null ? "" : text);
        tf.setPromptText("What to do…");
        HBox.setHgrow(tf, Priority.ALWAYS);
        tf.setOnAction(e -> persistDesc());
        tf.focusedProperty().addListener((o, was, now) -> {
            if (was && !now) persistDesc();
        });
        Button rm = new Button("×");
        rm.setOnAction(e -> {
            if (descList != null) descList.getChildren().remove(rm.getParent());
            persistDesc();
        });
        HBox row = new HBox(6, box, tf, rm);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void persistDesc() {
        if (fillingDesc || selectedTask == null) return;
        saveTaskDescription();
    }

    private static final class DescLine {
        final boolean done;
        final String text;
        DescLine(boolean done, String text) {
            this.done = done;
            this.text = text;
        }
    }

    /** Lines as {@code [x] text} / {@code [ ] text}; plain text → one unchecked item. */
    private static List<DescLine> parseDescLines(String raw) {
        List<DescLine> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        String[] lines = raw.split("\\R", -1);
        boolean anyMark = false;
        for (String line : lines) {
            String t = line == null ? "" : line.trim();
            if (t.startsWith("[x]") || t.startsWith("[X]") || t.startsWith("[ ]")) {
                anyMark = true;
                break;
            }
        }
        if (!anyMark) {
            String whole = raw.trim();
            if (!whole.isEmpty()) out.add(new DescLine(false, whole));
            return out;
        }
        for (String line : lines) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("[x]") || t.startsWith("[X]")) {
                out.add(new DescLine(true, t.substring(3).trim()));
            } else if (t.startsWith("[ ]")) {
                out.add(new DescLine(false, t.substring(3).trim()));
            } else {
                out.add(new DescLine(false, t));
            }
        }
        return out;
    }

    private String serializeDescList() {
        if (descList == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean allDone = true;
        boolean any = false;
        for (javafx.scene.Node n : descList.getChildren()) {
            if (!(n instanceof HBox row) || row.getChildren().size() < 2) continue;
            if (!(row.getChildren().get(0) instanceof CheckBox box)) continue;
            if (!(row.getChildren().get(1) instanceof TextField tf)) continue;
            String t = tf.getText() == null ? "" : tf.getText().trim();
            if (t.isEmpty()) continue;
            any = true;
            if (!box.isSelected()) allDone = false;
            if (sb.length() > 0) sb.append('\n');
            sb.append(box.isSelected() ? "[x] " : "[ ] ").append(t);
        }
        if (sb.length() > 8000) return sb.substring(0, 8000);
        // stash all-done in descriptionDone via caller
        serializeDescAllDone = any && allDone;
        return sb.toString();
    }

    private boolean serializeDescAllDone;

    private void saveTaskDescription() {
        if (selectedTask == null) return;
        org.ashoreprospect.Command.EditTask.Snapshot next =
                org.ashoreprospect.Command.EditTask.Snapshot.of(selectedTask);
        next.description = serializeDescList();
        next.descriptionDone = serializeDescAllDone;
        try {
            new org.ashoreprospect.Command.EditTask(selectedTask, next, false).execute();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Could not save description: " + ex.getMessage()).showAndWait();
            return;
        }
        refresh();
        showTaskDescription(selectedTask);
    }

    private void toggleCli() {
        if (cliBar == null) return;
        boolean on = !cliBar.isVisible();
        cliBar.setVisible(on);
        cliBar.setManaged(on);
        if (on && cliInput != null) cliInput.requestFocus();
    }

    private void runCli() {
        if (cliInput == null) return;
        String raw = cliInput.getText() == null ? "" : cliInput.getText().trim();
        if (raw.isEmpty()) return;
        org.ashoreprospect.Command.InAppCli.Result result =
                org.ashoreprospect.Command.InAppCli.run(system, raw);
        cliSay(result.message);
        if (result.refresh) refresh();
    }

    private void cliSay(String msg) {
        if (cliOut != null) cliOut.setText(msg);
    }

    private boolean confirmYes(String title, String header, String body) {
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
        ask.setTitle(title);
        ask.setHeaderText(header);
        ask.setContentText(body);
        ask.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
        return ask.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    private void styleTab(Button tab, boolean active) {
        if (tab == null) return;
        tab.setStyle(active ? AppTheme.tabOn() : AppTheme.tabOff());
    }

    private void paintNet(boolean online) {
        if (lblNet == null) return;
        lblNet.getStyleClass().removeAll("online", "offline");
        lblNet.getStyleClass().add("net-chip");
        lblNet.getStyleClass().add(online ? "online" : "offline");
        lblNet.setText(online ? "online" : "offline");
    }

    private void onThemeChanged() {
        styleTab(tabScheduler, !typewriterOn && !accountOn);
        styleTab(tabTypewriter, typewriterOn);
        if (typewriterPane != null && typewriterPane.isVisible()) typewriterPane.applyTheme();
        if (accountPane != null) accountPane.refreshIfVisible();
        GuiHelper.paintInk(prevPane);
        GuiHelper.paintInk(nextPane);
        if (sidePanel != null) {
            sidePanel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + AppTheme.ink()
                    + "; " + AppTheme.font());
        }
        refresh();
    }

    private void maybeRememberChallenge() {
        if (!LocalSession.wantRemember() || !LocalSession.wantRandomChallenge()) return;
        if (!NetworkWatch.isOnline()) return;
        if (Math.random() > 0.18) return;
        PauseTransition wait = new PauseTransition(Duration.seconds(1.2));
        wait.setOnFinished(e -> SecurityChallenge.ask(root, this::failRememberChallenge));
        wait.play();
    }

    private void failRememberChallenge() {
        wipeCacheAndLogout();
    }

    private void flushThenWipeCacheAndLogout() {
        try {
            TypewriterStore.Data snap = typewriterPane == null
                    ? TypewriterStore.load() : typewriterPane.snapshot();
            TypewriterStore.saveImmediate(snap);
        } catch (Exception e) {
            System.err.println("typewriter flush before cache wipe failed: " + e.getMessage());
        }
        wipeCacheAndLogout();
    }

    private void wipeCacheAndLogout() {
        skipSaveOnLogout = true;
        try {
            if (system != null && system.getTaskRepo() != null) {
                system.getTaskRepo().wipeLocalCacheOnly();
            }
        } catch (Exception e) {
            System.err.println("cache wipe failed: " + e.getMessage());
        }
        logout();
    }

    private void attachIdleWatch(javafx.scene.Scene scene) {
        if (idleTimer == null) {
            idleTimer = new PauseTransition(Duration.minutes(120));
            idleTimer.setOnFinished(e -> Platform.runLater(this::logout));
        }
        scene.addEventFilter(InputEvent.ANY, e -> bumpIdle());
        bumpIdle();
    }

    public void applyIdleLimit() {
        bumpIdle();
    }

    private void bumpIdle() {
        if (loggingOut || idleTimer == null) return;
        Duration limit = idleLimit();
        if (limit == null) {
            idleTimer.stop();
            return;
        }
        idleTimer.setDuration(limit);
        idleTimer.playFromStart();
    }

    private void logout() {
        if (loggingOut) return;
        loggingOut = true;
        if (idleTimer != null) idleTimer.stop();
        if (nowLineClock != null) {
            nowLineClock.stop();
            nowLineClock = null;
        }
        if (typewriterPane != null && !skipSaveOnLogout) typewriterPane.saveAll();
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

    private void setViewMode(ViewMode next) {
        if (next == null) next = ViewMode.WEEK;
        viewMode = next;
        if (viewMode == ViewMode.WEEK) weekOffset = 0;
        else if (viewMode == ViewMode.DAY) dayOffset = 0;
        else monthOffset = 0;
        updateNavLabels();
        refresh();
        if (viewMode == ViewMode.WEEK) Platform.runLater(this::scrollToCurrentTime);
    }

    private void step(int delta) {
        if (viewMode == ViewMode.WEEK) {
            weekOffset += delta;
        } else if (viewMode == ViewMode.DAY) {
            dayOffset += delta;
        } else {
            monthOffset += delta;
        }
        refresh();
    }

    private void updateNavLabels() {
        if (viewMode == ViewMode.WEEK) {
            btnPrev.setText("Prev Week");
            btnNext.setText("Next Week");
            if (btnDayView != null) btnDayView.setText("Daily View");
            btnMonthView.setText("Monthly View");
            if (btnJumpNow != null) btnJumpNow.setText("Jump to this week");
        } else if (viewMode == ViewMode.DAY) {
            btnPrev.setText("Prev Day");
            btnNext.setText("Next Day");
            if (btnDayView != null) btnDayView.setText("Weekly View");
            btnMonthView.setText("Monthly View");
            if (btnJumpNow != null) btnJumpNow.setText("Jump to today");
        } else {
            btnPrev.setText("Prev Month");
            btnNext.setText("Next Month");
            if (btnDayView != null) btnDayView.setText("Daily View");
            btnMonthView.setText("Weekly View");
            if (btnJumpNow != null) btnJumpNow.setText("Jump to this month");
        }
        boolean edges = viewMode != ViewMode.DAY;
        if (prevPane != null) {
            prevPane.setVisible(edges);
            prevPane.setManaged(edges);
        }
        if (nextPane != null) {
            nextPane.setVisible(edges);
            nextPane.setManaged(edges);
        }
    }

    public void refresh() {
        if (typewriterOn || accountOn) return;
        if (refreshSoon == null) {
            refreshSoon = new PauseTransition(Duration.millis(20));
            refreshSoon.setOnFinished(e -> refreshNow());
        }
        refreshSoon.playFromStart();
    }

    private void refreshNow() {
        if (typewriterOn || accountOn) return;
        if (viewMode == ViewMode.WEEK) {
            weekGridBuilder.build(grid, sidePanel, weekOffset);
        } else if (viewMode == ViewMode.DAY) {
            dayGridBuilder.build(grid, sidePanel, dayOffset);
            if (gridScroll != null) gridScroll.setVvalue(0);
        } else {
            monthGridBuilder.build(grid, sidePanel, monthOffset);
            if (gridScroll != null) {
                gridScroll.setVvalue(0);
            }
        }
        if (selectedTask != null) {
            String id = selectedTask.getID();
            showTaskDescription(id == null ? null : org.ashoreprospect.Command.TaskLookup.anywhere(system, id));
        }
    }

    private void scrollToCurrentTime() {
        if (viewMode != ViewMode.WEEK || weekOffset != 0) return;

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