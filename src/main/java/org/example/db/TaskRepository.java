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
import java.util.UUID;

/**
 * Facade: SQLite always, Postgres when online.
 * Deletes: always log locally; push/pull delete log before task merge.
 * GC: only when Postgres returns a cutoff (needs ≥2 devices).
 */
public class TaskRepository {

    public static final int STALE_MONTHS = 3;
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
    // New task id (conflict check — not calendar slots)
    // -------------------------------------------------------------------------

    /**
     * Draw UUID until it is free of live tasks and tombstones (local always; Postgres if online).
     * Practically always succeeds on the first try.
     */
    public String allocateNewTaskId() {
        while (true) {
            String id = UUID.randomUUID().toString();
            if (!idIsTaken(id)) {
                return id;
            }
        }
    }

    /** True if id is already a task or a delete-log entry. */
    public boolean idIsTaken(String id) {
        if (id == null || id.isBlank()) return true;
        try {
            if (sqlite.existsTaskId(id) || sqlite.isDeletedId(id)) {
                return true;
            }
            if (postgresAvailable) {
                try {
                    if (postgres.existsTaskId(id) || postgres.isDeletedId(id)) {
                        return true;
                    }
                } catch (SQLException e) {
                    System.err.println("idIsTaken postgres check failed: " + e.getMessage());
                }
            }
            return false;
        } catch (SQLException e) {
            System.err.println("idIsTaken sqlite failed: " + e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void dbAddTask(Task task) throws SQLException {
        ensureTimestamps(task);
        Set<String> deleted = sqlite.fetchDeletedIds();
        if (deleted.contains(task.getID())) {
            System.out.println("dbAddTask: id is in sync_deletes, skipping upsert: " + task.getID());
            return;
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
        if (task == null || task.getID() == null) return;
        sqlite.deleteTaskAndLog(task);
        System.out.println("dbClearTask local ok id=" + task.getID());
        if (postgresAvailable) {
            try {
                postgres.deleteTaskAndLog(task);
                System.out.println("dbClearTask postgres ok id=" + task.getID());
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

        if (postgres.wipeIfAbandoned(ABANDONED_MONTHS)) {
            System.out.println("Abandoned account data wiped on Postgres");
            sqlite.wipeAllLocalData();
        }

        postgres.revokeStaleDevices(STALE_MONTHS);

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
        if (last == null || last.isBlank()) return false;
        Instant then = Instant.parse(last);
        long days = ChronoUnit.DAYS.between(then, Instant.now());
        return days >= (long) STALE_MONTHS * 30L;
    }

    private void maybeWarnLocalStaleOffline() throws SQLException {
        if (isLocalInstallStale()) {
            System.out.println("WARNING: offline and stale — connect Postgres once to refresh cache");
        }
    }

    private void syncDeletesBothWays() throws SQLException {
        for (Object[] row : postgres.fetchAllDeletes()) {
            String id = (String) row[0];
            Instant when = (Instant) row[1];
            String title = (row.length > 2 && row[2] != null) ? row[2].toString() : null;
            if (title != null && title.isBlank()) title = null;

            sqlite.deleteTaskRow(id);
            sqlite.recordDelete(id, when, title);
        }

        for (String[] row : sqlite.fetchAllDeletes()) {
            String id = row[0];
            Instant when = Instant.parse(row[1]);
            String title = (row.length > 2 && row[2] != null && !row[2].isBlank())
                    ? row[2]
                    : null;

            postgres.deleteTaskRow(id);
            postgres.recordDelete(id, when, title);
        }

        for (String id : sqlite.fetchDeletedIds()) {
            sqlite.deleteTaskRow(id);
            if (postgresAvailable) {
                try {
                    postgres.deleteTaskRow(id);
                } catch (SQLException e) {
                    System.err.println("force delete on postgres failed: " + id);
                }
            }
        }
    }

    private void syncTasksBothWays() throws SQLException {
        Set<String> deleted = new HashSet<>(sqlite.fetchDeletedIds());

        List<Task> remoteList = postgres.fetchAll();
        for (Task remote : remoteList) {
            if (deleted.contains(remote.getID())) {
                postgres.deleteTaskRow(remote.getID());
                continue;
            }
            Task local = sqlite.findById(remote.getID());
            Task merged = (local == null) ? remote : mergeTasks(local, remote);
            if (deleted.contains(merged.getID())) continue;
            sqlite.upsert(merged);
        }

        Map<String, Task> remoteById = new HashMap<>();
        for (Task r : remoteList) {
            if (!deleted.contains(r.getID())) {
                remoteById.put(r.getID(), r);
            }
        }

        Instant lastSync = null;
        String lastSyncStr = sqlite.getMeta("last_postgres_sync_at");
        if (lastSyncStr != null && !lastSyncStr.isBlank()) {
            try {
                lastSync = Instant.parse(lastSyncStr);
            } catch (Exception ignored) { }
        }

        for (Task local : sqlite.fetchAll()) {
            if (deleted.contains(local.getID())) continue;
            if (remoteById.containsKey(local.getID())) continue;

            Instant localEdit = local.getLastUpdatedAt();
            if (localEdit == null) localEdit = local.getPlacementUpdatedAt();

            if (lastSync != null && localEdit != null && !localEdit.isAfter(lastSync)) {
                System.out.println("Drop local (missing on server, not new): " + local.getID());
                sqlite.deleteTaskRow(local.getID());
                sqlite.recordDelete(local.getID(), Instant.now());
                try {
                    postgres.deleteTaskRow(local.getID());
                    postgres.recordDelete(local.getID(), Instant.now());
                } catch (SQLException e) {
                    System.err.println("server tombstone failed: " + local.getID());
                }
                continue;
            }

            try {
                postgres.upsert(local);
            } catch (SQLException e) {
                System.err.println("Push local-only failed: " + local.getID());
            }
        }
    }

    private void gcDeleteLogs() throws SQLException {
        Instant cutoff = postgres.minDeleteAckAmongActive(STALE_MONTHS);
        if (cutoff == null) {
            System.out.println("GC skipped (need ≥2 devices all acked, or no cutoff)");
            return;
        }
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