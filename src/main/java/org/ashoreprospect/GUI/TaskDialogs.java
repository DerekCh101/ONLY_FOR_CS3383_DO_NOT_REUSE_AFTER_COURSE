/** Add/edit task and blocked-time popups. */
package org.ashoreprospect.GUI;

import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import org.ashoreprospect.*;
import org.ashoreprospect.net.Api;

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
        CheckBox exceptionBox = new CheckBox("Exception (free this date / weekdays)");
        DatePicker fromPicker = new DatePicker(AppTimeReference.getCurrentDate());
        DatePicker toPicker = new DatePicker(AppTimeReference.getCurrentDate());
        fromPicker.disableProperty().bind(everyDay.selectedProperty().and(exceptionBox.selectedProperty().not()));
        toPicker.disableProperty().bind(everyDay.selectedProperty().and(exceptionBox.selectedProperty().not()));
        everyDay.selectedProperty().addListener((obs, was, now) ->
                exceptionBox.setText(now
                        ? "Exception (free this date / weekdays)"
                        : "Exception (free this range / weekdays)"));
        HBox whenRow = new HBox(8, everyDay, exceptionBox, new Label("From"), fromPicker, new Label("To"), toPicker);
        HBox weekRow = new HBox(6, new Label("Weekdays:"));
        CheckBox[] weekBoxes = new CheckBox[7];
        java.time.DayOfWeek[] dows = {
                java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.WEDNESDAY,
                java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY, java.time.DayOfWeek.SATURDAY,
                java.time.DayOfWeek.SUNDAY
        };
        String[] dowNames = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (int i = 0; i < 7; i++) {
            weekBoxes[i] = new CheckBox(dowNames[i]);
            weekRow.getChildren().add(weekBoxes[i]);
        }

        TextField nameField = new TextField();
        nameField.setPromptText("title (morning)");
        capField(nameField, 100);

        ComboBox<String> startBox = createSlotCombo(
                AppTimeReference.localTimeToSlot(LocalTime.of(6, 0)));
        ComboBox<String> endBox = createSlotCombo(
                AppTimeReference.localTimeToSlot(LocalTime.of(7, 0)));
        HBox timeRow = new HBox(8, new Label("Start"), startBox, new Label("End"), endBox);

        Button addBtn = new Button("Add");
        Button closeBtn = new Button("Close");
        HBox buttons = new HBox(10, addBtn, closeBtn);

        VBox listBox = new VBox(6);
        rebuildBlockedList(scene, listBox);
        ScrollPane listScroll = new ScrollPane(listBox);
        listScroll.setFitToWidth(true);
        listScroll.setPrefHeight(220);
        listScroll.setMaxHeight(220);

        addBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) name = "blocked";
            if (name.length() > 100) name = name.substring(0, 100);
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
            List<java.time.DayOfWeek> chosenDays = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                if (weekBoxes[i].isSelected()) chosenDays.add(dows[i]);
            }
            List<BlockedTime> rows = new ArrayList<>();
            if (isException && everyDay.isSelected() && !chosenDays.isEmpty()) {
                for (java.time.DayOfWeek d : chosenDays) {
                    BlockedTime row = BlockedTime.weekly(name, d, start, end);
                    row.setUserId(Api.INSTANCE.getUserId());
                    row.setException(true);
                    rows.add(row);
                }
            } else if (!isException && everyDay.isSelected() && chosenDays.isEmpty()) {
                BlockedTime row = BlockedTime.daily(name, start, end);
                row.setUserId(Api.INSTANCE.getUserId());
                rows.add(row);
            } else if (!isException && chosenDays.size() > 0 && (everyDay.isSelected() || fromPicker.getValue() == null)) {
                for (java.time.DayOfWeek d : chosenDays) {
                    BlockedTime row = BlockedTime.weekly(name, d, start, end);
                    row.setUserId(Api.INSTANCE.getUserId());
                    rows.add(row);
                }
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
                for (BlockedTime row : BlockedTime.dailyRange(name, from, to, start, end)) {
                    if (!chosenDays.isEmpty() && !chosenDays.contains(row.getSpecificDate().getDayOfWeek())) {
                        continue;
                    }
                    row.setUserId(Api.INSTANCE.getUserId());
                    row.setException(isException);
                    rows.add(row);
                }
                if (rows.isEmpty()) {
                    showAlertPopup(scene, "No dates in that range match the weekdays.");
                    return;
                }
            }

            java.time.LocalDateTime now = AppTimeReference.getCurrentDateTime();
            for (BlockedTime row : rows) {
                if (row.getSpecificDate() == null) continue;
                java.time.LocalTime until = row.getEndTime() == null
                        ? java.time.LocalTime.MAX : row.getEndTime();
                if (!java.time.LocalDateTime.of(row.getSpecificDate(), until).isAfter(now)) {
                    showAlertPopup(scene, "Cannot add a blocked time that has already ended.");
                    return;
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
                new Label("New block:"),
                nameField,
                whenRow,
                weekRow,
                timeRow,
                buttons,
                new Label("Existing:"),
                listScroll
        );
        currentPopup.getChildren().add(content);
        GuiHelper.paintInk(content);
        popupIsOpen = true;
        setupEscHandler(scene);
    }

    private void rebuildBlockedList(Scene scene, VBox listBox) {
        listBox.getChildren().clear();
        BlockedTimePeriod holder = system.getBlockedTime();
        if (holder == null || holder.blockedPeriod == null || holder.blockedPeriod.isEmpty()) {
            listBox.getChildren().add(new Label("(none)"));
            GuiHelper.paintInk(listBox);
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
                when = "exception " + (b.getWeekday() != null ? b.getWeekday() : b.getSpecificDate());
            } else if (b.isDaily()) {
                when = "every day";
            } else if (b.getWeekday() != null) {
                when = String.valueOf(b.getWeekday());
            } else {
                when = String.valueOf(b.getSpecificDate());
            }
            String label = (b.getTitle() == null ? "blocked" : b.getTitle())
                    + "  " + when + "  " + b.getStartTime() + "–" + b.getEndTime();
            Button del = new Button("Delete");
            HBox row = new HBox(8, new Label(label), del);
            row.setAlignment(Pos.CENTER_LEFT);
            del.setOnAction(ev -> {
                String name = b.getTitle() == null || b.getTitle().isBlank() ? "this blocked time" : b.getTitle();
                askConfirm(scene, "Delete blocked time “" + name + "”?", "Delete", () -> {
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
            });
            listBox.getChildren().add(row);
        }
        GuiHelper.paintInk(listBox);
    }

    public void showUnscheduledPopup() {
        Scene scene = root.getScene();
        removeMainEscHandler(scene);
        currentDimmedOverlay = GuiHelper.createDimmedOverlay(root);
        root.getChildren().add(currentDimmedOverlay);
        currentPopup = new Pane();
        currentPopup.setPrefSize(480, 420);
        GuiHelper.centerInParentOffset(currentPopup, currentDimmedOverlay, 0.7, 0.7);
        root.getChildren().add(currentPopup);

        VBox content = GuiHelper.createWhiteBox();
        Label title = new Label("Unscheduled tasks");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");
        List<Task> rows = new ArrayList<>();
        try {
            for (Task t : system.getTaskRepo().fetchUnscheduledTasks()) {
                if (t != null && t.canGoUnscheduled()) rows.add(t);
            }
        } catch (SQLException e) {
            rows.addAll(system.getUnscheduledTasks());
        }
        if (rows.isEmpty()) {
            content.getChildren().addAll(title, new Label("No unscheduled task at the moment."),
                    closeButton(scene));
            currentPopup.getChildren().add(content);
            GuiHelper.paintInk(content);
            popupIsOpen = true;
            setupEscHandler(scene);
            return;
        }
        VBox list = new VBox(6);
        for (Task t : rows) {
            String kind = t.hasDeadline() ? "Deadline" : "Flexible";
            Button open = new Button(t.getTaskName() + "  ·  " + kind
                    + "  ·  " + t.getDurationHours() + "h");
            open.setMaxWidth(Double.MAX_VALUE);
            open.setOnAction(e -> {
                closeEverything(scene);
                showUnscheduledTaskActions(t);
            });
            list.getChildren().add(open);
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(260);
        content.getChildren().addAll(title, scroll, closeButton(scene));
        currentPopup.getChildren().add(content);
        GuiHelper.paintInk(content);
        popupIsOpen = true;
        setupEscHandler(scene);
    }

    private void showUnscheduledTaskActions(Task task) {
        Scene scene = root.getScene();
        removeMainEscHandler(scene);
        currentDimmedOverlay = GuiHelper.createDimmedOverlay(root);
        root.getChildren().add(currentDimmedOverlay);
        currentPopup = new Pane();
        currentPopup.setPrefSize(440, 360);
        GuiHelper.centerInParentOffset(currentPopup, currentDimmedOverlay, 0.7, 0.7);
        root.getChildren().add(currentPopup);
        VBox content = GuiHelper.createWhiteBox();
        content.getChildren().add(new Label(task.getTaskName()));
        Spinner<Double> dur = new Spinner<>();
        dur.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.5, 24.0, Math.max(0.5, task.getDurationHours()), 0.5));
        dur.setEditable(true);
        GuiHelper.styleSpinner(dur);
        DatePicker deadlinePick = new DatePicker(task.getDeadLineDate());
        ComboBox<String> deadlineSlot = createSlotCombo(
                task.getDeadLineTime() == null
                        ? AppTimeReference.localTimeToSlot(LocalTime.of(18, 0))
                        : AppTimeReference.localTimeToSlot(task.getDeadLineTime()));
        Button retry = new Button("Resize / update and try again");
        Button delete = new Button("Delete completely");
        Button close = closeButton(scene);
        retry.setOnAction(e -> {
            org.ashoreprospect.Command.EditTask.Snapshot next =
                    org.ashoreprospect.Command.EditTask.Snapshot.of(task);
            next.durationHours = AppTimeReference.normalizeDurationHours(
                    dur.getValue() == null ? task.getDurationHours() : dur.getValue());
            if (task.hasDeadline()) {
                next.deadlineDate = deadlinePick.getValue();
                try {
                    next.deadlineTime = parseSlotCombo(deadlineSlot.getValue());
                } catch (Exception ex) {
                    showAlertPopup(scene, "Invalid deadline.");
                    return;
                }
            }
            try {
                new org.ashoreprospect.Command.EditTask(task, next, true).execute();
            } catch (SQLException ex) {
                showAlertPopup(scene, "Could not update: " + ex.getMessage());
                return;
            }
            closeEverything(scene);
            if (onChanged != null) onChanged.run();
        });
        delete.setOnAction(e -> {
            String name = task.getTaskName() == null || task.getTaskName().isBlank()
                    ? "this task" : task.getTaskName();
            askConfirm(scene, "Delete “" + name + "”?", "Delete", () -> {
                system.fetchPopupTask(new FetchPopOutTask(task));
                system.command("clearTask");
                closeEverything(scene);
                if (onChanged != null) onChanged.run();
            });
        });
        content.getChildren().addAll(
                new Label("Duration (hours)"), dur
        );
        if (task.hasDeadline()) {
            content.getChildren().addAll(new Label("Deadline"), new HBox(8, deadlinePick, deadlineSlot));
        }
        content.getChildren().addAll(retry, delete, close);
        currentPopup.getChildren().add(content);
        GuiHelper.paintInk(content);
        popupIsOpen = true;
        setupEscHandler(scene);
    }

    private Button closeButton(Scene scene) {
        Button close = new Button("Close");
        close.setOnAction(e -> closeEverything(scene));
        return close;
    }

    public void showTaskInfoPopup(Task task, LocalDate date, int slotIndex) {
        Scene scene = root.getScene();
        removeMainEscHandler(scene);

        currentDimmedOverlay = GuiHelper.createDimmedOverlay(root);
        root.getChildren().add(currentDimmedOverlay);

        currentPopup = new Pane();
        currentPopup.setPrefSize(440, 460);
        GuiHelper.centerInParentOffset(currentPopup, currentDimmedOverlay, 0.7, 0.7);
        root.getChildren().add(currentPopup);

        VBox content = GuiHelper.createWhiteBox();

        Label title = new Label("Task Information");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        String typeText = task.isFixedTime() ? "Fixed" :
                task.hasDeadline() ? "Deadline" : "Flexible";

        TextField nameField = new TextField(task.getTaskName());
        capField(nameField, 100);
        Label typeLabel = new Label("Type: " + typeText);
        Spinner<Double> durationSpinner = new Spinner<>();
        durationSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.5, 24.0, Math.max(0.5, task.getDurationHours()), 0.5));
        durationSpinner.setEditable(true);
        durationSpinner.setPrefWidth(100);
        GuiHelper.styleSpinner(durationSpinner);

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

        DatePicker deadlinePick = new DatePicker(task.getDeadLineDate());
        ComboBox<String> deadlineSlot = createSlotCombo(
                task.getDeadLineTime() == null
                        ? AppTimeReference.localTimeToSlot(LocalTime.of(18, 0))
                        : AppTimeReference.localTimeToSlot(task.getDeadLineTime()));
        VBox deadlineEdit = new VBox(6, new Label("Deadline:"), new HBox(8, deadlinePick, deadlineSlot));
        deadlineEdit.setVisible(task.hasDeadline());
        deadlineEdit.setManaged(task.hasDeadline());

        CheckBox finishBtn = new CheckBox();
        finishBtn.setSelected(task.getWorkflow() == Workflow.FINISHED);
        finishBtn.setText(finishBtn.isSelected()?"Mark unfinish":"Mark finished");
        Button saveBtn = new Button("Save changes");
        Button clearBtn = new Button("Clear Task");
        Button closeBtn = new Button("Close");

        HBox buttonBox = new HBox(12, finishBtn, saveBtn, clearBtn, closeBtn);

        content.getChildren().addAll(title, new Label("Name"), nameField, typeLabel,
                new Label("Duration (hours)"), durationSpinner, extraLabel, placedLabel, deadlineEdit, buttonBox);
        currentPopup.getChildren().add(content);
        GuiHelper.paintInk(content);
        popupIsOpen = true;

        saveBtn.setOnAction(e -> {
            org.ashoreprospect.Command.EditTask.Snapshot next =
                    org.ashoreprospect.Command.EditTask.Snapshot.of(task);
            next.name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (next.name.isEmpty()) {
                showAlertPopup(scene, "Task name cannot be empty.");
                return;
            }
            next.durationHours = AppTimeReference.normalizeDurationHours(
                    durationSpinner.getValue() == null ? task.getDurationHours() : durationSpinner.getValue());
            if (task.hasDeadline()) {
                next.deadlineDate = deadlinePick.getValue();
                try {
                    next.deadlineTime = parseSlotCombo(deadlineSlot.getValue());
                } catch (Exception ex) {
                    showAlertPopup(scene, "Invalid deadline time.");
                    return;
                }
            }
            try {
                new org.ashoreprospect.Command.EditTask(task, next, task.canGoUnscheduled()).execute();
            } catch (SQLException ex) {
                showAlertPopup(scene, "Could not save: " + ex.getMessage());
                return;
            }
            closeEverything(scene);
            if (onChanged != null) onChanged.run();
        });

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
            String name = task.getTaskName() == null || task.getTaskName().isBlank()
                    ? "this task" : task.getTaskName();
            askConfirm(scene, "Delete “" + name + "”?", "Delete", () -> {
                system.fetchPopupTask(new FetchPopOutTask(task));
                system.command("clearTask");
                closeEverything(scene);
                if (onChanged != null) onChanged.run();
            });
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

        LocalTime gridTime = fromGridClick ? AppTimeReference.slotToLocalTime(slotIndex) : null;
        Label title = new Label(fromGridClick
                ? "New Task @ " + clickedDate + " " + gridTime
                : "Add New Task");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        Label slotHint = fromGridClick
                ? new Label("Grid slot: " + clickedDate + "  " + gridTime)
                : null;

        TextField nameField = new TextField();
        nameField.setPromptText("Task name");
        capField(nameField, 100);
        TextField categoryField = new TextField();
        categoryField.setPromptText("category (e.g. study)");
        capField(categoryField, 80);
        TextField subcatField = new TextField();
        subcatField.setPromptText("subcategory (e.g. revision)");
        capField(subcatField, 80);
        javafx.scene.control.TextArea descField = new javafx.scene.control.TextArea();
        descField.setPromptText("what to do exactly");
        descField.setPrefRowCount(3);
        descField.setWrapText(true);
        capArea(descField, 8000);
        javafx.scene.control.CheckBox descDoneBox = new javafx.scene.control.CheckBox("Description done");

        Spinner<Double> durationSpinner = new Spinner<>();
        SpinnerValueFactory.DoubleSpinnerValueFactory durationFactory =
                new SpinnerValueFactory.DoubleSpinnerValueFactory(0.5, 24.0, 1.0, 0.5);
        durationSpinner.setValueFactory(durationFactory);
        durationSpinner.setEditable(true);
        durationSpinner.setPrefWidth(100);
        GuiHelper.styleSpinner(durationSpinner);

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
                title
        );
        if (slotHint != null) content.getChildren().add(slotHint);
        content.getChildren().addAll(
                new Label("Task Name:"), nameField,
                new Label("Category / subcategory:"),
                new HBox(8, categoryField, subcatField),
                new Label("Description:"), descField, descDoneBox,
                new Label("Duration (hours):"), durationSpinner,
                new Label("Type:"), typeBox,
                fixedBox,
                deadlineBox,
                btnBox
        );

        currentPopup.getChildren().add(content);
        GuiHelper.paintInk(content);
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
                    java.time.LocalDateTime dt = AppTimeReference.dateTimeForSlot(column, placeSlot + i);
                    if (system.getBlockedTime() != null
                            && system.getBlockedTime().isBlocked(dt.toLocalDate(), dt.toLocalTime())) {
                        showAlertPopup(scene, "That slot is a blocked time.");
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
            pop.setCategory(categoryField.getText());
            pop.setSubcategory(subcatField.getText());
            pop.setDescription(descField.getText());
            pop.setDescriptionDone(descDoneBox.isSelected());

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

    private void askConfirm(Scene scene, String message, String yesText, Runnable onYes) {
        if (alertIsOpen) return;
        if (escHandler != null) scene.removeEventFilter(KeyEvent.KEY_PRESSED, escHandler);

        StackPane overlay = GuiHelper.createDimmedOverlay(root);
        VBox box = GuiHelper.createWhiteBox();
        Label msg = new Label(message);
        msg.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        msg.setWrapText(true);
        Button yes = new Button(yesText == null || yesText.isBlank() ? "Delete" : yesText);
        Button no = new Button("Cancel");
        HBox buttons = new HBox(10, yes, no);
        box.getChildren().addAll(msg, buttons);
        GuiHelper.paintInk(box);
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

        no.setOnAction(e -> dismiss.run());
        yes.setOnAction(e -> {
            dismiss.run();
            if (onYes != null) onYes.run();
        });
        holder[0] = ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                ev.consume();
                dismiss.run();
            }
        };
        scene.addEventFilter(KeyEvent.KEY_PRESSED, holder[0]);
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
        GuiHelper.paintInk(box);
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
        GuiHelper.paintInk(box);
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

    private static void capField(TextField field, int max) {
        field.textProperty().addListener((o, a, b) -> {
            if (b != null && b.length() > max) field.setText(b.substring(0, max));
        });
    }

    private static void capArea(TextArea area, int max) {
        area.textProperty().addListener((o, a, b) -> {
            if (b != null && b.length() > max) area.setText(b.substring(0, max));
        });
    }
}