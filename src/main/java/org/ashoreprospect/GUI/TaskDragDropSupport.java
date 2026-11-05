/** Drag a placed task to another slot on the week grid. */
package org.ashoreprospect.GUI;

import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.input.*;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Day;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.Command.MoveTask;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    public void makeDraggable(Pane cell) {
        cell.setOnDragDetected(event -> {
            WeekGridBuilder.CellState st = WeekGridBuilder.cellState(cell);
            if (st == null || st.task == null || st.columnDate == null) return;
            Task task = st.task;
            if (task.getWorkflow() == org.ashoreprospect.Workflow.FINISHED
                    || task.getLifeCycle() == org.ashoreprospect.TaskLifeCycle.PAST) {
                return;
            }
            LocalDate fromColumn = st.columnDate;
            int clickedSlotIndex = st.slotIndex;
            Day day = system.getGrid().get(fromColumn);
            if (day == null) return;

            Task[] slots = day.returnDayGrid();
            if (slots == null || clickedSlotIndex < 0 || clickedSlotIndex >= slots.length) return;

            int realStartSlot = AppTimeReference.localTimeToSlot(task.getPlacedTime());
            if (realStartSlot < 0) {
                realStartSlot = clickedSlotIndex;
                while (realStartSlot > 0 && slots[realStartSlot - 1] == task) {
                    realStartSlot--;
                }
            }

            int slotCount = task.getDurationSlots();
            if (slotCount < 1) slotCount = 1;

            VBox dbContainer = new VBox(1);
            root.getChildren().add(dbContainer);
            dbContainer.setOpacity(20);
            dbContainer.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            dbContainer.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
            dbContainer.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

            WeekGridBuilder.styleSlot(cell, system, fromColumn, clickedSlotIndex);

            for (int i = 0; i < slotCount; i++) {
                Pane virtualDbCell = new Pane();
                Label taskName = new Label(task.getTaskName());
                taskName.setStyle("-fx-font-size: 10px;");
                virtualDbCell.getChildren().add(taskName);
                virtualDbCell.setBackground(cell.getBackground());
                virtualDbCell.setBorder(cell.getBorder());
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
                            fromColumn.toString() + "|" +
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

    public void setupDropTarget(Pane cell) {

        cell.setOnDragOver(event -> {
            if (event.getGestureSource() != cell && event.getDragboard().hasString()) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });

        cell.setOnDragExited(event -> {
            WeekGridBuilder.CellState st = WeekGridBuilder.cellState(cell);
            if (st != null && st.columnDate != null) {
                WeekGridBuilder.styleSlot(cell, system, st.columnDate, st.slotIndex);
            }
            event.consume();
        });

        cell.setOnDragEntered(event -> {
            WeekGridBuilder.CellState st = WeekGridBuilder.cellState(cell);
            if (st == null || st.columnDate == null) {
                event.consume();
                return;
            }
            LocalDate targetColumn = st.columnDate;
            int targetSlotIndex = st.slotIndex;
            if (event.getGestureSource() != cell && event.getDragboard().hasString()) {
                LocalDateTime start = AppTimeReference.dateTimeForSlot(targetColumn, targetSlotIndex);
                if (start.isAfter(AppTimeReference.getCurrentDateTime())) {
                    WeekGridBuilder.styleSlot(cell, system, targetColumn, targetSlotIndex);
                    Task movingTask = resolveMovingTask(event.getDragboard());
                    boolean ok = canDropOn(targetColumn, targetSlotIndex, movingTask);
                    WeekGridBuilder.paintDropHover(cell, st, ok);
                }
            }
            event.consume();
        });

        cell.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            WeekGridBuilder.CellState st = WeekGridBuilder.cellState(cell);
            LocalDate targetColumn = st == null ? null : st.columnDate;
            int targetSlotIndex = st == null ? -1 : st.slotIndex;

            if (db.hasString() && targetColumn != null && targetSlotIndex >= 0) {
                try {
                    String[] parts = db.getString().split("\\|");
                    String taskId = parts[0];
                    LocalDate fromColumn = LocalDate.parse(parts[2]);
                    int fromStartSlot = Integer.parseInt(parts[3]);

                    Task movingTask = findTaskById(taskId);
                    if (movingTask == null) {
                        Day fromDay = system.getGrid().get(fromColumn);
                        if (fromDay != null) {
                            movingTask = fromDay.returnDayGrid()[fromStartSlot];
                        }
                    }

                    if (movingTask != null && canDropOn(targetColumn, targetSlotIndex, movingTask)) {
                        success = new MoveTask(
                                movingTask,
                                fromColumn, fromStartSlot,
                                targetColumn, targetSlotIndex
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

    public boolean canDropOn(LocalDate targetColumn, int targetSlotIndex, Task movingTask) {
        if (movingTask == null) return false;

        try {
            system.getGrid().putIfAbsent(targetColumn, new Day(system.getBlockedTime()));
            Day targetDay = system.getGrid().get(targetColumn);

            int slotCount = movingTask.getDurationSlots();
            if (slotCount <= 0) return false;

            LocalDateTime targetStart = AppTimeReference.dateTimeForSlot(targetColumn, targetSlotIndex);
            if (!targetStart.isAfter(AppTimeReference.getCurrentDateTime())) {
                return false;
            }

            if (targetSlotIndex + slotCount > AppTimeReference.SLOTS_PER_DAY) {
                return false;
            }

            if (movingTask.hasDeadline()) {
                LocalDate deadlineDate = movingTask.getDeadLineDate();
                LocalTime deadlineTime = movingTask.getDeadLineTime();
                if (deadlineDate == null) return false;
                LocalDateTime finish = targetStart.plusMinutes(
                        (long) slotCount * AppTimeReference.MINUTES_PER_SLOT);
                LocalDateTime deadline = deadlineTime == null
                        ? deadlineDate.plusDays(1).atStartOfDay()
                        : LocalDateTime.of(deadlineDate, deadlineTime);
                if (finish.isAfter(deadline)) return false;
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

    private Task resolveMovingTask(Dragboard db) {
        try {
            String[] parts = db.getString().split("\\|");
            Task byId = findTaskById(parts[0]);
            if (byId != null) return byId;
            LocalDate fromColumn = LocalDate.parse(parts[2]);
            int fromStartSlot = Integer.parseInt(parts[3]);
            Day fromDay = system.getGrid().get(fromColumn);
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