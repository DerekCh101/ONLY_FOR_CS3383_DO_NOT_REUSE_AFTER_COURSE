/** Windows / in-app popups at user-chosen lead times before a placed task starts. */
package org.ashoreprospect.GUI;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;
import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.Workflow;
import org.ashoreprospect.net.LocalSession;

import java.awt.Toolkit;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TaskReminders {

    private static final long TICK_MILLIS = 20_000L;

    private static Timeline clock;

    /**
     * The tick loop used when there is no JavaFX toolkit. Normally null: the app runs on the FX
     * thread, so {@link #clock} drives the ticks.
     */
    private static Thread clockThread;
    private static volatile boolean ticking;

    private static TrayIcon trayIcon;
    private static final Set<String> sent = new HashSet<>();

    private TaskReminders() {}

    public static void start() {
        if (clockThread != null && !clockThread.isAlive()) clockThread = null;
        if (clock != null || clockThread != null) return;
        if (!startTimeline()) startThread();
        onFxThread(TaskReminders::tick);
    }

    public static void stop() {
        if (clockThread != null) clockThread.interrupt();
        onFxThread(TaskReminders::clearTimers);
        sent.clear();
        if (trayIcon != null && SystemTray.isSupported()) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception ignored) {
            }
            trayIcon = null;
        }
    }

    /** Installs the FX {@code Timeline}; false when no toolkit is available on this thread. */
    private static boolean startTimeline() {
        try {
            Timeline t = new Timeline(new KeyFrame(Duration.seconds(20), e -> tick()));
            t.setCycleCount(Timeline.INDEFINITE);
            t.play();
            clock = t;
            return true;
        } catch (IllegalStateException | IllegalArgumentException
                 | UnsupportedOperationException | NoClassDefFoundError noToolkit) {
            return false;
        }
    }

    /** Fallback tick loop for a headless JVM or a caller that is not on the FX thread. */
    private static void startThread() {
        ticking = true;
        Thread t = new Thread(() -> {
            while (ticking) {
                try {
                    Thread.sleep(TICK_MILLIS);
                } catch (InterruptedException stop) {
                    return;
                }
                if (!ticking) return;
                try {
                    tick();
                } catch (RuntimeException ignored) {
                    // A failing tick must not kill the loop; the next one retries.
                }
            }
        }, "task-reminders");
        t.setDaemon(true);
        clockThread = t;
        t.start();
    }

    /** Clears both timer fields. Runs on the FX thread when there is one, so stop() is legal. */
    private static void clearTimers() {
        if (clock != null) {
            clock.stop();
            clock = null;
        }
        ticking = false;
        clockThread = null;
    }

    /** Runs {@code body} on the FX thread, or inline when JavaFX has no toolkit. */
    private static void onFxThread(Runnable body) {
        try {
            if (Platform.isFxApplicationThread()) {
                body.run();
            } else {
                Platform.runLater(body);
            }
        } catch (IllegalStateException | UnsupportedOperationException | NoClassDefFoundError noToolkit) {
            body.run();
        }
    }

    private static void tick() {
        if (!LocalSession.wantReminders()) return;
        List<Integer> leads = LocalSession.reminderLeads();
        if (leads.isEmpty()) return;
        TaskManagementSystem sys;
        try {
            sys = TaskManagementSystem.getInstance();
        } catch (Exception e) {
            return;
        }
        List<Task> placed = sys.getPlacedTasks();
        if (placed == null || placed.isEmpty()) return;
        LocalDateTime now = AppTimeReference.getCurrentDateTime();
        int maxLead = 0;
        for (int lead : leads) if (lead > maxLead) maxLead = lead;
        for (Task t : new ArrayList<>(placed)) {
            if (t == null || t.isQuick() || t.getPlacedDate() == null || t.getPlacedTime() == null) continue;
            if (t.getWorkflow() == Workflow.FINISHED) continue;
            if (t.getLifeCycle() == TaskLifeCycle.PAST) continue;
            LocalDateTime start = LocalDateTime.of(t.getPlacedDate(), t.getPlacedTime());
            long mins = ChronoUnit.MINUTES.between(now, start);
            if (mins > maxLead) continue;
            String name = t.getTaskName() == null || t.getTaskName().isBlank() ? "Task" : t.getTaskName();
            for (int lead : leads) {
                boolean hit = lead <= 0
                        ? mins <= 0 && mins > -2
                        : mins <= lead && mins > lead - 2 && mins > 0;
                if (!hit) continue;
                String key = t.getID() + "|" + start + "|" + lead;
                if (!sent.add(key)) continue;
                String title = lead <= 0 ? "Task now" : "Starting soon";
                String body = lead <= 0
                        ? name + " is on (" + t.getPlacedTime() + ")"
                        : name + " starts in " + formatLead(lead) + " (" + t.getPlacedTime() + ")";
                org.ashoreprospect.SchedulerApp.notifyUser(title, body);
                tray(title, body);
                if (LocalSession.wantReminderSound()) {
                    try {
                        Toolkit.getDefaultToolkit().beep();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    public static String formatLead(int minutes) {
        if (minutes <= 0) return "when it starts";
        if (minutes % 1440 == 0) {
            int d = minutes / 1440;
            return d + (d == 1 ? " day" : " days");
        }
        if (minutes % 60 == 0) {
            int h = minutes / 60;
            return h + (h == 1 ? " hour" : " hours");
        }
        return minutes + " min";
    }

    private static void tray(String title, String body) {
        if (!SystemTray.isSupported()) return;
        java.awt.EventQueue.invokeLater(() -> {
            try {
                if (trayIcon == null) {
                    BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = img.createGraphics();
                    g.setColor(new java.awt.Color(28, 36, 48));
                    g.fillRoundRect(0, 0, 16, 16, 4, 4);
                    g.setColor(new java.awt.Color(243, 238, 228));
                    g.fillOval(4, 4, 8, 8);
                    g.dispose();
                    trayIcon = new TrayIcon(img, "Scheduler");
                    trayIcon.setImageAutoSize(true);
                    SystemTray.getSystemTray().add(trayIcon);
                }
                trayIcon.displayMessage(title, body, TrayIcon.MessageType.INFO);
            } catch (Exception ignored) {
            }
        });
    }
}
