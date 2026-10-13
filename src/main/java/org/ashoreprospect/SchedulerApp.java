package org.ashoreprospect;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.ashoreprospect.GUI.ServerPickView;
import org.ashoreprospect.net.Api;
import org.ashoreprospect.net.Endpoints;
import org.ashoreprospect.net.LoginView;
import org.ashoreprospect.net.NetworkWatch;

import static org.ashoreprospect.GUI.TypewriterStore.resetPostgres;

public class SchedulerApp extends Application {

    private static Stage stage;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        NetworkWatch.start();
        if (Api.INSTANCE.restoreSession()) {
            showMain();
            return;
        }
        if (needServerPick()) {
            showServerPick();
        } else {
            showLogin();
        }
    }

    private static boolean needServerPick() {
        if (!Endpoints.hasSavedServer()) return true;
        if (Endpoints.isCreatorHost()) return false;
        return Endpoints.postgresPassword().isBlank();
    }

    public static void returnToLogin() {
        Api.INSTANCE.logout();
        resetPostgres();
        TaskManagementSystem.resetInstance();
        Platform.runLater(SchedulerApp::showLogin);
    }

    public static void showServerPick() {
        if (stage == null) return;
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(SchedulerApp::showServerPick);
            return;
        }
        ServerPickView pick = new ServerPickView(SchedulerApp::showLogin);
        StackPane wrap = new StackPane(pick);
        wrap.setStyle("-fx-background-color: #1c2430;");
        setStageRoot(wrap);
        stage.setMaximized(true);
        stage.setTitle("Scheduler");
        stage.show();

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



    public static void notifyNetwork(boolean online) {
        Platform.runLater(() -> showToast(
                online ? "Online" : "Offline — using local cache",
                !online
        ));
    }

    private static void showToast(String msg, boolean bad) {
        if (stage == null || stage.getScene() == null) return;
        Parent root = stage.getScene().getRoot();
        if (!(root instanceof StackPane stack)) return;
        Label toast = new Label(msg);
        toast.setStyle(
                (bad
                        ? "-fx-background-color: #8b1e1e; -fx-text-fill: #f3eee4;"
                        : "-fx-background-color: #1c2430; -fx-text-fill: #f3eee4;")
                        + "-fx-background-radius: 8; -fx-padding: 10 16; -fx-font-size: 13px;"
                        + "-fx-font-family: Georgia; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 16, 0.2, 0, 4);"
        );
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 28, 0));
        stack.getChildren().add(toast);
        PauseTransition hide = new PauseTransition(Duration.seconds(2.6));
        hide.setOnFinished(e -> stack.getChildren().remove(toast));
        hide.play();
    }

    public static void main(String[] args) {
        launch(args);
    }
}