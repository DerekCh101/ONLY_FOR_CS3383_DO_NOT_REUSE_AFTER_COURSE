package org.example.GUI;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import org.example.AppTimeReference;
import org.example.Task;
import org.example.TaskManagementSystem;

import java.time.LocalDate;
import java.time.LocalTime;

public class WeekGridBuilder {

    private static final double CELL_WIDTH = 140;
    private static final double CELL_HEIGHT = 32;
    private static final double NOW_LINE_RIGHT_BLEED = 10;

    private final TaskManagementSystem system;
    private final TaskDragDropSupport dragDrop;
    private final TaskDialogs dialogs;

    /** Kept so the controller can nudge the line without full rebuild */
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

        LocalTime now = AppTimeReference.getCurrentTime();
        int nowSlot = AppTimeReference.localTimeToSlot(now);
        double fractionInSlot = fractionWithinSlot(now); // 0.0 .. 1.0

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
                LocalDate date = weekStart.plusDays(weekOffset * 7L + (col - 1));

                Pane cell = new Pane();
                cell.setPrefSize(CELL_WIDTH, CELL_HEIGHT);
                cell.setMinHeight(CELL_HEIGHT);
                cell.setMaxHeight(CELL_HEIGHT);
                applyDayStyle(cell, date);

                Task taskInCell = null;
                if (gridData != null && gridData.containsKey(date)) {
                    taskInCell = gridData.get(date).returnDayGrid()[slotIndex];
                    if (taskInCell != null) {
                        Label label = new Label(taskInCell.getTaskName());
                        label.setWrapText(true);
                        label.setStyle("-fx-font-size: 10px;");
                        cell.getChildren().add(label);
                        dragDrop.makeDraggable(cell, taskInCell, date, slotIndex);
                    }
                }

                final LocalDate finalDate = date;
                final int finalSlotIndex = slotIndex;
                final Task finalTaskInCell = taskInCell;
                final double[] mouseDown = new double[2];

                cell.setOnMousePressed(e -> {
                    mouseDown[0] = e.getScreenX();
                    mouseDown[1] = e.getScreenY();
                });

                cell.setOnMouseClicked(e -> {
                    double dx = Math.abs(e.getScreenX() - mouseDown[0]);
                    double dy = Math.abs(e.getScreenY() - mouseDown[1]);
                    if (dx < 5 && dy < 5) {
                        if (finalTaskInCell == null) {
                            dialogs.showTaskPopup(true, finalDate, finalSlotIndex);
                        } else {
                            dialogs.showTaskInfoPopup(finalTaskInCell, finalDate, finalSlotIndex);
                        }
                    }
                });

                cell.setOnMouseEntered(e -> {
                    if (finalTaskInCell == null) {
                        cell.setStyle("-fx-background-color: #ffccdd; -fx-border-color: #999999;");
                    }
                });
                cell.setOnMouseExited(e -> applyDayStyle(cell, finalDate));

                dragDrop.setupDropTarget(cell, date, slotIndex);
                grid.add(cell, col, row);
            }
        }

        // Dynamic red line
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

        // 17:15 → halfway down the 17:00 row, etc.
        nowBar.setTranslateY(fractionInSlot * CELL_HEIGHT);

        sidePanel.setText("Unscheduled task:\n" + system.getEngine().printUnscheduledTask());
    }

    /**
     * How far we are through the current half-hour slot (0 = start, 1 = end).
     * Example: 17:15 in the 17:00 slot → 15/30 = 0.5
     */
    public static double fractionWithinSlot(LocalTime time) {
        int totalMinutes = time.getHour() * 60 + time.getMinute();
        int relative = totalMinutes - AppTimeReference.GRID_START_MINUTES;
        if (relative < 0) return 0;
        int intoSlot = relative % AppTimeReference.MINUTES_PER_SLOT;
        return intoSlot / (double) AppTimeReference.MINUTES_PER_SLOT;
    }

    /** Call from a timer so the line creeps down without full logic elsewhere. */
    public void updateNowLinePosition() {
        if (nowBar == null) return;
        LocalTime now = AppTimeReference.getCurrentTime();
        int slot = AppTimeReference.localTimeToSlot(now);
        int row = slot + 1;

        // If the clock crossed into another half-hour, parent should refresh() the grid.
        if (row != nowBarRow) {
            return; // signal controller to full refresh
        }
        nowBar.setTranslateY(fractionWithinSlot(now) * CELL_HEIGHT);
    }

    public boolean nowLineNeedsRowChange() {
        if (nowBar == null) return true;
        int slot = AppTimeReference.currentSlotIndex();
        return (slot + 1) != nowBarRow;
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
}