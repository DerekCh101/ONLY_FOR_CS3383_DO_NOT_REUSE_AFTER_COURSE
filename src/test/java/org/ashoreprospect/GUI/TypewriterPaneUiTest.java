package org.ashoreprospect.GUI;

import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.net.Api;
import org.ashoreprospect.support.FxTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.ashoreprospect.support.FxTestSupport.call;
import static org.ashoreprospect.support.FxTestSupport.findAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "SCHEDULER_TEST_FX", matches = "true")
@ExtendWith(FxTestSupport.class)
@ResourceLock("app-clock")
@ResourceLock("javafx-toolkit")
@ResourceLock("node-session")
@ResourceLock("os-env")
@ResourceLock("vault")
class TypewriterPaneUiTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    @BeforeEach
    void setUp() {
        Api.INSTANCE.logoutKeepRemembered();
        AppTimeReference.setClock(Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            CountDownLatch settled = new CountDownLatch(1);
            call(() -> {
                PauseTransition wait = new PauseTransition(Duration.millis(600));
                wait.setOnFinished(event -> settled.countDown());
                wait.play();
                return null;
            });
            assertTrue(settled.await(5, TimeUnit.SECONDS), "Typewriter save did not settle");
        } finally {
            Api.INSTANCE.logoutKeepRemembered();
            AppTimeReference.setClock(Clock.systemDefaultZone());
        }
    }

    @Test
    void listCanBeEditedAndDeletionRequiresConfirmation() throws Exception {
        TypewriterPane pane = call(TypewriterPane::new);
        call(() -> {
            TypewriterStore.Data initial = new TypewriterStore.Data();
            initial.lists.add(new TypewriterPane.TwList("Existing"));
            pane.applyFromNode(initial);
            button(pane, "Lists").fire();
            button(pane, "New list").fire();
            field(pane, "List name").setText("Packing");
            field(pane, "Add item and press Enter").setText("Passport");
            button(pane, "Add").fire();

            TypewriterPane.TwList added = pane.snapshot().lists.stream()
                    .filter(list -> "Packing".equals(list.title)).findFirst().orElseThrow();
            assertEquals(1, added.items.size());
            assertEquals("Passport", added.items.get(0).text);
            findAll(pane, CheckBox.class).get(0).fire();
            assertTrue(added.items.get(0).done);

            button(pane, "Delete list").fire();
            assertTrue(hasLabel(overlay(pane), "Delete list?"));
            button(overlay(pane), "Cancel").fire();
            assertEquals(2, pane.snapshot().lists.size());
            button(pane, "Delete list").fire();
            button(overlay(pane), "Delete").fire();
            assertEquals(1, pane.snapshot().lists.size());
            assertEquals("Existing", pane.snapshot().lists.get(0).title);
            return null;
        });
    }

    @Test
    void pagesCanBeCreatedInsideANewFolder() throws Exception {
        TypewriterPane pane = call(TypewriterPane::new);
        call(() -> {
            TypewriterStore.Data initial = new TypewriterStore.Data();
            initial.pagesRoot.notes.add(new TypewriterPane.TwNote("Existing", null));
            pane.applyFromNode(initial);
            button(pane, "Pages").fire();
            button(pane, "New folder").fire();
            Node prompt = overlay(pane);
            assertTrue(hasLabel(prompt, "Folder name"));
            findAll(prompt, TextField.class).get(0).setText("Research");
            button(prompt, "Save").fire();
            button(pane, "New page").fire();
            field(pane, "Title").setText("References");

            TypewriterPane.TwFolder folder = pane.snapshot().pagesRoot.children.get(0);
            assertEquals("Research", folder.title);
            assertEquals(1, folder.notes.size());
            assertEquals("References", folder.notes.get(0).title);
            return null;
        });
    }

    @Test
    void diaryClearCanBeCancelledAndConfirmed() throws Exception {
        TypewriterPane pane = call(TypewriterPane::new);
        call(() -> {
            TypewriterStore.Data initial = new TypewriterStore.Data();
            TypewriterPane.TwNote note = new TypewriterPane.TwNote("Today", TODAY);
            note.body = "<p>A written entry</p>";
            initial.diary.put(TODAY, note);
            pane.applyFromNode(initial);
            button(pane, "Diary").fire();

            button(pane, "Clear this day").fire();
            button(overlay(pane), "Cancel").fire();
            assertTrue(pane.snapshot().diary.containsKey(TODAY));
            button(pane, "Clear this day").fire();
            button(overlay(pane), "Clear").fire();
            assertFalse(pane.snapshot().diary.containsKey(TODAY));
            return null;
        });
    }

    private static Node overlay(TypewriterPane pane) {
        StackPane layers = (StackPane) pane.getCenter();
        return layers.getChildren().get(layers.getChildren().size() - 1);
    }

    private static Button button(Node root, String text) {
        return findAll(root, Button.class).stream()
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    private static TextField field(Node root, String prompt) {
        return findAll(root, TextField.class).stream()
                .filter(field -> prompt.equals(field.getPromptText())).findFirst().orElseThrow();
    }

    private static boolean hasLabel(Node root, String text) {
        return findAll(root, Label.class).stream().anyMatch(label -> text.equals(label.getText()));
    }
}
