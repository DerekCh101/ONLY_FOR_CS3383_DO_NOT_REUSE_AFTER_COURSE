package org.ashoreprospect.GUI;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.ashoreprospect.net.Api;
import org.ashoreprospect.net.Endpoints;
import org.ashoreprospect.net.LocalSession;
import org.ashoreprospect.net.NetworkWatch;
import org.ashoreprospect.net.SmtpSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Full-window account settings. Home, then one page per action / session. */
public final class AccountPane extends BorderPane {

    public enum Page { HOME, USERNAME, EMAIL, PASSWORD, SECURITY, SESSIONS, SESSION, SMTP }

    private final Runnable onLogout;
    private final Runnable onWipeAndLogout;
    private final Consumer<String> onUsernameChanged;
    private final Label title = new Label("Account");
    private final Button back = new Button("Back");
    private final ScrollPane scroll = new ScrollPane();
    private Page page = Page.HOME;
    private List<Map<String, String>> sessions = new ArrayList<>();

    public AccountPane(Runnable onLogout, Runnable onWipeAndLogout, Consumer<String> onUsernameChanged) {
        this.onLogout = onLogout;
        this.onWipeAndLogout = onWipeAndLogout;
        this.onUsernameChanged = onUsernameChanged;
        getStyleClass().add("account-page");
        setPadding(new Insets(28, 36, 32, 36));

        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; " + AppTheme.font());
        back.setOnAction(e -> show(Page.HOME));
        HBox top = new HBox(12, back, title);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 0, 16, 0));
        setTop(top);

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        setCenter(scroll);
        AppTheme.listen(() -> Platform.runLater(this::paintChrome));
        paintChrome();
        show(Page.HOME);
    }

    public void show(Page next) {
        page = next == null ? Page.HOME : next;
        back.setOnAction(e -> show(Page.HOME));
        back.setVisible(page != Page.HOME);
        back.setManaged(page != Page.HOME);
        switch (page) {
            case USERNAME -> showForm("Change username", usernameForm());
            case EMAIL -> showForm("Change email", emailForm());
            case PASSWORD -> showForm("Change password", passwordForm());
            case SECURITY -> showForm("Security question", securityForm());
            case SMTP -> showForm("Reset mail (this host)", smtpForm());
            case SESSIONS -> loadSessions();
            case SESSION -> { /* opened via openSession */ }
            default -> showHome();
        }
    }

    public void refreshIfVisible() {
        if (!isVisible()) {
            paintChrome();
            return;
        }
        if (page == Page.SESSION) {
            paintChrome();
            return;
        }
        show(page);
    }

    private void showHome() {
        title.setText("Account");
        String user = Api.INSTANCE.getUsername();
        if (user == null || user.isBlank()) user = "user";
        VBox box = new VBox(0,
                muted("Signed in as " + user),
                row("Username", () -> show(Page.USERNAME)),
                row("Email", () -> show(Page.EMAIL)),
                row("Password", () -> show(Page.PASSWORD)),
                row("Security question", () -> show(Page.SECURITY)),
                row("Sessions", () -> show(Page.SESSIONS)));
        if (Endpoints.isLocalHost()) {
            box.getChildren().add(row(
                    SmtpSettings.configured() ? "Reset mail · set" : "Reset mail · Node console only",
                    () -> show(Page.SMTP)));
        }
        box.getChildren().addAll(
                themeRow(),
                challengeRow(),
                action("Clear this PC’s cache and sign out", true, this::confirmWipe),
                action("Logout", false, () -> {
                    if (onLogout != null) onLogout.run();
                })
        );
        box.setMaxWidth(480);
        scroll.setContent(wrap(box));
        paintChrome();
    }

    private void confirmWipe() {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Clear this PC’s cache");
        a.setHeaderText("Delete all local cache and sign out?");
        a.setContentText("Tasks and typewriter notes on this computer are wiped. "
                + "The copy on the server stays. You will be signed out.");
        a.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK && onWipeAndLogout != null) onWipeAndLogout.run();
        });
    }

    private void showForm(String heading, Node form) {
        title.setText(heading);
        VBox box = new VBox(10, form);
        box.setMaxWidth(420);
        scroll.setContent(wrap(box));
        paintChrome();
    }

    private Node usernameForm() {
        TextField field = new TextField(nz(Api.INSTANCE.getUsername()));
        field.setPromptText("new username");
        Label status = status();
        Button save = primary("Save username");
        save.setOnAction(e -> runIo(save, status, () -> {
            Map<String, String> r = Api.INSTANCE.saveUsername(field.getText());
            String next = r.get("username");
            Platform.runLater(() -> {
                if (onUsernameChanged != null && next != null) onUsernameChanged.accept(next);
            });
            return "Username updated.";
        }));
        return new VBox(10, muted("This is the name you type at login."), field, saveRow(save), status);
    }

    private Node emailForm() {
        TextField field = new TextField();
        field.setPromptText("email");
        Label status = status();
        Button save = primary("Save email");
        save.setOnAction(e -> runIo(save, status, () -> {
            Api.INSTANCE.saveEmail(field.getText());
            return "Email saved.";
        }));
        fillMe(field, "email", status);
        return new VBox(10, muted("Required. Forgot password will not work without a real email."),
                field, saveRow(save), status);
    }

    private Node passwordForm() {
        PasswordField oldP = new PasswordField();
        oldP.setPromptText("current password");
        PasswordField n1 = new PasswordField();
        n1.setPromptText("new password (min 8)");
        PasswordField n2 = new PasswordField();
        n2.setPromptText("repeat new password");
        Label status = status();
        Button save = primary("Change password");
        save.setOnAction(e -> {
            if (!n1.getText().equals(n2.getText())) {
                status.setText("New passwords do not match.");
                return;
            }
            runIo(save, status, () -> {
                Api.INSTANCE.changePassword(oldP.getText(), n1.getText());
                return "Password changed. Other sessions were signed out.";
            });
        });
        return new VBox(10, muted("Other devices will have to log in again. This PC stays signed in."),
                oldP, n1, n2, saveRow(save), status);
    }

    private Node securityForm() {
        TextField q = new TextField();
        q.setPromptText("Your own question, in your own words");
        PasswordField a = new PasswordField();
        a.setPromptText("Answer — hashed on the server only");
        Label status = status();
        Button save = primary("Save question");
        save.setOnAction(e -> runIo(save, status, () -> {
            Api.INSTANCE.saveSecurity(q.getText(), a.getText());
            return "Saved on the server. Not stored in this PC’s cache.";
        }));
        fillMe(q, "securityQuestion", status);
        return new VBox(10,
                muted("Required. Forgot password sends this question with the token. The answer is never written to SQLite."),
                q, a, saveRow(save), status);
    }

    private Node smtpForm() {
        Map<String, String> cur = SmtpSettings.load();
        TextField host = new TextField(nz(cur.get("host")));
        host.setPromptText("smtp.gmail.com");
        TextField port = new TextField(nz(cur.get("port")));
        port.setPromptText("587");
        TextField user = new TextField(nz(cur.get("user")));
        user.setPromptText("the Gmail that sends reset mail");
        PasswordField pass = new PasswordField();
        pass.setPromptText(SmtpSettings.configured()
                ? "App password already saved — leave blank to keep"
                : "Gmail App password, not your login password");
        TextField from = new TextField(nz(cur.get("from")));
        from.setPromptText("usually the same as the Gmail above");
        Label status = status();
        Button save = primary("Save on this host");
        save.setOnAction(e -> runIo(save, status, () -> {
            SmtpSettings.save(host.getText(), port.getText(), user.getText(),
                    pass.getText(), from.getText());
            if (SmtpSettings.configured()) {
                return "Saved. App password is SMTP_PASS (Windows env). Restart Node. Tokens last 8 minutes.";
            }
            return "Need Gmail address and App password.";
        }));
        Button clear = action("Turn mail off (console only)", false, () -> {});
        clear.setOnAction(e -> runIo(clear, status, () -> {
            SmtpSettings.clear();
            Platform.runLater(() -> {
                user.clear();
                pass.clear();
                from.clear();
            });
            return "Mail off. Reset tokens print in the Node console for 8 minutes.";
        }));
        return new VBox(10,
                muted("Only this host PC. Clients never see this."),
                muted("Gmail App password (16 characters, spaces optional). Not your Google login."),
                muted("1) Turn on 2-Step Verification: myaccount.google.com → Security → 2-Step Verification."),
                muted("2) Then open myaccount.google.com/apppasswords (it will not appear until 2-Step is on)."),
                muted("3) Create one named Ashore, paste it here. The app stores it as SMTP_PASS."),
                muted("Sender and receiver may be the same Gmail. Check Spam if it is."),
                muted("The host Node terminal always prints the token and the security question, "
                        + "even when mail is on. Token expires in 8 minutes."),
                host, port, user, pass, from, saveRow(save), clear, status);
    }

    private void loadSessions() {
        title.setText("Sessions");
        Label loading = muted("Loading…");
        scroll.setContent(wrap(new VBox(10, loading)));
        Thread t = new Thread(() -> {
            try {
                sessions = Api.INSTANCE.getSessions();
                Platform.runLater(this::showSessionList);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    title.setText("Sessions");
                    scroll.setContent(wrap(new VBox(10, statusOf(e.getMessage()))));
                });
            }
        }, "sessions-list");
        t.setDaemon(true);
        t.start();
    }

    private void showSessionList() {
        title.setText("Sessions");
        VBox box = new VBox(10);
        box.setMaxWidth(560);
        box.getChildren().add(muted("This device is marked current. Open a row to revoke that login."));
        Button kill = primary("Sign out all other sessions");
        Label status = status();
        kill.setOnAction(e -> runIo(kill, status, () -> {
            Api.INSTANCE.revokeOtherSessions();
            Platform.runLater(this::loadSessions);
            return "Other sessions ended.";
        }));
        box.getChildren().addAll(saveRow(kill), status);
        List<Node> rows = new ArrayList<>();
        for (Map<String, String> row : sessions) {
            boolean current = "true".equalsIgnoreCase(row.get("current"));
            String device = nz(first(row, "device", "Device"));
            String seen = first(row, "lastSeenAt", "last_seen_at");
            String line = (current ? "This device · " : "") + (device.isBlank() ? "Unknown device" : device);
            if (seen != null) line = line + "  ·  " + seen;
            Map<String, String> copy = row;
            rows.add(row(line, () -> openSession(copy)));
        }
        box.getChildren().addAll(rows);
        scroll.setContent(wrap(box));
        paintChrome();
    }

    private void openSession(Map<String, String> row) {
        page = Page.SESSION;
        back.setVisible(true);
        back.setManaged(true);
        back.setOnAction(e -> {
            back.setOnAction(ev -> show(Page.HOME));
            show(Page.SESSIONS);
        });
        boolean current = "true".equalsIgnoreCase(row.get("current"));
        title.setText(current ? "This session" : "Session");
        Label status = status();
        VBox details = new VBox(8,
                muted("Device"), line(nz(first(row, "device"))),
                muted("Created"), line(nz(first(row, "createdAt", "created_at"))),
                muted("Last seen"), line(nz(first(row, "lastSeenAt", "last_seen_at"))),
                muted("Id"), line(nz(first(row, "id")))
        );
        VBox box = new VBox(8, details);
        if (current) {
            box.getChildren().add(muted("This is the login on this PC. Revoking it signs you out here."));
        }
        Button revoke = primary(current ? "Sign out this PC" : "Revoke this session");
        String id = first(row, "id");
        revoke.setOnAction(e -> runIo(revoke, status, () -> {
            Map<String, String> r = Api.INSTANCE.revokeSession(id);
            boolean self = "true".equalsIgnoreCase(r.get("current")) || current;
            if (self) {
                Platform.runLater(() -> {
                    if (onLogout != null) onLogout.run();
                });
                return "Signed out.";
            }
            Platform.runLater(() -> show(Page.SESSIONS));
            return "Session revoked.";
        }));
        box.getChildren().addAll(saveRow(revoke), status);
        box.setMaxWidth(520);
        scroll.setContent(wrap(box));
        paintChrome();
    }

    private Node challengeRow() {
        CheckBox box = new CheckBox("Random security check on Remember-me login");
        box.setWrapText(true);
        box.setSelected(LocalSession.wantRandomChallenge());
        box.setStyle(AppTheme.font() + "-fx-font-size: 13px; -fx-text-fill: " + AppTheme.ink() + ";");
        box.selectedProperty().addListener((o, a, on) -> LocalSession.setRandomChallenge(on));
        HBox row = new HBox(box);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 2, 6, 2));
        return row;
    }

    private Node themeRow() {
        Label name = new Label("Theme");
        name.setStyle("-fx-font-size: 13px; " + AppTheme.font() + "-fx-text-fill: " + AppTheme.ink() + ";");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox row = new HBox(10, name, grow, new ThemeSwitch());
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 2, 4, 2));
        row.setStyle("-fx-border-color: transparent transparent " + line() + " transparent; -fx-border-width: 0 0 1 0;");
        return row;
    }

    private Button row(String name, Runnable go) {
        Label n = new Label(name);
        n.setStyle("-fx-font-size: 13px; " + AppTheme.font() + "-fx-text-fill: " + AppTheme.ink() + ";");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Label chev = new Label("›");
        chev.setStyle("-fx-font-size: 14px; " + AppTheme.font() + "-fx-text-fill: " + AppTheme.muted() + ";");
        HBox inner = new HBox(8, n, grow, chev);
        inner.setAlignment(Pos.CENTER_LEFT);
        Button b = new Button();
        b.setGraphic(inner);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 7 2; "
                + "-fx-text-fill: " + AppTheme.ink() + "; "
                + "-fx-border-color: transparent transparent " + line() + " transparent; -fx-border-width: 0 0 1 0;");
        b.setOnAction(e -> go.run());
        return b;
    }

    private Button action(String text, boolean danger, Runnable go) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setStyle((danger
                ? "-fx-background-color: transparent; -fx-text-fill: #a35d55;"
                : "-fx-background-color: transparent; -fx-text-fill: " + AppTheme.ink() + ";")
                + " -fx-padding: 8 2; " + AppTheme.font()
                + "-fx-border-color: transparent transparent " + line() + " transparent; -fx-border-width: 0 0 1 0;");
        b.setOnAction(e -> go.run());
        return b;
    }

    private static String line() {
        return AppTheme.isDark() ? "#2a3340" : "#e0d6c6";
    }

    private Button primary(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("account-save");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setCursor(javafx.scene.Cursor.HAND);
        return b;
    }

    private Node saveRow(Button save) {
        ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(20, 20);
        spin.setMaxSize(20, 20);
        spin.setVisible(false);
        spin.setManaged(false);
        save.setUserData(spin);
        HBox row = new HBox(12, save, spin);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(save, Priority.ALWAYS);
        return row;
    }

    private static Label muted(String t) {
        Label l = new Label(t);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 12px; " + AppTheme.font() + "-fx-text-fill: "
                + AppTheme.muted() + "; -fx-padding: 0 2 10 2;");
        return l;
    }

    private static Label line(String t) {
        Label l = new Label(t);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 13px; " + AppTheme.font() + "-fx-text-fill: "
                + AppTheme.ink() + ";");
        return l;
    }

    private static Label status() {
        Label l = new Label();
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 12px; " + AppTheme.font() + "-fx-text-fill: "
                + AppTheme.ink() + ";");
        return l;
    }

    private static Label statusOf(String msg) {
        Label l = status();
        l.setText(msg);
        return l;
    }

    private void fillMe(TextField field, String key, Label status) {
        if (!NetworkWatch.isOnline()) {
            status.setText("Offline — edits wait until you are online.");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Map<String, String> me = Api.INSTANCE.getMe();
                Platform.runLater(() -> {
                    if (me.get(key) != null) field.setText(me.get(key));
                });
            } catch (Exception e) {
                Platform.runLater(() -> status.setText(e.getMessage()));
            }
        }, "account-me");
        t.setDaemon(true);
        t.start();
    }

    private void runIo(Button save, Label status, IoJob job) {
        ProgressIndicator spin = save.getUserData() instanceof ProgressIndicator p ? p : null;
        String old = save.getText();
        save.setDisable(true);
        save.setText("Working…");
        status.setText("Working…");
        if (spin != null) {
            spin.setVisible(true);
            spin.setManaged(true);
        }
        Thread t = new Thread(() -> {
            try {
                String msg = job.run();
                Platform.runLater(() -> finishIo(save, spin, old, status, msg));
            } catch (Exception e) {
                Platform.runLater(() -> finishIo(save, spin, old, status, e.getMessage()));
            }
        }, "account-save");
        t.setDaemon(true);
        t.start();
    }

    private static void finishIo(Button save, ProgressIndicator spin, String old, Label status, String msg) {
        save.setDisable(false);
        save.setText(old);
        status.setText(msg == null ? "" : msg);
        if (spin != null) {
            spin.setVisible(false);
            spin.setManaged(false);
        }
    }

    private Node wrap(VBox box) {
        box.setPadding(new Insets(4, 4, 20, 4));
        return box;
    }

    private void paintChrome() {
        setStyle("-fx-background-color: " + AppTheme.typewriterBg() + ";");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; " + AppTheme.font()
                + "-fx-text-fill: " + AppTheme.ink() + ";");
        back.setStyle(AppTheme.font() + "-fx-background-color: transparent; -fx-text-fill: "
                + AppTheme.ink() + "; -fx-padding: 4 8;");
        applyInk(scroll.getContent());
    }

    private static void applyInk(Node n) {
        if (n == null) return;
        if (n instanceof Label l) {
            String s = l.getStyle() == null ? "" : l.getStyle();
            if (s.contains("#8b1e1e") || s.contains("#8a6a62")) {
                /* keep error / danger */
            } else if (s.contains("-fx-opacity: 0.72") || s.contains(AppTheme.muted())
                    || s.contains("#9aa3ad") || s.contains("#333333") || s.contains("#5c564c")) {
                l.setStyle(s.replaceAll("-fx-text-fill:\\s*#[0-9a-fA-F]{3,8}", "")
                        + "; -fx-text-fill: " + AppTheme.muted() + ";");
            } else {
                l.setStyle(s.replaceAll("-fx-text-fill:\\s*#[0-9a-fA-F]{3,8}", "")
                        + "; -fx-text-fill: " + AppTheme.ink() + ";");
            }
        }
        if (n instanceof CheckBox c) {
            c.setStyle(AppTheme.font() + "-fx-font-size: 13px; -fx-text-fill: "
                    + AppTheme.ink() + ";");
        }
        if (n instanceof TextField tf) {
            tf.setStyle(AppTheme.fieldCss());
        }
        if (n instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) applyInk(child);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String first(Map<String, String> row, String... keys) {
        if (row == null) return null;
        for (String k : keys) {
            String v = row.get(k);
            if (v != null && !v.isBlank() && !"null".equals(v)) return v;
        }
        return null;
    }

    @FunctionalInterface
    private interface IoJob {
        String run() throws Exception;
    }
}
