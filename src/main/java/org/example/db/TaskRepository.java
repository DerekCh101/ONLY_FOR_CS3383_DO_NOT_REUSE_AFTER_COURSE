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
 * Facade over two stores:
 *   - SQLite  = always-on local cache (works offline)
 *   - Postgres = shared server when reachable (via Tailscale JDBC URL below)
 *
 * Write path:  memory change → sqlite first → postgres if online
 * Read path:   if online, fullOnlineSync() then load sqlite into memory
 *
 * Deletes use a tombstone log (sync_deletes) so another device can learn
 * "this id is gone" and not resurrect it on the next merge.
 *
 * GC of tombstones only runs when Postgres says enough active devices
 * have all acknowledged deletes (needs ≥2 devices).
 */
public class TaskRepository {

    /** Device not seen this long → treated as stale for GC / revoke logic. */
    public static final int STALE_MONTHS = 3;
    /** No active device this long → optional wipe of abandoned server data. */
    public static final int ABANDONED_MONTHS = 12;

    private final SqliteTaskRepository sqlite =
            new SqliteTaskRepository("jdbc:sqlite:scheduler-cache.db");

    /**
     * Arg1 = JDBC URL (host:port/db). Host is Tailscale IP of the desktop, not an HTTP API.
     * Arg2 = Postgres user. Arg3 = password from env (never hardcode).
     */
    private final PostgresTaskRepository postgres = new PostgresTaskRepository(
            "jdbc:postgresql://100.113.239.82:5432/Scheduler",
            "postgres",
            System.getenv("postgres_pw")
    );

    /** Stable id for this install; used in TASK.devices for sync ack / GC. */
    private final String deviceId = DeviceConfig.getOrCreateDeviceId();

    /** Flipped false on connection/sync failure; app keeps working on SQLite only. */
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

    /** Call when user comes back online; re-runs schema ensure + sets flag. */
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
    // New task id (UUID conflict check — not calendar slots)
    // -------------------------------------------------------------------------

    /**
     * Keep drawing random UUIDs until one is free in live tasks and delete log.
     * Collisions are astronomically rare; loop is for correctness, not capacity.
     */
    public String allocateNewTaskId() {
        while (true) {
            String id = UUID.randomUUID().toString();
            if (!idIsTaken(id)) {
                return id;
            }
        }
    }

    /**
     * Taken if: row in local tasks, or local tombstone, or (when online)
     * same on Postgres — so we do not reuse an id another device deleted.
     */
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
            // Fail open so create is not blocked if local check breaks
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Public API (commands / GUI call these)
    // -------------------------------------------------------------------------

    /** Upsert task locally; mirror to Postgres if up. Skips if id is tombstoned. */
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

    /** Workflow-only update (e.g. FINISHED); bumps lastUpdatedAt. */
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

    /**
     * Remove task row and write tombstone locally (and on server if online)
     * so other devices drop it on next sync instead of merging it back.
     */
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

    /** Wipe all tasks; each id gets a tombstone (clear-all is syncable). */
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

    /**
     * Startup / refresh: sync with Postgres when possible, then always
     * hydrate in-memory lists + grid from SQLite.
     */
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
    // Sync (Postgres online only)
    // -------------------------------------------------------------------------

    /**
     * Order matters:
     * 1) register device
     * 2) optional abandoned wipe / revoke stale devices
     * 3) exchange DELETE logs first (never merge a tombstoned row)
     * 4) merge/push tasks
     * 5) GC tombstones if multi-device ack allows
     */
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

        // Deletes before tasks — critical
        syncDeletesBothWays();
        postgres.touchDeleteSync(deviceId);

        syncTasksBothWays();
        postgres.touchTaskSync(deviceId);

        sqlite.setMeta("last_postgres_sync_at", Instant.now().toString());
        sqlite.setMeta("last_delete_sync_at", Instant.now().toString());

        gcDeleteLogs();
    }

    /** True if last successful sync was older than STALE_MONTHS (approx days). */
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

    /**
     * Bidirectional tombstone exchange, then force-delete task rows for every
     * known deleted id on both sides.
     */
    private void syncDeletesBothWays() throws SQLException {
        // Server → local
        for (Object[] row : postgres.fetchAllDeletes()) {
            String id = (String) row[0];
            Instant when = (Instant) row[1];
            String title = (row.length > 2 && row[2] != null) ? row[2].toString() : null;
            if (title != null && title.isBlank()) title = null;

            sqlite.deleteTaskRow(id);
            sqlite.recordDelete(id, when, title);
        }

        // Local → server
        for (String[] row : sqlite.fetchAllDeletes()) {
            String id = row[0];
            Instant when = Instant.parse(row[1]);
            String title = (row.length > 2 && row[2] != null && !row[2].isBlank())
                    ? row[2]
                    : null;

            postgres.deleteTaskRow(id);
            postgres.recordDelete(id, when, title);
        }

        // Belt-and-suspenders: no task row may remain for a tombstoned id
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

    /**
     * Pull remote tasks (merge with local by timestamps), then push local-only
     * rows that look like real offline creates. Local rows that vanished on
     * server and were not edited after last sync are treated as remote deletes.
     */
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

            // Missing on server + not newer than last sync → other device deleted it
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

            // Offline create (or edit after last sync) → push up
            try {
                postgres.upsert(local);
            } catch (SQLException e) {
                System.err.println("Push local-only failed: " + local.getID());
            }
        }
    }

    /**
     * Delete old tombstones only when Postgres returns a cutoff
     * (all active devices have acked deletes). Single-device → no-op.
     */
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

    /**
     * Last-write-wins style merge:
     * - placement fields follow newer placementUpdatedAt
     * - other fields follow newer lastUpdatedAt
     */
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

    /** null is treated as older than any real Instant. */
    static int compareInstant(Instant a, Instant b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    /** Fill missing timestamps so merge/sync has something to compare. */
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

    /**
     * Rebuild pending / placed / unscheduled + day grid from SQLite only.
     * Skips tombstoned ids. May write back if lifecycle changes (e.g. → PAST).
     */
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