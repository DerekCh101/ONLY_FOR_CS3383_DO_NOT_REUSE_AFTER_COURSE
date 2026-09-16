package org.example.GUI;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.example.net.Api;
import org.example.net.Endpoints;

public final class ServerPickView extends VBox {

    private static final String FONT =
            "-fx-font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif;";

    public interface Done {
        void run();
    }

    public ServerPickView(Done onPicked) {
        setAlignment(Pos.CENTER);
        setFillWidth(true);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setStyle("-fx-background-color: #1c2430;");
        setPadding(new Insets(0));

        VBox card = new VBox(22);
        card.setMaxWidth(640);
        card.setPrefWidth(640);
        card.setPadding(new Insets(40, 44, 40, 44));
        card.setStyle(
                "-fx-background-color: #f3eee4;"
                        + "-fx-background-radius: 16;"
                        + "-fx-border-color: #d9d0c0;"
                        + "-fx-border-radius: 16;"
                        + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 24, 0.2, 0, 8);"
        );

        Label kicker = new Label("SERVER");
        kicker.setStyle("-fx-text-fill: #6b6358; -fx-font-size: 11px;" + FONT);

        Label title = new Label("Where should this copy talk?");
        title.setWrapText(true);
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #1c2430;" + FONT);

        Label current = new Label("Now: " + Endpoints.nodeBase());
        current.setWrapText(true);
        current.setStyle("-fx-text-fill: #3d4550; -fx-font-size: 13px;" + FONT);

        ToggleGroup g = new ToggleGroup();

        RadioButton creator = radio("Use the creator's server");
        Label creatorHint = hint("The hosted copy. Different database. Your PC data is not deleted. Current Version not implemented.");

        RadioButton thisPc = radio("This computer is the server");
        Label thisHint = hint("You must have Postgres and Node, and run them (node server.js) on this PC. Uses http://127.0.0.1:3456");

        RadioButton other = radio("This device is a client from your self-host server (Tailscale or wifi)");
        Label otherHint = hint("This window is a client. Same host PC = same data. Only the address changes.");

        creator.setToggleGroup(g);
        thisPc.setToggleGroup(g);
        other.setToggleGroup(g);

        Label need = new Label(
                "Self-host only (this PC or client):\n"
                        + "1. PostgreSQL must already be installed on the host PC.\n"
                        + "2. The Node process must be running (node server.js) on the host PC.\n"
                        + "If either is missing, login will fail."
        );
        need.setWrapText(true);
        need.setStyle("-fx-text-fill: #1c2430; -fx-font-size: 13px; -fx-line-spacing: 4px;" + FONT);

        Label hostIp = label("Host address");
        TextField custom = field("http://100.113.239.82:3456");

        Label pwLabel = label("postgres_pw");
        PasswordField pwField = new PasswordField();
        pwField.setText(Endpoints.postgresPassword());
        pwField.setPromptText("Password that already works in pgAdmin");
        pwField.setStyle(fieldStyle());

        Label pwHint = hint(
                "Type the password you already use for Postgres user postgres.\n"
                        + "So it will help you set it as the environment variable postgres_pw\n"
                        + "That is: (Windows: setx postgres_pw).\n"
                        + "Not used if you pick the creator's server."
        );

        Label dataNote = new Label(
                "This PC ↔ Tailscale to the same PC does not wipe tasks.\n"
                        + "Creator's server is a different database, so your data might be different there"
        );
        dataNote.setWrapText(true);
        dataNote.setStyle("-fx-text-fill: #1c2430; -fx-font-size: 13px; -fx-line-spacing: 4px;" + FONT);

        String saved = Endpoints.hasSavedServer() ? Endpoints.nodeBase() : "";
        if (!saved.isBlank() && saved.startsWith(Endpoints.CREATOR_HOST)) {
            creator.setSelected(true);
        } else if (saved.contains("127.0.0.1") || saved.contains("localhost") || saved.isBlank()) {
            thisPc.setSelected(true);
        } else {
            other.setSelected(true);
            custom.setText(saved);
        }

