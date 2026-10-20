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

    private boolean addBuilt;
    private boolean addFromGrid;
    private LocalDate addClickedDate;
    private int addSlotIndex = -1;
    private StackPane addDim;
    private Pane addPopup;
    private Label addTitle;
    private Label addSlotHint;
    private TextField addName;
    private TextArea addDesc;
    private CheckBox addDescDone;
    private Spinner<Double> addDur;
    private Spinner<Integer> addMin;
    private CheckBox addMinMode;
    private RadioButton addFixed;
    private RadioButton addDeadline;
    private RadioButton addFlexible;
    private ToggleGroup addTypeGroup;
    private DatePicker addStartDate;
    private ComboBox<String> addStartSlot;
    private VBox addFixedBox;
    private DatePicker addDeadlineDate;
    private ComboBox<String> addDeadlineSlot;
    private VBox addDeadlineBox;
    private CheckBox addEndMode;
    private ComboBox<String> addEndSlot;
    private static javafx.collections.ObservableList<String> SLOT_CHOICES;

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
        CheckBox rangeBox = new CheckBox("Range");
        CheckBox exceptionBox = new CheckBox("Exception (from every day)");
        DatePicker fromPicker = new DatePicker(AppTimeReference.getCurrentDate());
        DatePicker toPicker = new DatePicker(AppTimeReference.getCurrentDate());
        Label fromLab = new Label("Date");
        Label toLab = new Label("To");
        HBox dateRow = new HBox(8, fromLab, fromPicker, toLab, toPicker);
        HBox whenRow = new HBox(8, everyDay, rangeBox, exceptionBox);
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

        Runnable syncBlockedFields = () -> {
            boolean every = everyDay.isSelected();
            boolean range = rangeBox.isSelected();
            boolean ex = exceptionBox.isSelected();
            exceptionBox.setText(every
                    ? "Exception (from every day)"
                    : range ? "Exception (from this range)"
                    : "Exception (this date / weekdays)");
            weekRow.setVisible(ex);
            weekRow.setManaged(ex);
            boolean showFrom = !every || ex;
            boolean showTo = range || (every && ex);
            if (every && !ex) {
                fromPicker.setValue(null);
                toPicker.setValue(null);
                fromPicker.getEditor().clear();
                toPicker.getEditor().clear();
            } else if (!(every && ex && !range)) {
                LocalDate today = AppTimeReference.getCurrentDate();
                if (fromPicker.getValue() == null) fromPicker.setValue(today);
                if (showTo && toPicker.getValue() == null) toPicker.setValue(fromPicker.getValue());
            }
            fromLab.setText(showTo ? "From" : "Date");
            fromLab.setVisible(showFrom);
            fromLab.setManaged(showFrom);
            fromPicker.setVisible(showFrom);
            fromPicker.setManaged(showFrom);
            toLab.setVisible(showTo);
            toLab.setManaged(showTo);
            toPicker.setVisible(showTo);
            toPicker.setManaged(showTo);
            dateRow.setVisible(showFrom);
            dateRow.setManaged(showFrom);
        };
        everyDay.selectedProperty().addListener((o, was, now) -> {
            if (now) rangeBox.setSelected(false);
            syncBlockedFields.run();
        });
        rangeBox.selectedProperty().addListener((o, was, now) -> {
            if (now) everyDay.setSelected(false);
            syncBlockedFields.run();
        });
        exceptionBox.selectedProperty().addListener((o, a, b) -> syncBlockedFields.run());
        syncBlockedFields.run();

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

            boolean every = everyDay.isSelected();
            boolean range = rangeBox.isSelected();
            boolean isException = exceptionBox.isSelected();
            List<java.time.DayOfWeek> chosenDays = new ArrayList<>();
            if (isException) {
                for (int i = 0; i < 7; i++) {
                    if (weekBoxes[i].isSelected()) chosenDays.add(dows[i]);
                }
            }
            List<BlockedTime> rows = new ArrayList<>();
            LocalDate from = fromPicker.getValue();
            LocalDate to = (range || (every && isException)) && toPicker.getValue() != null
                    ? toPicker.getValue() : from;
            if (isException) {
                if (range && from == null) {
                    showAlertPopup(scene, "Pick dates for this range.");
                    return;
                }
                if (from == null && chosenDays.isEmpty()) {
                    showAlertPopup(scene, "Pick weekdays or a date for the exception.");
                    return;
                }
                if (from != null) {
                    if (to == null) to = from;
                    if (to.isBefore(from)) {
                        showAlertPopup(scene, "End date is before start date.");
                        return;
                    }
                    for (BlockedTime row : BlockedTime.dailyRange(name, from, to, start, end)) {
                        if (!chosenDays.isEmpty()
                                && !chosenDays.contains(row.getSpecificDate().getDayOfWeek())) {
                            continue;
                        }
                        row.setUserId(Api.INSTANCE.getUserId());
                        row.setException(true);
                        rows.add(row);
                    }
                    if (rows.isEmpty()) {
                        showAlertPopup(scene, "No dates in that range match the weekdays.");
                        return;
                    }
                } else {
                    for (java.time.DayOfWeek d : chosenDays) {
                        BlockedTime row = BlockedTime.weekly(name, d, start, end);
                        row.setUserId(Api.INSTANCE.getUserId());
                        row.setException(true);
                        rows.add(row);
                    }
                }
            } else if (every) {
                BlockedTime row = BlockedTime.daily(name, start, end);
                row.setUserId(Api.INSTANCE.getUserId());
                rows.add(row);
            } else {
                if (from == null) {
                    showAlertPopup(scene, "Pick a date.");
                    return;
                }
                if (to == null) to = from;
                if (to.isBefore(from)) {
                    showAlertPopup(scene, "End date is before start date.");
                    return;
                }
                for (BlockedTime row : BlockedTime.dailyRange(name, from, to, start, end)) {
                    row.setUserId(Api.INSTANCE.getUserId());
                    rows.add(row);
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
                dateRow,
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
        Label title = new Label("View task list");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");
        List<Task> rows = new ArrayList<>();
        try {
            for (Task t : system.getTaskRepo().fetchUnscheduledTasks()) {
                if (t != null && !t.isQuick() && t.canGoUnscheduled()) rows.add(t);
            }
        } catch (SQLException e) {
            for (Task t : system.getUnscheduledTasks()) {
                if (t != null && !t.isQuick() && t.canGoUnscheduled()) rows.add(t);
            }
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

    public void showTaskInfoPopup(Task raw, LocalDate date, int slotIndex) {
        final Task task = system == null ? raw : system.mainTask(raw);
        if (task == null || task.isQuick()) return;
        Scene scene = root.getScene();
        removeMainEscHandler(scene);

        currentDimmedOverlay = GuiHelper.createDimmedOverlay(root);
        root.getChildren().add(currentDimmedOverlay);

        currentPopup = new Pane();
        currentPopup.setPrefSize(480, 620);
        GuiHelper.centerInParentOffset(currentPopup, currentDimmedOverlay, 0.7, 0.45);
        root.getChildren().add(currentPopup);

        VBox content = GuiHelper.createWhiteBox();

        Label title = new Label("Task Information");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        String typeText = task.isFixedTime() ? "Fixed" :
                task.hasDeadline() ? "Deadline" : "Flexible";

        TextField nameField = new TextField(task.getTaskName());
        capField(nameField, 100);
        Label typeLabel = new Label("Type: " + typeText);
        int storedMins = Math.max(1, (int) Math.round(task.getDurationMinutes()));
        Spinner<Double> durationSpinner = new Spinner<>();
        durationSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                1.0 / 60.0, 24.0, Math.max(1.0 / 60.0, storedMins / 60.0), 0.5));
        durationSpinner.setEditable(true);
        durationSpinner.setPrefWidth(100);
        GuiHelper.styleSpinner(durationSpinner);
        Spinner<Integer> minuteSpinner = new Spinner<>();
        minuteSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 24 * 60, storedMins, 1));
        minuteSpinner.setEditable(true);
        minuteSpinner.setPrefWidth(90);
        GuiHelper.styleSpinner(minuteSpinner);
        CheckBox minuteMode = new CheckBox("Minute duration");
        boolean startInMinutes = storedMins % 30 != 0;
        minuteMode.setSelected(startInMinutes);
        minuteSpinner.setVisible(startInMinutes);
        minuteSpinner.setManaged(startInMinutes);
        final boolean[] linkingInfo = { false };
        durationSpinner.valueProperty().addListener((o, a, b) -> {
            if (linkingInfo[0]) return;
            linkingInfo[0] = true;
            int mins = (int) Math.round((b == null ? 1.0 : b) * 60.0);
            minuteSpinner.getValueFactory().setValue(Math.max(1, mins));
            linkingInfo[0] = false;
        });
        minuteSpinner.valueProperty().addListener((o, a, b) -> {
            if (linkingInfo[0]) return;
            linkingInfo[0] = true;
            durationSpinner.getValueFactory().setValue((b == null ? 60 : b) / 60.0);
            minuteMode.setSelected(true);
            minuteSpinner.setVisible(true);
            minuteSpinner.setManaged(true);
            linkingInfo[0] = false;
        });
        minuteMode.selectedProperty().addListener((o, a, on) -> {
            minuteSpinner.setVisible(on);
            minuteSpinner.setManaged(on);
        });
        ComboBox<String> placedBox = createSlotCombo(
                task.getPlacedTime() == null ? slotIndex : AppTimeReference.localTimeToSlot(task.getPlacedTime()));
        if (task.getPlacedTime() != null) {
            placedBox.setValue(String.format("%02d:%02d",
                    task.getPlacedTime().getHour(), task.getPlacedTime().getMinute()));
        }

        Label extraLabel;
        if (task.isFixedTime()) {
            extraLabel = new Label("Fixed Time: " + (task.getStartingDate()==null?task.getPlacedDate().toString():task.getStartingDate()) + " " +
                    (task.getStartingTime()==null?task.getPlacedTime().toString():task.getStartingTime().toString()));
        } else if (task.hasDeadline()) {
            extraLabel = new Label("Deadline: " + task.getDeadLineDate() + " " + task.getDeadLineTime());
        } else {
            extraLabel = new Label("Flexible task");
        }

        Label placedLabel = new Label("Stored time (grid occupancy still rounds to 30 min):");

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
        Button quickBtn = new Button("Add another quick event");
        boolean canQuick = !task.isQuick() && task.getPlacedTime() != null && task.getPlacedDate() != null;
        quickBtn.setVisible(canQuick);
        quickBtn.setManaged(canQuick);

        HBox buttonBox = new HBox(12, finishBtn, saveBtn, clearBtn, closeBtn);

        CheckBox endMode = new CheckBox("End time");
        ComboBox<String> endBox = createSlotCombo(
                task.getPlacedTime() == null
                        ? slotIndex
                        : AppTimeReference.localTimeToSlot(task.getPlacedTime().plusMinutes(storedMins)));
        if (task.getPlacedTime() != null) {
            LocalTime endAt = task.getPlacedTime().plusMinutes(storedMins);
            endBox.setValue(String.format("%02d:%02d", endAt.getHour(), endAt.getMinute()));
        }
        endBox.setVisible(false);
        endBox.setManaged(false);
        endMode.selectedProperty().addListener((o, a, on) -> {
            endBox.setVisible(on);
            endBox.setManaged(on);
        });
        endBox.valueProperty().addListener((o, a, b) -> {
            if (linkingInfo[0] || !endMode.isSelected()) return;
            try {
                LocalTime start = parseSlotCombo(placedBox.getValue());
                LocalTime end = parseSlotCombo(b);
                int mins = minutesBetween(start, end);
                linkingInfo[0] = true;
                minuteSpinner.getValueFactory().setValue(mins);
                durationSpinner.getValueFactory().setValue(mins / 60.0);
                minuteMode.setSelected(mins % 30 != 0);
                minuteSpinner.setVisible(minuteMode.isSelected());
                minuteSpinner.setManaged(minuteMode.isSelected());
                linkingInfo[0] = false;
            } catch (Exception ignored) {
                linkingInfo[0] = false;
            }
        });

        VBox nestedBox = new VBox(4);
        Label nestedHead = new Label("Quick time events");
        nestedHead.setStyle("-fx-font-weight: bold;");
        nestedBox.getChildren().add(nestedHead);
        List<Task> nested = system == null ? List.of() : system.quicksOf(task);
        if (nested.isEmpty()) {
            Label empty = new Label("None on this task yet.");
            empty.setWrapText(true);
            nestedBox.getChildren().add(empty);
        } else {
            boolean parentDone = task.getWorkflow() == Workflow.FINISHED;
            for (Task q : nested) {
                if (q == null) continue;
                String clock = q.getPlacedTime() == null ? ""
                        : String.format("%02d:%02d  ", q.getPlacedTime().getHour(), q.getPlacedTime().getMinute());
                int mins = (int) Math.round(q.getDurationMinutes());
                boolean done = parentDone || q.getWorkflow() == Workflow.FINISHED;
                Label row = new Label((done ? "done  ·  " : "") + clock + q.getTaskName()
                        + "  ·  " + mins + " min");
                row.setWrapText(true);
                nestedBox.getChildren().add(row);
            }
        }
        nestedBox.setVisible(true);
        nestedBox.setManaged(true);

        content.getChildren().addAll(title, new Label("Name"), nameField, typeLabel,
                new Label("Duration (hours / optional minutes):"),
                new HBox(8, durationSpinner, minuteMode, minuteSpinner),
                new Label("Or set an end time instead of duration:"),
                new HBox(8, endMode, endBox),
                extraLabel, placedLabel, placedBox, deadlineEdit,
                nestedBox, quickBtn, buttonBox);
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
            int durationMinutes = minuteMode.isSelected()
                    ? (minuteSpinner.getValue() == null ? storedMins : minuteSpinner.getValue())
                    : AppTimeReference.hoursToMinutes(
                            durationSpinner.getValue() == null ? task.getDurationHours() : durationSpinner.getValue());
            try {
                LocalTime nextTime = parseSlotCombo(placedBox.getValue());
                if (task.getPlacedDate() != null && nextTime != null) {
                    task.setPlacedTime(nextTime);
                }
                if (endMode.isSelected()) {
                    LocalTime end = parseSlotCombo(endBox.getValue());
                    durationMinutes = minutesBetween(nextTime, end);
                }
            } catch (Exception ex) {
                showAlertPopup(scene, "Invalid stored or end time. Use HH:mm such as 18:31.");
                return;
            }
            next.durationHours = durationMinutes / 60.0;
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
        quickBtn.setOnAction(e -> {
            closeEverything(scene);
            showQuickEventPopup(task);
        });
        setupEscHandler(scene);
    }

    private void showQuickEventPopup(Task parent) {
        if (system != null) parent = system.mainTask(parent);
        if (parent == null || parent.isQuick()
                || parent.getPlacedDate() == null || parent.getPlacedTime() == null) {
            return;
        }
        final Task host = parent;
        Scene scene = root.getScene();
        removeMainEscHandler(scene);
        currentDimmedOverlay = GuiHelper.createDimmedOverlay(root);
        root.getChildren().add(currentDimmedOverlay);
        currentPopup = new Pane();
        currentPopup.setPrefSize(420, 360);
        GuiHelper.centerInParentOffset(currentPopup, currentDimmedOverlay, 0.7, 0.7);
        root.getChildren().add(currentPopup);
        VBox content = GuiHelper.createWhiteBox();
        Label title = new Label("During “"
                + (host.getTaskName() == null ? "task" : host.getTaskName()) + "”");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        title.setWrapText(true);
        Label hint = new Label("A timed bit of this task — not a separate task. Time must stay inside the parent.");
        hint.setWrapText(true);
        TextField nameField = new TextField();
        nameField.setPromptText("Name");
        capField(nameField, 100);
        LocalDateTime p0 = LocalDateTime.of(host.getPlacedDate(), host.getPlacedTime());
        int parentMins = Math.max(1, (int) Math.round(host.getDurationMinutes()));
        LocalDateTime p1 = p0.plusMinutes(parentMins);
        ComboBox<String> startBox = new ComboBox<>();
        for (LocalDateTime t = p0; t.isBefore(p1); t = t.plusMinutes(1)) {
            String label = String.format("%02d:%02d", t.getHour(), t.getMinute());
            if (!startBox.getItems().contains(label)) startBox.getItems().add(label);
        }
        if (startBox.getItems().isEmpty()) {
            startBox.getItems().add(String.format("%02d:%02d", p0.getHour(), p0.getMinute()));
        }
        startBox.setValue(startBox.getItems().get(0));
        startBox.setEditable(true);
        startBox.setPrefWidth(100);
        Spinner<Integer> minuteSpinner = new Spinner<>();
        minuteSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, parentMins, Math.min(15, parentMins), 1));
        minuteSpinner.setEditable(true);
        GuiHelper.styleSpinner(minuteSpinner);
        Runnable syncRemain = () -> {
            try {
                LocalDateTime q0 = instantInParent(p0, p1, parseSlotCombo(startBox.getValue()));
                int remain = q0 == null ? parentMins
                        : (int) java.time.Duration.between(q0, p1).toMinutes();
                if (remain < 1) remain = 1;
                int cur = minuteSpinner.getValue() == null ? 1 : minuteSpinner.getValue();
                minuteSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                        1, remain, Math.min(cur, remain), 1));
                GuiHelper.styleSpinner(minuteSpinner);
            } catch (Exception ignored) {
            }
        };
        startBox.valueProperty().addListener((o, a, b) -> syncRemain.run());
        Button saveBtn = new Button("Add");
        Button cancelBtn = new Button("Cancel");
        saveBtn.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isEmpty()) {
                showAlertPopup(scene, "Name cannot be empty.");
                return;
            }
            LocalTime start;
            try {
                start = parseSlotCombo(startBox.getValue());
            } catch (Exception ex) {
                showAlertPopup(scene, "Invalid time.");
                return;
            }
            LocalDateTime q0 = instantInParent(p0, p1, start);
            int mins = minuteSpinner.getValue() == null ? 15 : minuteSpinner.getValue();
            if (q0 == null || !AppTimeReference.rangeInside(
                    host.getPlacedDate(), host.getPlacedTime(), parentMins,
                    q0.toLocalDate(), q0.toLocalTime(), mins)) {
                showAlertPopup(scene, "Must stay inside the parent task’s time.");
                return;
            }
            int state = host.isFixedTime() ? 1 : host.hasDeadline() ? 2 : 3;
            FetchPopOutTask pop = new FetchPopOutTask(
                    name, mins / 60.0, state,
                    q0.toLocalDate(), q0.toLocalTime(),
                    null, null,
                    q0.toLocalDate(), q0.toLocalTime()
            );
            pop.setParentId(host.getID());
            system.fetchPopupTask(pop);
            if (!system.command("addTask")) {
                showAlertPopup(scene, "Must stay inside the parent task’s time.");
                return;
            }
            if (onChanged != null) onChanged.run();
            closeEverything(scene);
        });
        cancelBtn.setOnAction(e -> closeEverything(scene));
        content.getChildren().addAll(
                title, hint,
                new Label("Name"), nameField,
                new Label("Start (inside this task)"), startBox,
                new Label("Minutes"), minuteSpinner,
                new HBox(10, saveBtn, cancelBtn)
        );
        currentPopup.getChildren().add(content);
        GuiHelper.paintInk(content);
        popupIsOpen = true;
        setupEscHandler(scene);
    }

    public void prewarmAddTask() {
        if (addBuilt || root == null || root.getScene() == null) return;
        ensureAddTaskUi(root.getScene());
        hideAddTask();
        popupIsOpen = false;
    }

    public void showTaskPopup(boolean fromGridClick, LocalDate clickedDate, int slotIndex)  {
        if (popupIsOpen) return;
        popupIsOpen = true;
        Scene scene = root.getScene();
        removeMainEscHandler(scene);
        ensureAddTaskUi(scene);
        fillAddTaskContext(fromGridClick, clickedDate, slotIndex);
        addDim.setVisible(true);
        addDim.setManaged(true);
        addDim.setMouseTransparent(false);
        addPopup.setVisible(true);
        addPopup.setManaged(false);
        addPopup.setMouseTransparent(false);
        currentDimmedOverlay = addDim;
        currentPopup = addPopup;
        setupEscHandler(scene);
    }

    private void hideAddTask() {
        if (addDim != null) {
            addDim.setVisible(false);
            addDim.setManaged(false);
            addDim.setMouseTransparent(true);
        }
        if (addPopup != null) {
            addPopup.setVisible(false);
            addPopup.setManaged(false);
            addPopup.setMouseTransparent(true);
        }
    }

    private void fillAddTaskContext(boolean fromGridClick, LocalDate clickedDate, int slotIndex) {
        addFromGrid = fromGridClick;
        addClickedDate = clickedDate;
        addSlotIndex = slotIndex;
        LocalTime gridTime = fromGridClick ? AppTimeReference.slotToLocalTime(slotIndex) : null;
        addTitle.setText(fromGridClick
                ? "New Task @ " + clickedDate + " " + gridTime
                : "Add New Task");
        if (addSlotHint != null) {
            addSlotHint.setText(fromGridClick ? "Grid slot: " + clickedDate + "  " + gridTime : "");
            addSlotHint.setVisible(fromGridClick);
            addSlotHint.setManaged(fromGridClick);
        }
        addName.clear();
        addDesc.clear();
        addDescDone.setSelected(false);
        addMinMode.setSelected(false);
        addMin.setVisible(false);
        addMin.setManaged(false);
        addDur.getValueFactory().setValue(1.0);
        addMin.getValueFactory().setValue(60);
        addFixed.setSelected(true);
        addEndMode.setSelected(false);
        addEndSlot.setVisible(false);
        addEndSlot.setManaged(false);
        LocalDate day = clickedDate == null ? AppTimeReference.getCurrentDate() : clickedDate;
        addStartDate.setValue(day);
        addDeadlineDate.setValue(day);
        addStartDate.setDisable(fromGridClick);
        if (fromGridClick) {
            addStartSlot.setValue(clockLabel(slotIndex));
            addFixedBox.setVisible(true);
            addFixedBox.setManaged(true);
        } else {
            addStartSlot.setValue(clockLabel(AppTimeReference.localTimeToSlot(LocalTime.of(9, 0))));
            addFixedBox.setVisible(true);
            addFixedBox.setManaged(true);
        }
        addDeadlineSlot.setValue(clockLabel(AppTimeReference.localTimeToSlot(LocalTime.of(18, 0))));
        addDeadlineBox.setVisible(false);
        addDeadlineBox.setManaged(false);
        addEndSlot.setValue(clockLabel(AppTimeReference.localTimeToSlot(LocalTime.of(10, 0))));
    }

    private void ensureAddTaskUi(Scene scene) {
        if (addBuilt) return;

        addDim = GuiHelper.createDimmedOverlay(root);
        addDim.setVisible(false);
        addDim.setManaged(false);
        root.getChildren().add(addDim);

        addPopup = new Pane();
        addPopup.setPrefSize(500, 580);
        addPopup.setVisible(false);
        addPopup.setManaged(false);
        GuiHelper.centerInParentOffset(addPopup, addDim, 0.7, 0.45);
        root.getChildren().add(addPopup);

        VBox content = GuiHelper.createWhiteBox();

        addTitle = new Label("Add New Task");
        addTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        addSlotHint = new Label();
        addSlotHint.setVisible(false);
        addSlotHint.setManaged(false);

        addName = new TextField();
        addName.setPromptText("Task name");
        capField(addName, 100);
        addDesc = new TextArea();
        addDesc.setPromptText("what to do exactly");
        addDesc.setPrefRowCount(3);
        addDesc.setWrapText(true);
        capArea(addDesc, 8000);
        addDescDone = new CheckBox("Description done");

        addDur = new Spinner<>();
        addDur.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(1.0 / 60.0, 24.0, 1.0, 0.5));
        addDur.setEditable(true);
        addDur.setPrefWidth(100);
        GuiHelper.styleSpinner(addDur);
        addMin = new Spinner<>();
        addMin.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 24 * 60, 60, 1));
        addMin.setEditable(true);
        addMin.setPrefWidth(90);
        GuiHelper.styleSpinner(addMin);
        addMinMode = new CheckBox("Minute duration");
        addMin.setVisible(false);
        addMin.setManaged(false);
        final boolean[] linking = { false };
        addDur.valueProperty().addListener((o, a, b) -> {
            if (linking[0]) return;
            linking[0] = true;
            int mins = (int) Math.round((b == null ? 1.0 : b) * 60.0);
            addMin.getValueFactory().setValue(Math.max(1, mins));
            linking[0] = false;
        });
        addMin.valueProperty().addListener((o, a, b) -> {
            if (linking[0]) return;
            linking[0] = true;
            addDur.getValueFactory().setValue((b == null ? 60 : b) / 60.0);
            addMinMode.setSelected(true);
            addMin.setVisible(true);
            addMin.setManaged(true);
            linking[0] = false;
        });
        addMinMode.selectedProperty().addListener((o, a, on) -> {
            addMin.setVisible(on);
            addMin.setManaged(on);
        });
        addTypeGroup = new ToggleGroup();
        addFixed = new RadioButton("Fixed");
        addDeadline = new RadioButton("Deadline");
        addFlexible = new RadioButton("Flexible");
        addFixed.setToggleGroup(addTypeGroup);
        addDeadline.setToggleGroup(addTypeGroup);
        addFlexible.setToggleGroup(addTypeGroup);
        addFixed.setSelected(true);
        HBox typeBox = new HBox(12, addFixed, addDeadline, addFlexible);

        addStartDate = new DatePicker(AppTimeReference.getCurrentDate());
        addStartSlot = createSlotCombo(AppTimeReference.localTimeToSlot(LocalTime.of(9, 0)));
        HBox startRow = new HBox(10, addStartDate, addStartSlot);
        addFixedBox = new VBox(6, new Label("Start Date & Time:"), startRow);

        addDeadlineDate = new DatePicker(AppTimeReference.getCurrentDate());
        addDeadlineSlot = createSlotCombo(AppTimeReference.localTimeToSlot(LocalTime.of(18, 0)));
        HBox deadlineRow = new HBox(10, addDeadlineDate, addDeadlineSlot);
        addDeadlineBox = new VBox(6, new Label("Deadline Date & Time:"), deadlineRow);
        addDeadlineBox.setVisible(false);
        addDeadlineBox.setManaged(false);

        addEndMode = new CheckBox("End time");
        addEndSlot = createSlotCombo(AppTimeReference.localTimeToSlot(LocalTime.of(10, 0)));
        addEndSlot.setVisible(false);
        addEndSlot.setManaged(false);
        Runnable syncEndFromDuration = () -> {
            if (linking[0] || !addEndMode.isSelected()) return;
            try {
                LocalTime start = parseSlotCombo(addStartSlot.getValue());
                int mins = addMinMode.isSelected()
                        ? (addMin.getValue() == null ? 60 : addMin.getValue())
                        : AppTimeReference.hoursToMinutes(
                                addDur.getValue() == null ? 1.0 : addDur.getValue());
                LocalTime end = start.plusMinutes(mins);
                linking[0] = true;
                addEndSlot.setValue(String.format("%02d:%02d", end.getHour(), end.getMinute()));
                linking[0] = false;
            } catch (Exception ignored) {
                linking[0] = false;
            }
        };
        addEndMode.selectedProperty().addListener((o, a, on) -> {
            addEndSlot.setVisible(on);
            addEndSlot.setManaged(on);
            if (on) syncEndFromDuration.run();
        });
        addEndSlot.valueProperty().addListener((o, a, b) -> {
            if (linking[0] || !addEndMode.isSelected()) return;
            try {
                LocalTime start = parseSlotCombo(addStartSlot.getValue());
                LocalTime end = parseSlotCombo(b);
                int mins = minutesBetween(start, end);
                linking[0] = true;
                addMin.getValueFactory().setValue(mins);
                addDur.getValueFactory().setValue(mins / 60.0);
                addMinMode.setSelected(mins % 30 != 0);
                addMin.setVisible(addMinMode.isSelected());
                addMin.setManaged(addMinMode.isSelected());
                linking[0] = false;
            } catch (Exception ignored) {
                linking[0] = false;
            }
        });
        addDur.valueProperty().addListener((o, a, b) -> syncEndFromDuration.run());
        addMin.valueProperty().addListener((o, a, b) -> syncEndFromDuration.run());
        addStartSlot.valueProperty().addListener((o, a, b) -> syncEndFromDuration.run());

        addTypeGroup.selectedToggleProperty().addListener((o, old, neo) -> {
            boolean isFixed = neo == addFixed;
            boolean isDeadline = neo == addDeadline;
            if (!addFromGrid) {
                addFixedBox.setVisible(isFixed);
                addFixedBox.setManaged(isFixed);
            }
            addDeadlineBox.setVisible(isDeadline);
            addDeadlineBox.setManaged(isDeadline);
        });

        Button saveBtn = new Button("Save Task");
        Button cancelBtn = new Button("Cancel");
        HBox btnBox = new HBox(10, saveBtn, cancelBtn);

        content.getChildren().addAll(
                addTitle, addSlotHint,
                new Label("Task Name:"), addName,
                new Label("Description:"), addDesc, addDescDone,
                new Label("Duration (hours / optional minutes):"),
                new HBox(8, addDur, addMinMode, addMin),
                new Label("Or set an end time instead of duration:"),
                new HBox(8, addEndMode, addEndSlot),
                new Label("Type:"), typeBox,
                addFixedBox,
                addDeadlineBox,
                btnBox
        );

        addPopup.getChildren().add(content);
        GuiHelper.paintInk(content);
        addBuilt = true;

        saveBtn.setOnAction(e -> saveAddTask(scene));
        cancelBtn.setOnAction(e -> closeEverything(scene));
    }

    private void saveAddTask(Scene scene) {
            String name = addName.getText().trim();
            if (name.isEmpty()) {
                showAlertPopup(scene, "Task name cannot be empty.");
                return;
            }

            int durationMinutes = addMinMode.isSelected()
                    ? (addMin.getValue() == null ? 60 : addMin.getValue())
                    : AppTimeReference.hoursToMinutes(
                            AppTimeReference.normalizeDurationHours(
                                    addDur.getValue() == null ? 1.0 : addDur.getValue()));
            double durationHours = durationMinutes / 60.0;
            int slotCount = AppTimeReference.minutesToSlots(durationMinutes);

            LocalDateTime nowDt = AppTimeReference.getCurrentDateTime();

            LocalTime clickedTime = null;
            if (addFromGrid) {
                clickedTime = parseSlotCombo(addStartSlot.getValue());
                LocalDateTime clickDt = LocalDateTime.of(addClickedDate, clickedTime);
                if (!clickDt.isAfter(nowDt)) {
                    showAlertPopup(scene, "Cannot create a task in the past.");
                    return;
                }
            }

            if (addEndMode.isSelected()) {
                try {
                    LocalTime durationStart = parseSlotCombo(addStartSlot.getValue());
                    LocalTime end = parseSlotCombo(addEndSlot.getValue());
                    durationMinutes = minutesBetween(durationStart, end);
                    durationHours = durationMinutes / 60.0;
                    slotCount = AppTimeReference.minutesToSlots(durationMinutes);
                } catch (Exception ex) {
                    showAlertPopup(scene, "Invalid end time. Use HH:mm such as 18:31.");
                    return;
                }
            }

            boolean isFixed = addFixed.isSelected();
            boolean isDeadline = addDeadline.isSelected();

            LocalDate startDate = null;
            LocalTime startTime = null;
            LocalDate deadlineDate = null;
            LocalTime deadlineTime = null;

            try {
                if (isFixed) {
                    if (addFromGrid) {
                        startDate = addClickedDate;
                        startTime = clickedTime;
                    } else {
                        startDate = addStartDate.getValue();
                        if (startDate == null) {
                            showAlertPopup(scene, "Please select a start date.");
                            return;
                        }
                        startTime = parseSlotCombo(addStartSlot.getValue());
                    }
                    if (!LocalDateTime.of(startDate, startTime).isAfter(nowDt)) {
                        showAlertPopup(scene, "Cannot create Fixed task in the past.");
                        return;
                    }
                } else if (isDeadline) {
                    deadlineDate = addDeadlineDate.getValue();
                    if (deadlineDate == null) {
                        showAlertPopup(scene, "Please select a deadline date.");
                        return;
                    }
                    deadlineTime = parseSlotCombo(addDeadlineSlot.getValue());
                    if (!LocalDateTime.of(deadlineDate, deadlineTime).isAfter(nowDt)) {
                        showAlertPopup(scene, "Cannot set deadline in the past.");
                        return;
                    }
                    if (addFromGrid) {
                        LocalDateTime start = LocalDateTime.of(addClickedDate, clickedTime);
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

            if (addFromGrid) {
                placeDate = addClickedDate;
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
                    addFromGrid ? addClickedDate : null,
                    addFromGrid ? clickedTime : null
            );
            pop.setDescription(addDesc.getText());
            pop.setDescriptionDone(addDescDone.isSelected());

            system.fetchPopupTask(pop);

            if (!system.command("addTask")) {
                if (isDeadline) {
                    showAlertPopup(scene,
                            "Could not fit this deadline task before its deadline.\nTask was not created.");
                } else if (addFlexible.isSelected()) {
                    showAlertPopup(scene,
                            "Could not place this flexible task.\nTask was not created.");
                } else {
                    showAlertPopup(scene, "Failed to create task.");
                }
                return;
            }

            if (onChanged != null) onChanged.run();
            closeEverything(scene);
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
        if (currentPopup == addPopup && addPopup != null) {
            hideAddTask();
            currentPopup = null;
        } else if (currentPopup != null) {
            root.getChildren().remove(currentPopup);
            currentPopup = null;
        }
        if (currentDimmedOverlay == addDim && addDim != null) {
            hideAddTask();
            currentDimmedOverlay = null;
        } else if (currentDimmedOverlay != null) {
            root.getChildren().remove(currentDimmedOverlay);
            currentDimmedOverlay = null;
        }
        popupIsOpen = false;
        areYouSureIsOpen = false;
        alertIsOpen = false;
    }

    private static int minutesBetween(LocalTime start, LocalTime end) {
        int a = start.getHour() * 60 + start.getMinute();
        int b = end.getHour() * 60 + end.getMinute();
        int d = b - a;
        if (d <= 0) d += 24 * 60;
        if (d > 24 * 60) d = 24 * 60;
        return Math.max(1, d);
    }

    private ComboBox<String> createSlotCombo(int defaultSlotIndex) {
        ComboBox<String> box = new ComboBox<>();
        box.setItems(javafx.collections.FXCollections.observableArrayList(slotChoices()));
        if (defaultSlotIndex < 0) defaultSlotIndex = 0;
        if (defaultSlotIndex >= AppTimeReference.SLOTS_PER_DAY) {
            defaultSlotIndex = AppTimeReference.SLOTS_PER_DAY - 1;
        }
        box.setValue(clockLabel(defaultSlotIndex));
        box.setEditable(true);
        box.setPrefWidth(100);
        return box;
    }

    private static javafx.collections.ObservableList<String> slotChoices() {
        if (SLOT_CHOICES == null) {
            SLOT_CHOICES = javafx.collections.FXCollections.observableArrayList();
            for (int s = 0; s < AppTimeReference.SLOTS_PER_DAY; s++) {
                SLOT_CHOICES.add(clockLabel(s));
            }
        }
        return SLOT_CHOICES;
    }

    private static String clockLabel(int slotIndex) {
        LocalTime t = AppTimeReference.slotToLocalTime(slotIndex);
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private static LocalDateTime instantInParent(LocalDateTime p0, LocalDateTime p1, LocalTime clock) {
        if (clock == null || p0 == null || p1 == null) return null;
        LocalDateTime t = LocalDateTime.of(p0.toLocalDate(), clock);
        if (t.isBefore(p0)) t = t.plusDays(1);
        if (t.isBefore(p0) || !t.isBefore(p1)) return null;
        return t;
    }

    private LocalTime parseSlotCombo(String value) {
        String raw = value == null ? "" : value.replace("+", "").trim();
        String[] p = raw.split(":");
        int h = Integer.parseInt(p[0].trim());
        int m = Integer.parseInt(p[1].trim());
        if (h < 0 || h > 23 || m < 0 || m > 59) throw new IllegalArgumentException("time");
        return LocalTime.of(h, m);
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