package org.example;

import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class SchedulerController {

    @FXML private StackPane root;
    @FXML private BorderPane borderPane;
    @FXML private GridPane grid;
    @FXML private Label sidePanel;
    @FXML private Button btnPrev, btnNext,btnUndo,btnRedo;
    @FXML private MenuItem btnImport, btnRun;

    private TaskManagementSystem system;
    private int weekOffset = 0;
    private LocalDate baseDate;

    private boolean popupIsOpen = false;
    private boolean areYouSureIsOpen = false;
    private boolean alertIsOpen = false;
    private EventHandler<KeyEvent> escHandler;

    // Currently open main popup nodes
    private StackPane currentDimmedOverlay;
    private Pane currentPopup;

    // Currently open confirmation overlay (so we can remove it cleanly)
    private StackPane currentConfirmOverlay;

    @FXML
    public void initialize() {
        system = TaskManagementSystem.getInstance();
        system.attachGUI(this);
        baseDate = LocalDate.now();

        btnPrev.setOnAction(e -> { weekOffset--; renderGrid(); });
        btnNext.setOnAction(e -> { weekOffset++; renderGrid(); });

        btnImport.setOnAction(e -> {
            system.command("addTaskFromFile");
            renderGrid();
        });

        btnRun.setOnAction(e -> {
            system.command("runScheduler");
            system.command("printAllTask");
            renderGrid();
        });

        btnUndo.setOnAction(e->{
            system.command("undo");
            system.command("printAllTask");
            renderGrid();
        });

        btnRedo.setOnAction(e->{
            system.command("redo");
            system.command("printAllTask");
            renderGrid();
        });


        Platform.runLater(this::renderGrid);
    }

    public void renderGrid() {
        grid.getChildren().clear();
        var gridData = system.getGrid();

        // Header
        for (int col = 0; col < 7; col++) {
            int weekDayIndex = weekOffset * 7 + col;
            LocalDate clickedDate = Calendar.getStartOfWeek(baseDate).plusDays(weekDayIndex);
            String text = clickedDate.getDayOfWeek().toString().substring(0, 3) + " "
                    + clickedDate.getMonthValue() + "/" + clickedDate.getDayOfMonth();
            grid.add(new Label(text), col + 1, 0);
        }

        // Body
        for (int row = 1; row <= 17; row++) {
            int hourIndex = row - 1;
            int displayHour = hourIndex + 6;
            grid.add(new Label(displayHour + ":00"), 0, row);

            for (int col = 1; col <= 7; col++) {
                int dayIndex = weekOffset * 7 + (col - 1);
                LocalDate clickedDate = Calendar.getStartOfWeek(baseDate).plusDays(dayIndex);

                Pane cell = new Pane();
                cell.setPrefSize(150, 100);
                cell.setStyle("-fx-border-color: black;");

                if (gridData != null && gridData.containsKey(clickedDate)) {
                    Task task = gridData.get(clickedDate).returnDayGrid()[hourIndex];
                    if (task != null) {
                        Label taskLabel = new Label(task.getTaskName());
                        taskLabel.setWrapText(true);
                        cell.getChildren().add(taskLabel);
                    }
                }

                cell.setOnMouseEntered(e ->
                        cell.setStyle("-fx-background-color: #ffccdd; -fx-border-color: black;"));
                cell.setOnMouseExited(e ->
                        cell.setStyle("-fx-background-color: transparent; -fx-border-color: black;"));
                cell.setOnMouseClicked(e -> showTaskInputPopup(cell, clickedDate, hourIndex));

                grid.add(cell, col, row);
            }
        }

        sidePanel.setText("Unscheduled task: \n" + system.getEngine().printUnscheduledTask());
    }

    // =====================================================================
    //  Reusable helpers
    // =====================================================================

    private StackPane createDimmedOverlay() {
        StackPane overlay = new StackPane();
        overlay.prefWidthProperty().bind(root.widthProperty());
        overlay.prefHeightProperty().bind(root.heightProperty());
        overlay.setStyle("-fx-background-color: rgba(0, 0, 0, 0.6);");
        return overlay;
    }

    private void centerInParent(Pane node, Region parent) {
        node.layoutXProperty().bind(
                parent.widthProperty().subtract(node.widthProperty()).divide(2));
        node.layoutYProperty().bind(
                parent.heightProperty().subtract(node.heightProperty()).divide(2));
        node.setManaged(false);
    }

    private void centerInParentOffset(Pane node, Region parent,double widthOffSet, double heightOffset) {
        node.layoutXProperty().bind(
                parent.widthProperty().subtract(node.widthProperty()).divide(2).multiply(widthOffSet));
        node.layoutYProperty().bind(
                parent.heightProperty().subtract(node.heightProperty()).divide(2).multiply(heightOffset));
        node.setManaged(false);
    }

    private VBox createWhiteBox() {
        VBox box = new VBox(12);
        box.setStyle("-fx-background-color: #f8f8f8; -fx-padding: 20; "
                + "-fx-border-color: #333333; -fx-border-width: 2; "
                + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 10, 0, 0, 0);");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return box;
    }

    private void removeMainEscHandler(Scene scene) {
        if (escHandler != null) {
            scene.removeEventFilter(KeyEvent.KEY_PRESSED, escHandler);
            escHandler = null;
        }
    }

    private void closeEverything(Scene scene) {
        removeMainEscHandler(scene);

        // Only remove the temporary nodes we created
        if (currentConfirmOverlay != null) {
            root.getChildren().remove(currentConfirmOverlay);
            currentConfirmOverlay = null;
        }
        if (currentPopup != null) {
            root.getChildren().remove(currentPopup);
            currentPopup = null;
        }
        if (currentDimmedOverlay != null) {
            root.getChildren().remove(currentDimmedOverlay);
            currentDimmedOverlay = null;
        }

        popupIsOpen = false;
        areYouSureIsOpen = false;
        alertIsOpen = false;
    }

    // =====================================================================
    //  Empty-name alert (ESC only closes itself)
    // =====================================================================

    private void showAlertPopup(Scene scene, String message) {
        if (alertIsOpen) return;

        // Temporarily disable main ESC handler
        if (escHandler != null) {
            scene.removeEventFilter(KeyEvent.KEY_PRESSED, escHandler);
        }

        StackPane alertOverlay = createDimmedOverlay();
        VBox alertBox = createWhiteBox();

        Label msg = new Label(message);
        msg.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Button okBtn = new Button("Okay");

        alertBox.getChildren().addAll(msg, okBtn);
        alertOverlay.getChildren().add(alertBox);
        StackPane.setAlignment(alertBox, Pos.CENTER);

        root.getChildren().add(alertOverlay);
        alertIsOpen = true;

        final EventHandler<KeyEvent>[] alertEscHolder = new EventHandler[1];

        Runnable dismiss = () -> {
            root.getChildren().remove(alertOverlay);
            alertIsOpen = false;

            if (alertEscHolder[0] != null) {
                scene.removeEventFilter(KeyEvent.KEY_PRESSED, alertEscHolder[0]);
            }
            // Restore main ESC handler
            if (escHandler != null) {
                scene.addEventFilter(KeyEvent.KEY_PRESSED, escHandler);
            }
        };

        okBtn.setOnAction(e -> dismiss.run());

        alertEscHolder[0] = ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                ev.consume();
                dismiss.run();
            }
        };
        scene.addEventFilter(KeyEvent.KEY_PRESSED, alertEscHolder[0]);
    }

    // =====================================================================
    //  Confirmation dialog ("Are you sure?")
    // =====================================================================

    private void showConfirmation(Scene scene) {
        if (areYouSureIsOpen || alertIsOpen) return;

        areYouSureIsOpen = true;

        currentConfirmOverlay = createDimmedOverlay();
        Pane confirmPane = new Pane();
        confirmPane.setPrefSize(400, 200);
        centerInParentOffset(confirmPane, currentConfirmOverlay,0.8,0.9);

        VBox box = createWhiteBox();
        Label message = new Label("Are you sure? Unsaved Changes can be discarded.");
        message.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        HBox buttons = new HBox(10);
        Button discardBtn = new Button("Discard and leave");
        Button cancelBtn = new Button("Cancel");
        buttons.getChildren().addAll(discardBtn, cancelBtn);

        box.getChildren().addAll(message, buttons);
        confirmPane.getChildren().add(box);
        currentConfirmOverlay.getChildren().add(confirmPane);
        root.getChildren().add(currentConfirmOverlay);

        // Cancel → only close confirmation
        cancelBtn.setOnAction(e -> {
            root.getChildren().remove(currentConfirmOverlay);
            currentConfirmOverlay = null;
            areYouSureIsOpen = false;
        });

        // Discard → close everything
        discardBtn.setOnAction(e -> {
            root.getChildren().remove(currentConfirmOverlay);
            currentConfirmOverlay = null;
            closeEverything(scene);
        });
    }

    private void dismissConfirmation() {
        if (currentConfirmOverlay != null) {
            root.getChildren().remove(currentConfirmOverlay);
            currentConfirmOverlay = null;
        }
        areYouSureIsOpen = false;
    }

    // =====================================================================
    //  Main task popup
    // =====================================================================

    private void showTaskInputPopup(Pane owner, LocalDate clickedDate, int hourIndex) {
        Scene scene = owner.getScene();

        removeMainEscHandler(scene);

        currentDimmedOverlay = createDimmedOverlay();
        root.getChildren().add(currentDimmedOverlay);

        currentPopup = new Pane();
        currentPopup.setPrefSize(400, 380);
        centerInParentOffset(currentPopup, currentDimmedOverlay, 0.7, 0.7);
        root.getChildren().add(currentPopup);

        VBox content = createWhiteBox();

        Label title = new Label("New Task @ " + clickedDate);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Label taskNameLabel = new Label("Task Name:");
        TextField taskNameField = new TextField();
        taskNameField.setPromptText("Enter task name");

        Label durationLabel = new Label("Duration (hours):");
        TextField durationField = new TextField("2");
        durationField.setPrefWidth(80);

        // ----- Task Type (Radio Buttons) -----
        ToggleGroup taskTypeGroup = new ToggleGroup();
        HBox radioButtonBox = new HBox(5);
        RadioButton fixedRadio = new RadioButton("Fixed task (cannot be moved)");
        RadioButton deadlineRadio = new RadioButton("Deadline task");
        RadioButton flexibleRadio = new RadioButton("Flexible task (Position don't care, it has the lowest priority.)");

        fixedRadio.setToggleGroup(taskTypeGroup);
        deadlineRadio.setToggleGroup(taskTypeGroup);
        flexibleRadio.setToggleGroup(taskTypeGroup);

        radioButtonBox.getChildren().addAll(fixedRadio,flexibleRadio,deadlineRadio);

        // default selection
        fixedRadio.setSelected(true);

        // ----- Deadline Inputs -----
        Label deadlineDateLabel = new Label("Deadline clickedDate:");
        TextField deadlineDateField = new TextField();
        deadlineDateField.setPromptText("dd-MM-yyyy");

        Label deadlineTimeLabel = new Label("Deadline time (optional):");
        TextField deadlineTimeField = new TextField();
        deadlineTimeField.setPromptText("HH-mm");

        VBox deadlineBox = new VBox(5,
                deadlineDateLabel, deadlineDateField,
                deadlineTimeLabel, deadlineTimeField
        );

        deadlineBox.setVisible(false);
        deadlineBox.setManaged(false);

        // Toggle behavior
        taskTypeGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {

            if (newToggle == deadlineRadio) {
                deadlineBox.setVisible(true);
                deadlineBox.setManaged(true);
            } else {
                deadlineBox.setVisible(false);
                deadlineBox.setManaged(false);
            }

            // Fixed & Flexible both only require name + duration
            if (newToggle == flexibleRadio) {
                // nothing extra needed
            }

            if (newToggle == fixedRadio) {
                // nothing extra needed
            }
        });

        HBox buttonBox = new HBox(10);
        Button saveButton = new Button("Save Task");
        Button cancelButton = new Button("Cancel");
        buttonBox.getChildren().addAll(saveButton, cancelButton);

        content.getChildren().addAll(
                title,
                taskNameLabel, taskNameField,
                durationLabel, durationField,
                radioButtonBox,
                deadlineBox,
                buttonBox
        );

        currentPopup.getChildren().add(content);
        popupIsOpen = true;

        // ----- Save -----

        saveButton.setOnAction(e -> {
            String name = taskNameField.getText().trim();
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH-mm");
            LocalDate nowDate = LocalDate.now();
            LocalTime nowTime = LocalTime.now();
            LocalTime clickedTime = LocalTime.of(hourIndex + system.getEngine().getStartingHour(), 0);

            boolean isFixed = fixedRadio.isSelected();
            boolean hasDeadline = deadlineRadio.isSelected();
            boolean isFlexible = flexibleRadio.isSelected();

            if (name.isEmpty()) {
                showAlertPopup(scene, "Task Name cannot be empty.");
                return;
            }

            int duration;
            try {
                duration = Integer.parseInt(durationField.getText().trim());
                if (duration <= 0) throw new Exception();
            } catch (Exception ex) {
                showAlertPopup(scene, "Duration must be a positive number.");
                return;
            }

            // Only Fixed tasks care about the clicked cell being in the past
            if (isFixed) {
                if (clickedDate.isBefore(nowDate) ||
                        (clickedDate.equals(nowDate) && hourIndex + system.getEngine().getStartingHour() <= nowTime.getHour())) {
                    showAlertPopup(scene, "Cannot add Fixed task into the past.");
                    return;
                }
            }

            LocalDate startDate = null;
            LocalTime startTime = null;
            LocalDate deadlineDate = null;
            LocalTime deadlineTime = null;

            if (isFixed) {
                startDate = clickedDate;
                startTime = clickedTime;
            } else if (hasDeadline) {
                if (deadlineDateField.getText().trim().isEmpty()) {
                    showAlertPopup(scene, "Deadline Task must have a deadline date.");
                    return;
                }

                try {
                    deadlineDate = LocalDate.parse(deadlineDateField.getText().trim(), dateFormatter);
                } catch (Exception ex) {
                    showAlertPopup(scene, "Deadline date format invalid (dd-MM-yyyy).");
                    return;
                }

                if (!deadlineTimeField.getText().trim().isEmpty()) {
                    try {
                        deadlineTime = LocalTime.parse(deadlineTimeField.getText().trim(), timeFormatter);
                    } catch (Exception ex) {
                        showAlertPopup(scene, "Deadline time format invalid (HH-mm).");
                        return;
                    }
                } else {
                    deadlineTime = LocalTime.of(23, 59);
                }

                if (deadlineDate.isBefore(nowDate) ||
                        (deadlineDate.equals(nowDate) && deadlineTime.isBefore(nowTime))) {
                    showAlertPopup(scene, "Cannot have deadline in the past.");
                    return;
                }
            }
            // Flexible → both stay null

            // Time conflict check only makes sense for Fixed tasks
            if (isFixed) {
                for (int i = 0; i < duration; i++) {
                    int checkHour = hourIndex + i;
                    if (checkHour >= system.getEngine().getTotalHours()) {
                        showAlertPopup(scene, "Task exceeds daily time range.");
                        return;
                    }

                    system.getGrid().putIfAbsent(clickedDate, new Day(system.getBlockedTime()));
                    if (system.getGrid().get(clickedDate).returnDayGrid()[checkHour] != null) {
                        showAlertPopup(scene, "The task has time-conflict with other tasks.");
                        return;
                    }
                }
            }

            int state = isFixed ? 1 : (hasDeadline ? 2 : 3);

            FetchPopOutTask popoutPackage = new FetchPopOutTask(
                    name, duration, state,
                    startDate, startTime,
                    deadlineDate, deadlineTime);

            system.fetchPopupTask(popoutPackage);

            if (!system.command("addTask")) {
                showAlertPopup(scene, "Task input error.");
                return;
            }

            System.out.println("Task Created: " + name);
            System.out.printf("Unscheduled Task:\n %s",system.getEngine().printUnscheduledTask());
            renderGrid();
            closeEverything(scene);
        });


        cancelButton.setOnAction(e -> closeEverything(scene));

        escHandler = event -> {
            if (event.getCode() != KeyCode.ESCAPE) return;
            event.consume();

            if (alertIsOpen) return;

            if (!areYouSureIsOpen) {
                showConfirmation(scene);
            } else {
                dismissConfirmation();
            }
        };

        scene.addEventFilter(KeyEvent.KEY_PRESSED, escHandler);
    }

    public void refresh() {
        renderGrid();
    }
}