        Runnable updateFields = () -> {
            boolean remote = other.isSelected();
            boolean selfHost = thisPc.isSelected() || other.isSelected();
            setShown(need, selfHost);
            setShown(hostIp, remote);
            setShown(custom, remote);
            custom.setDisable(!remote);
            if (remote && custom.getText().isBlank()) {
                custom.setText(Endpoints.DEFAULT_TAILSCALE_NODE);
            }
            setShown(pwLabel, selfHost);
            setShown(pwField, selfHost);
            setShown(pwHint, selfHost);
        };
        creator.selectedProperty().addListener((o, a, b) -> updateFields.run());
        thisPc.selectedProperty().addListener((o, a, b) -> updateFields.run());
        other.selectedProperty().addListener((o, a, b) -> updateFields.run());
        updateFields.run();

        Label remember = hint("Remembered on this PC. You can change it later on the login screen.");

        Label err = new Label();
        err.setWrapText(true);
        err.setStyle("-fx-text-fill: #8b1e1e; -fx-font-size: 13px;" + FONT);

        Button ok = new Button("Continue");
        ok.setDefaultButton(true);
        ok.setMaxWidth(Double.MAX_VALUE);
        ok.setStyle(
                "-fx-background-color: #1c2430;"
                        + "-fx-text-fill: #f3eee4;"
                        + "-fx-font-size: 15px;"
                        + "-fx-padding: 12 18;"
                        + "-fx-background-radius: 8;"
                        + FONT
        );
        ok.setOnAction(e -> {
            String url;
            if (thisPc.isSelected()) {
                url = Endpoints.LOCAL_NODE;
            } else if (creator.isSelected()) {
                url = Endpoints.CREATOR_HOST;
                if (url.contains("YOUR-DOMAIN")) {
                    err.setText("Creator's server is not set up yet.");
                    return;
                }
            } else {
                url = custom.getText() == null ? "" : custom.getText().trim();
                if (url.isBlank() || !url.startsWith("http")) {
                    err.setText("Enter the host URL, e.g. http://100.113.239.82:3456");
                    return;
                }
            }
            if (thisPc.isSelected() || other.isSelected()) {
                String pw = pwField.getText() == null ? "" : pwField.getText().trim();
                if (pw.isBlank()) {
                    err.setText("Enter postgres_pw.");
                    return;
                }
                Endpoints.saveNode(url);
                Endpoints.savePostgresPassword(pw);
            } else {
                Endpoints.saveNode(url);
            }
            try {
                Api.INSTANCE.logout();
            } catch (Exception ignored) {
            }
            if (onPicked != null) onPicked.run();
        });

        VBox creatorBlock = block(creator, creatorHint);
        VBox thisBlock = block(thisPc, thisHint);
        VBox otherBlock = block(other, otherHint);

        card.getChildren().addAll(
                kicker, title, current,
                creatorBlock,
                thisBlock,
                otherBlock,
                need,
                hostIp, custom,
                pwLabel, pwField, pwHint,
                dataNote, remember, err, ok
        );

        StackPane inner = new StackPane(card);
        inner.setAlignment(Pos.CENTER);
        inner.setPadding(new Insets(28));
        inner.setStyle("-fx-background-color: #1c2430;");
        StackPane.setAlignment(card, Pos.CENTER);

        ScrollPane scroll = new ScrollPane(inner);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: #1c2430; -fx-background-color: #1c2430;");

        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().add(scroll);
    }

    private static VBox block(RadioButton radio, Label hint) {
        VBox b = new VBox(6, radio, hint);
        return b;
    }

    private static void setShown(javafx.scene.Node n, boolean on) {
        n.setVisible(on);
        n.setManaged(on);
    }

    private static RadioButton radio(String text) {
        RadioButton r = new RadioButton(text);
        r.setWrapText(true);
        r.setStyle("-fx-text-fill: #1c2430; -fx-font-size: 15px;" + FONT);
        return r;
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #1c2430; -fx-font-size: 13px;" + FONT);
        return l;
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill: #5c564c; -fx-font-size: 12px; -fx-line-spacing: 3px;" + FONT);
        return l;
    }

    private static TextField field(String prompt) {
        TextField t = new TextField();
        t.setPromptText(prompt);
        t.setStyle(fieldStyle());
        return t;
    }

    private static String fieldStyle() {
        return "-fx-background-color: #ffffff;"
                + "-fx-text-fill: #1c2430;"
                + "-fx-prompt-text-fill: #8a8378;"
                + "-fx-font-size: 13px;"
                + "-fx-padding: 10 12;"
                + "-fx-background-radius: 6;"
                + FONT;
    }
}