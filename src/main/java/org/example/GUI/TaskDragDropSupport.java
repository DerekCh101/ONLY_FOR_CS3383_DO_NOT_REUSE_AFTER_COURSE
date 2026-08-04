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
    private final Runnable onMoved;

    private long lastWeekSwitchMs = 0;
    private static final long WEEK_SWITCH_COOLDOWN_MS = 600;

    public TaskDragDropSupport(TaskManagementSystem system, StackPane root, Runnable onMoved) {
        this.system = system;
        this.root = root;
        this.onMoved = onMoved;
    }

    public void setupWeekEdgePanes(Pane prevPane, Pane nextPane, IntConsumer onWeekDelta) {
        if (prevPane != null) wireWeekEdgePane(prevPane, -1, onWeekDelta);
        if (nextPane != null) wireWeekEdgePane(nextPane, +1, onWeekDelta);
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

    public void makeDraggable(Pane cell, Task task, LocalDate fromDate, int clickedSlotIndex) {
        cell.setOnDragDetected(event -> {
            Day day = system.getGrid().get(fromDate);
            if (day == null) return;

            Task[] slots = day.returnDayGrid();
            int realStartSlot = clickedSlotIndex;
            while (realStartSlot > 0 && slots[realStartSlot - 1] == task) {
                realStartSlot--;
            }

            int slotCount = task.getDurationSlots();

            VBox dbContainer = new VBox(1);
            root.getChildren().add(dbContainer);
            dbContainer.setOpacity(20);
            dbContainer.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            dbContainer.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
            dbContainer.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

            applyDayStyle(cell, fromDate);

            for (int i = 0; i < slotCount; i++) {
                Pane virtualDbCell = new Pane();
                Label taskName = new Label(task.getTaskName());
                taskName.setStyle("-fx-font-size: 10px;");
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
                            realStartSlot + "|" +
                            task.getDurationMinutes()
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

    public void setupDropTarget(Pane cell, LocalDate targetDate, int targetSlotIndex) {

        cell.setOnDragOver(event -> {
            if (event.getGestureSource() != cell && event.getDragboard().hasString()) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });

        cell.setOnDragExited(event -> {
            applyDayStyle(cell, targetDate);
            event.consume();
        });

        cell.setOnDragEntered(event -> {
            if (event.getGestureSource() != cell && event.getDragboard().hasString()) {
                Task movingTask = resolveMovingTask(event.getDragboard());
                boolean ok = canDropOn(targetDate, targetSlotIndex, movingTask);
                cell.setStyle(ok
                        ? "-fx-background-color: #a0e7a0; -fx-border-color: #999999;"
                        : "-fx-background-color: #f38888; -fx-border-color: #999999;");
            }
            event.consume();
        });

        cell.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;

            if (db.hasString()) {
                try {
                    String[] parts = db.getString().split("\\|");
                    String taskId = parts[0];
                    LocalDate fromDate = LocalDate.parse(parts[2]);
                    int fromStartSlot = Integer.parseInt(parts[3]);

                    Task movingTask = findTaskById(taskId);
                    if (movingTask == null) {
                        Day fromDay = system.getGrid().get(fromDate);
                        if (fromDay != null) {
                            movingTask = fromDay.returnDayGrid()[fromStartSlot];
                        }
                    }

                    if (movingTask != null && canDropOn(targetDate, targetSlotIndex, movingTask)) {
                        success = new MoveTask(
                                movingTask,
                                fromDate, fromStartSlot,
                                targetDate, targetSlotIndex
                        ).execute();
                    }
                } catch (Exception ex) {
                    success = false;
                }
            }

            event.setDropCompleted(success);
            event.consume();
            if (success && onMoved != null) onMoved.run();
        });
    }

    /**
     * Shared drop rules: not in the past, fits in day, no conflict,
     * deadline finish-by (with midnight wrap).
     */
    public boolean canDropOn(LocalDate targetDate, int targetSlotIndex, Task movingTask) {
        if (movingTask == null) return false;

        try {
            system.getGrid().putIfAbsent(targetDate, new Day(system.getBlockedTime()));
            Day targetDay = system.getGrid().get(targetDate);

            int slotCount = movingTask.getDurationSlots();
            if (slotCount <= 0) return false;

            LocalTime targetStart = AppTimeReference.slotToLocalTime(targetSlotIndex);

            // Reject past (and "now" as start)
            if (targetDate.isBefore(AppTimeReference.getCurrentDate())
                    || (targetDate.equals(AppTimeReference.getCurrentDate())
                    && !targetStart.isAfter(AppTimeReference.getCurrentTime()))) {
                return false;
            }

            if (targetSlotIndex + slotCount > AppTimeReference.SLOTS_PER_DAY) {
                return false;
            }

            // Deadline: must finish on/before deadline (same rules as add/grid)
            if (movingTask.hasDeadline()) {
                LocalDate deadlineDate = movingTask.getDeadLineDate();
                LocalTime deadlineTime = movingTask.getDeadLineTime();
                if (deadlineDate == null) return false;

                if (targetDate.isAfter(deadlineDate)) {
                    return false;
                }

                if (targetDate.equals(deadlineDate) && deadlineTime != null) {
                    long durationMin = (long) slotCount * AppTimeReference.MINUTES_PER_SLOT;
                    LocalTime finish = targetStart.plusMinutes(durationMin);
                    boolean wrappedPastMidnight = finish.isBefore(targetStart);

                    // start already after deadline, or finish after deadline, or wrapped to next day
                    if (targetStart.isAfter(deadlineTime)
                            || wrappedPastMidnight
                            || finish.isAfter(deadlineTime)) {
                        return false;
                    }
                }
            }

            for (int i = 0; i < slotCount; i++) {
                Task existing = targetDay.returnDayGrid()[targetSlotIndex + i];
                if (existing != null && existing != movingTask) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void applyDayStyle(Pane cell, LocalDate date) {
        if (date.equals(AppTimeReference.getCurrentDate())) {
            cell.setStyle("-fx-background-color: #70d9f1; -fx-border-color: #999999;");
        } else if (date.isBefore(AppTimeReference.getCurrentDate())) {
            cell.setStyle("-fx-background-color: #bdc8c8; -fx-border-color: #999999;");
        } else {
            cell.setStyle("-fx-border-color: #999999;");
        }
    }

    private Task resolveMovingTask(Dragboard db) {
        try {
            String[] parts = db.getString().split("\\|");
            Task byId = findTaskById(parts[0]);
            if (byId != null) return byId;
            LocalDate fromDate = LocalDate.parse(parts[2]);
            int fromStartSlot = Integer.parseInt(parts[3]);
            Day fromDay = system.getGrid().get(fromDate);
            if (fromDay != null) return fromDay.returnDayGrid()[fromStartSlot];
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