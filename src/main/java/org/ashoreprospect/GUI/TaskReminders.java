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

    private static Timeline clock;
    private static TrayIcon trayIcon;
    private static final Set<String> sent = new HashSet<>();

    private TaskReminders() {}

    public static void start() {
        if (clock != null) return;
        clock = new Timeline(new KeyFrame(Duration.seconds(20), e -> tick()));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        Platform.runLater(TaskReminders::tick);
    }

    public static void stop() {
        if (clock != null) {
            clock.stop();
            clock = null;
        }
        sent.clear();
        if (trayIcon != null && SystemTray.isSupported()) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception ignored) {
            }
            trayIcon = null;
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
                    trayIcon = new TrayIcon(img, "Ashore Prospect");
                    trayIcon.setImageAutoSize(true);
                    SystemTray.getSystemTray().add(trayIcon);
                }
                trayIcon.displayMessage(title, body, TrayIcon.MessageType.INFO);
            } catch (Exception ignored) {
            }
        });
    }
}
