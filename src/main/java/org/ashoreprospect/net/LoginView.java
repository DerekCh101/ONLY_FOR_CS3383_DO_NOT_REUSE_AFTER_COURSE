/** Login, register, and password-reset screens. */
package org.ashoreprospect.net;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.ashoreprospect.GUI.AppTheme;
import org.ashoreprospect.crypto.Vault;
import org.ashoreprospect.db.TaskRepository;

import java.util.Map;

import static org.ashoreprospect.SchedulerApp.showServerPick;

public class LoginView {

    public void show(Stage stage, Runnable onOk) {
        VBox[] loginHold = new VBox[1];
        VBox[] resetHold = new VBox[1];
        VBox[] registerHold = new VBox[1];
        loginHold[0] = buildLoginCard(onOk, () -> {
            loginHold[0].setVisible(false);
            registerHold[0].setVisible(true);
        }, () -> {
            loginHold[0].setVisible(false);
            resetHold[0].setVisible(true);
        });
        registerHold[0] = buildRegisterCard(onOk, () -> {
            registerHold[0].setVisible(false);
            loginHold[0].setVisible(true);
        });
        resetHold[0] = buildResetCard(() -> {
            resetHold[0].setVisible(false);
            loginHold[0].setVisible(true);
        });
        registerHold[0].setVisible(false);
        resetHold[0].setVisible(false);

        StackPane root = new StackPane(loginHold[0], registerHold[0], resetHold[0]);
        root.setStyle("-fx-background-color: #1c2430;");
        root.setPadding(new Insets(40));
        StackPane.setAlignment(loginHold[0], Pos.CENTER);
        StackPane.setAlignment(registerHold[0], Pos.CENTER);
        StackPane.setAlignment(resetHold[0], Pos.CENTER);

        org.ashoreprospect.SchedulerApp.setStageRoot(root);
        stage.setTitle("Ashore Prospect");
        org.ashoreprospect.GUI.WindowFrame.snapWorkArea();
        stage.show();
    }

    private VBox buildLoginCard(Runnable onOk, Runnable showRegister, Runnable showReset) {
        Label kicker = muted("ACCOUNT");
        Label title = heading("Sign in");
        Label where = muted(Endpoints.nodeBase());

        TextField user = field("username");
        PasswordField pass = secret("password");
        String last = LocalSession.lastUsername();
        if (!last.isBlank()) user.setText(last);

        CheckBox remember = new CheckBox("Remember login");
        remember.setSelected(LocalSession.wantRemember());
        remember.setStyle("-fx-text-fill: " + AppTheme.cardInk() + "; -fx-font-size: 13px; " + AppTheme.font());
        Label rememberHint = muted("Checked: next launch skips this screen and opens the local cache, even offline.");

        Label err = error();
        Button login = new Button("Login");
        Button register = new Button("Register");
        Button changeServer = new Button("Change server");
        Button forgot = new Button("Forgot password");
        stylePrimary(login);
        styleGhost(register);
        styleGhost(changeServer);
        styleGhost(forgot);
        stretch(login, register, changeServer, forgot);

        login.setOnAction(e -> goLogin(user.getText(), pass.getText(), remember.isSelected(),
                err, login, register, onOk));
        changeServer.setOnAction(e -> showServerPick());

        VBox card = card(
                kicker, title, where, user, pass, remember, rememberHint,
                login, register, forgot, changeServer, err
        );

        register.setOnAction(e -> showRegister.run());
        forgot.setOnAction(e -> showReset.run());
        return card;
    }

    private VBox buildRegisterCard(Runnable onOk, Runnable back) {
        Label title = heading("Create account");
        Label hint = muted(
                "Email and a security question are required. Without both, "
                        + "Forgot password cannot recover the account. "
                        + PasswordRules.HINT
        );
        TextField user = field("username");
        PasswordField pass = secret("password (8+, A-z, number, symbol)");
        TextField email = field("email");
        TextField question = field("security question (your own words, min 8)");
        PasswordField answer = secret("security answer (min 4)");
        CheckBox remember = new CheckBox("Remember login");
        remember.setSelected(LocalSession.wantRemember());
        remember.setStyle("-fx-text-fill: " + AppTheme.cardInk() + "; -fx-font-size: 13px; " + AppTheme.font());
        Label err = error();
        Button create = new Button("Create account");
        Button cancel = new Button("Back to login");
        stylePrimary(create);
        styleGhost(cancel);
        stretch(create, cancel);
        create.setOnAction(e -> goRegister(
                user.getText(), pass.getText(), email.getText(),
                question.getText(), answer.getText(), remember.isSelected(),
                err, create, cancel, onOk
        ));
        cancel.setOnAction(e -> back.run());
        return card(title, hint, user, pass, email, question, answer, remember, create, cancel, err);
    }

