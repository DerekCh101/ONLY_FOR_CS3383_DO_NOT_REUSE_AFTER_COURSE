/** Account screen: profile, sessions, device id, optional encryption. */
package org.ashoreprospect.GUI;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Accordion;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.ashoreprospect.AppVersion;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.crypto.Vault;
import org.ashoreprospect.db.DeviceConfig;
import org.ashoreprospect.net.Api;
import org.ashoreprospect.net.Endpoints;
import org.ashoreprospect.net.LocalSession;
import org.ashoreprospect.net.NetworkWatch;
import org.ashoreprospect.net.PasswordRules;
import org.ashoreprospect.net.SmtpSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

/** Full-window account settings. Home, then one page per action / session. */
public final class AccountPane extends BorderPane {

    public enum Page { HOME, USERNAME, EMAIL, PASSWORD, SECURITY, SESSIONS, SESSION, SMTP, NOTIFICATIONS, DEVICE }

    private enum Section { USER, SECURITY, NOTIFICATIONS, DEVICE }

    private final Runnable onLogout;
    private final Runnable onWipeAndLogout;
    private final Runnable onAccountDeleted;
    private final Consumer<String> onUsernameChanged;
    private final Supplier<TypewriterStore.Data> typewriterSnap;
    private final Label title = new Label("Account");
    private final Button back = new Button("Back");
    private final VBox nav = new VBox(4);
    private final Button navUser = new Button("User info");
    private final Button navSecurity = new Button("Security");
    private final Button navNotify = new Button("Notifications");
    private final Button navDevice = new Button("Device");
    private final ScrollPane scroll = new ScrollPane();
    private Page page = Page.HOME;
    private Section section = Section.USER;
    private List<Map<String, String>> sessions = new ArrayList<>();

    public AccountPane(Runnable onLogout, Runnable onWipeAndLogout, Consumer<String> onUsernameChanged) {
        this(onLogout, onWipeAndLogout, onUsernameChanged, null, null);
    }

    public AccountPane(Runnable onLogout, Runnable onWipeAndLogout, Consumer<String> onUsernameChanged,
                       Supplier<TypewriterStore.Data> typewriterSnap) {
        this(onLogout, onWipeAndLogout, onUsernameChanged, typewriterSnap, null);
    }

    public AccountPane(Runnable onLogout, Runnable onWipeAndLogout, Consumer<String> onUsernameChanged,
                       Supplier<TypewriterStore.Data> typewriterSnap, Runnable onAccountDeleted) {
        this.onLogout = onLogout;
        this.onWipeAndLogout = onWipeAndLogout;
        this.onAccountDeleted = onAccountDeleted != null ? onAccountDeleted : onWipeAndLogout;
        this.onUsernameChanged = onUsernameChanged;
        this.typewriterSnap = typewriterSnap;
        getStyleClass().add("account-page");
        setPadding(new Insets(0));

        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; " + AppTheme.font());
        back.setOnAction(e -> show(Page.HOME));
        HBox top = new HBox(12, back, title);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(20, 28, 12, 24));
        setTop(top);

