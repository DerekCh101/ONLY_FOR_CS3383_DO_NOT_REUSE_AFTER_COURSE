package org.example.GUI;

import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import org.example.*;

import java.time.LocalDate;
import java.time.LocalTime;

public class TaskDialogs {

    private final StackPane root;
    private final TaskManagementSystem system;
    private final Runnable onChanged;

    private boolean popupIsOpen = false;
    private boolean areYouSureIsOpen = false;
    private boolean alertIsOpen = false;
    private EventHandler<KeyEvent> escHandler;

    private StackPane currentDimmedOverlay;
    private Pane currentPopup;
    private StackPane currentConfirmOverlay;

    public TaskDialogs(StackPane root, TaskManagementSystem system, Runnable onChanged) {
        this.root = root;
        this.system = system;
        this.onChanged = onChanged;
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
            extraLabel = new Label("Fixed Time: " + task.getStartingDate() + " " + task.getStartingTime());
        } else if (task.hasDeadline()) {
            extraLabel = new Label("Deadline: " + task.getDeadLineDate() + " " + task.getDeadLineTime());
        } else {
            extraLabel = new Label("Flexible task");
        }

        String placedText = (task.getPlacedDate() != null && task.getPlacedTime() != null)
                ? task.getPlacedDate() + " " + task.getPlacedTime()
                : date + " " + AppTimeReference.formatSlotLabel(slotIndex);
        Label placedLabel = new Label("Currently placed: " + placedText);

        Button clearBtn = new Button("Clear Task");
        Button closeBtn = new Button("Close");
        HBox buttonBox = new HBox(12, clearBtn, closeBtn);

        content.getChildren().addAll(title, nameLabel, typeLabel, durationLabel, extraLabel, placedLabel, buttonBox);
        currentPopup.getChildren().add(content);
        popupIsOpen = true;

        clearBtn.setOnAction(e -> {
            system.fetchPopupTask(new FetchPopOutTask(task));
            system.command("clearTask");
            closeEverything(scene);
            if (onChanged != null) onChanged.run();
        });

        closeBtn.setOnAction(e -> closeEverything(scene));
        setupEscHandler(scene);
    }

    public void showTaskPopup(boolean fromGridClick, LocalDate clickedDate, int slotIndex) {
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

            LocalDate today = AppTimeReference.getCurrentDate();
            LocalTime now = AppTimeReference.getCurrentTime();

            LocalTime clickedTime = null;
            if (fromGridClick) {
                clickedTime = AppTimeReference.slotToLocalTime(slotIndex);
                if (clickedDate.isBefore(today)
                        || (clickedDate.equals(today)
                        && (clickedTime.isBefore(now) || clickedTime.equals(now)))) {
                    showAlertPopup(scene, "Cannot create a task in the past.");
                    return;
                }
            }

            boolean isFixed = fixedRadio.isSelected();
            boolean isDeadline = deadlineRadio.isSelected();
            boolean isFlexible = flexibleRadio.isSelected();

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
                    if (startDate.isBefore(today)
                            || (startDate.equals(today) && startTime.isBefore(now))) {
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
                    if (deadlineDate.isBefore(today)
                            || (deadlineDate.equals(today) && deadlineTime.isBefore(now))) {
                        showAlertPopup(scene, "Cannot set deadline in the past.");
                        return;
                    }
                    if (fromGridClick) {
                        long durationMin = (long) slotCount * AppTimeReference.MINUTES_PER_SLOT;
                        LocalTime finish = clickedTime.plusMinutes(durationMin);
                        boolean wrappedPastMidnight = finish.isBefore(clickedTime);

                        if (clickedDate.isAfter(deadlineDate)
                                || (clickedDate.equals(deadlineDate)
                                && (wrappedPastMidnight || finish.isAfter(deadlineTime)))) {
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
            int placeSlot = -1;

            if (fromGridClick) {
                placeDate = clickedDate;
                placeTime = clickedTime;
                placeSlot = slotIndex;
            } else if (isFixed && startDate != null && startTime != null) {
                placeDate = startDate;
                placeTime = startTime;
                placeSlot = AppTimeReference.localTimeToSlot(startTime);
            }

            if (placeDate != null && placeTime != null && placeSlot >= 0) {
                if (placeSlot + slotCount > AppTimeReference.SLOTS_PER_DAY) {
                    showAlertPopup(scene, "Task exceeds daily time range.");
                    return;
                }
                system.getGrid().putIfAbsent(placeDate, new Day(system.getBlockedTime()));
                Day day = system.getGrid().get(placeDate);
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
                } else if (isFlexible) {
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
            box.getItems().add(AppTimeReference.formatSlotLabel(s));
        }
        if (defaultSlotIndex < 0) defaultSlotIndex = 0;
        if (defaultSlotIndex >= AppTimeReference.SLOTS_PER_DAY) {
            defaultSlotIndex = AppTimeReference.SLOTS_PER_DAY - 1;
        }
        box.setValue(AppTimeReference.formatSlotLabel(defaultSlotIndex));
        box.setPrefWidth(90);
        return box;
    }

    private LocalTime parseSlotCombo(String value) {
        String[] p = value.split(":");
        return LocalTime.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
    }
}