    private static VBox buildResetCard(Runnable back) {
        Label title = heading("Reset password");
        Label hint = muted(
                "Email + security question. Email alone is not enough.\n"
                        + "Token and question always print on the host Node terminal, "
                        + "even if mail is on. Sender and receiver may be the same Gmail. "
                        + "Token lasts 8 minutes."
        );
        TextField user = field("username");
        TextField email = field("email on the account");
        Label question = muted("Security question arrives with the token.");
        question.setWrapText(true);
        TextField token = field("reset token from email / host console");
        PasswordField answer = secret("security answer");
        PasswordField np = secret("new password (8+, A-z, number, symbol)");
        Label err = error();
        Button send = new Button("Email me a token");
        Button apply = new Button("Reset with token + answer");
        Button cancel = new Button("Back to login");
        stylePrimary(send);
        stylePrimary(apply);
        styleGhost(cancel);
        stretch(send, apply, cancel);
        send.setOnAction(e -> {
            err.setText("");
            send.setDisable(true);
            send.setText("Working…");
            Thread t = new Thread(() -> {
                try {
                    Api.INSTANCE.forgotPassword(user.getText(), email.getText());
                    Platform.runLater(() -> {
                        err.setText("If that account exists, the token and security question are on the host Node terminal (and email if SMTP worked). Nothing is filled in here.");
                        send.setDisable(false);
                        send.setText("Email me a token");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        err.setText(ex.getMessage());
                        send.setDisable(false);
                        send.setText("Email me a token");
                    });
                }
            }, "forgot-http");
            t.setDaemon(true);
            t.start();
        });
        apply.setOnAction(e -> {
            err.setText("");
            String weak = PasswordRules.reject(np.getText());
            if (weak != null) {
                err.setText(weak);
                return;
            }
            apply.setDisable(true);
            apply.setText("Working…");
            Thread t = new Thread(() -> {
                try {
                    Api.INSTANCE.resetPassword(token.getText(), answer.getText(), np.getText());
                    Platform.runLater(() -> {
                        err.setText("Password reset. All sessions were signed out. Log in.");
                        apply.setDisable(false);
                        apply.setText("Reset with token + answer");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        err.setText(ex.getMessage());
                        apply.setDisable(false);
                        apply.setText("Reset with token + answer");
                    });
                }
            }, "reset-http");
            t.setDaemon(true);
            t.start();
        });
        cancel.setOnAction(e -> back.run());
        return card(title, hint, user, email, send, question, token, answer, np, apply, cancel, err);
    }

    private void goLogin(String user, String password, boolean remember,
                         Label err, Button login, Button registerBtn, Runnable onOk) {
        err.setText("");
        login.setDisable(true);
        registerBtn.setDisable(true);
        Thread t = new Thread(() -> {
            try {
                NodeApi.AuthResult incoming = Api.INSTANCE.authenticate(false, user, password);
                finishAuth(incoming, remember, password, err, login, registerBtn, onOk);
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

    private void goRegister(String user, String password, String email, String question, String answer,
                            boolean remember, Label err, Button create, Button cancel, Runnable onOk) {
        err.setText("");
        if (blank(user) || blank(password) || blank(email) || blank(question) || blank(answer)) {
            err.setText("Username, password, email, security question, and answer are all required.");
            return;
        }
        String weak = PasswordRules.reject(password);
        if (weak != null) {
            err.setText(weak);
            return;
        }
        create.setDisable(true);
        cancel.setDisable(true);
        Thread t = new Thread(() -> {
            try {
                NodeApi.AuthResult incoming = Api.INSTANCE.registerAccount(
                        user, password, email, question, answer);
                finishAuth(incoming, remember, password, err, create, cancel, onOk);
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    err.setText(ex.getMessage());
                    create.setDisable(false);
                    cancel.setDisable(false);
                });
            }
        }, "register-http");
        t.setDaemon(true);
        t.start();
    }

    private void finishAuth(NodeApi.AuthResult incoming, boolean remember, String password,
                            Label err, Button a, Button b, Runnable onOk) {
        Vault.unlockWithPassword(incoming.userId, password);
        String cached = TaskRepository.peekCachedUserId();
        Platform.runLater(() -> {
            if (cached != null && !cached.equals(incoming.userId) && !confirmReplaceCache()) {
                a.setDisable(false);
                b.setDisable(false);
                return;
            }
            Api.INSTANCE.applySession(incoming, remember);
            onOk.run();
        });
    }

    private static boolean confirmReplaceCache() {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Replace local cache?");
        a.setHeaderText("This account is not the one stored on this PC.");
        a.setContentText(
                "Continuing deletes the offline tasks for the previous account on this device.\n\n"
                        + "Cancel keeps the current cache and stays on login."
        );
        return a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private static VBox card(javafx.scene.Node... kids) {
        VBox box = new VBox(12, kids);
        box.getStyleClass().add("login-card");
        box.setPadding(new Insets(36, 40, 36, 40));
        box.setMaxWidth(420);
        box.setPrefWidth(420);
        return box;
    }

    private static Label heading(String t) {
        Label l = new Label(t);
        l.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: "
                + AppTheme.cardInk() + "; " + AppTheme.font());
        return l;
    }

    private static Label muted(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("login-muted");
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 12px; -fx-text-fill: "
                + AppTheme.cardMuted() + "; " + AppTheme.font());
        return l;
    }

    private static Label error() {
        Label l = new Label();
        l.getStyleClass().add("login-err");
        l.setWrapText(true);
        l.setStyle("-fx-text-fill: #8b1e1e; -fx-font-size: 13px;");
        return l;
    }

    private static TextField field(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.setStyle(fieldStyle());
        return f;
    }

    private static PasswordField secret(String prompt) {
        PasswordField f = new PasswordField();
        f.setPromptText(prompt);
        f.setStyle(fieldStyle());
        return f;
    }

    private static String fieldStyle() {
        return AppTheme.fieldCss();
    }

    private static void stretch(Button... buttons) {
        for (Button b : buttons) b.setMaxWidth(Double.MAX_VALUE);
    }

    private static void stylePrimary(Button b) {
        b.setStyle(AppTheme.cardPrimaryBtnCss() + "-fx-font-size: 14px;");
    }

    private static void styleGhost(Button b) {
        b.setStyle(AppTheme.cardGhostBtnCss() + "-fx-font-size: 14px;");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
