package org.example;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.Popup;
import jdk.jfr.EventType;

import java.time.LocalDate;

public class SchedulerController {

    @FXML private StackPane root;
    @FXML private BorderPane borderPane;
    @FXML private GridPane grid;
    @FXML private Label sidePanel;
    @FXML private Button btnPrev, btnNext;
    @FXML private MenuItem btnImport, btnRun;

    private TaskManagementSystem system;
    private int weekOffset = 0;
    private LocalDate baseDate;
    private boolean popupIsOpen = false;
    private boolean areYouSureIsOpen = false;

    @FXML
    public void initialize() {
        system = TaskManagementSystem.getInstance();
        system.attachGUI(this);

        baseDate = LocalDate.now();

        btnPrev.setOnAction(e -> { weekOffset--; renderGrid(); });
        btnNext.setOnAction(e -> { weekOffset++; renderGrid(); });

        btnImport.setOnAction(e -> {
            system.command("addTaskFromFile");
            renderGrid();
        });

        btnRun.setOnAction(e -> {
            system.command("runScheduler");
            renderGrid();
        });

        Platform.runLater(this::renderGrid);
    }

    public void renderGrid() {
        grid.getChildren().clear();

        var gridData = system.getGrid();   // Changed to correct method

        // Header
        for (int col = 0; col < 7; col++) {
            int weekDayIndex = weekOffset * 7 + col;
            LocalDate date = Calendar.getStartOfWeek(baseDate).plusDays(weekDayIndex);

            String text = date.getDayOfWeek().toString().substring(0, 3) + " "
                    + date.getMonthValue() + "/" + date.getDayOfMonth();

            grid.add(new Label(text), col + 1, 0);
        }

        // Grid body
        for (int row = 1; row <= 17; row++) {
            int hourIndex = row - 1;
            int displayHour = hourIndex + 6;

            grid.add(new Label(displayHour + ":00"), 0, row);

            for (int col = 1; col <= 7; col++) {
                int dayIndex = weekOffset * 7 + (col - 1);
                LocalDate date = Calendar.getStartOfWeek(baseDate).plusDays(dayIndex);

                Pane cell = new Pane();
                cell.setPrefSize(110, 45);
                cell.setStyle("-fx-border-color: black;");

                // Show existing task
                if (gridData != null && gridData.containsKey(date)) {
                    Task task = gridData.get(date).returnDayGrid()[hourIndex];
                    if (task != null) {
                        Label taskLabel = new Label(task.getTaskName());
                        taskLabel.setWrapText(true);
                        cell.getChildren().add(taskLabel);
                    }
                }

                // Hover effect
                cell.setOnMouseEntered(e ->
                        cell.setStyle("-fx-background-color: #ffccdd; -fx-border-color: black;"));
                cell.setOnMouseExited(e ->
                        cell.setStyle("-fx-background-color: transparent; -fx-border-color: black;"));

                // Click to add task
                cell.setOnMouseClicked(e -> showTaskInputPopup(cell, date, hourIndex));

                grid.add(cell, col, row);
            }
        }

        sidePanel.setText("Unscheduled task: \n" + system.getEngine().printUnscheduledTask());
    }