        navUser.setMaxWidth(Double.MAX_VALUE);
        navSecurity.setMaxWidth(Double.MAX_VALUE);
        navNotify.setMaxWidth(Double.MAX_VALUE);
        navDevice.setMaxWidth(Double.MAX_VALUE);
        navUser.setOnAction(e -> show(Page.HOME));
        navSecurity.setOnAction(e -> show(Page.SECURITY));
        navNotify.setOnAction(e -> show(Page.NOTIFICATIONS));
        navDevice.setOnAction(e -> show(Page.DEVICE));
        Region navGrow = new Region();
        VBox.setVgrow(navGrow, Priority.ALWAYS);
        Button logout = action("Logout", false, () -> {
            if (onLogout != null) onLogout.run();
        });
        Button wipe = action("Clear this PC’s cache and sign out", true, this::confirmWipe);
        wipe.setWrapText(true);
        nav.getChildren().addAll(navUser, navSecurity, navNotify, navDevice, navGrow, wipe, logout);
        nav.setPrefWidth(220);
        nav.setMinWidth(200);
        nav.setPadding(new Insets(8, 12, 20, 16));
        setLeft(nav);

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
        back.setVisible(page == Page.SESSION);
        back.setManaged(page == Page.SESSION);
        switch (page) {
            case PASSWORD, SECURITY, SESSIONS, SMTP -> showSecurity();
            case NOTIFICATIONS -> showNotifications();
            case DEVICE -> showDevice();
            case SESSION -> { /* opened via openSession */ }
            default -> showUser();
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

    private void showUser() {
        section = Section.USER;
        title.setText("User info");
        String user = Api.INSTANCE.getUsername();
        if (user == null || user.isBlank()) user = "user";
        Label emailNow = muted("Email: loading…");
        fillMeLabel(emailNow, "email", "Email: ");
        VBox box = new VBox(14,
                muted("Ashore Prospect " + AppVersion.LABEL),
                muted("Signed in as " + user),
                deviceRow(),
                emailNow,
                heading("Change username"),
                usernameForm(),
                heading("Change email"),
                emailForm(),
                heading("Appearance"),
                themeRow());
        box.setMaxWidth(560);
        scroll.setContent(wrap(box));
        paintChrome();
    }

    private void showSecurity() {
        section = Section.SECURITY;
        title.setText("Security");
        VBox sessionsSlot = new VBox(8, muted("Open to load sessions."));
        TitledPane sessionsPane = fold("Sessions", sessionsSlot);
        sessionsPane.expandedProperty().addListener((o, a, on) -> {
            if (Boolean.TRUE.equals(on)) loadSessionsInto(sessionsSlot);
        });
        Accordion folds = new Accordion();
        // Encrypt is for Postgres / pgAdmin on the host, not a client-side lock.
        // Clients unlock automatically on login; only the host toggles sealing.
        if (Endpoints.isHostDevice()) {
            folds.getPanes().add(fold("Encrypt data at rest", encryptBox()));
        }
        folds.getPanes().addAll(
                fold("Change password", passwordForm()),
                fold("Security question", securityForm()),
                sessionsPane,
                fold("Delete this account", deleteAccountBox()));
        if (Endpoints.isLocalHost()) {
            folds.getPanes().add(fold("Reset mail (this host)", smtpForm()));
        }
        folds.setMaxWidth(560);
        VBox box = new VBox(10,
                muted("Open a row to edit it."),
                folds,
                heading("Login check"),
                challengeRow());
        box.setMaxWidth(560);
        scroll.setContent(wrap(box));
        paintChrome();
    }

    private static TitledPane fold(String name, Node content) {
        TitledPane p = new TitledPane(name, content);
        p.setExpanded(false);
        p.setAnimated(false);
        p.setCollapsible(true);
        p.getStyleClass().add("account-fold");
        p.setMaxWidth(Double.MAX_VALUE);
        return p;
    }

    private void showNotifications() {
        section = Section.NOTIFICATIONS;
        title.setText("Notifications");
        VBox box = new VBox(14,
                muted("Choose how far ahead to be reminded for placed unfinished tasks. A Windows popup and an in-app banner both show. Sound is optional."),
                reminderPanel());
        box.setMaxWidth(560);
        scroll.setContent(wrap(box));
        paintChrome();
    }

    private void showDevice() {
        section = Section.DEVICE;
        title.setText("Device");
        VBox box = new VBox(14,
                muted("This PC only. Idle logout signs you out after no mouse or keyboard. Default is Never. Refresh does not log you out."),
                idlePanel(),
                heading("Appearance"),
                themeRow());
        box.setMaxWidth(560);
        scroll.setContent(wrap(box));
        paintChrome();
    }

    private static Label heading(String t) {
        Label l = new Label(t);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; " + AppTheme.font()
                + "-fx-text-fill: " + AppTheme.ink() + "; -fx-padding: 10 2 0 2;");
        return l;
    }

