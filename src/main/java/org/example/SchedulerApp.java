package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.example.GUI.SchedulerController;
import org.example.net.Api;
import org.example.net.LoginView;

public class SchedulerApp extends Application {

    private static Stage stage;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        showLogin();
    }

    public static void returnToLogin() {
        Api.INSTANCE.logout();
        TaskManagementSystem.resetInstance();
        Platform.runLater(SchedulerApp::showLogin);
    }

    private static void showLogin() {
        if (stage == null) return;
        new LoginView().show(stage, SchedulerApp::showMain);
    }

    private static void showMain() {
        if (stage == null) return;
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(SchedulerApp::showMain);
            return;
        }
        System.out.println("showMain on FX thread");
        showBootScene("Loading workspace…");

        Thread boot = new Thread(() -> {
            try {
                System.out.println("boot-tms start");
                TaskManagementSystem.getInstance();
                System.out.println("boot-tms done");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            Platform.runLater(SchedulerApp::loadMain);
        }, "boot-tms");
        boot.setDaemon(true);
        boot.start();
    }

    private static void showBootScene(String text) {
        ProgressIndicator spin = new ProgressIndicator();
        spin.setMaxSize(48, 48);
        Label msg = new Label(text);
        msg.setStyle("-fx-text-fill: white; -fx-font-size: 15px;");
        VBox box = new VBox(16, spin, msg);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-background-color: #1c2430;");
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        if (stage.getScene() == null) {
            stage.setScene(new Scene(box, bounds.getWidth(), bounds.getHeight()));
        } else {
            stage.getScene().setRoot(box);
        }
        stage.setTitle("Scheduler");
        stage.setMaximized(true);
        stage.show();
    }

    private static void loadMain() {
        try {
            FXMLLoader loader = new FXMLLoader(SchedulerApp.class.getResource("/UIScheduler.fxml"));
            Parent root = loader.load();
            if (loader.getController() == null) {
                System.err.println("FXML controller is null");
            }
            if (stage.getScene() == null) {
                Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
                stage.setScene(new Scene(root, bounds.getWidth(), bounds.getHeight()));
            } else {
                stage.getScene().setRoot(root);
            }
            stage.setTitle("Scheduler");
            stage.setMaximized(true);
            stage.show();
        } catch (Exception e) {
            e.printStackTrace();
            showLogin();
        }
    }

    private static void setStageRoot(Parent root) {
        if (stage == null) return;
        boolean maximized = stage.isMaximized();
        boolean fullScreen = stage.isFullScreen();
        double w = stage.getWidth();
        double h = stage.getHeight();
        if (w <= 1 || h <= 1 || Double.isNaN(w) || Double.isNaN(h)) {
            Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
            w = bounds.getWidth();
            h = bounds.getHeight();
        }
        if (stage.getScene() == null) {
            stage.setScene(new Scene(root, w, h));
        } else {
            stage.getScene().setRoot(root);
        }
        stage.setTitle("Scheduler");
        if (fullScreen) {
            stage.setFullScreen(true);
        } else {
            stage.setMaximized(maximized);
            if (!maximized) {
                stage.setWidth(w);
                stage.setHeight(h);
            }
        }
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}