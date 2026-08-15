package org.example.db;

import org.example.*;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TaskRepository {

    /** No Postgres sync this long → wipe local SQLite, require reconnect. */
    public static final int STALE_MONTHS = 3;
    /** No active device this long → wipe Postgres app data (abandoned). */
    public static final int ABANDONED_MONTHS = 12;

    private final SqliteTaskRepository sqlite =
            new SqliteTaskRepository("jdbc:sqlite:scheduler-cache.db");

    private final PostgresTaskRepository postgres = new PostgresTaskRepository(
            "jdbc:postgresql://100.113.239.82:5432/Scheduler",
            "postgres",
            System.getenv("postgres_pw")
    );

    private final String deviceId = DeviceConfig.getOrCreateDeviceId();
    private boolean postgresAvailable = false;

    public TaskRepository() {
        try {
            sqlite.initialize();
            System.out.println("SQLite cache ready, deviceId=" + deviceId);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            postgres.initialize();
            postgresAvailable = true;
            System.out.println("Postgres Connected OK");
        } catch (Exception e) {
            postgresAvailable = false;
            System.out.println("Postgres offline — SQLite only");
        }
    }

    public boolean isPostgresAvailable() {
        return postgresAvailable;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void tryReconnectPostgres() {
        try {
            postgres.initialize();
            postgresAvailable = true;
            System.out.println("Postgres reconnected");
        } catch (Exception e) {
            postgresAvailable = false;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void dbAddTask(Task task) throws SQLException {
        ensureTimestamps(task);
        Set<String> deleted = sqlite.fetchDeletedIds();
        if (deleted.contains(task.getID())) {
            // resurrect only if explicitly adding again with same id (you use new UUIDs normally)
            // still allow upsert; optional: remove from delete log — skip for safety
        }
        sqlite.upsert(task);
        if (postgresAvailable) {
            try {
                postgres.upsert(task);
            } catch (SQLException e) {
                postgresAvailable = false;
                e.printStackTrace();
            }
        }
    }

    public void dbUpdateWorkflow(Task task) throws SQLException {
        task.touchLastUpdated();
        sqlite.updateWorkflow(task);
        if (postgresAvailable) {
            try {
                postgres.updateWorkflow(task);
            } catch (SQLException e) {
                postgresAvailable = false;
                e.printStackTrace();
            }
        }
    }

    public void dbClearTask(Task task) throws SQLException {
        sqlite.deleteTaskAndLog(task);
        if (postgresAvailable) {
            try {
                postgres.deleteTaskAndLog(task);
            } catch (SQLException e) {
                postgresAvailable = false;
                e.printStackTrace();
            }
        }
    }

    public void dbCleanAll() throws SQLException {
        sqlite.deleteAllTasksAndLogEach();
        if (postgresAvailable) {
            try {
                postgres.deleteAllTasksAndLog();
            } catch (SQLException e) {
                postgresAvailable = false;
                e.printStackTrace();
            }
        }
    }

    public void dbfetchAll(TaskManagementSystem theSystem) throws SQLException {
        if (postgresAvailable) {
            try {
                fullOnlineSync();
            } catch (SQLException e) {
                postgresAvailable = false;
                System.err.println("Sync failed — SQLite only: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            // offline: if local considers itself stale, refuse to show old cache as fresh
            maybeWarnLocalStaleOffline();
        }
        loadSqliteIntoMemory(theSystem);
    }

    public List<Task> fetchUnscheduledTasks() throws SQLException {
        return sqlite.fetchUnscheduled();
    }

    public boolean containsIdByDateTime(LocalDate placedDate, LocalTime placedTime) throws SQLException {
        return sqlite.containsIdByDateTime(placedDate, placedTime);
    }

    // -------------------------------------------------------------------------
    // Sync
    // -------------------------------------------------------------------------

    private void fullOnlineSync() throws SQLException {
        postgres.upsertDevice(deviceId, guessName(), System.getProperty("os.name"));

        // Abandoned account / no devices for a year → drop everything remote
        if (postgres.wipeIfAbandoned(ABANDONED_MONTHS)) {
            System.out.println("Abandoned account data wiped on Postgres");
            sqlite.wipeAllLocalData();
        }

        // Revoke devices that disappeared for STALE_MONTHS
        postgres.revokeStaleDevices(STALE_MONTHS);

        // This install stale locally → wipe SQLite, force full pull
        if (isLocalInstallStale()) {
            System.out.println("Local install stale > " + STALE_MONTHS + " months — wiping SQLite, full pull");
            sqlite.wipeAllLocalData();
        }

        syncDeletesBothWays();
        postgres.touchDeleteSync(deviceId);

        syncTasksBothWays();
        postgres.touchTaskSync(deviceId);

        sqlite.setMeta("last_postgres_sync_at", Instant.now().toString());
        sqlite.setMeta("last_delete_sync_at", Instant.now().toString());

        gcDeleteLogs();
    }

    private boolean isLocalInstallStale() throws SQLException {
        String last = sqlite.getMeta("last_postgres_sync_at");
        if (last == null || last.isBlank()) return false; // first run
        Instant then = Instant.parse(last);
        return ChronoUnit.MONTHS.between(then, Instant.now()) >= STALE_MONTHS;
    }

    private void maybeWarnLocalStaleOffline() throws SQLException {
        if (isLocalInstallStale()) {
            System.out.println("WARNING: offline and stale — connect Postgres once to refresh cache");
            // optional hard wipe offline:
            // sqlite.wipeAllLocalData();
        }
    }

    private void syncDeletesBothWays() throws SQLException {
        // Pull remote deletes → local
        for (Object[] row : postgres.fetchAllDeletes()) {
            String id = (String) row[0];
            Instant when = (Instant) row[1];
            sqlite.deleteTaskRow(id);
            sqlite.recordDelete(id, when);
        }
        // Push local deletes → remote
        for (String[] row : sqlite.fetchAllDeletes()) {
            String id = row[0];
            Instant when = Instant.parse(row[1]);
            postgres.deleteTaskRow(id);
            postgres.recordDelete(id, when);
        }
    }

    private void syncTasksBothWays() throws SQLException {
        Set<String> deleted = sqlite.fetchDeletedIds();

        List<Task> remoteList = postgres.fetchAll();
        for (Task remote : remoteList) {
            if (deleted.contains(remote.getID())) continue;
            Task local = sqlite.findById(remote.getID());
            Task merged = (local == null) ? remote : mergeTasks(local, remote);
            if (deleted.contains(merged.getID())) continue;
            sqlite.upsert(merged);
        }

        Map<String, Task> remoteById = new HashMap<>();
        for (Task r : remoteList) remoteById.put(r.getID(), r);

        for (Task local : sqlite.fetchAll()) {
            if (deleted.contains(local.getID())) continue;
            if (!remoteById.containsKey(local.getID())) {
                try {
                    postgres.upsert(local);
                } catch (SQLException e) {
                    System.err.println("Push local-only failed: " + local.getID());
                }
            }
        }
    }

    private void gcDeleteLogs() throws SQLException {
        Instant cutoff = postgres.minDeleteAckAmongActive(STALE_MONTHS);
        if (cutoff == null) return;
        postgres.purgeDeletesBefore(cutoff);
        sqlite.purgeDeletesBefore(cutoff);
        System.out.println("GC sync_deletes before " + cutoff);
    }

    static Task mergeTasks(Task local, Task remote) {
        Task out = Task.loadTaskFromdb(
                local.getID(),
                local.getTaskName(),
                local.getDurationHours(),
                local.getState(),
                local.getStartingDate(),
                local.getStartingTime(),
                local.getDeadLineDate(),
                local.getDeadLineTime(),
                local.getPlacedDate(),
                local.getPlacedTime()
        );
        out.setLifeCycle(local.getLifeCycle());
        out.setWorkflow(local.getWorkflow());
        out.setPlacementUpdatedAt(local.getPlacementUpdatedAt());
        out.setLastUpdatedAt(local.getLastUpdatedAt());

        int placeCmp = compareInstant(local.getPlacementUpdatedAt(), remote.getPlacementUpdatedAt());
        if (placeCmp <= 0) {
            out.setPlacedDate(remote.getPlacedDate());
            out.setPlacedTime(remote.getPlacedTime());
            out.setPlacementUpdatedAt(remote.getPlacementUpdatedAt());
        }

        int lastCmp = compareInstant(local.getLastUpdatedAt(), remote.getLastUpdatedAt());
        Task src = (lastCmp <= 0) ? remote : local;
        out.setTaskName(src.getTaskName());
        out.setDurationHours(src.getDurationHours());
        out.setState(src.getState());
        out.setDeadLineDate(src.getDeadLineDate());
        out.setDeadLineTime(src.getDeadLineTime());
        out.setStartingDate(src.getStartingDate());
        out.setStartingTime(src.getStartingTime());
        out.setLifeCycle(src.getLifeCycle());
        out.setWorkflow(src.getWorkflow());
        out.setLastUpdatedAt(src.getLastUpdatedAt() != null ? src.getLastUpdatedAt() : Instant.now());
        return out;
    }

    static int compareInstant(Instant a, Instant b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    private void ensureTimestamps(Task task) {
        Instant now = Instant.now();
        if (task.getLastUpdatedAt() == null) task.setLastUpdatedAt(now);
        if (task.getPlacementUpdatedAt() == null && task.getPlacedDate() != null) {
            task.setPlacementUpdatedAt(now);
        }
    }

    private String guessName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "device";
        }
    }

    private void loadSqliteIntoMemory(TaskManagementSystem theSystem) throws SQLException {
        Set<String> deleted = sqlite.fetchDeletedIds();
        for (Task task : sqlite.fetchAll()) {
            if (deleted.contains(task.getID())) continue;

            LocalDate placedDate = task.getPlacedDate();
            LocalTime placedTime = task.getPlacedTime();
            TaskLifeCycle lifeFromDb = task.getLifeCycle();

            if (placedDate != null && placedTime != null) {
                theSystem.getGrid().putIfAbsent(placedDate, new Day(theSystem.getBlockedTime()));
                Day day = theSystem.getGrid().get(placedDate);
                int slot = AppTimeReference.localTimeToSlot(placedTime);
                day.placeTask(slot, task);
                theSystem.getPlacedTasks().add(task);

                TaskLifeCycle before = task.getLifeCycle();
                RefreshLifeCycle.refreshLifeCycle(task);
                if (before != task.getLifeCycle()) {
                    dbAddTask(task);
                }
            } else if (lifeFromDb == TaskLifeCycle.UNSCHEDULED) {
                theSystem.getUnscheduledTasks().add(task);
            } else {
                theSystem.getPendingTasks().add(task);
            }
        }
    }
}