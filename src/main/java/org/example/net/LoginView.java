package org.example.net;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import static org.example.SchedulerApp.showServerPick;

public class LoginView {

    public void show(Stage stage, Runnable onOk) {
        Label kicker = new Label("ACCOUNT");
        kicker.setStyle("-fx-text-fill: #6b6358; -fx-font-size: 11px; -fx-font-family: Georgia;");

        Label title = new Label("Sign in");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #1c2430; -fx-font-family: Georgia;");

        Label where = new Label(org.example.net.Endpoints.nodeBase());
        where.setWrapText(true);
        where.setStyle("-fx-text-fill: #5c564c; -fx-font-size: 12px; -fx-font-family: Georgia;");

        TextField user = new TextField();
        user.setPromptText("username");
        user.setStyle(fieldStyle());

        PasswordField pass = new PasswordField();
        pass.setPromptText("password");
        pass.setStyle(fieldStyle());

        Label err = new Label();
        err.setWrapText(true);
        err.setStyle("-fx-text-fill: #8b1e1e; -fx-font-size: 13px;");

        Button login = new Button("Login");
        Button register = new Button("Register");
        Button changeServer = new Button("Change server");
        stylePrimary(login);
        styleGhost(register);
        styleGhost(changeServer);
        login.setMaxWidth(Double.MAX_VALUE);
        register.setMaxWidth(Double.MAX_VALUE);
        changeServer.setMaxWidth(Double.MAX_VALUE);

        login.setOnAction(e -> go(false, user.getText(), pass.getText(), err, login, register, onOk));
        register.setOnAction(e -> go(true, user.getText(), pass.getText(), err, login, register, onOk));
        changeServer.setOnAction(e -> showServerPick());

        VBox card = new VBox(12, kicker, title, where, user, pass, login, register, changeServer, err);
        card.setPadding(new Insets(36, 40, 36, 40));
        card.setMaxWidth(420);
        card.setPrefWidth(420);
        card.setStyle(
                "-fx-background-color: #f3eee4;"
                        + "-fx-background-radius: 16;"
                        + "-fx-border-color: #d9d0c0;"
                        + "-fx-border-radius: 16;"
                        + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 24, 0.2, 0, 8);"
        );

        StackPane root = new StackPane(card);
        root.setStyle("-fx-background-color: #1c2430;");
        root.setPadding(new Insets(40));
        StackPane.setAlignment(card, Pos.CENTER);

        if (stage.getScene() == null) {
            stage.setScene(new Scene(root, 900, 640));
        } else {
            stage.getScene().setRoot(root);
        }
        stage.setTitle("login");
        stage.setMaximized(true);
        stage.show();
    }

    private void go(boolean register, String user, String password,
                    Label err, Button login, Button registerBtn, Runnable onOk) {
        err.setText("");
        login.setDisable(true);
        registerBtn.setDisable(true);

        Thread t = new Thread(() -> {
            try {
                if (register) {
                    Api.INSTANCE.register(user, password);
                } else {
                    Api.INSTANCE.login(user, password);
                }
                Platform.runLater(onOk);
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    err.setText(ex.getMessage());
                    login.setDisable(false);
                    registerBtn.setDisable(false);
                });
            }
        }, "login-http");
        t.setDaemon(true);
        t.start();
    }

    private static String fieldStyle() {
        return "-fx-background-color: #ffffff;"
                + "-fx-text-fill: #1c2430;"
                + "-fx-prompt-text-fill: #8a8378;"
                + "-fx-font-size: 14px;"
                + "-fx-padding: 9 12;"
                + "-fx-background-radius: 6;";
    }

    private static void stylePrimary(Button b) {
        b.setStyle(
                "-fx-background-color: #1c2430; -fx-text-fill: #f3eee4;"
                        + "-fx-font-size: 14px; -fx-padding: 10 18; -fx-background-radius: 8; -fx-font-family: Georgia;"
        );
    }

    private static void styleGhost(Button b) {
        b.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #1c2430;"
                        + "-fx-border-color: #1c2430; -fx-border-radius: 8;"
                        + "-fx-font-size: 14px; -fx-padding: 8 18; -fx-font-family: Georgia;"
        );
    }
}