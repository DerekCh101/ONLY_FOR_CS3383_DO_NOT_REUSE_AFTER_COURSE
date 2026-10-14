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
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.InputEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.RefreshLifeCycle;
import org.ashoreprospect.SchedulerApp;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.net.Api;
import org.ashoreprospect.net.LocalSession;
import org.ashoreprospect.net.NetworkWatch;

public class SchedulerController {

    private static final Duration IDLE_LIMIT = Duration.minutes(100);

    private enum ViewMode { WEEK, MONTH }

    @FXML private MenuItem resetAll;
    @FXML private MenuItem clearPast;
    @FXML private StackPane root;
    @FXML private BorderPane borderPane;
    @FXML private GridPane grid;
    @FXML private Label sidePanel;
    @FXML private Label lblUser;
    @FXML private Label lblNet;
    @FXML private Button btnPrev, btnNext, btnUndo, btnRedo, btnMonthView, btnJumpNow;
    @FXML private Button btnFab, btnFabPrev, btnFabNext;
    @FXML private Button btnAction;
    @FXML private MenuButton menuView;
    @FXML private MenuItem btnImport, btnRun, menuAddTask, menuBlocked;
    @FXML private MenuItem menuWeek, menuMonth, menuThisWeek, menuThisMonth, menuUndo, menuRedo;
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

    private TaskDragDropSupport dragDrop;
    private TaskDialogs dialogs;
    private WeekGridBuilder weekGridBuilder;
    private MonthGridBuilder monthGridBuilder;
    private Timeline nowLineClock;
    private PauseTransition idleTimer;
    private boolean loggingOut;
    private boolean skipSaveOnLogout;
    private AccountPane accountPane;
    private boolean accountOn;

    @FXML
    public void initialize() {
        system = TaskManagementSystem.getInstance();
        system.attachGUI(this);

        paintUser();
        paintNet(NetworkWatch.isOnline());
        NetworkWatch.addListener(online -> Platform.runLater(() -> paintNet(online)));
        AppTheme.listen(() -> Platform.runLater(this::onThemeChanged));
        if (lblUser != null) {
            lblUser.setOnMouseClicked(e -> showAccount(AccountPane.Page.HOME));
        }

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
        if (clearPast != null) {
            clearPast.setOnAction(e -> {
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
        if (menuBlocked != null) menuBlocked.setOnAction(e -> dialogs.showBlockedEditor());
        wireMenus();

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
            accountPane = new AccountPane(this::logout, this::wipeCacheAndLogout, name -> paintUser());
            accountPane.setVisible(false);
            accountPane.setManaged(false);
            if (mainStack != null) mainStack.getChildren().add(accountPane);
            refreshAfterLoad();
            maybeRememberChallenge();
        });
    }

    public void refreshAfterLoad() {
        refresh();
        Platform.runLater(this::scrollToCurrentTime);
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
            if (viewMode != ViewMode.WEEK) toggleViewMode();
        });
        if (menuMonth != null) menuMonth.setOnAction(e -> {
            showMain(false);
            if (viewMode != ViewMode.MONTH) toggleViewMode();
        });
        if (menuThisWeek != null) menuThisWeek.setOnAction(e -> {
            showMain(false);
            if (viewMode != ViewMode.WEEK) toggleViewMode();
            weekOffset = 0;
            refresh();
        });
        if (menuThisMonth != null) menuThisMonth.setOnAction(e -> {
            showMain(false);
            if (viewMode != ViewMode.MONTH) toggleViewMode();
            monthOffset = 0;
            refresh();
        });
        addThemeToViewMenu();
        if (menuUndo != null) menuUndo.setOnAction(e -> {
            system.command("undo");
            refresh();
        });
        if (menuRedo != null) menuRedo.setOnAction(e -> {
            system.command("redo");
            refresh();
        });
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

    private void setSchedChrome(boolean on) {
        if (btnPrev != null) { btnPrev.setVisible(on); btnPrev.setManaged(on); }
        if (btnMonthView != null) { btnMonthView.setVisible(on); btnMonthView.setManaged(on); }
        if (btnNext != null) { btnNext.setVisible(on); btnNext.setManaged(on); }
        if (btnUndo != null) { btnUndo.setVisible(on); btnUndo.setManaged(on); }
        if (btnRedo != null) { btnRedo.setVisible(on); btnRedo.setManaged(on); }
        if (btnAction != null) { btnAction.setVisible(on); btnAction.setManaged(on); }
        if (btnFab != null) { btnFab.setVisible(on); btnFab.setManaged(on); }
        if (btnFabPrev != null) { btnFabPrev.setVisible(on); btnFabPrev.setManaged(on); }
        if (btnFabNext != null) { btnFabNext.setVisible(on); btnFabNext.setManaged(on); }
        if (btnJumpNow != null) { btnJumpNow.setVisible(on); btnJumpNow.setManaged(on); }
    }

    private void addThemeToViewMenu() {
        if (menuView == null) return;
        Label name = new Label("Theme");
        name.setStyle("-fx-text-fill: #e8e4dc; " + AppTheme.font());
        HBox row = new HBox(12, name, new ThemeSwitch());
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new javafx.geometry.Insets(4, 8, 4, 8));
        CustomMenuItem item = new CustomMenuItem(row, false);
        menuView.getItems().addAll(new SeparatorMenuItem(), item);
    }

    private void jumpNow() {
        showMain(false);
        if (viewMode == ViewMode.WEEK) weekOffset = 0;
        else monthOffset = 0;
        refresh();
        if (viewMode == ViewMode.WEEK) Platform.runLater(this::scrollToCurrentTime);
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
        if (root != null && root.getScene() != null) AppTheme.apply(root.getScene());
        styleTab(tabScheduler, !typewriterOn && !accountOn);
        styleTab(tabTypewriter, typewriterOn);
        if (typewriterPane != null) typewriterPane.applyTheme();
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
            if (btnJumpNow != null) btnJumpNow.setText("Jump to this week");
        } else {
            btnPrev.setText("Prev Month");
            btnNext.setText("Next Month");
            btnMonthView.setText("Weekly View");
            if (btnJumpNow != null) btnJumpNow.setText("Jump to this month");
        }
    }

    public void refresh() {
        if (typewriterOn || accountOn) return;
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