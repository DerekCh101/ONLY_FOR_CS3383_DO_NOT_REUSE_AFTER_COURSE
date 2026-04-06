import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;

import java.time.LocalDate;

public class SchedulerApp extends Application {

    private TaskManagementSystem system;

    private Task[][] gridData;

    private int weekOffset = 0;

    private GridPane grid;

    private LocalDate baseDate;

    @Override
    public void start(Stage primaryStage) {

        system = TaskManagementSystem.getInstance();
        system.attachGUI(this);

        baseDate = LocalDate.now();

        grid = new GridPane();

        Button prev = new Button("Prev");
        Button next = new Button("Next");

        prev.setOnAction(e -> {
            if (weekOffset > 0) {
                weekOffset--;
                renderGrid();
            }
        });

        next.setOnAction(e -> {
            if ((weekOffset + 1) * 7 < 56) {
                weekOffset++;
                renderGrid();
            }
        });

        GridPane root = new GridPane();
        root.add(prev, 0, 0);
        root.add(next, 1, 0);
        root.add(new ScrollPane(grid), 0, 1, 10, 1);

        Scene scene = new Scene(root, 900, 500);
        primaryStage.setTitle("Scheduler");
        primaryStage.setScene(scene);
        primaryStage.show();

        renderGrid();

        // Start CLI in background thread
        new Thread(() -> {
            system.command(null);
        }).start();
    }

    public void renderGrid() {

        grid.getChildren().clear();

        gridData = system.getGrid();

        // Header with actual dates
        for (int col = 0; col < 7; col++) {

            int dayIndex = weekOffset * 7 + col;
            LocalDate currentDate = baseDate.plusDays(dayIndex);

            String text =
                    currentDate.getDayOfWeek().toString().substring(0, 3)
                    + " "
                    + currentDate.getMonthValue()
                    + "/"
                    + currentDate.getDayOfMonth();

            grid.add(new Label(text), col + 1, 0);
        }

        // Time rows
        for (int row = 1; row <= 17; row++) {

            int hourIndex = row - 1;
            int displayHour = hourIndex + 6;

            grid.add(new Label(displayHour + ":00"), 0, row);

            for (int col = 1; col <= 7; col++) {

                int dayIndex = weekOffset * 7 + (col - 1);

                Pane cell = new Pane();
                cell.setPrefSize(100, 40);

                if (gridData != null && dayIndex < 56) {
                    Task task = gridData[dayIndex][hourIndex];

                    if (task != null) {
                        cell.getChildren().add(
                                new Label(task.getTaskName()));
                    }
                }

                grid.add(cell, col, row);
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}