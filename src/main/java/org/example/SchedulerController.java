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

public class SchedulerController {

    @FXML private StackPane root;
    @FXML private BorderPane borderPane;
    @FXML private GridPane grid;
    @FXML private Label sidePanel;
    @FXML private Button btnPrev, btnNext, btnUndo, btnRedo, btnAdd;
    @FXML private MenuItem btnImport, btnRun;

    private TaskManagementSystem system;
    private int weekOffset = 0;
    private LocalDate baseDate;

    private boolean popupIsOpen = false;
    private boolean areYouSureIsOpen = false;
    private boolean alertIsOpen = false;
    private EventHandler<KeyEvent> escHandler;

    private StackPane currentDimmedOverlay;
    private Pane currentPopup;
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

        btnUndo.setOnAction(e -> {
            system.command("undo");
            system.command("printAllTask");
            renderGrid();
        });

        btnRedo.setOnAction(e -> {
            system.command("redo");
            system.command("printAllTask");
            renderGrid();
        });

        btnAdd.setOnAction(e -> showFreeAddTaskPopup());

        Platform.runLater(this::renderGrid);
    }

    public void renderGrid() {
        grid.getChildren().clear();
        var gridData = system.getGrid();

        for (int col = 0; col < 7; col++) {
            int weekDayIndex = weekOffset * 7 + col;
            LocalDate date = Calendar.getStartOfWeek(baseDate).plusDays(weekDayIndex);
            String text = date.getDayOfWeek().toString().substring(0, 3) + " "
                    + date.getMonthValue() + "/" + date.getDayOfMonth();
            grid.add(new Label(text), col + 1, 0);
        }

        for (int row = 1; row <= 17; row++) {
            int hourIndex = row - 1;
            grid.add(new Label((hourIndex + 6) + ":00"), 0, row);

            for (int col = 1; col <= 7; col++) {
                int dayIndex = weekOffset * 7 + (col - 1);
                LocalDate date = Calendar.getStartOfWeek(baseDate).plusDays(dayIndex);

                Pane cell = new Pane();
                cell.setPrefSize(150, 100);
                cell.setStyle("-fx-border-color: black;");

                if (gridData != null && gridData.containsKey(date)) {
                    Task task = gridData.get(date).returnDayGrid()[hourIndex];
                    if (task != null) {
                        Label label = new Label(task.getTaskName());
                        label.setWrapText(true);
                        cell.getChildren().add(label);
                    }
                }

                cell.setOnMouseEntered(e -> cell.setStyle("-fx-background-color: #ffccdd; -fx-border-color: black;"));
                cell.setOnMouseExited(e -> cell.setStyle("-fx-background-color: transparent; -fx-border-color: black;"));
                cell.setOnMouseClicked(e -> showTaskInputPopup(date, hourIndex));

                grid.add(cell, col, row);
            }
        }

        sidePanel.setText("Unscheduled task:\n" + system.getEngine().printUnscheduledTask());
    }

    // ==================== Common Helpers ====================

    private void removeMainEscHandler(Scene scene) {
        if (escHandler != null) {
            scene.removeEventFilter(KeyEvent.KEY_PRESSED, escHandler);
            escHandler = null;
        }
    }

    private void closeEverything(Scene scene) {
        removeMainEscHandler(scene);
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

    private void showAlertPopup(Scene scene, String message) {
        if (alertIsOpen) return;
        if (escHandler != null) scene.removeEventFilter(KeyEvent.KEY_PRESSED, escHandler);

        StackPane overlay = GuiHelper.createDimmedOverlay(root);
        VBox box = GuiHelper.createWhiteBox();

        Label msg = new Label(message);
        msg.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Button ok = new Button("Okay");

        box.getChildren().addAll(msg, ok);
        overlay.getChildren().add(box);
        StackPane.setAlignment(box, Pos.CENTER);
        root.getChildren().add(overlay);
        alertIsOpen = true;

        final EventHandler<KeyEvent>[] holder = new EventHandler[1];
        Runnable dismiss = () -> {
            root.getChildren().remove(overlay);
            alertIsOpen = false;
            if (holder[0] != null) scene.removeEventFilter(KeyEvent.KEY_PRESSED, holder[0]);
            if (escHandler != null) scene.addEventFilter(KeyEvent.KEY_PRESSED, escHandler);
        };

        ok.setOnAction(e -> dismiss.run());
        holder[0] = ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                ev.consume();
                dismiss.run();
            }
        };
        scene.addEventFilter(KeyEvent.KEY_PRESSED, holder[0]);
    }

    private void showConfirmation(Scene scene) {
        if (areYouSureIsOpen || alertIsOpen) return;
        areYouSureIsOpen = true;

        currentConfirmOverlay = GuiHelper.createDimmedOverlay(root);
        Pane pane = new Pane();
        pane.setPrefSize(400, 200);
        GuiHelper.centerInParentOffset(pane, currentConfirmOverlay, 0.8, 0.9);

        VBox box = GuiHelper.createWhiteBox();
        Label msg = new Label("Are you sure? Unsaved changes will be discarded.");
        msg.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Button discard = new Button("Discard and leave");
        Button cancel = new Button("Cancel");
        HBox buttons = new HBox(10, discard, cancel);

        box.getChildren().addAll(msg, buttons);
        pane.getChildren().add(box);
        currentConfirmOverlay.getChildren().add(pane);
        root.getChildren().add(currentConfirmOverlay);

        cancel.setOnAction(e -> {
            root.getChildren().remove(currentConfirmOverlay);
            currentConfirmOverlay = null;
            areYouSureIsOpen = false;
        });

        discard.setOnAction(e -> {
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

    private void setupEscHandler(Scene scene) {
        escHandler = event -> {
            if (event.getCode() != KeyCode.ESCAPE) return;
            event.consume();
            if (alertIsOpen) return;
            if (!areYouSureIsOpen) showConfirmation(scene);
            else dismissConfirmation();
        };
        scene.addEventFilter(KeyEvent.KEY_PRESSED, escHandler);
    }

    private ComboBox<String> createHourCombo(int from, int to, int defaultHour) {
        ComboBox<String> box = new ComboBox<>();
        for (int h = from; h <= to; h++) {
            box.getItems().add(String.format("%02d:00", h));
        }
        box.setValue(String.format("%02d:00", defaultHour));
        box.setPrefWidth(90);
        return box;
    }

    // ==================== Free Add Popup ====================

    private void showFreeAddTaskPopup() {
        Scene scene = root.getScene();
        removeMainEscHandler(scene);

        currentDimmedOverlay = GuiHelper.createDimmedOverlay(root);
        root.getChildren().add(currentDimmedOverlay);

        currentPopup = new Pane();
        currentPopup.setPrefSize(480, 480);
        GuiHelper.centerInParentOffset(currentPopup, currentDimmedOverlay, 0.7, 0.7);
        root.getChildren().add(currentPopup);

        VBox content = GuiHelper.createWhiteBox();

        Label title = new Label("Add New Task");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");

        TextField nameField = new TextField();
        nameField.setPromptText("Task name");

        TextField durationField = new TextField("2");
        durationField.setPrefWidth(70);

        ToggleGroup typeGroup = new ToggleGroup();
        RadioButton fixedRadio = new RadioButton("Fixed");
        RadioButton deadlineRadio = new RadioButton("Deadline");
        RadioButton flexibleRadio = new RadioButton("Flexible");
        fixedRadio.setToggleGroup(typeGroup);
        deadlineRadio.setToggleGroup(typeGroup);
        flexibleRadio.setToggleGroup(typeGroup);
        fixedRadio.setSelected(true);
        HBox typeBox = new HBox(12, fixedRadio, deadlineRadio, flexibleRadio);

        // ===== Fixed section =====
        DatePicker startDatePicker = new DatePicker(LocalDate.now());
        ComboBox<String> startHourBox = createHourCombo(6, 22, 9);

        HBox startRow = new HBox(10, startDatePicker, startHourBox);
        VBox fixedBox = new VBox(6, new Label("Start Date & Time:"), startRow);

        // ===== Deadline section =====
        DatePicker deadlineDatePicker = new DatePicker(LocalDate.now());
        ComboBox<String> deadlineHourBox = createHourCombo(6, 23, 18);

        HBox deadlineRow = new HBox(10, deadlineDatePicker, deadlineHourBox);
        VBox deadlineBox = new VBox(6, new Label("Deadline Date & Time:"), deadlineRow);
        deadlineBox.setVisible(false);
        deadlineBox.setManaged(false);

        typeGroup.selectedToggleProperty().addListener((o, old, neo) -> {
            boolean isFixed = neo == fixedRadio;
            boolean isDeadline = neo == deadlineRadio;
            fixedBox.setVisible(isFixed);
            fixedBox.setManaged(isFixed);
            deadlineBox.setVisible(isDeadline);
            deadlineBox.setManaged(isDeadline);
        });

        Button saveBtn = new Button("Save Task");
        Button cancelBtn = new Button("Cancel");
        HBox btnBox = new HBox(10, saveBtn, cancelBtn);

        content.getChildren().addAll(
                title,
                new Label("Task Name:"), nameField,
                new Label("Duration (hours):"), durationField,
                new Label("Type:"), typeBox,
                fixedBox,
                deadlineBox,
                btnBox
        );

        currentPopup.getChildren().add(content);
        popupIsOpen = true;

        saveBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                showAlertPopup(scene, "Task name cannot be empty.");
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

            boolean isFixed = fixedRadio.isSelected();
            boolean isDeadline = deadlineRadio.isSelected();

            LocalDate startDate = null;
            LocalTime startTime = null;
            LocalDate deadlineDate = null;
            LocalTime deadlineTime = null;

            LocalDate today = LocalDate.now();
            LocalTime now = LocalTime.now();

            try {
                if (isFixed) {
                    startDate = startDatePicker.getValue();
                    if (startDate == null) {
                        showAlertPopup(scene, "Please select a start date.");
                        return;
                    }
                    int hour = Integer.parseInt(startHourBox.getValue().substring(0, 2));
                    startTime = LocalTime.of(hour, 0);

                    if (startDate.isBefore(today) || (startDate.equals(today) && startTime.isBefore(now))) {
                        showAlertPopup(scene, "Cannot create Fixed task in the past.");
                        return;
                    }
                } else if (isDeadline) {
                    deadlineDate = deadlineDatePicker.getValue();
                    if (deadlineDate == null) {
                        showAlertPopup(scene, "Please select a deadline date.");
                        return;
                    }
                    int hour = Integer.parseInt(deadlineHourBox.getValue().substring(0, 2));
                    deadlineTime = LocalTime.of(hour, 0);

                    if (deadlineDate.isBefore(today) || (deadlineDate.equals(today) && deadlineTime.isBefore(now))) {
                        showAlertPopup(scene, "Cannot set deadline in the past.");
                        return;
                    }
                }
            } catch (Exception ex) {
                showAlertPopup(scene, "Invalid date or time.");
                return;
            }

            int state = isFixed ? 1 : (isDeadline ? 2 : 3);
            FetchPopOutTask pop = new FetchPopOutTask(name, duration, state, startDate, startTime, deadlineDate, deadlineTime);
            system.fetchPopupTask(pop);

            if (!system.command("addTask")) {
                showAlertPopup(scene, "Failed to create task.");
                return;
            }

            renderGrid();
            closeEverything(scene);
        });

        cancelBtn.setOnAction(e -> closeEverything(scene));
        setupEscHandler(scene);
    }

    // ==================== Grid Click Popup ====================

    private void showTaskInputPopup(LocalDate clickedDate, int hourIndex) {
        Scene scene = root.getScene();
        removeMainEscHandler(scene);

        currentDimmedOverlay = GuiHelper.createDimmedOverlay(root);
        root.getChildren().add(currentDimmedOverlay);

        currentPopup = new Pane();
        currentPopup.setPrefSize(460, 420);
        GuiHelper.centerInParentOffset(currentPopup, currentDimmedOverlay, 0.7, 0.7);
        root.getChildren().add(currentPopup);

        VBox content = GuiHelper.createWhiteBox();

        Label title = new Label("New Task @ " + clickedDate);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        TextField nameField = new TextField();
        nameField.setPromptText("Task name");

        TextField durationField = new TextField("2");
        durationField.setPrefWidth(70);

        ToggleGroup typeGroup = new ToggleGroup();
        RadioButton fixedRadio = new RadioButton("Fixed");
        RadioButton deadlineRadio = new RadioButton("Deadline");
        RadioButton flexibleRadio = new RadioButton("Flexible");
        fixedRadio.setToggleGroup(typeGroup);
        deadlineRadio.setToggleGroup(typeGroup);
        flexibleRadio.setToggleGroup(typeGroup);
        fixedRadio.setSelected(true);
        HBox typeBox = new HBox(12, fixedRadio, deadlineRadio, flexibleRadio);

        // Deadline row (DatePicker + Hour on the same line)
        DatePicker deadlineDatePicker = new DatePicker(LocalDate.now());
        ComboBox<String> deadlineHourBox = createHourCombo(6, 23, 18);
        HBox deadlineRow = new HBox(10, deadlineDatePicker, deadlineHourBox);

        VBox deadlineBox = new VBox(6, new Label("Deadline Date & Time:"), deadlineRow);
        deadlineBox.setVisible(false);
        deadlineBox.setManaged(false);

        typeGroup.selectedToggleProperty().addListener((o, old, neo) -> {
            boolean show = neo == deadlineRadio;
            deadlineBox.setVisible(show);
            deadlineBox.setManaged(show);
        });

        Button saveBtn = new Button("Save Task");
        Button cancelBtn = new Button("Cancel");
        HBox btnBox = new HBox(10, saveBtn, cancelBtn);

        content.getChildren().addAll(
                title,
                new Label("Task Name:"), nameField,
                new Label("Duration (hours):"), durationField,
                new Label("Type:"), typeBox,
                deadlineBox,
                btnBox
        );

        currentPopup.getChildren().add(content);
        popupIsOpen = true;

        saveBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                showAlertPopup(scene, "Task name cannot be empty.");
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

            boolean isFixed = fixedRadio.isSelected();
            boolean isDeadline = deadlineRadio.isSelected();

            LocalDate nowDate = LocalDate.now();
            LocalTime nowTime = LocalTime.now();
            LocalTime clickedTime = LocalTime.of(hourIndex + system.getEngine().getStartingHour(), 0);

            if (isFixed) {
                if (clickedDate.isBefore(nowDate) || (clickedDate.equals(nowDate) && clickedTime.isBefore(nowTime))) {
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
            } else if (isDeadline) {
                deadlineDate = deadlineDatePicker.getValue();
                if (deadlineDate == null) {
                    showAlertPopup(scene, "Please select a deadline date.");
                    return;
                }
                int hour = Integer.parseInt(deadlineHourBox.getValue().substring(0, 2));
                deadlineTime = LocalTime.of(hour, 0);

                if (deadlineDate.isBefore(nowDate) || (deadlineDate.equals(nowDate) && deadlineTime.isBefore(nowTime))) {
                    showAlertPopup(scene, "Cannot set deadline in the past.");
                    return;
                }
            }

            if (isFixed) {
                for (int i = 0; i < duration; i++) {
                    int checkHour = hourIndex + i;
                    if (checkHour >= system.getEngine().getTotalHours()) {
                        showAlertPopup(scene, "Task exceeds daily time range.");
                        return;
                    }
                    system.getGrid().putIfAbsent(clickedDate, new Day(system.getBlockedTime()));
                    if (system.getGrid().get(clickedDate).returnDayGrid()[checkHour] != null) {
                        showAlertPopup(scene, "Time conflict with another task.");
                        return;
                    }
                }
            }

            int state = isFixed ? 1 : (isDeadline ? 2 : 3);
            FetchPopOutTask pop = new FetchPopOutTask(name, duration, state, startDate, startTime, deadlineDate, deadlineTime);
            system.fetchPopupTask(pop);

            if (!system.command("addTask")) {
                showAlertPopup(scene, "Failed to create task.");
                return;
            }

            renderGrid();
            closeEverything(scene);
        });

        cancelBtn.setOnAction(e -> closeEverything(scene));
        setupEscHandler(scene);
    }

    public void refresh() {
        renderGrid();
    }
}