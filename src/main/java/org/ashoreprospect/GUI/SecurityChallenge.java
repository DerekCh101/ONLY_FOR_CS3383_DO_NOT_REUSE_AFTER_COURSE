/** Optional security-question pause on Remember-me login. */
package org.ashoreprospect.GUI;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.ashoreprospect.net.Api;

import java.util.Map;

/**
 * Occasional online check when Remember me is on.
 * Answer is verified on Node — nothing to brute-force in the local cache.
 */
public final class SecurityChallenge {

    private SecurityChallenge() {}

    public static void ask(StackPane root, Runnable onFail) {
        if (root == null) return;
        Thread t = new Thread(() -> {
            try {
                Map<String, String> q = Api.INSTANCE.challengeQuestion();
                boolean has = "true".equalsIgnoreCase(q.get("hasSecurity"));
                String question = q.get("securityQuestion");
                if (!has || question == null || question.isBlank()) return;
                Platform.runLater(() -> show(root, question, onFail));
            } catch (Exception ignored) {
            }
        }, "sec-challenge");
        t.setDaemon(true);
        t.start();
    }

    private static void show(StackPane root, String question, Runnable onFail) {
        StackPane dim = GuiHelper.createDimmedOverlay(root);
        VBox box = GuiHelper.createWhiteBox();
        box.setMaxWidth(420);
        Label h = new Label("Confirm it is you");
        h.setStyle("-fx-font-weight: bold; -fx-font-size: 16px; " + AppTheme.font()
                + "-fx-text-fill: " + AppTheme.cardInk() + ";");
        Label q = new Label(question);
        q.setWrapText(true);
        q.setStyle(AppTheme.font() + "-fx-font-size: 13px; -fx-text-fill: " + AppTheme.cardInk() + ";");
        PasswordField ans = new PasswordField();
        ans.setPromptText("Your answer");
        ans.setStyle(AppTheme.fieldCss());
        Label err = new Label();
        err.setStyle("-fx-text-fill: #c45c4a;");
        Button ok = new Button("Continue");
        ok.setStyle(AppTheme.cardPrimaryBtnCss());
        Button cancel = new Button("Logout");
        cancel.setStyle(AppTheme.cardGhostBtnCss());
        ok.setDefaultButton(true);
        VBox inner = new VBox(10, h, q, ans, err, ok, cancel);
        inner.setPadding(new Insets(4));
        box.getChildren().add(inner);
        GuiHelper.paintInk(box);
        dim.getChildren().add(box);
        StackPane.setAlignment(box, Pos.CENTER);
        root.getChildren().add(dim);

        Runnable fail = () -> {
            root.getChildren().remove(dim);
            if (onFail != null) onFail.run();
        };
        cancel.setOnAction(e -> fail.run());
        ok.setOnAction(e -> {
            ok.setDisable(true);
            Thread th = new Thread(() -> {
                try {
                    Api.INSTANCE.challengeAnswer(ans.getText());
                    Platform.runLater(() -> root.getChildren().remove(dim));
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        err.setText("Wrong answer — signing out.");
                        PauseTransitionHack.run(fail);
                    });
                }
            }, "sec-answer");
            th.setDaemon(true);
            th.start();
        });
    }

    private static final class PauseTransitionHack {
        static void run(Runnable r) {
            javafx.animation.PauseTransition p =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(0.8));
            p.setOnFinished(e -> r.run());
            p.play();
        }
    }
}
