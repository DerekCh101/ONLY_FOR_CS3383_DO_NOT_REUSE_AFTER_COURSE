package org.example.GUI;

import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import org.example.*;
import org.example.net.Api;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class TaskDialogs {

    private final StackPane root;
    private final TaskManagementSystem system;
    private final Runnable onChanged;

    private boolean popupIsOpen = false;
    private boolean areYouSureIsOpen = false;
    private boolean alertIsOpen = false;
    private boolean discardIt = false;
    private boolean madeDecisionOnConfirmation = false;
    private EventHandler<KeyEvent> escHandler;

    private StackPane currentDimmedOverlay;
    private Pane currentPopup;
    private StackPane currentConfirmOverlay;

    public TaskDialogs(StackPane root, TaskManagementSystem system, Runnable onChanged) {
        this.root = root;
        this.system = system;
        this.onChanged = onChanged;
    }

    public void showBlockedEditor() {
        Scene scene = root.getScene();
        removeMainEscHandler(scene);

        currentDimmedOverlay = GuiHelper.createDimmedOverlay(root);
        root.getChildren().add(currentDimmedOverlay);

        currentPopup = new Pane();
        currentPopup.setPrefSize(620, 600);
        GuiHelper.centerInParentOffset(currentPopup, currentDimmedOverlay, 0.7, 0.7);
        root.getChildren().add(currentPopup);

        VBox content = GuiHelper.createWhiteBox();

        Label title = new Label("Blocked times");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        CheckBox everyDay = new CheckBox("Every day");
        everyDay.setSelected(true);
        CheckBox exceptionBox = new CheckBox("Exception (free this date)");
        DatePicker fromPicker = new DatePicker(AppTimeReference.getCurrentDate());
        DatePicker toPicker = new DatePicker(AppTimeReference.getCurrentDate());
        fromPicker.disableProperty().bind(everyDay.selectedProperty().and(exceptionBox.selectedProperty().not()));
        toPicker.disableProperty().bind(everyDay.selectedProperty().and(exceptionBox.selectedProperty().not()));
        everyDay.disableProperty().bind(exceptionBox.selectedProperty());
        exceptionBox.selectedProperty().addListener((o, was, is) -> {
            if (is) everyDay.setSelected(false);
        });
        HBox whenRow = new HBox(8, everyDay, exceptionBox, new Label("From"), fromPicker, new Label("To"), toPicker);

        VBox listBox = new VBox(6);
        rebuildBlockedList(scene, listBox);
        ScrollPane listScroll = new ScrollPane(listBox);
        listScroll.setFitToWidth(true);
        listScroll.setPrefHeight(220);
        listScroll.setMaxHeight(220);

        TextField nameField = new TextField();
        nameField.setPromptText("title (morning)");

        ComboBox<String> startBox = createSlotCombo(
                AppTimeReference.localTimeToSlot(LocalTime.of(6, 0)));
        ComboBox<String> endBox = createSlotCombo(
                AppTimeReference.localTimeToSlot(LocalTime.of(7, 0)));
        HBox timeRow = new HBox(8, new Label("Start"), startBox, new Label("End"), endBox);

        Button addBtn = new Button("Add");
        Button closeBtn = new Button("Close");
        HBox buttons = new HBox(10, addBtn, closeBtn);

        addBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) name = "blocked";
            LocalTime start;
            LocalTime end;
            try {
                start = parseSlotCombo(startBox.getValue());
                end = parseSlotCombo(endBox.getValue());
            } catch (Exception ex) {
                showAlertPopup(scene, "Invalid time.");
                return;
            }
            if (!start.isBefore(end)) {
                showAlertPopup(scene, "End must be after start.");
                return;
            }

            boolean isException = exceptionBox.isSelected();
            List<BlockedTime> rows;
            if (!isException && (everyDay.isSelected() || fromPicker.getValue() == null)) {
                BlockedTime row = BlockedTime.daily(name, start, end);
                row.setUserId(Api.INSTANCE.getUserId());
                rows = List.of(row);
            } else {
                LocalDate from = fromPicker.getValue();
                LocalDate to = toPicker.getValue() == null ? from : toPicker.getValue();
                if (from == null) {
                    showAlertPopup(scene, "Pick a date.");
                    return;
                }
                if (to.isBefore(from)) {
                    showAlertPopup(scene, "End date is before start date.");
                    return;
                }
                rows = BlockedTime.dailyRange(name, from, to, start, end);
                for (BlockedTime row : rows) {
                    row.setUserId(Api.INSTANCE.getUserId());
                    row.setException(isException);
                }
            }

            BlockedTimePeriod holder = system.getBlockedTime();
            String clash = holder.conflictAmong(rows);
            if (clash != null) {
                showAlertPopup(scene, clash);
                return;
            }
            try {
                for (BlockedTime row : rows) {
                    system.getTaskRepo().dbAddBlocked(row);
                }
                holder.replaceBlockedPeriods(system.getTaskRepo().fetchAllBlocked());
            } catch (SQLException ex) {
                showAlertPopup(scene, "Could not save blocked time: " + ex.getMessage());
                return;
            }
            nameField.clear();
            rebuildBlockedList(scene, listBox);
            if (onChanged != null) onChanged.run();
        });

        closeBtn.setOnAction(e -> closeEverything(scene));

        content.getChildren().addAll(
                title,
                new Label("Existing:"),
                listScroll,
                new Label("New block:"),
                nameField,
                whenRow,
                timeRow,
                buttons
        );
        currentPopup.getChildren().add(content);
        popupIsOpen = true;
        setupEscHandler(scene);
    }

    private void rebuildBlockedList(Scene scene, VBox listBox) {
        listBox.getChildren().clear();
        BlockedTimePeriod holder = system.getBlockedTime();
        if (holder == null || holder.blockedPeriod == null || holder.blockedPeriod.isEmpty()) {
            listBox.getChildren().add(new Label("(none)"));
            return;
        }
        List<BlockedTime> items = new ArrayList<>(holder.blockedPeriod);
        items.sort((a, b) -> {
            int c = a.getStartTime().compareTo(b.getStartTime());
            if (c != 0) return c;
            if (a.getSpecificDate() == null && b.getSpecificDate() == null) return 0;
            if (a.getSpecificDate() == null) return -1;
            if (b.getSpecificDate() == null) return 1;
            return a.getSpecificDate().compareTo(b.getSpecificDate());
        });
        for (BlockedTime b : items) {
            String when;
            if (b.isException()) {
                when = "exception " + b.getSpecificDate();
            } else if (b.isDaily()) {
                when = "every day";
            } else {
                when = String.valueOf(b.getSpecificDate());
            }
            String label = (b.getTitle() == null ? "blocked" : b.getTitle())
                    + "  " + when + "  " + b.getStartTime() + "–" + b.getEndTime();
            Button del = new Button("Delete");
            HBox row = new HBox(8, new Label(label), del);
            row.setAlignment(Pos.CENTER_LEFT);
            del.setOnAction(ev -> {
                try {
                    system.getTaskRepo().dbClearBlocked(b);
                    holder.replaceBlockedPeriods(system.getTaskRepo().fetchAllBlocked());
                } catch (SQLException ex) {
                    showAlertPopup(scene, "Could not delete: " + ex.getMessage());
                    return;
                }
                rebuildBlockedList(scene, listBox);
                if (onChanged != null) onChanged.run();
            });
            listBox.getChildren().add(row);
        }
    }

    public void showTaskInfoPopup(Task task, LocalDate date, int slotIndex) {
        Scene scene = root.getScene();
        removeMainEscHandler(scene);

        currentDimmedOverlay = GuiHelper.createDimmedOverlay(root);
        root.getChildren().add(currentDimmedOverlay);

        currentPopup = new Pane();
        currentPopup.setPrefSize(400, 340);
        GuiHelper.centerInParentOffset(currentPopup, currentDimmedOverlay, 0.7, 0.7);
        root.getChildren().add(currentPopup);

        VBox content = GuiHelper.createWhiteBox();

        Label title = new Label("Task Information");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        String typeText = task.isFixedTime() ? "Fixed" :
                task.hasDeadline() ? "Deadline" : "Flexible";

        Label nameLabel = new Label("Name: " + task.getTaskName());
        Label typeLabel = new Label("Type: " + typeText);
        Label durationLabel = new Label("Duration: " + task.getDurationHours() + " hour(s)");

        Label extraLabel;
        if (task.isFixedTime()) {
            extraLabel = new Label("Fixed Time: " + (task.getStartingDate()==null?task.getPlacedDate().toString():task.getStartingDate()) + " " +
                    (task.getStartingTime()==null?task.getPlacedTime().toString():task.getStartingTime().toString()));
        } else if (task.hasDeadline()) {
            extraLabel = new Label("Deadline: " + task.getDeadLineDate() + " " + task.getDeadLineTime());
        } else {
            extraLabel = new Label("Flexible task");
        }

        String placedText = (task.getPlacedDate() != null && task.getPlacedTime() != null)
                ? task.getPlacedDate() + " " + task.getPlacedTime()
                : date + " " + AppTimeReference.slotToLocalTime(slotIndex);
        Label placedLabel = new Label("Currently placed: " + placedText);

        CheckBox finishBtn = new CheckBox();
        finishBtn.setSelected(task.getWorkflow() == Workflow.FINISHED);
        finishBtn.setText(finishBtn.isSelected()?"Mark unfinish":"Mark finished");
        Button clearBtn = new Button("Clear Task");
        Button closeBtn = new Button("Close");

        HBox buttonBox = new HBox(12,  finishBtn, clearBtn, closeBtn);

        content.getChildren().addAll(title, nameLabel, typeLabel, durationLabel, extraLabel, placedLabel, buttonBox);
        currentPopup.getChildren().add(content);
        popupIsOpen = true;

        finishBtn.setOnAction(e -> {
            system.fetchPopupTask(new FetchPopOutTask(task));
            if(finishBtn.isSelected()) {
                system.command("finishTask");
            }
            else{
                system.command("unfinishTask");
            }
            if (onChanged != null) onChanged.run();
        });

        clearBtn.setOnAction(e -> {
            showAlertPopup(scene, "Task is cleared, press undo to restore");
            system.fetchPopupTask(new FetchPopOutTask(task));
            system.command("clearTask");
            closeEverything(scene);
            if (onChanged != null) onChanged.run();
        });

        closeBtn.setOnAction(e -> closeEverything(scene));
        setupEscHandler(scene);
    }

    public void showTaskPopup(boolean fromGridClick, LocalDate clickedDate, int slotIndex)  {
        Scene scene = root.getScene();
        removeMainEscHandler(scene);

        currentDimmedOverlay = GuiHelper.createDimmedOverlay(root);
        root.getChildren().add(currentDimmedOverlay);

        currentPopup = new Pane();
        currentPopup.setPrefSize(480, 520);
        GuiHelper.centerInParentOffset(currentPopup, currentDimmedOverlay, 0.7, 0.7);
        root.getChildren().add(currentPopup);

        VBox content = GuiHelper.createWhiteBox();

        Label title = new Label(fromGridClick ? "New Task @ " + clickedDate : "Add New Task");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");

        TextField nameField = new TextField();
        nameField.setPromptText("Task name");

        Spinner<Double> durationSpinner = new Spinner<>();
        SpinnerValueFactory.DoubleSpinnerValueFactory durationFactory =
                new SpinnerValueFactory.DoubleSpinnerValueFactory(0.5, 12.0, 1.0, 0.5);
        durationSpinner.setValueFactory(durationFactory);
        durationSpinner.setEditable(true);
        durationSpinner.setPrefWidth(100);

        ToggleGroup typeGroup = new ToggleGroup();
        RadioButton fixedRadio = new RadioButton("Fixed");
        RadioButton deadlineRadio = new RadioButton("Deadline");
        RadioButton flexibleRadio = new RadioButton("Flexible");
        fixedRadio.setToggleGroup(typeGroup);
        deadlineRadio.setToggleGroup(typeGroup);
        flexibleRadio.setToggleGroup(typeGroup);
        fixedRadio.setSelected(true);
        HBox typeBox = new HBox(12, fixedRadio, deadlineRadio, flexibleRadio);

        DatePicker startDatePicker = new DatePicker((clickedDate==null?AppTimeReference.getCurrentDate():clickedDate));
        ComboBox<String> startSlotBox = createSlotCombo(
                AppTimeReference.localTimeToSlot(LocalTime.of(9, 0)));
        HBox startRow = new HBox(10, startDatePicker, startSlotBox);
        VBox fixedBox = new VBox(6, new Label("Start Date & Time:"), startRow);

        DatePicker deadlineDatePicker = new DatePicker(clickedDate==null?AppTimeReference.getCurrentDate():clickedDate);
        ComboBox<String> deadlineSlotBox = createSlotCombo(
                AppTimeReference.localTimeToSlot(LocalTime.of(18, 0)));
        HBox deadlineRow = new HBox(10, deadlineDatePicker, deadlineSlotBox);
        VBox deadlineBox = new VBox(6, new Label("Deadline Date & Time:"), deadlineRow);
        deadlineBox.setVisible(false);
        deadlineBox.setManaged(false);

        if (fromGridClick) {
            fixedBox.setVisible(false);
            fixedBox.setManaged(false);
        }

        typeGroup.selectedToggleProperty().addListener((o, old, neo) -> {
            boolean isFixed = neo == fixedRadio;
            boolean isDeadline = neo == deadlineRadio;
            if (!fromGridClick) {
                fixedBox.setVisible(isFixed);
                fixedBox.setManaged(isFixed);
            }
            deadlineBox.setVisible(isDeadline);
            deadlineBox.setManaged(isDeadline);
        });

        Button saveBtn = new Button("Save Task");
        Button cancelBtn = new Button("Cancel");
        HBox btnBox = new HBox(10, saveBtn, cancelBtn);

        content.getChildren().addAll(
                title,
                new Label("Task Name:"), nameField,
                new Label("Duration (hours):"), durationSpinner,
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

            double durationHours = AppTimeReference.normalizeDurationHours(
                    durationSpinner.getValue() == null ? 1.0 : durationSpinner.getValue());
            int slotCount = AppTimeReference.hoursToSlots(durationHours);

            LocalDateTime nowDt = AppTimeReference.getCurrentDateTime();

            LocalTime clickedTime = null;
            if (fromGridClick) {
                clickedTime = AppTimeReference.slotToLocalTime(slotIndex);
                LocalDateTime clickDt = LocalDateTime.of(clickedDate, clickedTime);
                if (!clickDt.isAfter(nowDt)) {
                    showAlertPopup(scene, "Cannot create a task in the past.");
                    return;
                }
            }

            boolean isFixed = fixedRadio.isSelected();
            boolean isDeadline = deadlineRadio.isSelected();

            LocalDate startDate = null;
            LocalTime startTime = null;
            LocalDate deadlineDate = null;
            LocalTime deadlineTime = null;

            try {
                if (isFixed) {
                    if (fromGridClick) {
                        startDate = clickedDate;
                        startTime = clickedTime;
                    } else {
                        startDate = startDatePicker.getValue();
                        if (startDate == null) {
                            showAlertPopup(scene, "Please select a start date.");
                            return;
                        }
                        startTime = parseSlotCombo(startSlotBox.getValue());
                    }
                    if (!LocalDateTime.of(startDate, startTime).isAfter(nowDt)) {
                        showAlertPopup(scene, "Cannot create Fixed task in the past.");
                        return;
                    }
                } else if (isDeadline) {
                    deadlineDate = deadlineDatePicker.getValue();
                    if (deadlineDate == null) {
                        showAlertPopup(scene, "Please select a deadline date.");
                        return;
                    }
                    deadlineTime = parseSlotCombo(deadlineSlotBox.getValue());
                    if (!LocalDateTime.of(deadlineDate, deadlineTime).isAfter(nowDt)) {
                        showAlertPopup(scene, "Cannot set deadline in the past.");
                        return;
                    }
                    if (fromGridClick) {
                        LocalDateTime start = LocalDateTime.of(clickedDate, clickedTime);
                        LocalDateTime finish = start.plusMinutes(
                                (long) slotCount * AppTimeReference.MINUTES_PER_SLOT);
                        LocalDateTime deadline = LocalDateTime.of(deadlineDate, deadlineTime);
                        if (finish.isAfter(deadline)) {
                            showAlertPopup(scene, "Cannot place deadline task after its deadline.");
                            return;
                        }
                    }
                }
            } catch (Exception ex) {
                showAlertPopup(scene, "Invalid date or time.");
                return;
            }

            LocalDate placeDate = null;
            LocalTime placeTime = null;

            if (fromGridClick) {
                placeDate = clickedDate;
                placeTime = clickedTime;
            } else if (isFixed && startDate != null && startTime != null) {
                placeDate = startDate;
                placeTime = startTime;
            }

            if (placeDate != null && placeTime != null) {
                LocalDate column = AppTimeReference.columnDateFor(placeDate, placeTime);
                int placeSlot = AppTimeReference.localTimeToSlot(placeTime);
                if (placeSlot + slotCount > AppTimeReference.SLOTS_PER_DAY) {
                    showAlertPopup(scene, "Task exceeds daily time range.");
                    return;
                }
                system.getGrid().putIfAbsent(column, new Day(system.getBlockedTime()));
                Day day = system.getGrid().get(column);
                for (int i = 0; i < slotCount; i++) {
                    if (day.returnDayGrid()[placeSlot + i] != null) {
                        showAlertPopup(scene, "Time conflict with another task.");
                        return;
                    }
                }
            }

            int state = isFixed ? 1 : (isDeadline ? 2 : 3);

            FetchPopOutTask pop = new FetchPopOutTask(
                    name, durationHours, state,
                    startDate, startTime,
                    deadlineDate, deadlineTime,
                    fromGridClick ? clickedDate : null,
                    fromGridClick ? clickedTime : null
            );

            system.fetchPopupTask(pop);

            if (!system.command("addTask")) {
                if (isDeadline) {
                    showAlertPopup(scene,
                            "Could not fit this deadline task before its deadline.\nTask was not created.");
                } else if (flexibleRadio.isSelected()) {
                    showAlertPopup(scene,
                            "Could not place this flexible task.\nTask was not created.");
                } else {
                    showAlertPopup(scene, "Failed to create task.");
                }
                return;
            }

            if (onChanged != null) onChanged.run();
            closeEverything(scene);
        });

        cancelBtn.setOnAction(e -> closeEverything(scene));
        setupEscHandler(scene);
    }

    public void showAlertPopup(Scene scene, String message) {
        if (alertIsOpen) return;
        if (escHandler != null) scene.removeEventFilter(KeyEvent.KEY_PRESSED, escHandler);

        StackPane overlay = GuiHelper.createDimmedOverlay(root);
        VBox box = GuiHelper.createWhiteBox();

        Label msg = new Label(message);
        msg.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        msg.setWrapText(true);
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
        Label msg = new Label("Are you sure? Info will be discarded.");
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

    private ComboBox<String> createSlotCombo(int defaultSlotIndex) {
        ComboBox<String> box = new ComboBox<>();
        for (int s = 0; s < AppTimeReference.SLOTS_PER_DAY; s++) {
            box.getItems().add(clockLabel(s));
        }
        if (defaultSlotIndex < 0) defaultSlotIndex = 0;
        if (defaultSlotIndex >= AppTimeReference.SLOTS_PER_DAY) {
            defaultSlotIndex = AppTimeReference.SLOTS_PER_DAY - 1;
        }
        box.setValue(clockLabel(defaultSlotIndex));
        box.setPrefWidth(90);
        return box;
    }

    private static String clockLabel(int slotIndex) {
        LocalTime t = AppTimeReference.slotToLocalTime(slotIndex);
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private LocalTime parseSlotCombo(String value) {
        String raw = value == null ? "" : value.replace("+", "").trim();
        String[] p = raw.split(":");
        return LocalTime.of(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
    }
}