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
 * One door for all task/blocked saving.
 * Phone-book rule: always write SQLite on this PC first.
 * If the internet server (Postgres) is up, copy the same change there.
 * If Postgres is down, the app still works from the local file.
 */
public class TaskRepository {

    public static final int STALE_MONTHS = 3; // unused PC for this many months is treated as old
    public static final int ABANDONED_MONTHS = 12; // unused this long: wipe server copy as abandoned

    private final SqliteTaskRepository sqlite =
            new SqliteTaskRepository("jdbc:sqlite:scheduler-cache.db"); // local file next to the app

    private final PostgresTaskRepository postgres = new PostgresTaskRepository(
            "jdbc:postgresql://100.113.239.82:5432/Scheduler", // Tailscale address of the shared database
            "postgres",
            System.getenv("postgres_pw") // password from the OS environment, not hard-coded
    );

    private final String deviceId = DeviceConfig.getOrCreateDeviceId(); // this PC's id, created once and reused
    private boolean postgresAvailable = false; // true only after a successful open

    public TaskRepository() { // runs once when TaskManagementSystem is first created (login boot)
        try {
            sqlite.initialize(); // create local tables if the file is new
            System.out.println("SQLite cache ready, deviceId=" + deviceId);
        } catch (Exception e) {
            e.printStackTrace(); // local file failed; later saves will also fail
        }

        try {
            postgres.initialize(); // open the network database and create missing tables
            postgresAvailable = true; // later methods may copy to the server
            System.out.println("Postgres Connected OK");
        } catch (Exception e) {
            postgresAvailable = false; // stay local-only this session
            System.out.println("Postgres offline — SQLite only");
            System.out.println("Postgres reason: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public boolean isPostgresAvailable() { // GUI or sync code asking "is the server up right now?"
        return postgresAvailable;
    }

    public String getDeviceId() { // who is this PC in TASK.devices
        return deviceId;
    }

    public void tryReconnectPostgres() { // called before a save if we think the server was down
        try {
            postgres.initialize(); // try the network again
            postgresAvailable = true;
            System.out.println("Postgres reconnected");
        } catch (Exception e) {
            postgresAvailable = false; // still down; keep using SQLite
        }
    }

    public String allocateNewTaskId() { // Add Task button: pick an id no row already uses
        while (true) {
            String id = UUID.randomUUID().toString(); // random id
            if (!idIsTaken(id)) return id; // unused in live rows and in the delete log
        }
    }

    public boolean idIsTaken(String id) { // used only while making a new id
        if (id == null || id.isBlank()) return true; // empty is not allowed
        try {
            if (sqlite.existsTaskId(id) || sqlite.isDeletedId(id)) return true; // already on this PC
            if (postgresAvailable) {
                try {
                    if (postgres.existsTaskId(id) || postgres.isDeletedId(id)) return true; // already on server
                } catch (SQLException e) {
                    System.err.println("idIsTaken postgres check failed: " + e.getMessage());
                }
            }
            return false; // safe to use
        } catch (SQLException e) {
            System.err.println("idIsTaken sqlite failed: " + e.getMessage());
            return false;
        }
    }

    public void dbAddTask(Task task) throws SQLException { // new task, edit, move, or load that changed lifecycle
        ensureTimestamps(task); // fill missing clocks so sync can compare
        Set<String> deleted = sqlite.fetchDeletedIds();
        if (deleted.contains(task.getID())) { // this id was deleted; do not bring it back through add
            System.out.println("dbAddTask: id is in sync_deletes, skipping upsert: " + task.getID());
            return;
        }
        sqlite.upsert(task); // insert or overwrite the local row
        if (postgresAvailable) {
            try {
                postgres.upsert(task); // same row on the server
            } catch (SQLException e) {
                postgresAvailable = false; // server died mid-save
                e.printStackTrace();
            }
        }
    }

    public void dbUpdateWorkflow(Task task) throws SQLException { // Mark done / Reopen in the info dialog
        task.touchLastUpdated(); // this edit is newer than other devices
        sqlite.updateWorkflow(task); // only the finished/unfinished column locally
        if (postgresAvailable) {
            try {
                postgres.updateWorkflow(task); // same column on the server (pgAdmin workflow)
            } catch (SQLException e) {
                postgresAvailable = false;
                e.printStackTrace();
            }
        }
    }

    public void dbClearTask(Task task) throws SQLException { // Delete in the info dialog / ClearTask command
        if (task == null || task.getID() == null) return;

        sqlite.deleteTaskAndLog(task); // remove local row and write a tombstone so sync will not revive it
        System.out.println("dbClearTask sqlite ok id=" + task.getID());

        if (!postgresAvailable) {
            tryReconnectPostgres(); // one more try so pgAdmin can see the delete
        }
        if (postgresAvailable) {
            try {
                postgres.deleteTaskAndLog(task); // remove TASK.tasks row and add TASK.sync_deletes
                System.out.println("dbClearTask postgres ok id=" + task.getID());
            } catch (SQLException e) {
                postgresAvailable = false;
                System.err.println("dbClearTask postgres FAILED: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.err.println("dbClearTask: Postgres offline — only SQLite changed");
        }
    }

    public void dbRestoreTask(Task task) throws SQLException { // Undo after ClearTask
        if (task == null || task.getID() == null) return;
        ensureTimestamps(task);

        sqlite.unrecordDelete(task.getID()); // take the id out of the local delete log
        sqlite.upsert(task); // put the row back on this PC

        if (!postgresAvailable) tryReconnectPostgres();
        if (postgresAvailable) {
            try {
                postgres.unrecordDelete(task.getID()); // take the id out of TASK.sync_deletes
                postgres.upsert(task); // put TASK.tasks back so pgAdmin shows it again
                System.out.println("dbRestoreTask postgres ok id=" + task.getID());
            } catch (SQLException e) {
                postgresAvailable = false;
                System.err.println("dbRestoreTask postgres FAILED: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void dbCleanAll() throws SQLException { // File -> Reset My Tasks
        sqlite.deleteAllTasksAndLogEach(); // every local task becomes a tombstone
        if (postgresAvailable) {
            try {
                postgres.deleteAllTasksAndLog(); // same on the server for this user
            } catch (SQLException e) {
                postgresAvailable = false;
                e.printStackTrace();
            }
        }
    }

    public void dbAddBlocked(BlockedTime blocked) throws SQLException { // save one blocked period from the editor
        if (blocked.getLastUpdatedAt() == null) blocked.touch(); // first save needs a clock
        Set<String> deleted = sqlite.fetchBlockedDeletedIds();
        if (deleted.contains(blocked.getId())) { // deleted blocked slots must stay dead
            System.out.println("dbAddBlocked: id tombstoned, skip " + blocked.getId());
            return;
        }
        sqlite.upsertBlocked(blocked); // local blocked_times
        if (postgresAvailable) {
            try {
                postgres.upsertBlocked(blocked); // TASK.blocked_times
            } catch (SQLException e) {
                postgresAvailable = false;
                e.printStackTrace();
            }
        }
    }

    public void dbClearBlocked(BlockedTime blocked) throws SQLException { // delete one blocked period
        if (blocked == null || blocked.getId() == null) return;
        sqlite.deleteBlockedAndLog(blocked); // local row + blocked tombstone
        if (postgresAvailable) {
            try {
                postgres.deleteBlockedAndLog(blocked); // server row + server tombstone
            } catch (SQLException e) {
                postgresAvailable = false;
                e.printStackTrace();
            }
        }
    }

    public List<BlockedTime> fetchAllBlocked() throws SQLException { // paint blocked cells after login
        return sqlite.fetchAllBlocked(); // always from the local file (already synced if online)
    }

    public void dbfetchAll(TaskManagementSystem theSystem) throws SQLException { // after login, before the grid paints
        String uid = org.example.net.Api.INSTANCE.getUserId();
        if (uid == null || uid.isBlank()) { // not logged in: do not leak another account
            System.out.println("No logged-in user — skip load");
            return;
        }
        String cachedUser = sqlite.getMeta("user_id");
        if (cachedUser == null || !cachedUser.equals(uid)) { // different person used this PC last
            sqlite.wipeAllLocalData(); // drop the other account's cache
            sqlite.setMeta("user_id", uid); // remember who this file now belongs to
        }

        if (postgresAvailable) {
            try {
                fullOnlineSync(); // merge this PC and the server
            } catch (SQLException e) {
                postgresAvailable = false;
                System.err.println("Sync failed — SQLite only: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            maybeWarnLocalStaleOffline(); // file may be months old
        }
        loadBlockedIntoMemory(theSystem); // fill the blocked list used by Day
        loadSqliteIntoMemory(theSystem); // fill placed / pending / unscheduled lists
    }

    public List<Task> fetchUnscheduledTasks() throws SQLException { // side panel "could not refit"
        return sqlite.fetchUnscheduled();
    }

    public boolean containsIdByDateTime(LocalDate placedDate, LocalTime placedTime) throws SQLException { // "is this slot taken in the file?"
        return sqlite.containsIdByDateTime(placedDate, placedTime);
    }

    private void fullOnlineSync() throws SQLException { // only from dbfetchAll when Postgres opened
        postgres.upsertDevice(deviceId, guessName(), System.getProperty("os.name")); // check in this PC

        if (postgres.wipeIfAbandoned(ABANDONED_MONTHS)) { // nobody used the account for a year
            System.out.println("Abandoned account data wiped on Postgres");
            sqlite.wipeAllLocalData();
        }

        postgres.revokeStaleDevices(STALE_MONTHS); // mark quiet devices as revoked

        if (isLocalInstallStale()) { // this PC has not synced for 3+ months
            System.out.println("Local install stale > " + STALE_MONTHS + " months — wiping SQLite, full pull");
            sqlite.wipeAllLocalData(); // take a clean copy from the server next
        }

        syncDeletesBothWays(); // agree which task ids are dead
        syncBlockedDeletesBothWays(); // agree which blocked ids are dead
        postgres.touchDeleteSync(deviceId); // this PC has seen the delete log

        syncTasksBothWays(); // merge live tasks
        syncBlockedBothWays(); // merge live blocked times
        postgres.touchTaskSync(deviceId); // this PC has seen the task rows

        sqlite.setMeta("last_postgres_sync_at", Instant.now().toString()); // local clock of last good sync
        sqlite.setMeta("last_delete_sync_at", Instant.now().toString());

        gcDeleteLogs(); // drop old tombstones only when every active device has seen them
    }

    private boolean isLocalInstallStale() throws SQLException { // helper for fullOnlineSync
        String last = sqlite.getMeta("last_postgres_sync_at");
        if (last == null || last.isBlank()) return false; // first run is not stale
        Instant then = Instant.parse(last);
        long days = ChronoUnit.DAYS.between(then, Instant.now());
        return days >= (long) STALE_MONTHS * 30L; // about 3 months
    }

    private void maybeWarnLocalStaleOffline() throws SQLException { // helper when Postgres did not open
        if (isLocalInstallStale()) {
            System.out.println("WARNING: offline and stale — connect Postgres once to refresh cache");
        }
    }

    private void syncDeletesBothWays() throws SQLException { // part of login sync
        for (Object[] row : postgres.fetchAllDeletes()) { // server says these task ids are dead
            String id = (String) row[0];
            Instant when = (Instant) row[1];
            String title = (row.length > 2 && row[2] != null) ? row[2].toString() : null;
            if (title != null && title.isBlank()) title = null;
            sqlite.deleteTaskRow(id); // drop local live row
            sqlite.recordDelete(id, when, title); // keep the same tombstone locally
        }

        for (String[] row : sqlite.fetchAllDeletes()) { // this PC deleted something while offline
            String id = row[0];
            Instant when = Instant.parse(row[1]);
            String title = (row.length > 2 && row[2] != null && !row[2].isBlank()) ? row[2] : null;
            postgres.deleteTaskRow(id); // drop server live row
            postgres.recordDelete(id, when, title); // tombstone on the server
        }

        for (String id : sqlite.fetchDeletedIds()) { // belt and braces: dead ids must not remain as live rows
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

    private void syncBlockedDeletesBothWays() throws SQLException { // same idea for blocked periods
        for (Object[] row : postgres.fetchAllBlockedDeletes()) { // server deleted these blocked ids
            String id = (String) row[0];
            Instant when = (Instant) row[1];
            String title = (row.length > 2 && row[2] != null) ? row[2].toString() : null;
            if (title != null && title.isBlank()) title = null;
            sqlite.deleteBlockedRow(id);
            sqlite.recordBlockedDelete(id, when, title);
        }

        for (String[] row : sqlite.fetchAllBlockedDeletes()) { // this PC deleted blocked time while offline
            String id = row[0];
            Instant when = Instant.parse(row[1]);
            String title = (row.length > 2 && row[2] != null && !row[2].isBlank()) ? row[2] : null;
            postgres.deleteBlockedRow(id);
            postgres.recordBlockedDelete(id, when, title);
        }

        for (String id : sqlite.fetchBlockedDeletedIds()) {
            sqlite.deleteBlockedRow(id);
            if (postgresAvailable) {
                try {
                    postgres.deleteBlockedRow(id);
                } catch (SQLException e) {
                    System.err.println("force blocked delete on postgres failed: " + id);
                }
            }
        }
    }

    private void syncTasksBothWays() throws SQLException { // after deletes are agreed
        Set<String> deleted = new HashSet<>(sqlite.fetchDeletedIds()); // ids we must never revive

        List<Task> remoteList = postgres.fetchAll(); // every live server task for this user
        for (Task remote : remoteList) {
            if (deleted.contains(remote.getID())) { // server still has a row we already buried
                postgres.deleteTaskRow(remote.getID());
                continue;
            }
            Task local = sqlite.findById(remote.getID());
            Task merged = (local == null) ? remote : mergeTasks(local, remote); // keep the newer fields
            if (deleted.contains(merged.getID())) continue;
            sqlite.upsert(merged); // local file now matches the winner
        }

        Map<String, Task> remoteById = new HashMap<>();
        for (Task r : remoteList) {
            if (!deleted.contains(r.getID())) remoteById.put(r.getID(), r);
        }

        Instant lastSync = parseMetaInstant(sqlite.getMeta("last_postgres_sync_at"));

        for (Task local : sqlite.fetchAll()) { // local rows the server does not have
            if (deleted.contains(local.getID())) continue;
            if (remoteById.containsKey(local.getID())) continue;

            Instant localEdit = local.getLastUpdatedAt();
            if (localEdit == null) localEdit = local.getPlacementUpdatedAt();

            if (lastSync != null && localEdit != null && !localEdit.isAfter(lastSync)) {
                // last seen as already-synced, but missing on server = deleted elsewhere
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
                postgres.upsert(local); // created on this PC while offline: push up
            } catch (SQLException e) {
                System.err.println("Push local-only failed: " + local.getID());
            }
        }
    }

    private void syncBlockedBothWays() throws SQLException { // same merge rules as tasks
        Set<String> deleted = new HashSet<>(sqlite.fetchBlockedDeletedIds());

        List<BlockedTime> remoteList = postgres.fetchAllBlocked();
        for (BlockedTime remote : remoteList) {
            if (deleted.contains(remote.getId())) {
                postgres.deleteBlockedRow(remote.getId());
                continue;
            }
            BlockedTime local = sqlite.findBlockedById(remote.getId());
            BlockedTime merged = (local == null) ? remote : mergeBlocked(local, remote);
            if (deleted.contains(merged.getId())) continue;
            sqlite.upsertBlocked(merged);
        }

        Map<String, BlockedTime> remoteById = new HashMap<>();
        for (BlockedTime r : remoteList) {
            if (!deleted.contains(r.getId())) remoteById.put(r.getId(), r);
        }

        Instant lastSync = parseMetaInstant(sqlite.getMeta("last_postgres_sync_at"));

        for (BlockedTime local : sqlite.fetchAllBlocked()) {
            if (deleted.contains(local.getId())) continue;
            if (remoteById.containsKey(local.getId())) continue;

            Instant localEdit = local.getLastUpdatedAt();
            if (lastSync != null && localEdit != null && !localEdit.isAfter(lastSync)) {
                sqlite.deleteBlockedRow(local.getId());
                sqlite.recordBlockedDelete(local.getId(), Instant.now(), local.getTitle());
                try {
                    postgres.deleteBlockedRow(local.getId());
                    postgres.recordBlockedDelete(local.getId(), Instant.now(), local.getTitle());
                } catch (SQLException e) {
                    System.err.println("server blocked tombstone failed: " + local.getId());
                }
                continue;
            }

            try {
                postgres.upsertBlocked(local); // new blocked slot from this PC
            } catch (SQLException e) {
                System.err.println("Push local blocked failed: " + local.getId());
            }
        }
    }

    private void gcDeleteLogs() throws SQLException { // end of a successful online sync
        Instant cutoff = postgres.minDeleteAckAmongActive(STALE_MONTHS);
        if (cutoff == null) { // need at least two living devices that all read the delete log
            System.out.println("GC skipped (need ≥2 devices all acked, or no cutoff)");
            return;
        }
        postgres.purgeDeletesBefore(cutoff); // old task tombstones
        sqlite.purgeDeletesBefore(cutoff);
        postgres.purgeBlockedDeletesBefore(cutoff); // old blocked tombstones
        sqlite.purgeBlockedDeletesBefore(cutoff);
        System.out.println("GC sync_deletes / blocked deletes before " + cutoff);
    }

    static Task mergeTasks(Task local, Task remote) { // two copies of the same id: pick fields by timestamp
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
        if (placeCmp <= 0) { // server move is newer (or equal): take its slot
            out.setPlacedDate(remote.getPlacedDate());
            out.setPlacedTime(remote.getPlacedTime());
            out.setPlacementUpdatedAt(remote.getPlacementUpdatedAt());
        }

        int lastCmp = compareInstant(local.getLastUpdatedAt(), remote.getLastUpdatedAt());
        Task src = (lastCmp <= 0) ? remote : local; // newer title / duration / deadline / done flag
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

    static BlockedTime mergeBlocked(BlockedTime local, BlockedTime remote) { // newer blocked row wins whole
        int cmp = compareInstant(local.getLastUpdatedAt(), remote.getLastUpdatedAt());
        return (cmp <= 0) ? remote : local;
    }

    static int compareInstant(Instant a, Instant b) { // null is treated as older than a real time
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    private Instant parseMetaInstant(String s) { // read last-sync text from sync_meta
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private void ensureTimestamps(Task task) { // before any upsert so merge has something to compare
        Instant now = Instant.now();
        if (task.getLastUpdatedAt() == null) task.setLastUpdatedAt(now);
        if (task.getPlacementUpdatedAt() == null && task.getPlacedDate() != null) {
            task.setPlacementUpdatedAt(now); // first time it sat on the grid
        }
    }

    private String guessName() { // label this PC in TASK.devices
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "device";
        }
    }

    private void loadBlockedIntoMemory(TaskManagementSystem theSystem) throws SQLException { // end of dbfetchAll
        if (theSystem == null || theSystem.getBlockedTime() == null) return;
        theSystem.getBlockedTime().replaceBlockedPeriods(sqlite.fetchAllBlocked()); // grid can now paint rust cells
    }

    private void loadSqliteIntoMemory(TaskManagementSystem theSystem) throws SQLException { // end of dbfetchAll
        Set<String> deleted = sqlite.fetchDeletedIds();
        for (Task task : sqlite.fetchAll()) {
            if (deleted.contains(task.getID())) continue; // tombstoned: skip

            LocalDate placedDate = task.getPlacedDate();
            LocalTime placedTime = task.getPlacedTime();
            TaskLifeCycle lifeFromDb = task.getLifeCycle();

            if (placedDate != null && placedTime != null) { // has a slot: put it on the week grid
                theSystem.getGrid().putIfAbsent(placedDate, new Day(theSystem.getBlockedTime()));
                Day day = theSystem.getGrid().get(placedDate);
                int slot = AppTimeReference.localTimeToSlot(placedTime);
                day.placeTask(slot, task);
                theSystem.getPlacedTasks().add(task);

                TaskLifeCycle before = task.getLifeCycle();
                RefreshLifeCycle.refreshLifeCycle(task); // overdue / still valid
                if (before != task.getLifeCycle()) {
                    dbAddTask(task); // lifecycle changed, persist it
                }
            } else if (lifeFromDb == TaskLifeCycle.UNSCHEDULED) {
                theSystem.getUnscheduledTasks().add(task); // side panel leftovers
            } else {
                theSystem.getPendingTasks().add(task); // created but never placed
            }
        }
    }
}