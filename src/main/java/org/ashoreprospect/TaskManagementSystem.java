/** Live task lists, blocked time, and GUI refresh. Named actions live in SystemCommands. */
package org.ashoreprospect;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javafx.application.Platform;
import org.ashoreprospect.CliRunner.CLIRunner;
import org.ashoreprospect.Command.*;
import org.ashoreprospect.GUI.SchedulerController;
import org.ashoreprospect.db.TaskRepository;
import org.ashoreprospect.net.Api;

public class TaskManagementSystem {

    private static TaskManagementSystem theSystem;
    private List<Task> pendingTasks;
    private List<Task> placedTasks;
    private List<Task> unscheduledTasks;
    private SchedulerEngine engine;
    private BlockedTimePeriod blockedTime;
    private LocalDate baseDate;
    private SchedulerController guiController;
    private boolean cliFlag;
    private Map<LocalDate, Day> daysGrid;
    private FetchPopOutTask popupTask;
    private TaskRepository repo;
    private volatile boolean pollRunning;
    private final List<Task> pendingCarryOrphans = new ArrayList<>();
    private final java.util.Set<String> carryPrompted = new java.util.HashSet<>();

    private TaskManagementSystem() {
        System.out.println("System Created - with RunSchedulerCommand");
        pendingTasks = new ArrayList<>();
        placedTasks = new ArrayList<>();
        unscheduledTasks = new ArrayList<>();
        baseDate = AppTimeReference.currentColumnDate();
        blockedTime = new BlockedTimePeriod();
        daysGrid = new TreeMap<>();
        engine = new SchedulerEngine(this, baseDate, daysGrid);
        cliFlag = false;
        repo = new TaskRepository();

        try {
            repo.dbfetchAll(this);
        } catch (SQLException e) {
            System.out.println("Failed to load tasks: " + e.getMessage());
            e.printStackTrace();
        }

        startNodePoll();
    }

    public static TaskManagementSystem getInstance() {
        if (theSystem != null) {
            return theSystem;
        }
        theSystem = new TaskManagementSystem();
        return theSystem;
    }

    public static void resetInstance() {
        if (theSystem != null) {
            theSystem.pollRunning = false;
        }
        theSystem = null;
    }

    public void attachGUI(SchedulerController controller) {
        this.guiController = controller;
        refreshGUI();
    }

    public List<Task> getPendingTasks() {
        return pendingTasks;
    }

    public List<Task> getPlacedTasks() {
        return placedTasks;
    }

    public List<Task> getUnscheduledTasks() {
        return unscheduledTasks;
    }

    public void offerCarryOrphan(Task past) {
        if (past == null || past.getID() == null) return;
        if (!carryPrompted.add(past.getID())) return;
        pendingCarryOrphans.add(past);
    }

    public List<Task> takeCarryOrphans() {
        List<Task> out = new ArrayList<>(pendingCarryOrphans);
        pendingCarryOrphans.clear();
        return out;
    }

    public BlockedTimePeriod getBlockedTime() {
        return blockedTime;
    }

    public Map<LocalDate, Day> getGrid() {
        return daysGrid;
    }

    public SchedulerEngine getEngine() {
        return engine;
    }

    public TaskRepository getTaskRepo() {
        return repo;
    }

    /** Rewrite live tasks/blocked through the store so enable/disable encryption covers data already saved. */
    public void rewriteEncryptedStores() throws SQLException {
        Instant now = Instant.now();
        List<Task> all = new ArrayList<>();
        all.addAll(placedTasks);
        all.addAll(unscheduledTasks);
        all.addAll(pendingTasks);
        for (Task t : all) {
            if (t == null) continue;
            org.ashoreprospect.crypto.Vault.openTask(t);
            t.setLastUpdatedAt(now);
            repo.dbAddTask(t);
        }
        if (blockedTime != null && blockedTime.blockedPeriod != null) {
            for (BlockedTime b : blockedTime.blockedPeriod) {
                if (b == null) continue;
                org.ashoreprospect.crypto.Vault.openBlocked(b);
                b.setLastUpdatedAt(now);
                repo.dbAddBlocked(b);
            }
        }
    }

    private void startNodePoll() {
        if (pollRunning) return;
        pollRunning = true;
        Thread t = new Thread(() -> {
            while (pollRunning) {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    break;
                }
                if (!pollRunning) break;
                if (Api.INSTANCE.getToken() == null || Api.INSTANCE.getToken().isBlank()) {
                    continue;
                }
                try {
                    if (repo.pullFromNode()) {
                        repo.reloadFromSqlite(this);
                        refreshGUI();
                    }
                } catch (Exception e) {
                    System.err.println("node poll: " + e.getMessage());
                }
            }
        }, "node-poll");
        t.setDaemon(true);
        t.start();
    }

    public void updateSchedule() {
        ArrayList<Task> toSchedule = new ArrayList<>();

        for (Task t : new ArrayList<>(placedTasks)) {
            if (!t.isFixedTime()) {
                toSchedule.add(t);
            }
        }
        for (Task t : new ArrayList<>(pendingTasks)) {
            if (!t.isFixedTime() && !toSchedule.contains(t)) {
                toSchedule.add(t);
            }
        }
        for (Task t : new ArrayList<>(unscheduledTasks)) {
            if (!t.isFixedTime() && !toSchedule.contains(t)) {
                toSchedule.add(t);
            }
        }

        for (Task t : toSchedule) {
            placedTasks.remove(t);
            pendingTasks.remove(t);
            unscheduledTasks.remove(t);
            for (Day day : daysGrid.values()) {
                Task[] slots = day.returnDayGrid();
                boolean touched = false;
                for (int i = 0; i < slots.length; i++) {
                    if (slots[i] == t) {
                        slots[i] = null;
                        touched = true;
                    }
                }
                if (touched) {
                    day.recomputeLoadFromGrid();
                }
            }
            t.setPlacedDate(null);
            t.setPlacedTime(null);
        }

        engine.scheduleTasks(toSchedule);
    }

    public void refreshGUI() {
        if (guiController != null) {
            Platform.runLater(() -> guiController.refresh());
        }
    }

    public FetchPopOutTask fetchPopupTask(FetchPopOutTask popupTask) {
        this.popupTask = popupTask;
        return popupTask;
    }

    public FetchPopOutTask getPopupTask() {
        return popupTask;
    }

    public boolean command(String cmdString) {
        try {
            return SystemCommands.run(cmdString, this);
        } catch (SQLException e) {
            System.out.println("Database error: " + e.getMessage());
            e.printStackTrace();
            return false;
        } catch (Exception e) {
            System.out.println("Command failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public CLIRunner getCliMode() {
        return new CLIRunner();
    }

    public boolean getCliFlag() {
        return cliFlag;
    }
}