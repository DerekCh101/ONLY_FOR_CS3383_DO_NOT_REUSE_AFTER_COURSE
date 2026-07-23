package org.example;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.geometry.Rectangle2D;
import org.example.GUI.SchedulerController;

public class SchedulerApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/UIScheduler.fxml"));
        Parent root = loader.load();

        SchedulerController controller = loader.getController();  // important!

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(root, bounds.getWidth() * 0.8, bounds.getHeight() * 0.8);

        primaryStage.setTitle("Scheduler");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}