package org.example.GUI;

import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import org.example.Calendar;
import org.example.Task;
import org.example.TaskManagementSystem;

import java.time.LocalDate;

public class WeekGridBuilder {

    private final TaskManagementSystem system;
    private final TaskDragDropSupport dragDrop;
    private final TaskDialogs dialogs;

    public WeekGridBuilder(TaskManagementSystem system,
                           TaskDragDropSupport dragDrop,
                           TaskDialogs dialogs) {
        this.system = system;
        this.dragDrop = dragDrop;
        this.dialogs = dialogs;
    }

    public void build(GridPane grid, Label sidePanel, int weekOffset, LocalDate baseDate) {
        grid.getChildren().clear();
        var gridData = system.getGrid();

        // Header row
        for (int col = 0; col < 7; col++) {
            int weekDayIndex = weekOffset * 7 + col;
            LocalDate date = Calendar.getStartOfWeek(baseDate).plusDays(weekDayIndex);
            String text = date.getDayOfWeek().toString().substring(0, 3) + " "
                    + date.getMonthValue() + "/" + date.getDayOfMonth();
            grid.add(new Label(text), col + 1, 0);
        }

        // Body
        for (int row = 1; row <= 17; row++) {
            int hourIndex = row - 1;
            grid.add(new Label((hourIndex + 6) + ":00"), 0, row);

            for (int col = 1; col <= 7; col++) {
                int dayIndex = weekOffset * 7 + (col - 1);
                LocalDate date = Calendar.getStartOfWeek(baseDate).plusDays(dayIndex);

                Pane cell = new Pane();
                cell.setPrefSize(150, 100);
                cell.setStyle("-fx-border-color: black;");

                Task taskInCell = null;
                if (gridData != null && gridData.containsKey(date)) {
                    taskInCell = gridData.get(date).returnDayGrid()[hourIndex];
                    if (taskInCell != null) {
                        Label label = new Label(taskInCell.getTaskName());
                        label.setWrapText(true);
                        cell.getChildren().add(label);
                        dragDrop.makeDraggable(cell, taskInCell, date, hourIndex);
                    }
                }

                final LocalDate finalDate = date;
                final int finalHourIndex = hourIndex;
                final Task finalTaskInCell = taskInCell;

                // Click vs small movement (so drag does not open popup)
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
                            dialogs.showTaskPopup(true, finalDate, finalHourIndex);
                        } else {
                            dialogs.showTaskInfoPopup(finalTaskInCell, finalDate, finalHourIndex);
                        }
                    }
                });

                cell.setOnMouseEntered(e -> {
                    if (finalTaskInCell == null) {
                        cell.setStyle("-fx-background-color: #ffccdd; -fx-border-color: black;");
                    }
                });
                cell.setOnMouseExited(e -> {
                    cell.setStyle("-fx-background-color: transparent; -fx-border-color: black;");
                });

                dragDrop.setupDropTarget(cell, date, hourIndex);
                grid.add(cell, col, row);
            }
        }

        sidePanel.setText("Unscheduled task:\n" + system.getEngine().printUnscheduledTask());
    }
}