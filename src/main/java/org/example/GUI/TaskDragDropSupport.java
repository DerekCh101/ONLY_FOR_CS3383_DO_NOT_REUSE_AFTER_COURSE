package org.example.GUI;

import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.input.*;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.example.AppTimeReference;
import org.example.Day;
import org.example.Task;
import org.example.TaskManagementSystem;
import org.example.Command.MoveTask;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.function.IntConsumer;

public class TaskDragDropSupport {

    private final TaskManagementSystem system;
    private final StackPane root;
    private final Runnable onMoved; // usually controller::refresh

    private long lastWeekSwitchMs = 0;
    private static final long WEEK_SWITCH_COOLDOWN_MS = 600;

    public TaskDragDropSupport(TaskManagementSystem system, StackPane root, Runnable onMoved) {
        this.system = system;
        this.root = root;
        this.onMoved = onMoved;
    }

    // ==================== Week edge panes ====================

    public void setupWeekEdgePanes(Pane prevPane, Pane nextPane, IntConsumer onWeekDelta) {
        if (prevPane != null) {
            wireWeekEdgePane(prevPane, -1, onWeekDelta);
        }
        if (nextPane != null) {
            wireWeekEdgePane(nextPane, +1, onWeekDelta);
        }
    }

    private void wireWeekEdgePane(Pane edgePane, int weekDelta, IntConsumer onWeekDelta) {
        edgePane.setOnDragOver(event -> {
            if (event.getDragboard().hasString()) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });

        edgePane.setOnDragEntered(event -> {
            if (!event.getDragboard().hasString()) {
                event.consume();
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastWeekSwitchMs < WEEK_SWITCH_COOLDOWN_MS) {
                event.consume();
                return;
            }
            lastWeekSwitchMs = now;

            onWeekDelta.accept(weekDelta);
            edgePane.setStyle("-fx-background-color: #cce0ff;");
            event.consume();
        });

        edgePane.setOnDragExited(event -> {
            edgePane.setStyle("");
            event.consume();
        });

        edgePane.setOnDragDropped(event -> {
            event.setDropCompleted(false);
            event.consume();
        });
    }

    // ==================== Make cell draggable ====================

    public void makeDraggable(Pane cell, Task task, LocalDate fromDate, int clickedHourIndex) {
        cell.setOnDragDetected(event -> {
            Day day = system.getGrid().get(fromDate);
            if (day == null) return;

            Task[] slots = day.returnDayGrid();
            int realStartHour = clickedHourIndex;

            while (realStartHour > 0 && slots[realStartHour - 1] == task) {
                realStartHour--;
            }

            int duration = task.getDurationHours();
            if (duration <= 0) duration = 2;

            // Ghost image
            VBox dbContainer = new VBox(1);
            root.getChildren().add(dbContainer);
            dbContainer.setOpacity(100);
            dbContainer.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            dbContainer.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
            dbContainer.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);


            if(fromDate.equals(org.example.AppTimeReference.getCurrentDate())){
                cell.setStyle("-fx-background-color: #70d9f1;-fx-border-color: black;");
            }else if (fromDate.isBefore(org.example.AppTimeReference.getCurrentDate())){
                cell.setStyle("-fx-background-color: #bdc8c8;-fx-border-color: black;");
            }else{
                cell.setStyle("-fx-border-color: black;");
            }

            for (int i = 0; i < duration; i++) {
                Pane virtualDbCell = new Pane();
                Label taskName = new Label(task.getTaskName());
                virtualDbCell.getChildren().add(taskName);
                virtualDbCell.setStyle(cell.getStyle());
                virtualDbCell.setPrefSize(cell.getWidth(), cell.getHeight());
                dbContainer.getChildren().add(virtualDbCell);
            }

            dbContainer.applyCss();
            dbContainer.layout();

            Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
            WritableImage snapshot = dbContainer.snapshot(null, null);
            db.setDragView(snapshot);
            root.getChildren().remove(dbContainer);

            ClipboardContent content = new ClipboardContent();
            content.putString(
                    task.getID() + "|" +
                            task.getTaskName() + "|" +
                            fromDate.toString() + "|" +
                            realStartHour + "|" +
                            duration
            );
            db.setContent(content);

            cell.setOpacity(0.4);
            event.consume();
        });

