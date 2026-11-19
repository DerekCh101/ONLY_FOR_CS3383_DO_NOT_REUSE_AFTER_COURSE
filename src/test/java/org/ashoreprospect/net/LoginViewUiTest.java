package org.ashoreprospect.net;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.ashoreprospect.crypto.Vault;
import org.ashoreprospect.db.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.ashoreprospect.support.FxTestSupport;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ashoreprospect.support.FxTestSupport.await;
import static org.ashoreprospect.support.FxTestSupport.call;

@EnabledIfEnvironmentVariable(named = "SCHEDULER_TEST_FX", matches = "true")
@ExtendWith(FxTestSupport.class)
@ResourceLock("os-env")
@ResourceLock("node-session")
@ResourceLock("vault")
@ResourceLock("javafx-toolkit")
class LoginViewUiTest {

    private FakeNode node;
    private String authUserId;

    @BeforeEach
    void setUp() throws Exception {
        Api.INSTANCE.logout();
        Vault.eraseLocalKeys();
        LocalSession.savePrefs(false, "");
        String cachedUserId = TaskRepository.peekCachedUserId();
        authUserId = cachedUserId == null ? "login-user" : cachedUserId;
        node = new FakeNode();
        node.pointNodeAt();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) node.close();
        Api.INSTANCE.logout();
        Vault.eraseLocalKeys();
        Files.deleteIfExists(Endpoints.dir().resolve("login.txt"));
    }

    @Test
    void loginCardRestoresPreferencesAndNavigatesToRegistrationAndReset() throws Exception {
        LocalSession.savePrefs(true, "previous-user");
        AtomicInteger register = new AtomicInteger();
        AtomicInteger reset = new AtomicInteger();

        call(() -> {
            VBox card = loginCard(() -> {}, register::incrementAndGet, reset::incrementAndGet);
            assertEquals("previous-user", field(card, "username").getText());
            assertTrue(checkBox(card).isSelected());
            button(card, "Register").fire();
            button(card, "Forgot password").fire();
            return null;
        });
        assertEquals(1, register.get());
        assertEquals(1, reset.get());
        assertEquals(0, node.callCount());
    }

    @Test
    void loginDisplaysServerErrorsThenAppliesASuccessfulSession() throws Exception {
        CountDownLatch signedIn = new CountDownLatch(1);
        VBox card = call(() -> loginCard(signedIn::countDown, () -> {}, () -> {}));
        node.replyWith(401, "{\"error\":\"bad credentials\"}");
        call(() -> {
            field(card, "username").setText("alice");
            field(card, "password").setText("Strong1!");
            button(card, "Login").fire();
            return null;
        });
        await(() -> "bad credentials".equals(error(card).getText())
                && !button(card, "Login").isDisabled() && !button(card, "Register").isDisabled());

        node.replyWith(200, authReply("login-token", authUserId));
        call(() -> {
            button(card, "Login").fire();
            return null;
        });
        assertTrue(signedIn.await(10, TimeUnit.SECONDS), "login did not complete");
        assertEquals("login-token", Api.INSTANCE.getToken());
        assertEquals(authUserId, Api.INSTANCE.getUserId());
        assertFalse(LocalSession.wantRemember());
        assertEquals("/login", node.calls().get(1).path);
    }

    @Test
    void registrationValidatesFieldsAndHandlesErrorAndSuccess() throws Exception {
        CountDownLatch signedIn = new CountDownLatch(1);
        AtomicInteger back = new AtomicInteger();
        VBox card = call(() -> registerCard(signedIn::countDown, back::incrementAndGet));
        call(() -> {
            button(card, "Create account").fire();
            assertEquals("Username, password, email, security question, and answer are all required.",
                    error(card).getText());
            field(card, "username").setText("alice");
            field(card, "password (8+, A-z, number, symbol)").setText("weak");
            field(card, "email").setText("alice@example.test");
            field(card, "security question (your own words, min 8)").setText("What is your test question?");
            field(card, "security answer (min 4)").setText("answer");
            button(card, "Create account").fire();
            assertEquals(PasswordRules.HINT, error(card).getText());
            button(card, "Back to login").fire();
            return null;
        });
        assertEquals(1, back.get());
        assertEquals(0, node.callCount());

        node.replyWith(400, "{\"error\":\"username taken\"}");
        call(() -> {
            field(card, "password (8+, A-z, number, symbol)").setText("Strong1!");
            button(card, "Create account").fire();
            return null;
        });
        await(() -> "username taken".equals(error(card).getText())
                && !button(card, "Create account").isDisabled()
                && !button(card, "Back to login").isDisabled());

        node.replyWith(200, authReply("register-token", authUserId));
        call(() -> {
            button(card, "Create account").fire();
            return null;
        });
        assertTrue(signedIn.await(10, TimeUnit.SECONDS), "registration did not complete");
        assertEquals("register-token", Api.INSTANCE.getToken());
        assertEquals(authUserId, Api.INSTANCE.getUserId());
        assertEquals("/register", node.calls().get(1).path);
        assertTrue(node.calls().get(1).body.contains("\"email\":\"alice@example.test\""));
    }

    @Test
    void resetCardValidatesAndReportsRecoveryResults() throws Exception {
        AtomicInteger back = new AtomicInteger();
        VBox card = call(() -> resetCard(back::incrementAndGet));
        call(() -> {
            field(card, "new password (8+, A-z, number, symbol)").setText("weak");
            button(card, "Reset with token + answer").fire();
            assertEquals(PasswordRules.HINT, error(card).getText());
            return null;
        });
        assertEquals(0, node.callCount());

        node.replyWith(200, "{\"ok\":true}");
        call(() -> {
            field(card, "username").setText("alice");
            field(card, "email on the account").setText("alice@example.test");
            button(card, "Email me a token").fire();
            return null;
        });
        await(() -> error(card).getText().startsWith("If that account exists")
                && !button(card, "Email me a token").isDisabled());
        assertEquals("/forgot", node.calls().get(0).path);

        node.replyWith(500, "{\"error\":\"mail unavailable\"}");
        call(() -> {
            button(card, "Email me a token").fire();
            return null;
        });
        await(() -> "mail unavailable".equals(error(card).getText())
                && !button(card, "Email me a token").isDisabled());

        node.replyWith(400, "{\"error\":\"invalid token\"}");
        call(() -> {
            field(card, "reset token from email / host console").setText("token");
            field(card, "security answer").setText("answer");
            field(card, "new password (8+, A-z, number, symbol)").setText("Strong1!");
            button(card, "Reset with token + answer").fire();
            return null;
        });
        await(() -> "invalid token".equals(error(card).getText())
                && !button(card, "Reset with token + answer").isDisabled());

        node.replyWith(200, "{\"ok\":true}");
        call(() -> {
            button(card, "Reset with token + answer").fire();
            return null;
        });
        await(() -> "Password reset. All sessions were signed out. Log in.".equals(error(card).getText())
                && !button(card, "Reset with token + answer").isDisabled());
        assertEquals("/reset", node.calls().get(3).path);
        call(() -> {
            button(card, "Back to login").fire();
            return null;
        });
        assertEquals(1, back.get());
    }

    private static String authReply(String token, String userId) {
        String escapedId = userId.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return "{\"token\":\"" + token + "\",\"userId\":\"" + escapedId + "\",\"username\":\"alice\"}";
    }

    private static VBox loginCard(Runnable onOk, Runnable register, Runnable reset) throws Exception {
        Method method = LoginView.class.getDeclaredMethod(
                "buildLoginCard", Runnable.class, Runnable.class, Runnable.class);
        method.setAccessible(true);
        return (VBox) method.invoke(new LoginView(), onOk, register, reset);
    }

    private static VBox registerCard(Runnable onOk, Runnable back) throws Exception {
        Method method = LoginView.class.getDeclaredMethod("buildRegisterCard", Runnable.class, Runnable.class);
        method.setAccessible(true);
        return (VBox) method.invoke(new LoginView(), onOk, back);
    }

    private static VBox resetCard(Runnable back) throws Exception {
        Method method = LoginView.class.getDeclaredMethod("buildResetCard", Runnable.class);
        method.setAccessible(true);
        return (VBox) method.invoke(null, back);
    }

    private static TextField field(VBox card, String prompt) {
        return card.getChildren().stream().filter(TextField.class::isInstance)
                .map(TextField.class::cast).filter(f -> prompt.equals(f.getPromptText()))
                .findFirst().orElseThrow();
    }

    private static Button button(VBox card, String text) {
        return card.getChildren().stream().filter(Button.class::isInstance)
                .map(Button.class::cast).filter(b -> text.equals(b.getText()))
                .findFirst().orElseThrow();
    }

    private static CheckBox checkBox(VBox card) {
        return card.getChildren().stream().filter(CheckBox.class::isInstance)
                .map(CheckBox.class::cast).findFirst().orElseThrow();
    }

    private static Label error(VBox card) {
        return card.getChildren().stream().filter(Label.class::isInstance)
                .map(Label.class::cast).filter(l -> l.getStyleClass().contains("login-err"))
                .findFirst().orElseThrow();
    }

}
