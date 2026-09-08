package org.example.net;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class LoginView {
    public void show(Stage stage, Runnable onOk) {
        TextField user = new TextField();
        user.setPromptText("username");
        PasswordField pass = new PasswordField();
        pass.setPromptText("password");
        Label err = new Label();
        Button login = new Button("login");
        Button register = new Button("register");

        login.setOnAction(e -> go(false, user.getText(), pass.getText(), err, login, register, onOk));
        register.setOnAction(e -> go(true, user.getText(), pass.getText(), err, login, register, onOk));

        VBox box = new VBox(8, user, pass, login, register, err);
        box.setPadding(new Insets(16));
        stage.setTitle("login");
        stage.setScene(new Scene(box, 280, 220));
        stage.show();
    }

    private void go(boolean register, String user, String password,
                    Label err, Button login, Button registerBtn, Runnable onOk) {
        err.setText("");
        login.setDisable(true);
        registerBtn.setDisable(true);

        Thread t = new Thread(() -> {
            try {
                System.out.println("login-http thread started");
                if (register) {
                    Api.INSTANCE.register(user, password);
                } else {
                    Api.INSTANCE.login(user, password);
                }
                Platform.runLater(onOk); // showMain on the JavaFX thread
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
}