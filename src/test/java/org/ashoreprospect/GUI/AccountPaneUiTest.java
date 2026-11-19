package org.ashoreprospect.GUI;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.ashoreprospect.net.LocalSession;
import org.ashoreprospect.support.FxTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.ashoreprospect.support.FxTestSupport.call;
import static org.ashoreprospect.support.FxTestSupport.findAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "SCHEDULER_TEST_FX", matches = "true")
@ExtendWith(FxTestSupport.class)
@ResourceLock("javafx-toolkit")
@ResourceLock("node-session")
@ResourceLock("os-env")
@ResourceLock("session-prefs")
@ResourceLock("theme-prefs")
class AccountPaneUiTest {

    private boolean reminders;
    private boolean sound;
    private List<Integer> leads;

    @BeforeEach
    void rememberPreferences() {
        reminders = LocalSession.wantReminders();
        sound = LocalSession.wantReminderSound();
        leads = LocalSession.reminderLeads();
    }

    @AfterEach
    void restorePreferences() {
        LocalSession.setReminders(reminders);
        LocalSession.setReminderSound(sound);
        LocalSession.setReminderLeads(leads);
        AppTheme.clearListeners();
    }

    @Test
    void notificationsValidateAndPersistReminderSettings() throws Exception {
        call(() -> {
            AccountPane account = new AccountPane(null, null, null);
            button(account, "Notifications").fire();
            assertTrue(hasLabel(account, "Notifications"));

            CheckBox master = checkbox(account, "Pop-up reminders (Windows notification + in-app banner)");
            CheckBox soundBox = checkbox(account, "Play a sound with the reminder");
            if (master.isSelected()) master.fire();
            assertFalse(LocalSession.wantReminders());
            assertTrue(soundBox.isDisabled());
            master.fire();
            assertTrue(LocalSession.wantReminders());
            assertFalse(soundBox.isDisabled());

            TextField custom = findAll(account, TextField.class).stream()
                    .filter(field -> "Custom minutes (e.g. 15)".equals(field.getPromptText()))
                    .findFirst().orElseThrow();
            custom.setText("0");
            button(account, "Add").fire();
            assertTrue(hasLabel(account, "Enter minutes from 1 to 20160 (14 days)."));
            custom.setText("17");
            button(account, "Add").fire();
            assertTrue(LocalSession.reminderLeads().contains(17));
            assertEquals("", custom.getText());
            button(account, "Remove custom times").fire();
            assertFalse(LocalSession.reminderLeads().contains(17));
            return null;
        });
    }

    @Test
    void navigationAndLogoutInvokeTheSuppliedCallback() throws Exception {
        AtomicInteger logouts = new AtomicInteger();
        call(() -> {
            AccountPane account = new AccountPane(logouts::incrementAndGet, null, null);
            button(account, "Device").fire();
            assertTrue(hasLabel(account, "Device"));
            button(account, "User info").fire();
            assertTrue(hasLabel(account, "User info"));
            button(account, "Logout").fire();
            assertEquals(1, logouts.get());
            return null;
        });
    }

    private static Button button(AccountPane account, String text) {
        return findAll(account, Button.class).stream()
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    private static CheckBox checkbox(AccountPane account, String text) {
        return findAll(account, CheckBox.class).stream()
                .filter(box -> text.equals(box.getText())).findFirst().orElseThrow();
    }

    private static boolean hasLabel(AccountPane account, String text) {
        return findAll(account, Label.class).stream().anyMatch(label -> text.equals(label.getText()));
    }
}
