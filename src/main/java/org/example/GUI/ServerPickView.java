package org.example.GUI;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import org.example.net.Api;
import org.example.net.Endpoints;

public final class ServerPickView extends VBox {

    public interface Done {
        void run();
    }

    public ServerPickView(Done onPicked) {
        setSpacing(14);
        setPadding(new Insets(32));
        setAlignment(Pos.TOP_LEFT);
        setMaxWidth(560);
        setStyle(
                "-fx-background-color: #f3eee4;"
                        + "-fx-background-radius: 12;"
                        + "-fx-border-color: #cfc6b6;"
                        + "-fx-border-radius: 12;"
        );

        Label title = new Label("Choose a server");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #1c2430; -fx-font-family: Georgia;");

        ToggleGroup g = new ToggleGroup();

        RadioButton creator = radio("Use the creator's server");
        Label creatorHint = hint(
                "Log into the app creator's hosted copy.\n"
                        + "This is a different database from anything on your PC.\n"
                        + "Your local tasks are not deleted; they just are not on this server."
        );

        RadioButton thisPc = radio("This computer is the server");
        Label thisHint = hint(
                "Node and Postgres run on this PC.\n"
                        + "Use this when you sit at the machine that stores the data.\n"
                        + "Address: http://127.0.0.1:3456"
        );

        RadioButton other = radio("Another of my computers  (Tailscale or same wifi)");
        Label otherHint = hint(
                "This window is only a client. Your other PC already runs Node.\n"
                        + "Tailscale: http://100.x.x.x:3456\n"
                        + "Same wifi: http://192.168.x.x:3456\n"
                        + "Same host PC as before = same data. Only the address changes."
        );

        creator.setToggleGroup(g);
        thisPc.setToggleGroup(g);
        other.setToggleGroup(g);

        Label HostIP = new Label("Put down your Host IP here: ");
        TextField custom = new TextField();
        custom.setPromptText("http://100.113.239.82:3456");
        custom.setDisable(true);
        custom.setStyle(
                "-fx-background-color: #ffffff;"
                        + "-fx-text-fill: #1c2430;"
                        + "-fx-prompt-text-fill: #7a7468;"
                        + "-fx-font-size: 13px;"
                        + "-fx-padding: 8 10;"
        );

        Label dataNote = hint(
                "Data is not wiped when you switch how you reach the same host.\n"
                        + "This PC → Tailscale to that same PC keeps your tasks.\n"
                        + "Jumping to the creator's server shows the creator's database instead."
        );
        dataNote.setStyle("-fx-text-fill: #1c2430; -fx-font-size: 13px; -fx-font-family: Georgia; -fx-font-weight: bold;");

        String current = Endpoints.hasSavedServer() ? Endpoints.nodeBase() : "";
        if (!current.isBlank() && current.startsWith(Endpoints.CREATOR_HOST)) {
            creator.setSelected(true);
        } else if (current.contains("127.0.0.1") || current.contains("localhost") || current.isBlank()) {
            thisPc.setSelected(true);
        } else {
            other.setSelected(true);
            custom.setText(current);
            custom.setDisable(false);
        }

        creator.selectedProperty().addListener((o, a, on) -> {
            if (on) custom.setDisable(true);
        });
        thisPc.selectedProperty().addListener((o, a, on) -> {
            if (on) custom.setDisable(true);
        });
        other.selectedProperty().addListener((o, a, on) -> {
            custom.setDisable(!on);
            if (on && custom.getText().isBlank()) {
                custom.setText(Endpoints.DEFAULT_TAILSCALE_NODE);
            }
        });

        Label remember = hint(
                "Saved on this PC. You will not see this screen at next startup.\n"
                        + "Login page → Change server to switch later."
        );

        Label err = new Label();
        err.setWrapText(true);
        err.setStyle("-fx-text-fill: #8b1e1e; -fx-font-size: 13px;");

        Button ok = new Button("Continue");
        ok.setDefaultButton(true);
        ok.setStyle(
                "-fx-background-color: #1c2430;"
                        + "-fx-text-fill: #f3eee4;"
                        + "-fx-font-size: 14px;"
                        + "-fx-padding: 8 18;"
                        + "-fx-background-radius: 8;"
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
            Endpoints.saveNode(url);
            if (!Endpoints.isCreatorHost()) {
                Endpoints.ensurePostgresPassword();
            }
            try {
                Api.INSTANCE.logout();
            } catch (Exception ignored) {
            }
            if (onPicked != null) onPicked.run();
        });

        getChildren().addAll(
                title,
                creator, creatorHint,
                thisPc, thisHint,
                other, otherHint, HostIP, custom,
                dataNote,
                remember,
                err,
                ok
        );
    }

    private static RadioButton radio(String text) {
        RadioButton r = new RadioButton(text);
        r.setStyle("-fx-text-fill: #1c2430; -fx-font-size: 14px; -fx-font-family: Georgia;");
        return r;
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill: #3d4550; -fx-font-size: 12px; -fx-font-family: Georgia;");
        return l;
    }
}