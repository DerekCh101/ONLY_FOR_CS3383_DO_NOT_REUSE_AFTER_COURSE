package org.ashoreprospect.net;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.StackPane;
import org.ashoreprospect.GUI.SecurityChallenge;
import org.ashoreprospect.support.FxTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.concurrent.atomic.AtomicInteger;

import static org.ashoreprospect.support.FxTestSupport.await;
import static org.ashoreprospect.support.FxTestSupport.call;
import static org.ashoreprospect.support.FxTestSupport.findAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "SCHEDULER_TEST_FX", matches = "true")
@ExtendWith(FxTestSupport.class)
@ResourceLock("javafx-toolkit")
@ResourceLock("node-session")
@ResourceLock("os-env")
@ResourceLock("session-prefs")
@ResourceLock("vault")
class SecurityChallengeUiTest {

    private FakeNode node;

    @BeforeEach
    void setUp() throws Exception {
        Api.INSTANCE.logoutKeepRemembered();
        NodeApi.AuthResult session = new NodeApi.AuthResult();
        session.userId = "challenge-user";
        session.username = "test-user";
        session.token = "challenge-token";
        Api.INSTANCE.applySession(session, false);
        node = new FakeNode();
        node.pointNodeAt();
        node.replyWith(200, "{\"hasSecurity\":true,\"securityQuestion\":\"A test question?\"}");
    }

    @AfterEach
    void tearDown() {
        if (node != null) node.close();
        Api.INSTANCE.logoutKeepRemembered();
    }

    @Test
    void correctAnswerDismissesChallenge() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        StackPane root = openChallenge(failures);
        node.replyWith(200, "{\"ok\":true}");
        call(() -> {
            findAll(root, PasswordField.class).get(0).setText("test answer");
            button(root, "Continue").fire();
            return null;
        });
        await(() -> root.getChildren().isEmpty());
        assertEquals(0, failures.get());
        assertEquals("/challenge-answer", node.calls().get(1).path);
        assertTrue(node.calls().get(1).body.contains("\"answer\":\"test answer\""));
    }

    @Test
    void wrongAnswerShowsMessageThenSignsOut() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        StackPane root = openChallenge(failures);
        node.replyWith(401, "{\"error\":\"wrong answer\"}");
        call(() -> {
            findAll(root, PasswordField.class).get(0).setText("wrong");
            button(root, "Continue").fire();
            return null;
        });
        await(() -> findAll(root, Label.class).stream()
                .anyMatch(label -> "Wrong answer — signing out.".equals(label.getText())));
        await(() -> failures.get() == 1 && root.getChildren().isEmpty());
    }

    @Test
    void logoutCancelsWithoutSendingAnAnswer() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        StackPane root = openChallenge(failures);
        call(() -> {
            button(root, "Logout").fire();
            assertTrue(root.getChildren().isEmpty());
            return null;
        });
        assertEquals(1, failures.get());
        assertEquals(1, node.callCount());
    }

    private static StackPane openChallenge(AtomicInteger failures) throws Exception {
        StackPane root = call(StackPane::new);
        call(() -> {
            SecurityChallenge.ask(root, failures::incrementAndGet);
            return null;
        });
        await(() -> findAll(root, Label.class).stream()
                .anyMatch(label -> "A test question?".equals(label.getText())));
        return root;
    }

    private static Button button(StackPane root, String text) {
        return findAll(root, Button.class).stream()
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }
}