        cell.setOnDragDone(event -> {
            cell.setOpacity(1.0);
            event.consume();
        });
    }

    // ==================== Drop target ====================

    public void setupDropTarget(Pane cell, LocalDate targetDate, int targetHourIndex) {

        cell.setOnDragOver(event -> {
            if (event.getGestureSource() != cell && event.getDragboard().hasString()) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });


        cell.setOnDragExited(event -> {
            if(targetDate.equals(org.example.AppTimeReference.getCurrentDate())){
                cell.setStyle("-fx-background-color: #70d9f1;-fx-border-color: black;");
            }else if (targetDate.isBefore(org.example.AppTimeReference.getCurrentDate())){
                cell.setStyle("-fx-background-color: #bdc8c8;-fx-border-color: black;");
            }else{
                cell.setStyle("-fx-border-color: black;");
            }
            event.consume();
        });

        cell.setOnDragEntered(event -> {
            if (event.getGestureSource() != cell && event.getDragboard().hasString()) {
                Task movingTask = resolveMovingTask(event.getDragboard());
                boolean ok = canDropOn(targetDate, targetHourIndex, movingTask);
                if (ok) {
                    cell.setStyle("-fx-background-color: #a0e7a0; -fx-border-color: black;");
                } else {
                    cell.setStyle("-fx-background-color: #f38888; -fx-border-color: black;");
                }
            }
            event.consume();
        });

        cell.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;

            if (db.hasString()) {
                try {
                    String[] parts = db.getString().split("\\|");
                    // id|name|fromDate|fromHour|duration
                    String taskId = parts[0];
                    LocalDate fromDate = LocalDate.parse(parts[2]);
                    int fromStartHour = Integer.parseInt(parts[3]);
                    int duration = Integer.parseInt(parts[4]);

                    Task movingTask = findTaskById(taskId);
                    if (movingTask == null) {
                        // fallback to old grid lookup
                        Day fromDay = system.getGrid().get(fromDate);
                        if (fromDay != null) {
                            movingTask = fromDay.returnDayGrid()[fromStartHour];
                        }
                    }

                    if (movingTask != null && canDropOn(targetDate, targetHourIndex, movingTask)) {
                        MoveTask moveCmd = new MoveTask(
                                movingTask,
                                fromDate, fromStartHour,
                                targetDate, targetHourIndex,
                                duration
                        );
                        moveCmd.execute();
                        success = true;
                    }
                } catch (Exception ex) {
                    success = false;
                }
            }

            event.setDropCompleted(success);
            event.consume();

            if (success && onMoved != null) {
                onMoved.run();
            }
        });
    }

    // ==================== Validation ====================

    public boolean canDropOn(LocalDate targetDate, int targetHourIndex, Task movingTask) {
        if (movingTask == null) return false;

        try {
            system.getGrid().putIfAbsent(targetDate, new Day(system.getBlockedTime()));
            Day targetDay = system.getGrid().get(targetDate);

            int duration = movingTask.getDurationHours();
            if (duration <= 0) duration = 2;

            LocalDate today = LocalDate.now();
            LocalTime now = LocalTime.now();
            int targetClockHour = system.getEngine().getStartingHour() + targetHourIndex;
            LocalTime targetStartTime = LocalTime.of(targetClockHour, 0);

            // Past
            if (targetDate.isBefore(today) ||
                    (targetDate.equals(today) && !targetStartTime.isAfter(now))) {
                return false;
            }

            // Exceeds day
            if (targetHourIndex + duration > system.getEngine().getTotalHours()) {
                return false;
            }

            // Deadline
            if (movingTask.hasDeadline()) {
                LocalDate deadlineDate = movingTask.getDeadLineDate();
                LocalTime deadlineTime = movingTask.getDeadLineTime();
                if (deadlineDate == null) return false;
                if (targetDate.isAfter(deadlineDate)) return false;
                if (targetDate.equals(deadlineDate) && deadlineTime != null) {
                    LocalTime finishTime = targetStartTime.plusHours(duration);
                    if (finishTime.isAfter(deadlineTime)) return false;
                }
            }

            // Conflicts
            for (int i = 0; i < duration; i++) {
                Task existing = targetDay.returnDayGrid()[targetHourIndex + i];
                if (existing != null && existing != movingTask) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Helpers ====================

    private Task resolveMovingTask(Dragboard db) {
        try {
            String[] parts = db.getString().split("\\|");
            if (parts.length >= 1) {
                Task byId = findTaskById(parts[0]);
                if (byId != null) return byId;
            }
            if (parts.length >= 4) {
                LocalDate fromDate = LocalDate.parse(parts[2]);
                int fromStartHour = Integer.parseInt(parts[3]);
                Day fromDay = system.getGrid().get(fromDate);
                if (fromDay != null) {
                    return fromDay.returnDayGrid()[fromStartHour];
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Task findTaskById(String id) {
        if (id == null) return null;
        for (Task t : system.getPlacedTasks()) {
            if (id.equals(t.getID())) return t;
        }
        for (Task t : system.getPendingTasks()) {
            if (id.equals(t.getID())) return t;
        }
        for (Task t : system.getUnscheduledTasks()) {
            if (id.equals(t.getID())) return t;
        }
        return null;
    }
}