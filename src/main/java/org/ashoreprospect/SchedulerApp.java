/** JavaFX entry: pick server, log in, then show the calendar window. */
package org.ashoreprospect;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.ashoreprospect.GUI.AppTheme;
import org.ashoreprospect.GUI.ServerPickView;
import org.ashoreprospect.GUI.WindowFrame;
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
        WindowFrame.install(stage);
        NetworkWatch.start();
        NetworkWatch.addListener(online -> {
            if (!online) return;
            Platform.runLater(() -> {
                if (wipeIfAccountDeleted()) returnToLogin();
            });
        });
        if (Api.INSTANCE.restoreSession()) {
            if (wipeIfAccountDeleted()) {
                showLogin();
                return;
            }
            org.ashoreprospect.crypto.Vault.unlockRemembered(Api.INSTANCE.getUserId());
            showMain();
            return;
        }
        wipeIfAccountDeleted();
        if (needServerPick()) {
            showServerPick();
        } else {
            showLogin();
        }
    }

    private static boolean needServerPick() {
        if (!Endpoints.hasSavedServer()) return true;
        if (Endpoints.isCreatorHost()) return false;
        if (Endpoints.isLocalHost()) return Endpoints.postgresPassword().isBlank();
        return false;
    }

    /**
     * If the server says this user id was deleted, wipe the local cache.
     * Offline devices do this the next time they can reach Node.
     */
    public static boolean wipeIfAccountDeleted() {
        String id = Api.INSTANCE.getUserId();
        if (id == null || id.isBlank()) {
            id = org.ashoreprospect.db.TaskRepository.peekCachedUserId();
        }
        if (id == null || id.isBlank()) return false;
        if (!NetworkWatch.isOnline()) return false;
        if (!Api.INSTANCE.isAccountDeleted(id)) return false;
        try {
            TaskManagementSystem.getInstance().getTaskRepo().wipeLocalCacheOnly();
        } catch (Exception e) {
            System.err.println("deleted-account wipe failed: " + e.getMessage());
        }
        org.ashoreprospect.crypto.Vault.eraseLocalKeys();
        Api.INSTANCE.logout();
        return true;
    }

    public static void returnToLogin() {
        returnToLogin(true);
    }

    /** Idle logout keeps the remember-me token. Menu / wipe logout still forgets it. */
    public static void returnToLogin(boolean forgetRemembered) {
        org.ashoreprospect.GUI.TaskReminders.stop();
        if (forgetRemembered) Api.INSTANCE.logout();
        else Api.INSTANCE.logoutKeepRemembered();
        org.ashoreprospect.crypto.Vault.lock();
        resetPostgres();
        TaskManagementSystem.resetInstance();
        org.ashoreprospect.GUI.AppTheme.clearListeners();
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
        stage.setTitle("Ashore Prospect");
        WindowFrame.snapWorkArea();
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
        spin.getStyleClass().add("boot-spin");
        spin.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent;");
        spin.skinProperty().addListener((o, a, b) -> Platform.runLater(() -> stripBootSpinPlate(spin)));
        Label msg = new Label(text);
        msg.setStyle("-fx-text-fill: white; -fx-font-size: 15px;");
        Label ver = new Label("Ashore Prospect " + AppVersion.LABEL);
        ver.setStyle("-fx-text-fill: #9aa4b2; -fx-font-size: 12px;");
        VBox box = new VBox(16, spin, msg, ver);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-background-color: #1c2430;");
        setStageRoot(box);
        box.setStyle("-fx-background-color: #1c2430;");
        Platform.runLater(() -> stripBootSpinPlate(spin));
        stage.setTitle("Ashore Prospect");
        WindowFrame.snapWorkArea();
        stage.show();
    }

    private static void stripBootSpinPlate(ProgressIndicator spin) {
        spin.applyCss();
        spin.layout();
        for (String sel : new String[]{".indicator", ".spinner", ".percentage", ".progress"}) {
            var n = spin.lookup(sel);
            if (n != null) n.setStyle("-fx-background-color: transparent;");
        }
    }

    private static void loadMain() {
        try {
            FXMLLoader loader = new FXMLLoader(SchedulerApp.class.getResource("/UIScheduler.fxml"));
            Parent root = loader.load();
            if (loader.getController() == null) {
                System.err.println("FXML controller is null");
            }
            setStageRoot(root);
            stage.setTitle("Ashore Prospect");
            WindowFrame.snapWorkArea();
            stage.show();
        } catch (Exception e) {
            e.printStackTrace();
            showLogin();
        }
    }

    public static void setStageRoot(Parent root) {
        if (stage == null) return;
        WindowFrame.setContent(root);
        AppTheme.apply(stage.getScene());
        stage.show();
    }



    public static void notifyNetwork(boolean online) {
        Platform.runLater(() -> showToast(
                online ? "Online" : "Offline — using local cache",
                !online
        ));
    }

    /** In-window popup reminder (task start). */
    public static void notifyUser(String title, String body) {
        String line = (title == null || title.isBlank() ? "" : title + " — ")
                + (body == null ? "" : body);
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showToast(line, false, 7));
        } else {
            showToast(line, false, 7);
        }
    }

    private static void showToast(String msg, boolean bad) {
        showToast(msg, bad, 2.6);
    }

    private static void showToast(String msg, boolean bad, double seconds) {
        if (stage == null || stage.getScene() == null) return;
        Parent root = stage.getScene().getRoot();
        StackPane stack = root instanceof StackPane s
                ? s
                : org.ashoreprospect.GUI.WindowFrame.frame();
        if (stack == null) return;
        Label toast = new Label(msg);
        toast.setWrapText(true);
        toast.setMaxWidth(420);
        toast.setStyle(
                (bad
                        ? "-fx-background-color: #8b1e1e; -fx-text-fill: #f3eee4;"
                        : "-fx-background-color: #1c2430; -fx-text-fill: #f3eee4;")
                        + "-fx-background-radius: 8; -fx-padding: 10 16; -fx-font-size: 13px;"
                        + "-fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 16, 0.2, 0, 4);"
        );
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 28, 0));
        stack.getChildren().add(toast);
        PauseTransition hide = new PauseTransition(Duration.seconds(seconds));
        hide.setOnFinished(e -> stack.getChildren().remove(toast));
        hide.play();
    }

    public static void main(String[] args) {
        launch(args);
    }
}