    private void showTaskInputPopup(Pane owner, LocalDate date, int hourIndex) {
        StackPane dimmedOverlay = new StackPane();
        root.getChildren().add(dimmedOverlay);
        dimmedOverlay.prefWidthProperty().bind(root.widthProperty());
        dimmedOverlay.prefHeightProperty().bind(root.heightProperty());
        dimmedOverlay.setStyle("-fx-background-color: rgba(0, 0, 0, 0.6);");
        Pane popup = new Pane();
        root.getChildren().add(popup);
        popup.setPrefSize(400, 250);

        VBox content = new VBox(12);
        content.setStyle("-fx-background-color: #f8f8f8; -fx-padding: 20; "
                + "-fx-border-color: #333333; -fx-border-width: 2; "
                + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 10, 0, 0, 0);");

        // Title
        Label title = new Label("New Task @ " + date);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        // Task Name
        Label taskNameLabel = new Label("Task Name:");
        TextField taskNameField = new TextField();
        taskNameField.setPromptText("Enter task name");

        // Duration
        Label durationLabel = new Label("Duration (hours):");
        TextField durationField = new TextField("2");
        durationField.setPrefWidth(80);

        // Deadline
        Label deadlineLabel = new Label("Deadline (optional):");
        TextField deadlineDateField = new TextField();
        deadlineDateField.setPromptText("dd-MM-yyyy");

        // Fixed Task Checkbox
        CheckBox fixedCheckBox = new CheckBox("Fixed task (cannot be moved by scheduler)");

        // Buttons
        HBox buttonBox = new HBox(10);
        Button saveButton = new Button("Save Task");
        Button cancelButton = new Button("Cancel");
        buttonBox.getChildren().addAll(saveButton, cancelButton);

        saveButton.setOnAction(e -> {
            String name = taskNameField.getText().trim();
            if (name.isEmpty()) {
                // You can add alert here later
                return;
            }

            int duration = 2;
            try {
                duration = Integer.parseInt(durationField.getText().trim());
            } catch (Exception ex) {
                // default to 2
            }

            // TODO: Create Task and add to system here
            System.out.println("Task Created: " + name + " on " + date);

            root.getChildren().removeAll(popup,dimmedOverlay);
            renderGrid();
        });

        cancelButton.setOnAction(e -> {
            buttonBox.getChildren().removeAll(saveButton,cancelButton);
            content.getChildren().removeAll(title,taskNameLabel,taskNameField,durationLabel,durationField,deadlineLabel,deadlineDateField,fixedCheckBox,buttonBox);
            popup.getChildren().remove(content);
            root.getChildren().removeAll(popup,dimmedOverlay);
            popupIsOpen=false;
        });

        content.getChildren().addAll(
                title,
                taskNameLabel, taskNameField,
                durationLabel, durationField,
                deadlineLabel, deadlineDateField,
                fixedCheckBox,
                buttonBox
        );

        popup.getChildren().add(content);
        popupIsOpen = true;

        Scene scene = owner.getScene();
        StackPane dimmedOverlay2 = new StackPane();
        Pane areYouSure = new Pane();
        VBox areYouSureContent = new VBox(12);
        Label areYouSureMessage = new Label("Are you sure? Unsaved Changes can be discarded.");
        HBox areYouSureButtonBox = new HBox(10);
        Button discardAndLeaveButton = new Button("Discard and leave");
        Button cancelAreYouSureButton = new Button("Cancel");

        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && !areYouSureIsOpen) {
                areYouSureIsOpen = true;
                dimmedOverlay2.prefWidthProperty().bind(dimmedOverlay.widthProperty());
                dimmedOverlay2.prefHeightProperty().bind(dimmedOverlay.heightProperty());
                dimmedOverlay2.setStyle("-fx-background-color: rgba(0, 0, 0, 0.6);");
                areYouSure.setPrefSize(400, 250);
                areYouSureContent.setStyle("-fx-background-color: #f8f8f8; -fx-padding: 20; "
                        + "-fx-border-color: #333333; -fx-border-width: 2; "
                        + "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 10, 0, 0, 0);");


                areYouSureMessage.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");


                popup.getChildren().add(dimmedOverlay2);
                dimmedOverlay2.getChildren().add(areYouSure);
                areYouSure.getChildren().add(areYouSureContent);
                areYouSureContent.getChildren().addAll(areYouSureMessage, areYouSureButtonBox);
                areYouSureButtonBox.getChildren().addAll(discardAndLeaveButton, cancelAreYouSureButton);

                cancelAreYouSureButton.setOnAction(e -> {
                    areYouSureButtonBox.getChildren().removeAll(discardAndLeaveButton, cancelAreYouSureButton);
                    areYouSureContent.getChildren().removeAll(areYouSureMessage, areYouSureButtonBox);
                    areYouSure.getChildren().remove(areYouSureContent);
                    dimmedOverlay2.getChildren().remove(areYouSure);
                    popup.getChildren().remove(dimmedOverlay2);
                    areYouSureIsOpen = false;
                });
            } else if (event.getCode() == KeyCode.ESCAPE && areYouSureIsOpen) {
                areYouSureButtonBox.getChildren().removeAll(discardAndLeaveButton, cancelAreYouSureButton);
                areYouSureContent.getChildren().removeAll(areYouSureMessage, areYouSureButtonBox);
                areYouSure.getChildren().remove(areYouSureContent);
                dimmedOverlay2.getChildren().remove(areYouSure);
                popup.getChildren().remove(dimmedOverlay2);
                areYouSureIsOpen = false;
            }

        });



    }



    public void refresh() {
        renderGrid();
    }
}