    private void confirmWipe() {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Clear this PC’s cache");
        a.setHeaderText("Delete all local cache and sign out?");
        a.setContentText("Tasks and typewriter notes on this computer are wiped. "
                + "Typewriter is flushed to the server first so Postgres keeps the copy. You will be signed out.");
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
        n1.setPromptText("new password (8+, A-z, number, symbol)");
        PasswordField n2 = new PasswordField();
        n2.setPromptText("repeat new password");
        Label status = status();
        Button save = primary("Change password");
        save.setOnAction(e -> {
            if (!n1.getText().equals(n2.getText())) {
                status.setText("New passwords do not match.");
                return;
            }
            String weak = PasswordRules.reject(n1.getText());
            if (weak != null) {
                status.setText(weak);
                return;
            }
            TypewriterStore.Data snap = typewriterSnap == null ? null : typewriterSnap.get();
            runIo(save, status, () -> {
                Map<String, String> r = Api.INSTANCE.changePassword(oldP.getText(), n1.getText());
                boolean mailed = "true".equalsIgnoreCase(r.get("emailed"));
                if (Vault.wanted()) {
                    Vault.rekey(Api.INSTANCE.getUserId(), oldP.getText(), n1.getText());
                    TaskManagementSystem.getInstance().rewriteEncryptedStores();
                    persistVaultStores(snap);
                    return mailed
                            ? "Password changed. Encrypted data re-sealed. A notice was sent to your email."
                            : "Password changed. Encrypted data re-sealed with the new password.";
                }
                return mailed
                        ? "Password changed. Other sessions were signed out. A notice was sent to your email."
                        : "Password changed. Other sessions were signed out.";
            });
        });
        return new VBox(10, muted("Other devices will have to log in again. This PC stays signed in. "
                        + PasswordRules.HINT),
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
                return "Saved as SMTP_PASS in OS user env. Restart Node. Tokens last 8 minutes.";
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
                muted("3) Create one named Ashore, paste it here. Stored as OS env SMTP_PASS, not in the project folder."),
                muted("Sender and receiver may be the same Gmail. Check Spam if it is."),
                muted("The host Node terminal always prints the token and the security question, "
                        + "even when mail is on. Token expires in 8 minutes."),
                host, port, user, pass, from, saveRow(save), clear, status);
    }

    private void loadSessionsInto(VBox slot) {
        Thread t = new Thread(() -> {
            try {
                sessions = Api.INSTANCE.getSessions();
                Platform.runLater(() -> fillSessionList(slot));
            } catch (Exception e) {
                Platform.runLater(() -> slot.getChildren().setAll(statusOf(e.getMessage())));
            }
        }, "sessions-list");
        t.setDaemon(true);
        t.start();
    }

    private void fillSessionList(VBox slot) {
        VBox box = new VBox(10);
        box.getChildren().add(muted("This device is marked current. Open a row to revoke that login."));
        Button kill = primary("Sign out all other sessions");
        Label status = status();
        kill.setOnAction(e -> runIo(kill, status, () -> {
            Api.INSTANCE.revokeOtherSessions();
            Platform.runLater(() -> loadSessionsInto(slot));
            return "Other sessions ended.";
        }));
        box.getChildren().addAll(saveRow(kill), status);
        for (Map<String, String> row : sessions) {
            boolean current = "true".equalsIgnoreCase(row.get("current"));
            String device = nz(first(row, "device", "Device"));
            String seen = first(row, "lastSeenAt", "last_seen_at");
            String line = (current ? "This device · " : "") + (device.isBlank() ? "Unknown device" : device);
            if (seen != null) line = line + "  ·  " + seen;
            Map<String, String> copy = row;
            box.getChildren().add(row(line, () -> openSession(copy)));
        }
        slot.getChildren().setAll(box);
        paintChrome();
    }

