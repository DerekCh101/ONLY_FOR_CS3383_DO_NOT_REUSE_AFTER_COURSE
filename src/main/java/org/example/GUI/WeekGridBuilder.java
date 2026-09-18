package org.example.GUI;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import org.example.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class WeekGridBuilder {

    private static final double CELL_WIDTH = 140;
    private static final double CELL_HEIGHT = 32;
    private static final double NOW_LINE_RIGHT_BLEED = 10;

    private final TaskManagementSystem system;
    private final TaskDragDropSupport dragDrop;
    private final TaskDialogs dialogs;

    private Region nowBar;
    private int nowBarRow = -1;

    public WeekGridBuilder(TaskManagementSystem system,
                           TaskDragDropSupport dragDrop,
                           TaskDialogs dialogs) {
        this.system = system;
        this.dragDrop = dragDrop;
        this.dialogs = dialogs;
    }

    public void build(GridPane grid, Label sidePanel, int weekOffset) {
        grid.getChildren().clear();
        grid.setHgap(0);
        grid.setVgap(0);
        nowBar = null;
        nowBarRow = -1;

        var gridData = system.getGrid();
        LocalDate weekStart = AppTimeReference.getStartOfWeek(AppTimeReference.getCurrentDate());
        List<BlockedTime> blocked = loadBlocked();
        List<Task> placed = system.getPlacedTasks() == null
                ? List.of() : new ArrayList<>(system.getPlacedTasks());

        LocalTime now = AppTimeReference.getCurrentTime();
        int nowSlot = AppTimeReference.localTimeToSlot(now);

        System.out.println("weekStart=" + weekStart
                + " offset=" + weekOffset
                + " today=" + AppTimeReference.getCurrentDate()
                + " column=" + AppTimeReference.currentColumnDate()
                + " gridDates=" + (gridData == null ? "null" : gridData.keySet())
                + " placed=" + placed.size());

        for (int col = 0; col < 7; col++) {
            LocalDate date = weekStart.plusDays(weekOffset * 7L + col);
            String text = date.getDayOfWeek().toString().substring(0, 3) + " "
                    + date.getMonthValue() + "/" + date.getDayOfMonth();
            grid.add(new Label(text), col + 1, 0);
        }

        for (int row = 1; row <= AppTimeReference.SLOTS_PER_DAY; row++) {
            int slotIndex = row - 1;

            Label timeLabel = new Label(AppTimeReference.formatSlotLabel(slotIndex));
            if (slotIndex == nowSlot) {
                timeLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
            }
            grid.add(timeLabel, 0, row);

            for (int col = 1; col <= 7; col++) {
                LocalDate columnDate = weekStart.plusDays(weekOffset * 7L + (col - 1));
                BlockedTime block = blockedAt(blocked, columnDate, slotIndex);
                boolean past = isPast(columnDate, slotIndex);

                Pane cell = new Pane();
                cell.setPrefSize(CELL_WIDTH, CELL_HEIGHT);
                cell.setMinHeight(CELL_HEIGHT);
                cell.setMaxHeight(CELL_HEIGHT);

                Task taskInCell = taskAt(gridData, placed, columnDate, slotIndex);
                if (taskInCell != null) {
                    cell.getChildren().add(taskLabel(taskInCell));
                    if (!(taskInCell.getWorkflow() == Workflow.FINISHED
                            || taskInCell.getLifeCycle() == TaskLifeCycle.PAST)) {
                        dragDrop.makeDraggable(cell, taskInCell, columnDate, slotIndex);
                    }
                }

                applyDayStyle(cell, columnDate, slotIndex, block, taskInCell);

                if (taskInCell == null && !past && block != null
                        && isSegmentStart(blocked, columnDate, slotIndex, block)) {
                    String title = block.getTitle() == null || block.getTitle().isBlank()
                            ? "blocked" : block.getTitle();
                    Label why = new Label(title + "  " + block.getStartTime() + "–" + block.getEndTime());
                    why.setStyle("-fx-font-size: 10px; -fx-font-style: italic; -fx-text-fill: #333333;");
                    why.setMouseTransparent(true);
                    cell.getChildren().add(why);
                }

                final LocalDate finalColumn = columnDate;
                final int finalSlotIndex = slotIndex;
                final Task finalTaskInCell = taskInCell;
                final BlockedTime finalBlock = block;
                final double[] mouseDown = new double[2];

                cell.setOnMousePressed(e -> {
                    mouseDown[0] = e.getScreenX();
                    mouseDown[1] = e.getScreenY();
                });

                cell.setOnMouseClicked(e -> {
                    if (Math.abs(e.getScreenX() - mouseDown[0]) < 5
                            && Math.abs(e.getScreenY() - mouseDown[1]) < 5) {
                        LocalDate clickDate = AppTimeReference.calendarDateForSlot(finalColumn, finalSlotIndex);
                        if (finalTaskInCell == null) {
                            dialogs.showTaskPopup(true, clickDate, finalSlotIndex);
                        } else {
                            dialogs.showTaskInfoPopup(finalTaskInCell, clickDate, finalSlotIndex);
                        }
                    }
                });

                cell.setOnMouseEntered(e -> {
                    if (finalTaskInCell == null && finalBlock == null
                            && !isPast(finalColumn, finalSlotIndex)) {
                        cell.setStyle("-fx-background-color: #ffccdd; -fx-border-color: #999999;");
                    }
                });
                cell.setOnMouseExited(e -> applyDayStyle(
                        cell, finalColumn, finalSlotIndex, finalBlock, finalTaskInCell));

                dragDrop.setupDropTarget(cell, columnDate, slotIndex);
                grid.add(cell, col, row);
            }
        }

        nowBar = new Region();
        nowBar.setMinHeight(3);
        nowBar.setPrefHeight(3);
        nowBar.setMaxHeight(3);
        nowBar.setStyle("-fx-background-color: red;");
        nowBar.setMouseTransparent(true);
        double barWidth = CELL_WIDTH * 7 + NOW_LINE_RIGHT_BLEED;
        nowBar.setMinWidth(barWidth);
        nowBar.setPrefWidth(barWidth);
        nowBar.setMaxWidth(barWidth);
        nowBarRow = nowSlot + 1;
        grid.add(nowBar, 1, nowBarRow, 7, 1);
        GridPane.setValignment(nowBar, VPos.TOP);
        GridPane.setHalignment(nowBar, HPos.LEFT);
        GridPane.setMargin(nowBar, new Insets(0, -NOW_LINE_RIGHT_BLEED, 0, 0));
        nowBar.setTranslateY(fractionWithinSlot(now) * CELL_HEIGHT);

        StringBuilder sb = new StringBuilder("Unscheduled task:\n");
        try {
            java.time.format.DateTimeFormatter fmt =
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (Task t : system.getTaskRepo().fetchUnscheduledTasks()) {
                sb.append(t.getTaskName())
                        .append(t.getState() == 1 ? " Fixed Task"
                                : t.getState() == 2 ? " Deadline Task" : " Flexible Task");
                if (t.getPlacementUpdatedAt() != null) {
                    java.time.LocalDateTime local = java.time.LocalDateTime.ofInstant(
                            t.getPlacementUpdatedAt(),
                            java.time.ZoneId.systemDefault());
                    sb.append("  ( ").append(local.format(fmt)).append(" )");
                }
                sb.append('\n');
            }
        } catch (SQLException e) {
            sb.append("(failed to load)\n");
        }
        sidePanel.setText(sb.toString());
    }

    private static Task taskAt(java.util.Map<LocalDate, Day> gridData,
                               List<Task> placed, LocalDate columnDate, int slotIndex) {
        if (gridData != null && gridData.containsKey(columnDate)) {
            Task[] cells = gridData.get(columnDate).returnDayGrid();
            if (cells != null && slotIndex >= 0 && slotIndex < cells.length && cells[slotIndex] != null) {
                return cells[slotIndex];
            }
        }
        for (Task t : placed) {
            if (t == null || t.getPlacedDate() == null || t.getPlacedTime() == null) continue;
            LocalDate col = AppTimeReference.columnDateFor(t.getPlacedDate(), t.getPlacedTime());
            if (!columnDate.equals(col)) continue;
            int start = AppTimeReference.localTimeToSlot(t.getPlacedTime());
            if (start < 0) continue;
            int count = t.getDurationSlots();
            if (count < 1) count = 1;
            if (slotIndex >= start && slotIndex < start + count) return t;
        }
        return null;
    }

    private static Label taskLabel(Task task) {
        boolean done = task.getWorkflow() == Workflow.FINISHED;
        Label label = new Label(done ? "done  ·  " + task.getTaskName() : task.getTaskName());
        label.setWrapText(true);
        label.setMouseTransparent(true);
        if (done) {
            label.setStyle(
                    "-fx-font-size: 10px;"
                            + "-fx-font-family: Georgia;"
                            + "-fx-font-style: italic;"
                            + "-fx-text-fill: #3f5348;"
                            + "-fx-padding: 3 6 0 8;");
        } else {
            label.setStyle("-fx-font-size: 10px; -fx-padding: 3 4 0 4;");
        }
        return label;
    }

    public static double fractionWithinSlot(LocalTime time) {
        int totalMinutes = time.getHour() * 60 + time.getMinute();
        int relative = totalMinutes - AppTimeReference.GRID_START_MINUTES;
        if (relative < 0) {
            relative = (AppTimeReference.MINUTES_PER_DAY - AppTimeReference.GRID_START_MINUTES)
                    + totalMinutes;
        }
        int intoSlot = relative % AppTimeReference.MINUTES_PER_SLOT;
        return intoSlot / (double) AppTimeReference.MINUTES_PER_SLOT;
    }

    public void updateNowLinePosition() {
        if (nowBar == null) return;
        LocalTime now = AppTimeReference.getCurrentTime();
        if (AppTimeReference.localTimeToSlot(now) + 1 != nowBarRow) return;
        nowBar.setTranslateY(fractionWithinSlot(now) * CELL_HEIGHT);
    }

    public boolean nowLineNeedsRowChange() {
        if (nowBar == null) return true;
        return AppTimeReference.currentSlotIndex() + 1 != nowBarRow;
    }

    private List<BlockedTime> loadBlocked() {
        BlockedTimePeriod holder = system.getBlockedTime();
        if (holder == null || holder.blockedPeriod == null) return new ArrayList<>();
        return holder.blockedPeriod;
    }

    private static boolean isPast(LocalDate columnDate, int slotIndex) {
        LocalDateTime start = AppTimeReference.dateTimeForSlot(columnDate, slotIndex);
        return !start.isAfter(AppTimeReference.getCurrentDateTime());
    }

    private static BlockedTime blockedAt(List<BlockedTime> blocked, LocalDate columnDate, int slotIndex) {
        LocalDate date = AppTimeReference.calendarDateForSlot(columnDate, slotIndex);
        LocalTime slotStart = AppTimeReference.slotToLocalTime(slotIndex);
        LocalTime slotEnd = slotStart.plusMinutes(AppTimeReference.MINUTES_PER_SLOT);

        BlockedTime daily = null;
        BlockedTime specific = null;
        boolean exception = false;

        for (BlockedTime b : blocked) {
            if (b.getStartTime() == null || b.getEndTime() == null) continue;
            if (!(slotStart.isBefore(b.getEndTime()) && slotEnd.isAfter(b.getStartTime()))) continue;

            if (b.isException()) {
                if (date.equals(b.getSpecificDate())) exception = true;
            } else if (b.isDaily()) {
                daily = b;
            } else if (b.appliesOn(date)) {
                specific = b;
            }
        }

        if (specific != null) return specific;
        if (exception) return null;
        return daily;
    }

    private static boolean isSegmentStart(List<BlockedTime> blocked, LocalDate columnDate,
                                          int slotIndex, BlockedTime current) {
        if (current == null || slotIndex <= 0) return current != null;
        BlockedTime prev = blockedAt(blocked, columnDate, slotIndex - 1);
        if (prev == null) return true;
        return !java.util.Objects.equals(prev.getId(), current.getId());
    }

    private void applyDayStyle(Pane cell, LocalDate columnDate, int slotIndex,
                               BlockedTime block, Task task) {
        if (task != null && task.getWorkflow() == Workflow.FINISHED) {
            cell.setStyle(
                    "-fx-background-color: linear-gradient(to right, #c3d2c6, #dfe6dc);"
                            + "-fx-border-color: #8ea494 #c5cec8 #c5cec8 #6f8a76;"
                            + "-fx-border-width: 1 1 1 4;");
            return;
        }
        if (isPast(columnDate, slotIndex)) {
            cell.setStyle("-fx-background-color: #bdc8c8; -fx-border-color: #999999;");
            return;
        }
        if (block != null) {
            cell.setStyle(blockedStyle(block));
            return;
        }
        if (columnDate.equals(AppTimeReference.currentColumnDate())) {
            cell.setStyle("-fx-background-color: #70d9f1; -fx-border-color: #999999;");
        } else {
            cell.setStyle("-fx-border-color: #999999;");
        }
    }

    private static String blockedStyle(BlockedTime block) {
        int hour = block.getStartTime().getHour();
        String fill;
        if (hour >= 21 || hour < 8) {
            fill = "#c5cbe8";
        } else if (hour >= 11 && hour < 14) {
            fill = "#f3d5a3";
        } else if (hour < 11) {
            fill = "#b9dde6";
        } else {
            fill = "#ddc6ea";
        }
        return "-fx-background-color: " + fill
                + "; -fx-border-color: #999999; -fx-border-width: 1;";
    }
}