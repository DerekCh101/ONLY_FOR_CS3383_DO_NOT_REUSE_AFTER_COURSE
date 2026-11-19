package org.ashoreprospect.GUI;

import javafx.animation.Animation;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.net.Api;
import org.ashoreprospect.net.LocalSession;
import org.ashoreprospect.net.NetworkWatch;
import org.ashoreprospect.support.FxTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.ashoreprospect.support.FxTestSupport.await;
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
@ResourceLock("session-prefs")
@ResourceLock("theme-prefs")
class SchedulerControllerUiTest {

    private FXMLLoader loader;
    private List<?> themeListeners;
    private List<?> previousThemeListeners;
    private List<?> networkListeners;
    private List<?> previousNetworkListeners;
    private boolean remember;
    private String lastUsername;

    @BeforeEach
    void setUp() throws Exception {
        Api.INSTANCE.logoutKeepRemembered();
        remember = LocalSession.wantRemember();
        lastUsername = LocalSession.lastUsername();
        LocalSession.savePrefs(false, lastUsername);
        AppTimeReference.setClock(Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"), ZoneOffset.UTC));
        TaskManagementSystem.resetInstance();
        TaskManagementSystem system = TaskManagementSystem.getInstance();
        system.getGrid().clear();
        system.getPlacedTasks().clear();
        system.getPendingTasks().clear();
        system.getUnscheduledTasks().clear();
        system.getBlockedTime().blockedPeriod.clear();
        themeListeners = listeners(AppTheme.class);
        previousThemeListeners = List.copyOf(themeListeners);
        networkListeners = listeners(NetworkWatch.class);
        previousNetworkListeners = List.copyOf(networkListeners);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            call(() -> {
                SchedulerController controller = loader == null ? null : loader.getController();
                if (controller != null) {
                    stopAnimation(controller, "nowLineClock");
                    stopAnimation(controller, "refreshSoon");
                    stopAnimation(controller, "idleTimer");
                }
                TaskReminders.stop();
                if (themeListeners != null) themeListeners.retainAll(previousThemeListeners);
                if (networkListeners != null) networkListeners.retainAll(previousNetworkListeners);
                return null;
            });
        } finally {
            LocalSession.savePrefs(remember, lastUsername);
            Api.INSTANCE.logoutKeepRemembered();
            TaskManagementSystem.resetInstance();
            AppTimeReference.setClock(Clock.systemDefaultZone());
        }
    }

    @Test
    void fxmlControllerNavigatesViewsTabsAndCli() throws Exception {
        call(() -> {
            loader = new FXMLLoader(getClass().getResource("/UIScheduler.fxml"));
            loader.load();
            return null;
        });
        Map<String, Object> controls = loader.getNamespace();
        GridPane grid = (GridPane) controls.get("grid");
        StackPane main = (StackPane) controls.get("mainStack");
        await(() -> main.getChildren().stream().anyMatch(TypewriterPane.class::isInstance)
                && main.getChildren().stream().anyMatch(AccountPane.class::isInstance)
                && hasLabel(grid, "MON 10/12"));

        call(() -> {
            ((Button) controls.get("btnNext")).fire();
            return null;
        });
        await(() -> hasLabel(grid, "MON 10/19"));

        call(() -> {
            ((Button) controls.get("btnDayView")).fire();
            assertEquals("Prev Day", ((Button) controls.get("btnPrev")).getText());
            return null;
        });
        await(() -> hasLabel(grid, "Today"));

        call(() -> {
            ((Button) controls.get("btnMonthView")).fire();
            assertEquals("Prev Month", ((Button) controls.get("btnPrev")).getText());
            return null;
        });
        await(() -> hasLabel(grid, "OCTOBER 2026"));

        call(() -> {
            HBox cliBar = (HBox) controls.get("cliBar");
            ((Button) controls.get("cliShow")).fire();
            assertTrue(cliBar.isVisible());
            ((TextField) controls.get("cliInput")).setText("not-a-command");
            ((Button) controls.get("cliRun")).fire();
            assertTrue(((Label) controls.get("cliOut")).getText().contains("unknown"));

            TypewriterPane typewriter = main.getChildren().stream()
                    .filter(TypewriterPane.class::isInstance).map(TypewriterPane.class::cast)
                    .findFirst().orElseThrow();
            AccountPane account = main.getChildren().stream()
                    .filter(AccountPane.class::isInstance).map(AccountPane.class::cast)
                    .findFirst().orElseThrow();
            ((Button) controls.get("tabTypewriter")).fire();
            assertTrue(typewriter.isVisible());
            ((MenuItem) controls.get("menuAccount")).fire();
            assertFalse(typewriter.isVisible());
            assertTrue(account.isVisible());
            ((Button) controls.get("tabScheduler")).fire();
            assertFalse(account.isVisible());
            return null;
        });
    }

    private static boolean hasLabel(GridPane grid, String text) {
        return findAll(grid, Label.class).stream().anyMatch(label -> text.equals(label.getText()));
    }

    private static List<?> listeners(Class<?> owner) throws Exception {
        Field field = owner.getDeclaredField("listeners");
        field.setAccessible(true);
        return (List<?>) field.get(null);
    }

    private static void stopAnimation(SchedulerController controller, String name) throws Exception {
        Field field = SchedulerController.class.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(controller);
        if (value instanceof Animation animation) animation.stop();
    }
}