    private void openSession(Map<String, String> row) {
        page = Page.SESSION;
        section = Section.SECURITY;
        back.setVisible(true);
        back.setManaged(true);
        back.setOnAction(e -> show(Page.SECURITY));
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
            Platform.runLater(() -> show(Page.SECURITY));
            return "Session revoked.";
        }));
        box.getChildren().addAll(saveRow(revoke), status);
        box.setMaxWidth(520);
        scroll.setContent(wrap(box));
        paintChrome();
    }

    private Node deviceRow() {
        String id = DeviceConfig.getOrCreateDeviceId();
        Label name = new Label("Device id");
        name.setStyle("-fx-font-size: 13px; " + AppTheme.font() + "-fx-text-fill: " + AppTheme.ink() + ";");
        Label value = new Label(id);
        value.setWrapText(true);
        value.setStyle("-fx-font-size: 12px; " + AppTheme.font() + "-fx-text-fill: " + AppTheme.muted() + ";");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(id);
            Clipboard.getSystemClipboard().setContent(c);
            copy.setText("Copied");
        });
        VBox left = new VBox(2, name, value);
        HBox row = new HBox(10, left, grow, copy);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 2, 8, 2));
        row.setStyle("-fx-border-color: transparent transparent " + line() + " transparent; -fx-border-width: 0 0 1 0;");
        return row;
    }

    private Node encryptBox() {
        CheckBox box = new CheckBox("Encrypt data at rest");
        box.setWrapText(true);
        box.setSelected(Vault.wanted());
        box.setStyle(AppTheme.font() + "-fx-font-size: 13px; -fx-text-fill: " + AppTheme.ink() + ";");
        Label hint = muted(Vault.wanted()
                ? "On — Postgres / pgAdmin stores ciphertext. Logged-in apps (this host and every client) show plaintext after login. Not a client lock."
                : "Off. Turn on so pgAdmin on this host cannot read task text. Clients decrypt automatically when they sign in.");
        PasswordField pw = new PasswordField();
        pw.setPromptText("Account password");
        Label status = status();
        Button apply = primary("Apply");
        apply.setOnAction(e -> {
            boolean turnOn = box.isSelected();
            TypewriterStore.Data snap = typewriterSnap == null ? null : typewriterSnap.get();
            runIo(apply, status, () -> {
                String uid = Api.INSTANCE.getUserId();
                if (turnOn) {
                    Vault.enable(uid, pw.getText());
                    TaskManagementSystem.getInstance().rewriteEncryptedStores();
                    persistVaultStores(snap);
                    return "Encryption on. Existing data rewritten. New writes are encrypted for pgAdmin.";
                }
                Vault.disable(uid, pw.getText());
                TaskManagementSystem.getInstance().rewriteEncryptedStores();
                persistVaultStores(snap);
                Vault.finishDisable();
                return "Encryption off. Existing data rewritten as plaintext.";
            });
        });
        VBox wrap = new VBox(8, box, hint, pw, saveRow(apply), status);
        wrap.setPadding(new Insets(10, 2, 12, 2));
        wrap.setStyle("-fx-border-color: transparent transparent " + line() + " transparent; -fx-border-width: 0 0 1 0;");
        return wrap;
    }

    private void persistVaultStores(TypewriterStore.Data snap) throws Exception {
        TypewriterStore.Data data = snap;
        if (data == null) {
            data = TypewriterStore.load();
        } else {
            TypewriterStore.openLoaded(data);
        }
        TypewriterStore.saveImmediate(data);
        Platform.runLater(() -> {
            try {
                TaskManagementSystem.getInstance().refreshGUI();
            } catch (Exception ignored) {
            }
        });
    }

    private Node reminderPanel() {
        CheckBox master = new CheckBox("Pop-up reminders (Windows notification + in-app banner)");
        master.setWrapText(true);
        master.setSelected(LocalSession.wantReminders());
        CheckBox sound = new CheckBox("Play a sound with the reminder");
        sound.setWrapText(true);
        sound.setSelected(LocalSession.wantReminderSound());
        sound.selectedProperty().addListener((o, a, on) -> LocalSession.setReminderSound(on));
        master.selectedProperty().addListener((o, a, on) -> {
            LocalSession.setReminders(on);
            sound.setDisable(!on);
        });
        sound.setDisable(!master.isSelected());

        List<Integer> leads = new ArrayList<>(LocalSession.reminderLeads());
        VBox ticks = new VBox(6);
        int[] presets = {0, 5, 10, 30, 60, 120, 1440};
        for (int mins : presets) {
            CheckBox box = new CheckBox(mins == 0 ? "When it starts" : TaskReminders.formatLead(mins) + " before");
            box.setSelected(leads.contains(mins));
            int lead = mins;
            box.selectedProperty().addListener((o, a, on) -> {
                List<Integer> next = new ArrayList<>(LocalSession.reminderLeads());
                if (on) {
                    if (!next.contains(lead)) next.add(lead);
                } else {
                    next.removeIf(x -> x == lead);
                }
                LocalSession.setReminderLeads(next);
            });
            ticks.getChildren().add(box);
        }

        Label extras = muted(customLeadSummary(leads, presets));
        TextField custom = new TextField();
        custom.setPromptText("Custom minutes (e.g. 15)");
        Button add = new Button("Add");
        add.setOnAction(e -> {
            try {
                int n = Integer.parseInt(custom.getText().trim());
                if (n < 1 || n > 20160) throw new NumberFormatException();
                List<Integer> next = new ArrayList<>(LocalSession.reminderLeads());
                if (!next.contains(n)) next.add(n);
                LocalSession.setReminderLeads(next);
                extras.setText(customLeadSummary(next, presets));
                custom.clear();
            } catch (Exception ex) {
                extras.setText("Enter minutes from 1 to 20160 (14 days).");
            }
        });
        Button clearCustom = new Button("Remove custom times");
        clearCustom.setOnAction(e -> {
            List<Integer> next = new ArrayList<>();
            for (int n : LocalSession.reminderLeads()) {
                boolean preset = false;
                for (int p : presets) if (p == n) { preset = true; break; }
                if (preset) next.add(n);
            }
            LocalSession.setReminderLeads(next);
            extras.setText(customLeadSummary(next, presets));
        });
        HBox customRow = new HBox(8, custom, add, clearCustom);
        customRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(custom, Priority.ALWAYS);

        VBox wrap = new VBox(10,
                master, sound, muted("Lead times"), ticks, extras, customRow);
        wrap.setPadding(new Insets(6, 2, 6, 2));
        return wrap;
    }

    private Node idlePanel() {
        ComboBox<String> idle = new ComboBox<>();
        idle.getItems().addAll(
                "Never", "5 minutes", "10 minutes", "15 minutes", "30 minutes",
                "45 minutes", "60 minutes", "90 minutes", "120 minutes", "150 minutes", "180 minutes");
        int cur = LocalSession.idleMinutes();
        idle.setValue(cur <= 0 ? "Never" : cur + " minutes");
        idle.setOnAction(e -> {
            String v = idle.getValue();
            if (v == null || v.startsWith("Never")) LocalSession.setIdleMinutes(0);
            else {
                try {
                    LocalSession.setIdleMinutes(Integer.parseInt(v.replace(" minutes", "").trim()));
                } catch (Exception ignored) {
                    LocalSession.setIdleMinutes(0);
                }
            }
            try {
                TaskManagementSystem.getInstance().applyIdleLimit();
            } catch (Exception ignored) {}
        });
        Label conflict = muted(
                "Idle returns to login. Remember login still signs in on the next launch.");
        VBox wrap = new VBox(8, new Label("Idle logout"), idle, conflict);
        wrap.setPadding(new Insets(6, 2, 6, 2));
        return wrap;
    }

    private static String customLeadSummary(List<Integer> leads, int[] presets) {
        List<Integer> extra = new ArrayList<>();
        for (int n : leads) {
            boolean preset = false;
            for (int p : presets) if (p == n) { preset = true; break; }
            if (!preset) extra.add(n);
        }
        if (extra.isEmpty()) return "Custom times you add show here.";
        StringBuilder sb = new StringBuilder("Also: ");
        for (int i = 0; i < extra.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(TaskReminders.formatLead(extra.get(i))).append(" before");
        }
        return sb.toString();
    }

    private Node deleteAccountBox() {
        PasswordField pw = new PasswordField();
        pw.setPromptText("Account password");
        Label status = status();
        Button go = primary("Delete this account");
        go.setOnAction(e -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION);
            a.setTitle("Delete this account");
            a.setHeaderText("Permanently delete this account and all its data?");
            a.setContentText("Tasks, Typewriter notes, images, and logins are removed on the server. "
                    + "Other devices wipe their local cache the next time they come online. This cannot be undone.");
            a.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            a.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.OK) return;
                runIo(go, status, () -> {
                    Api.INSTANCE.deleteAccount(pw.getText());
                    Vault.eraseLocalKeys();
                    try {
                        TaskManagementSystem.getInstance().getTaskRepo().wipeLocalCacheOnly();
                    } catch (Exception ignored) {}
                    Platform.runLater(() -> {
                        if (onAccountDeleted != null) onAccountDeleted.run();
                    });
                    return "Account deleted.";
                });
            });
        });
        return new VBox(8,
                muted("Requires your password. Offline devices clear themselves when they next reach the server."),
                pw, saveRow(go), status);
    }

    private Node challengeRow() {
        CheckBox box = new CheckBox("Random security check on Remember-me login");
        box.setWrapText(true);
        box.setSelected(LocalSession.wantRandomChallenge());
        box.setStyle(AppTheme.font() + "-fx-font-size: 13px; -fx-text-fill: " + AppTheme.ink() + ";");
        box.selectedProperty().addListener((o, a, on) -> LocalSession.setRandomChallenge(on));
        Label conflict = muted(
                "Idle returns to login. Remember login still signs in on the next launch.");
        VBox row = new VBox(6, box, conflict);
        row.setPadding(new Insets(6, 2, 6, 2));
        return row;
    }

    private Node themeRow() {
        Label name = new Label("Dark theme");
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

    private void fillMeLabel(Label label, String key, String prefix) {
        if (!NetworkWatch.isOnline()) {
            label.setText(prefix + "(offline)");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Map<String, String> me = Api.INSTANCE.getMe();
                Platform.runLater(() -> {
                    String v = me.get(key);
                    label.setText(prefix + (v == null || v.isBlank() ? "(none)" : v));
                });
            } catch (Exception e) {
                Platform.runLater(() -> label.setText(prefix + "(could not load)"));
            }
        }, "account-me-label");
        t.setDaemon(true);
        t.start();
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
        box.setPadding(new Insets(8, 36, 32, 28));
        return box;
    }

    private void paintChrome() {
        setStyle("-fx-background-color: " + AppTheme.typewriterBg() + ";");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; " + AppTheme.font()
                + "-fx-text-fill: " + AppTheme.ink() + ";");
        back.setStyle(AppTheme.font() + "-fx-background-color: transparent; -fx-text-fill: "
                + AppTheme.ink() + "; -fx-padding: 4 8;");
        nav.setStyle("-fx-background-color: " + AppTheme.typewriterRail() + ";");
        paintNavBtn(navUser, section == Section.USER);
        paintNavBtn(navSecurity, section == Section.SECURITY);
        paintNavBtn(navNotify, section == Section.NOTIFICATIONS);
        paintNavBtn(navDevice, section == Section.DEVICE);
        applyInk(scroll.getContent());
        applyInk(nav);
        paintNavBtn(navUser, section == Section.USER);
        paintNavBtn(navSecurity, section == Section.SECURITY);
        paintNavBtn(navNotify, section == Section.NOTIFICATIONS);
        paintNavBtn(navDevice, section == Section.DEVICE);
    }

    private static void paintNavBtn(Button b, boolean on) {
        if (b == null) return;
        b.setAlignment(Pos.CENTER_LEFT);
        b.setStyle(on ? AppTheme.railOn() : AppTheme.railOff());
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
