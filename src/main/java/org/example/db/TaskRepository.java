package org.example.db;

import org.example.*;
import org.example.net.Api;
import org.example.net.Endpoints;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * If Node session exists, copy the same change through HTTP too.
 */
public class TaskRepository {

    public static final int STALE_MONTHS = 3;
    public static final int ABANDONED_MONTHS = 12;

    private final SqliteTaskRepository sqlite =
            new SqliteTaskRepository("jdbc:sqlite:scheduler-cache.db");

    private PostgresTaskRepository postgres;

    private final String deviceId = DeviceConfig.getOrCreateDeviceId();
    private boolean postgresAvailable = false;

    public TaskRepository() {
        try {
            sqlite.initialize();
            System.out.println("SQLite cache ready, deviceId=" + deviceId);
        } catch (Exception e) {
            e.printStackTrace();
        }
        rebuildPostgres();
    }

    private void rebuildPostgres() {
        if (Endpoints.isCreatorHost()) {
            postgres = null;
            postgresAvailable = false;
            System.out.println("Creator host — Java skips Postgres JDBC");
            return;
        }
        try {
            postgres = new PostgresTaskRepository(
                    Endpoints.postgresUrl(),
                    "postgres",
                    Endpoints.postgresPassword()
            );
            postgres.initialize();
            postgresAvailable = true;
            System.out.println("Postgres Connected OK " + Endpoints.postgresUrl());
        } catch (Exception e) {
            postgres = null;
            postgresAvailable = false;
            System.out.println("Postgres offline — SQLite only");
            System.out.println("Postgres reason: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void tryReconnectPostgres() {
        rebuildPostgres();
    }

    public boolean isPostgresAvailable() {
        return postgresAvailable;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String allocateNewTaskId() {
        while (true) {
            String id = UUID.randomUUID().toString();
            if (!idIsTaken(id)) return id;
        }
    }

    public boolean idIsTaken(String id) {
        if (id == null || id.isBlank()) return true;
        try {
            if (sqlite.existsTaskId(id) || sqlite.isDeletedId(id)) return true;
            if (postgresAvailable) {
                try {
                    if (postgres.existsTaskId(id) || postgres.isDeletedId(id)) return true;
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
        nodePostTask(task);
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
        nodePostTask(task);
    }

    public void dbClearTask(Task task) throws SQLException {
        if (task == null || task.getID() == null) return;

        sqlite.deleteTaskAndLog(task);
        System.out.println("dbClearTask sqlite ok id=" + task.getID());

        if (!postgresAvailable) {
            tryReconnectPostgres();
        }
        if (postgresAvailable) {
            try {
                postgres.deleteTaskAndLog(task);
                System.out.println("dbClearTask postgres ok id=" + task.getID());
            } catch (SQLException e) {
                postgresAvailable = false;
                System.err.println("dbClearTask postgres FAILED: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.err.println("dbClearTask: Postgres offline — only SQLite changed");
        }
        nodeDeleteTask(task.getID());
    }

    public void dbRestoreTask(Task task) throws SQLException {
        if (task == null || task.getID() == null) return;
        ensureTimestamps(task);

        sqlite.unrecordDelete(task.getID());
        sqlite.upsert(task);

        if (!postgresAvailable) tryReconnectPostgres();
        if (postgresAvailable) {
            try {
                postgres.unrecordDelete(task.getID());
                postgres.upsert(task);
                System.out.println("dbRestoreTask postgres ok id=" + task.getID());
            } catch (SQLException e) {
                postgresAvailable = false;
                System.err.println("dbRestoreTask postgres FAILED: " + e.getMessage());
                e.printStackTrace();
            }
        }
        nodePostTask(task);
    }

    public void dbCleanAll() throws SQLException {
        List<Task> all = sqlite.fetchAll();
        sqlite.deleteAllTasksAndLogEach();
        if (postgresAvailable) {
            try {
                postgres.deleteAllTasksAndLog();
            } catch (SQLException e) {
                postgresAvailable = false;
                e.printStackTrace();
            }
        }
        for (Task t : all) {
            nodeDeleteTask(t.getID());
        }
    }

    public void dbAddBlocked(BlockedTime blocked) throws SQLException {
        if (blocked.getLastUpdatedAt() == null) blocked.touch();
        Set<String> deleted = sqlite.fetchBlockedDeletedIds();
        if (deleted.contains(blocked.getId())) {
            System.out.println("dbAddBlocked: id tombstoned, skip " + blocked.getId());
            return;
        }
        sqlite.upsertBlocked(blocked);
        if (postgresAvailable) {
            try {
                postgres.upsertBlocked(blocked);
            } catch (SQLException e) {
                postgresAvailable = false;
                e.printStackTrace();
            }
        }
        nodePostBlocked(blocked);
    }

    public void dbClearBlocked(BlockedTime blocked) throws SQLException {
        if (blocked == null || blocked.getId() == null) return;
        sqlite.deleteBlockedAndLog(blocked);
        if (postgresAvailable) {
            try {
                postgres.deleteBlockedAndLog(blocked);
            } catch (SQLException e) {
                postgresAvailable = false;
                e.printStackTrace();
            }
        }
        nodeDeleteBlocked(blocked.getId());
    }

    public List<BlockedTime> fetchAllBlocked() throws SQLException {
        return sqlite.fetchAllBlocked();
    }

    public void dbfetchAll(TaskManagementSystem theSystem) throws SQLException {
        String uid = Api.INSTANCE.getUserId();
        if (uid == null || uid.isBlank()) {
            System.out.println("No logged-in user — skip load");
            return;
        }
        String cachedUser = sqlite.getMeta("user_id");
        if (cachedUser == null || !cachedUser.equals(uid)) {
            sqlite.wipeAllLocalData();
            sqlite.setMeta("user_id", uid);
        }

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

        if (Api.INSTANCE.getToken() != null && !Api.INSTANCE.getToken().isBlank()) {
            pullFromNode();
            System.out.println("sqlite tasks=" + sqlite.fetchAll().size()
                    + " blocked=" + sqlite.fetchAllBlocked().size());
        }

        loadBlockedIntoMemory(theSystem);
        loadSqliteIntoMemory(theSystem);
        System.out.println("memory placed=" + theSystem.getPlacedTasks().size()
                + " pending=" + theSystem.getPendingTasks().size()
                + " unscheduled=" + theSystem.getUnscheduledTasks().size()
                + " days=" + theSystem.getGrid().size());
    }

    public void reloadFromSqlite(TaskManagementSystem theSystem) throws SQLException {
        if (theSystem == null) return;
        theSystem.getPendingTasks().clear();
        theSystem.getPlacedTasks().clear();
        theSystem.getUnscheduledTasks().clear();
        theSystem.getGrid().clear();
        loadBlockedIntoMemory(theSystem);
        loadSqliteIntoMemory(theSystem);
    }

    public List<Task> fetchUnscheduledTasks() throws SQLException {
        return sqlite.fetchUnscheduled();
    }

    public boolean containsIdByDateTime(LocalDate placedDate, LocalTime placedTime) throws SQLException {
        return sqlite.containsIdByDateTime(placedDate, placedTime);
    }

    public boolean pullFromNode() {
        if (Api.INSTANCE.getToken() == null || Api.INSTANCE.getToken().isBlank()) {
            return false;
        }
        try {
            mergeNodeTasks();
            mergeNodeBlocked();
            sqlite.setMeta("last_node_sync_at", Instant.now().toString());
            System.out.println("Node pull ok");
            return true;
        } catch (Exception e) {
            System.err.println("Node pull failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void nodePostTask(Task task) {
        if (!nodeReady() || task == null) return;
        try {
            Api.INSTANCE.postTask(taskToJson(task));
        } catch (Exception e) {
            System.err.println("node POST /tasks failed: " + e.getMessage());
        }
    }

    private void nodeDeleteTask(String id) {
        if (!nodeReady() || id == null) return;
        try {
            Api.INSTANCE.deleteTask(id);
        } catch (Exception e) {
            System.err.println("node DELETE /tasks failed: " + e.getMessage());
        }
    }

    private void nodePostBlocked(BlockedTime blocked) {
        if (!nodeReady() || blocked == null) return;
        try {
            Api.INSTANCE.postBlocked(blockedToJson(blocked));
        } catch (Exception e) {
            System.err.println("node POST /blocked failed: " + e.getMessage());
        }
    }

    private void nodeDeleteBlocked(String id) {
        if (!nodeReady() || id == null) return;
        try {
            Api.INSTANCE.deleteBlocked(id);
        } catch (Exception e) {
            System.err.println("node DELETE /blocked failed: " + e.getMessage());
        }
    }

    private static boolean nodeReady() {
        return Api.INSTANCE.getToken() != null && !Api.INSTANCE.getToken().isBlank();
    }

    private static String taskToJson(Task task) {
        String state = task.getState() == 1 ? "Fixed"
                : task.getState() == 2 ? "Deadline" : "Flexible";
        String life = task.getLifeCycle() != null ? task.getLifeCycle().name() : "UNSCHEDULED";
        String flow = task.getWorkflow() != null ? task.getWorkflow().name() : "UNFINISHED";
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        field(sb, "id", task.getID(), true);
        field(sb, "title", task.getTaskName(), false);
        sb.append(",\"duration\":").append(task.getDurationHours());
        field(sb, "state", state, false);
        field(sb, "placed_date", dateStr(task.getPlacedDate()), false);
        field(sb, "placed_time", timeStr(task.getPlacedTime()), false);
        field(sb, "deadline_date", dateStr(task.getDeadLineDate()), false);
        field(sb, "deadline_time", timeStr(task.getDeadLineTime()), false);
        field(sb, "lifecycle", life, false);
        field(sb, "workflow", flow, false);
        field(sb, "placement_updated_at", instantStr(task.getPlacementUpdatedAt()), false);
        field(sb, "last_updated_at", instantStr(task.getLastUpdatedAt()), false);
        sb.append("}");
        return sb.toString();
    }

    private static String blockedToJson(BlockedTime b) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        field(sb, "id", b.getId(), true);
        field(sb, "title", b.getTitle(), false);
        if (b.getWeekday() != null) {
            sb.append(",\"weekday\":").append(toWeekdayInt(b.getWeekday()));
        } else {
            sb.append(",\"weekday\":null");
        }
        field(sb, "specific_date", dateStr(b.getSpecificDate()), false);
        field(sb, "start_time", timeStr(b.getStartTime()), false);
        field(sb, "end_time", timeStr(b.getEndTime()), false);
        field(sb, "last_updated_at", instantStr(b.getLastUpdatedAt()), false);
        sb.append(",\"is_exception\":").append(b.isException() ? "true" : "false");
        sb.append("}");
        return sb.toString();
    }

    private static void field(StringBuilder sb, String key, String value, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(key).append('"').append(':');
        if (value == null) sb.append("null");
        else sb.append('"').append(escapeJson(value)).append('"');
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String dateStr(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static String timeStr(LocalTime t) {
        return t == null ? null : t.toString();
    }

    private static String instantStr(Instant i) {
        return i == null ? null : i.toString();
    }

    private static int toWeekdayInt(DayOfWeek d) {
        return d.getValue() % 7;
    }

    private void mergeNodeTasks() throws Exception {
        Set<String> deleted = new HashSet<>(sqlite.fetchDeletedIds());
        List<Task> remoteList = new ArrayList<>();
        for (Map<String, String> row : Api.INSTANCE.getTasks()) {
            Task remote = taskFromNode(row);
            if (remote == null || remote.getID() == null) continue;
            remoteList.add(remote);
        }

        for (Task remote : remoteList) {
            if (deleted.contains(remote.getID())) {
                sqlite.unrecordDelete(remote.getID());
                deleted.remove(remote.getID());
            }
            Task local = sqlite.findById(remote.getID());
            Task merged = (local == null) ? remote : mergeTasks(local, remote);
            sqlite.upsert(merged);
        }

        Map<String, Task> remoteById = new HashMap<>();
        for (Task r : remoteList) {
            remoteById.put(r.getID(), r);
        }

        Instant lastSync = parseMetaInstant(sqlite.getMeta("last_node_sync_at"));
        for (Task local : sqlite.fetchAll()) {
            if (deleted.contains(local.getID())) continue;
            if (remoteById.containsKey(local.getID())) continue;

            Instant localEdit = local.getLastUpdatedAt();
            if (localEdit == null) localEdit = local.getPlacementUpdatedAt();
            if (lastSync != null && localEdit != null && !localEdit.isAfter(lastSync)) {
                sqlite.deleteTaskRow(local.getID());
                sqlite.recordDelete(local.getID(), Instant.now());
            }
        }
    }

    private void mergeNodeBlocked() throws Exception {
        Set<String> deleted = new HashSet<>(sqlite.fetchBlockedDeletedIds());
        List<BlockedTime> remoteList = new ArrayList<>();
        for (Map<String, String> row : Api.INSTANCE.getBlocked()) {
            BlockedTime remote = blockedFromNode(row);
            if (remote == null || remote.getId() == null) continue;
            if (remote.getStartTime() == null || remote.getEndTime() == null) {
                System.err.println("skip blocked missing time id=" + remote.getId()
                        + " start=" + remote.getStartTime()
                        + " end=" + remote.getEndTime()
                        + " keys=" + row.keySet()
                        + " start_time=" + row.get("start_time")
                        + " end_time=" + row.get("end_time"));
                continue;
            }
            remoteList.add(remote);
        }

        for (BlockedTime remote : remoteList) {
            if (deleted.contains(remote.getId())) {
                deleted.remove(remote.getId());
            }
            BlockedTime local = sqlite.findBlockedById(remote.getId());
            BlockedTime merged = (local == null) ? remote : mergeBlocked(local, remote);
            if (merged.getStartTime() == null || merged.getEndTime() == null) continue;
            sqlite.upsertBlocked(merged);
        }

        Map<String, BlockedTime> remoteById = new HashMap<>();
        for (BlockedTime r : remoteList) {
            remoteById.put(r.getId(), r);
        }

        Instant lastSync = parseMetaInstant(sqlite.getMeta("last_node_sync_at"));
        for (BlockedTime local : sqlite.fetchAllBlocked()) {
            if (deleted.contains(local.getId())) continue;
            if (remoteById.containsKey(local.getId())) continue;

            Instant localEdit = local.getLastUpdatedAt();
            if (lastSync != null && localEdit != null && !localEdit.isAfter(lastSync)) {
                sqlite.deleteBlockedRow(local.getId());
                sqlite.recordBlockedDelete(local.getId(), Instant.now(), local.getTitle());
            }
        }
    }

    private static Task taskFromNode(Map<String, String> row) {
        String id = first(row, "id");
        if (id == null) return null;

        String name = first(row, "title");
        double duration = parseDouble(first(row, "duration"), 1);
        int state = stateToInt(first(row, "state"));
        LocalDate placedDate = parseDate(first(row, "placed_date", "placedDate"));
        LocalTime placedTime = parseTime(first(row, "placed_time", "placedTime"));
        LocalDate deadlineDate = parseDate(first(row, "deadline_date", "deadlineDate"));
        LocalTime deadlineTime = parseTime(first(row, "deadline_time", "deadlineTime"));

        Task task = Task.loadTaskFromdb(
                id, name, duration, state,
                null, null,
                deadlineDate, deadlineTime,
                placedDate, placedTime
        );

        String life = first(row, "lifecycle");
        if (life != null && !life.isBlank()) {
            try {
                task.setLifeCycle(TaskLifeCycle.valueOf(life));
            } catch (Exception ignored) {
            }
        }
        String flow = first(row, "workflow");
        if (flow != null && !flow.isBlank()) {
            try {
                task.setWorkflow(Workflow.valueOf(flow));
            } catch (Exception ignored) {
            }
        }

        Instant placeAt = parseInstant(first(row, "placement_updated_at", "placementUpdatedAt"));
        Instant lastAt = parseInstant(first(row, "last_updated_at", "lastUpdatedAt"));
        if (placeAt != null) task.setPlacementUpdatedAt(placeAt);
        if (lastAt != null) task.setLastUpdatedAt(lastAt);
        else if (placeAt != null) task.setLastUpdatedAt(placeAt);
        return task;
    }

    private static BlockedTime blockedFromNode(Map<String, String> row) {
        String id = first(row, "id");
        if (id == null) return null;
        String wdRaw = first(row, "weekday");
        Integer wd = null;
        if (wdRaw != null && !wdRaw.isBlank() && !"null".equals(wdRaw)) {
            try {
                wd = Integer.parseInt(wdRaw.trim());
            } catch (Exception ignored) {
            }
        }
        Instant last = parseInstant(first(row, "last_updated_at", "lastUpdatedAt"));
        if (last == null) last = Instant.now();

        LocalTime start = parseTime(first(row, "start_time", "startTime", "start"));
        LocalTime end = parseTime(first(row, "end_time", "endTime", "end"));

        BlockedTime b = new BlockedTime(
                id,
                first(row, "title"),
                toDayOfWeek(wd),
                parseDate(first(row, "specific_date", "specificDate")),
                start,
                end,
                last,
                first(row, "user_id", "userId")
        );
        String ex = first(row, "is_exception", "isException");
        b.setException("true".equalsIgnoreCase(ex) || "t".equalsIgnoreCase(ex) || "1".equals(ex));
        return b;
    }

    private static LocalTime parseTime(String s) {
        if (s == null || s.isBlank() || "null".equalsIgnoreCase(s)) return null;
        String t = s.trim();
        int dot = t.indexOf('.');
        if (dot > 0) t = t.substring(0, dot);
        if (t.length() >= 8) t = t.substring(0, 8);
        else if (t.length() == 5) t = t + ":00";
        try {
            return LocalTime.parse(t);
        } catch (Exception e) {
            return null;
        }
    }

    private static String first(Map<String, String> row, String... keys) {
        for (String k : keys) {
            String v = row.get(k);
            if (v != null && !v.isBlank() && !"null".equals(v)) return v;
        }
        return null;
    }

    private static int stateToInt(String state) {
        if (state == null) return 3;
        if ("Fixed".equalsIgnoreCase(state) || "1".equals(state)) return 1;
        if ("Deadline".equalsIgnoreCase(state) || "2".equals(state)) return 2;
        return 3;
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null) return fallback;
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static DayOfWeek toDayOfWeek(Integer weekday) {
        if (weekday == null) return null;
        if (weekday == 0) return DayOfWeek.SUNDAY;
        try {
            return DayOfWeek.of(weekday);
        } catch (Exception e) {
            return null;
        }
    }

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
        syncBlockedDeletesBothWays();
        postgres.touchDeleteSync(deviceId);

        syncTasksBothWays();
        syncBlockedBothWays();
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
            String title = (row.length > 2 && row[2] != null && !row[2].isBlank()) ? row[2] : null;
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

    private void syncBlockedDeletesBothWays() throws SQLException {
        for (Object[] row : postgres.fetchAllBlockedDeletes()) {
            String id = (String) row[0];
            Instant when = (Instant) row[1];
            String title = (row.length > 2 && row[2] != null) ? row[2].toString() : null;
            if (title != null && title.isBlank()) title = null;
            sqlite.deleteBlockedRow(id);
            sqlite.recordBlockedDelete(id, when, title);
        }

        for (String[] row : sqlite.fetchAllBlockedDeletes()) {
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
            if (!deleted.contains(r.getID())) remoteById.put(r.getID(), r);
        }

        Instant lastSync = parseMetaInstant(sqlite.getMeta("last_postgres_sync_at"));

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

    private void syncBlockedBothWays() throws SQLException {
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
                postgres.upsertBlocked(local);
            } catch (SQLException e) {
                System.err.println("Push local blocked failed: " + local.getId());
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
        postgres.purgeBlockedDeletesBefore(cutoff);
        sqlite.purgeBlockedDeletesBefore(cutoff);
        System.out.println("GC sync_deletes / blocked deletes before " + cutoff);
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

        boolean localHas = local.getPlacedDate() != null && local.getPlacedTime() != null;
        boolean remoteHas = remote.getPlacedDate() != null && remote.getPlacedTime() != null;
        if (remoteHas && !localHas) {
            out.setPlacedDate(remote.getPlacedDate());
            out.setPlacedTime(remote.getPlacedTime());
            out.setPlacementUpdatedAt(remote.getPlacementUpdatedAt());
        } else if (localHas && remoteHas) {
            int placeCmp = compareInstant(local.getPlacementUpdatedAt(), remote.getPlacementUpdatedAt());
            if (placeCmp <= 0) {
                out.setPlacedDate(remote.getPlacedDate());
                out.setPlacedTime(remote.getPlacedTime());
                out.setPlacementUpdatedAt(remote.getPlacementUpdatedAt());
            }
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

    static BlockedTime mergeBlocked(BlockedTime local, BlockedTime remote) {
        int cmp = compareInstant(local.getLastUpdatedAt(), remote.getLastUpdatedAt());
        return (cmp <= 0) ? remote : local;
    }

    static int compareInstant(Instant a, Instant b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    private Instant parseMetaInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
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

    private void loadBlockedIntoMemory(TaskManagementSystem theSystem) throws SQLException {
        if (theSystem == null || theSystem.getBlockedTime() == null) return;
        theSystem.getBlockedTime().replaceBlockedPeriods(sqlite.fetchAllBlocked());
    }

    private void loadSqliteIntoMemory(TaskManagementSystem theSystem) throws SQLException {
        Set<String> deleted = sqlite.fetchDeletedIds();
        for (Task task : sqlite.fetchAll()) {
            if (deleted.contains(task.getID())) continue;

            LocalDate placedDate = task.getPlacedDate();
            LocalTime placedTime = task.getPlacedTime();
            TaskLifeCycle lifeFromDb = task.getLifeCycle();

            if (placedDate != null && placedTime != null) {
                LocalDate column = AppTimeReference.columnDateFor(placedDate, placedTime);
                theSystem.getGrid().putIfAbsent(column, new Day(theSystem.getBlockedTime()));
                Day day = theSystem.getGrid().get(column);
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