package org.example;


import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx. geometry.Rectangle2D;

import javax.swing.*;
import java.time.LocalDate;
import java.util.Map;

public class SchedulerApp extends Application {

    private TaskManagementSystem system;

    private Map<LocalDate, Day> gridData;

    private int weekOffset = 0;

    private GridPane grid;

    private Label sidePanel;

    private LocalDate baseDate;

    @Override
    public void start(Stage primaryStage) {

        system = TaskManagementSystem.getInstance();
        system.attachGUI(this);

        baseDate = LocalDate.now();

        HBox root = new HBox();
        root.setSpacing(5);
        VBox leftPanel = new VBox();
        Button prev = new Button("Prev");
        Button next = new Button("Next");
        Button importTaskFromFile = new Button("Import From Files");
        Button RunScheduler = new Button("Run Scheduler");
        HBox spacer = new HBox();
        HBox.setHgrow(spacer,Priority.ALWAYS);

        prev.setOnAction(e -> {
                weekOffset--;
                renderGrid();

        });

        next.setOnAction(e -> {
           {
                weekOffset++;
                renderGrid();
            }
        });

        importTaskFromFile.setOnAction(e->{
            system.command("addTaskFromFile");
            system.command("update");
            renderGrid();
        });


        HBox topBar = new HBox(importTaskFromFile,spacer,prev,next);
        leftPanel.getChildren().add(topBar);

        grid = new GridPane();
        grid.setGridLinesVisible(false);
        grid.setHgap(0);
        grid.setVgap(0);
        grid.setStyle("-fx-border-color: black;");
        leftPanel.getChildren().add(new ScrollPane(grid));

        root.getChildren().add(leftPanel);

        VBox sidePanelRows = new VBox();

        sidePanel = new Label("Unscheduled Task: \n");
        sidePanelRows.getChildren().add(sidePanel);
        root.getChildren().add(sidePanelRows);


        Rectangle2D screenbounds = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(root, screenbounds.getWidth()*0.5, screenbounds.getHeight()*0.5);
        primaryStage.setTitle("Scheduler");
        primaryStage.setScene(scene);
        primaryStage.show();

        renderGrid();


    }

    public void renderGrid() {

        grid.getChildren().clear();

        gridData = system.getGrid();
        //rendering the grid header
        for (int col = 0; col < 7; col++) {
        // Header with actual dates like "Wed 4/7"
            int weekDayIndex = weekOffset * 7 + col;
            LocalDate fromStartOfTheWeek = Calendar.getStartOfWeek(baseDate);

            String text =
                    fromStartOfTheWeek.plusDays(weekDayIndex).getDayOfWeek().toString().substring(0, 3)
                    + " "
                    + fromStartOfTheWeek.plusDays(weekDayIndex).getMonthValue()
                    + "/"
                    + fromStartOfTheWeek.plusDays(weekDayIndex).getDayOfMonth();

            grid.add(new Label(text), col + 1, 0);
        }

        // rendering the Time rows, so basically filling from right to left first then downward repeat
        for (int row = 1; row <= 17; row++) {

            int hourIndex = row - 1;
            int displayHour = hourIndex + 6;

            grid.add(new Label(displayHour + ":00"), 0, row);

            for (int col = 1; col <= 7; col++) {

                int dayIndex = weekOffset * 7 + (col - 1);
                LocalDate date = Calendar.getStartOfWeek(baseDate).plusDays(dayIndex);

                Pane cell = new Pane();
                cell.setPrefSize(100, 40);
                cell.setStyle("-fx-border-color: black;");

                if (gridData != null && dayIndex < 365*5) {
                    if(!gridData.containsKey(date)){
                        system.getEngine().putIfAbsent(date);
                    //gridData.putIfAbsent(date,new Day(system.getBlockedTime()));
                    }
                    Task task = gridData.get(date).returnDayGrid()[hourIndex];

                    if (task != null) {
                        cell.getChildren().add(
                                new Label(task.getTaskName()));
                    }
                }
                cell.setOnMouseMoved(event -> {
                    cell.setStyle("-fx-background-color: rgb(100,30,50);-fx-border-color: black; -fx-opacity: 70%;");

                });
                cell.setOnMouseExited(event ->{
                    cell.setStyle("-fx-background-color: transparent;-fx-border-color: black;");
                });
                grid.add(cell, col, row);
                }

        }
        sidePanel.setText("Unscheduled task: \n");
        system.getEngine().
        sidePanel.setText(sidePanel.getText()+ system.getEngine().printUnscheduledTask());


    }

    public static void main(String[] args) {
        launch(args);
    }
}