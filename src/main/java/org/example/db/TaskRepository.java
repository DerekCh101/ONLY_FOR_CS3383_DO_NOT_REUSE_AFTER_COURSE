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

/**
 * Facade the rest of the app uses for all DB work.
 * Owns:
 *   - SQLite (always: offline cache)
 *   - Postgres (when reachable: shared truth + device registry)
 *   - sync order: deletes first, then tasks, then load into memory
 *
 * GUI / commands should call only this class (dbAddTask, dbClearTask, dbfetchAll, …),
 * not SqliteTaskRepository / PostgresTaskRepository directly.
 */
public class TaskRepository {

    /**
     * If this install has not successfully synced to Postgres for this many months,
     * wipe local SQLite and pull fresh on next online sync.
     */
    public static final int STALE_MONTHS = 3;

    /**
     * If no device has been active for this many months, wipe all app data on Postgres
     * (abandoned account policy).
     */
    public static final int ABANDONED_MONTHS = 12;

    /** Local file DB on this machine. */
    private final SqliteTaskRepository sqlite =
            new SqliteTaskRepository("jdbc:sqlite:scheduler-cache.db");

    /**
     * Shared Postgres (home desktop / Tailscale IP).
     * Password from environment variable postgres_pw — not hard-coded.
     */
    private final PostgresTaskRepository postgres = new PostgresTaskRepository(
            "jdbc:postgresql://100.113.239.82:5432/Scheduler",
            "postgres",
            System.getenv("postgres_pw")
    );

    /** Stable UUID for this install (file scheduler-device-id.txt). */
    private final String deviceId = DeviceConfig.getOrCreateDeviceId();

    /** True after a successful postgres.initialize() (or reconnect). */
    private boolean postgresAvailable = false;

    /**
     * Startup: always init SQLite; try Postgres once.
     * App still runs if Postgres fails (offline mode).
     */
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

    /**
     * Whether the last known state thinks Postgres is reachable.
     */
    public boolean isPostgresAvailable() {
        return postgresAvailable;
    }

    /**
     * This machine's device id (for TASK.devices and debugging).
     */
    public String getDeviceId() {
        return deviceId;
    }

    /**
     * Call when user comes back online (optional menu/button).
     * Retries schema/connect; does not by itself reload the grid
     * (caller should dbfetchAll + refreshGUI after).
     */
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
    // Public API — what AddTask / ClearTask / UI should use
    // -------------------------------------------------------------------------

    /**
     * Save a task: always SQLite; also Postgres if online.
     * If Postgres write fails, mark offline so later ops do not keep failing loudly.
     */
    public void dbAddTask(Task task) throws SQLException {
        ensureTimestamps(task);
        Set<String> deleted = sqlite.fetchDeletedIds();
        if (deleted.contains(task.getID())) {
            // Same UUID after delete is rare (you generate new UUIDs). Left as no-op note.
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

    /**
     * Persist FINISHED/UNFINISHED only (and last_updated_at).
     */
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
     * Delete one task and write delete-log so other devices remove it too.
     * Offline: only SQLite + local log; online sync pushes the log later.
     */
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

    /**
     * Delete every task and log each id (clear-all).
     */
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
     * Main entry at app start (and when you want a full refresh from DB):
     *   1) if online → fullOnlineSync()
     *   2) always load SQLite into TaskManagementSystem lists/grid
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

    /**
     * Side-panel style list from local cache only.
     */
    public List<Task> fetchUnscheduledTasks() throws SQLException {
        return sqlite.fetchUnscheduled();
    }

    /**
     * Local conflict helper: is this date+time already used in SQLite?
     */
    public boolean containsIdByDateTime(LocalDate placedDate, LocalTime placedTime) throws SQLException {
        return sqlite.containsIdByDateTime(placedDate, placedTime);
    }

    // -------------------------------------------------------------------------
    // Sync internals
    // -------------------------------------------------------------------------

    /**
     * Full online protocol (order matters):
     * register device → abandoned wipe? → revoke stale devices →
     * local stale wipe? → sync deletes → sync tasks → save cursors → GC logs
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

        syncDeletesBothWays();
        postgres.touchDeleteSync(deviceId);

        syncTasksBothWays();
        postgres.touchTaskSync(deviceId);

        sqlite.setMeta("last_postgres_sync_at", Instant.now().toString());
        sqlite.setMeta("last_delete_sync_at", Instant.now().toString());

        gcDeleteLogs();
    }

    /**
     * True if last successful Postgres sync was STALE_MONTHS or more ago.
     * First run (no meta) returns false so we do not wipe a brand-new cache.
     */
    private boolean isLocalInstallStale() throws SQLException {
        String last = sqlite.getMeta("last_postgres_sync_at");
        if (last == null || last.isBlank()) return false; // first run

        Instant then = Instant.parse(last);
        long days = ChronoUnit.DAYS.between(then, Instant.now());
        // ~3 months (STALE_MONTHS * 30 days)
        return days >= (long) STALE_MONTHS * 30L;
    }

    /**
     * Offline path: warn if cache is old; does not wipe by default
     * (so user can still see last known schedule without network).
     */
    private void maybeWarnLocalStaleOffline() throws SQLException {
        if (isLocalInstallStale()) {
            System.out.println("WARNING: offline and stale — connect Postgres once to refresh cache");
        }
    }

    /**
     * Exchange delete logs both ways, then remove those task rows on each side.
     * Must run BEFORE task upsert merge (or deleted tasks come back to life).
     */
    private void syncDeletesBothWays() throws SQLException {
        // Server → this laptop/desktop
        for (Object[] row : postgres.fetchAllDeletes()) {
            String id = (String) row[0];
            Instant when = (Instant) row[1];
            sqlite.deleteTaskRow(id);
            sqlite.recordDelete(id, when);
        }
        // This device → server
        for (String[] row : sqlite.fetchAllDeletes()) {
            String id = row[0];
            Instant when = Instant.parse(row[1]);
            postgres.deleteTaskRow(id);
            postgres.recordDelete(id, when);
        }
    }

    /**
     * Merge live tasks:
     *   - pull each remote task (skip if in delete log), merge with local, write SQLite
     *   - push local-only tasks to Postgres
     * Does not re-create ids that are in sync_deletes.
     */
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

    /**
     * Drop old sync_deletes rows once every active device has acknowledged
     * delete-sync past that time (MIN last_delete_sync_at).
     */
    private void gcDeleteLogs() throws SQLException {
        Instant cutoff = postgres.minDeleteAckAmongActive(STALE_MONTHS);
        if (cutoff == null) return;
        postgres.purgeDeletesBefore(cutoff);
        sqlite.purgeDeletesBefore(cutoff);
        System.out.println("GC sync_deletes before " + cutoff);
    }

    /**
     * Field-level merge: placement uses placement_updated_at;
     * name/duration/state/deadline/workflow use last_updated_at.
     * Newer timestamp wins (null counts as older).
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

    /**
     * Null-safe Instant compare: null is treated as older than any real time.
     * @return negative if a &lt; b, 0 if equal, positive if a &gt; b
     */
    static int compareInstant(Instant a, Instant b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    /**
     * Ensure new tasks have timestamps before first write (merge needs them).
     */
    private void ensureTimestamps(Task task) {
        Instant now = Instant.now();
        if (task.getLastUpdatedAt() == null) task.setLastUpdatedAt(now);
        if (task.getPlacementUpdatedAt() == null && task.getPlacedDate() != null) {
            task.setPlacementUpdatedAt(now);
        }
    }

    /**
     * Human-readable name for TASK.devices.display_name (hostname if possible).
     */
    private String guessName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "device";
        }
    }

    /**
     * After sync (or offline): read SQLite, skip deleted ids, fill
     * placedTasks / unscheduled / pending and Day grids in memory.
     * May refresh lifecycle and write back if PAST/ONGOING changed.
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