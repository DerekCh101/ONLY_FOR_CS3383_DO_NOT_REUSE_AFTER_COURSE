package org.example.db;

import org.example.*;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Local offline cache only (file scheduler-cache.db via jdbc:sqlite:...).
 * Always available even when Postgres/Tailscale is down.
 *
 * Tables (no schema name — SQLite is one file):
 *   tasks         – live tasks on this device
 *   sync_deletes  – tombstones (id was deleted; do not bring it back)
 *   sync_meta     – small key/value store (e.g. last_postgres_sync_at)
 *
 * Dates/times are stored as TEXT (ISO strings), not SQL DATE/TIME types.
 */
public class SqliteTaskRepository {

    /** Example: jdbc:sqlite:scheduler-cache.db */
    private final String url;

    public SqliteTaskRepository(String url) {
        this.url = url;
    }

    /** Open connection to the local .db file. Caller closes it (try-with-resources). */
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    /**
     * Create tables if missing; try ALTER for columns added in newer versions.
     * SQLite has no IF NOT EXISTS for columns — empty catch = column already there.
     */
    public void initialize() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT PRIMARY KEY,
                    title TEXT,
                    duration REAL,
                    placed_date TEXT,
                    placed_time TEXT,
                    state TEXT NOT NULL,
                    deadline_date TEXT,
                    deadline_time TEXT,
                    lifecycle TEXT,
                    workflow TEXT,
                    placement_updated_at TEXT,
                    last_updated_at TEXT
                )
                """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sync_deletes (
                    id TEXT PRIMARY KEY,
                    deleted_at TEXT NOT NULL,
                    title TEXT
                )
                """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sync_meta (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
                """);
            // Old cache files may lack these columns
            try { st.executeUpdate("ALTER TABLE tasks ADD COLUMN last_updated_at TEXT"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE tasks ADD COLUMN placement_updated_at TEXT"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE sync_deletes ADD COLUMN title TEXT"); } catch (SQLException ignored) {}
        }
    }

    /** True if this id already exists as a live task row in local tasks. */
    public boolean existsTaskId(String id) throws SQLException {
        if (id == null || id.isBlank()) return false;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM tasks WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next(); // found a row
            }
        }
    }

    /** True if this id is in the local delete log (tombstone). */
    public boolean isDeletedId(String id) throws SQLException {
        if (id == null || id.isBlank()) return false;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM sync_deletes WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Nuclear local reset: empty tasks + tombstones + forget last sync times.
     * Used when server says account abandoned or local install is too stale.
     */
    public void wipeAllLocalData() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM tasks");
            st.execute("DELETE FROM sync_deletes");
            st.execute("DELETE FROM sync_meta WHERE key IN ('last_postgres_sync_at','last_delete_sync_at')");
        }
    }

    /** Save a string setting (e.g. key=last_postgres_sync_at, value=ISO time). */
    public void setMeta(String key, String value) throws SQLException {
        String sql = """
            INSERT INTO sync_meta(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    /** Read a string setting; null if key never stored. */
    public String getMeta(String key) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT value FROM sync_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
                return null;
            }
        }
    }

    /**
     * Insert task, or if id exists, overwrite all mapped columns.
     * Main local "save this task" method.
     */
    public void upsert(Task task) throws SQLException {
        String sql = """
            INSERT INTO tasks (
                id, title, duration, state,
                placed_date, placed_time,
                deadline_date, deadline_time,
                lifecycle, workflow,
                placement_updated_at, last_updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                duration = excluded.duration,
                state = excluded.state,
                placed_date = excluded.placed_date,
                placed_time = excluded.placed_time,
                deadline_date = excluded.deadline_date,
                deadline_time = excluded.deadline_time,
                lifecycle = excluded.lifecycle,
                workflow = excluded.workflow,
                placement_updated_at = excluded.placement_updated_at,
                last_updated_at = excluded.last_updated_at
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, task);
            ps.executeUpdate();
        }
    }

    /** Only update workflow + last_updated_at (e.g. user marked FINISHED). */
    public void updateWorkflow(Task task) throws SQLException {
        String sql = "UPDATE tasks SET workflow = ?, last_updated_at = ? WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Workflow flow = task.getWorkflow() != null ? task.getWorkflow() : Workflow.UNFINISHED;
            Instant last = task.getLastUpdatedAt() != null ? task.getLastUpdatedAt() : Instant.now();
            ps.setString(1, flow.name());
            ps.setString(2, last.toString()); // Instant stored as text
            ps.setString(3, task.getID());
            ps.executeUpdate();
        }
    }

    /** Remove live row only — does not write a tombstone by itself. */
    public void deleteTaskRow(String taskId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM tasks WHERE id = ?")) {
            ps.setString(1, taskId);
            ps.executeUpdate();
        }
    }

    /**
     * Write/refresh tombstone in sync_deletes.
     * On conflict: keep the later deleted_at; keep title if one side has it.
     */
    public void recordDelete(String taskId, Instant deletedAt, String title) throws SQLException {
        String sql = """
            INSERT INTO sync_deletes(id, deleted_at, title) VALUES(?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                deleted_at = CASE
                    WHEN excluded.deleted_at > sync_deletes.deleted_at THEN excluded.deleted_at
                    ELSE sync_deletes.deleted_at
                END,
                title = COALESCE(excluded.title, sync_deletes.title)
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setString(2, deletedAt.toString());
            ps.setString(3, (title != null && !title.isBlank()) ? title : null);
            ps.executeUpdate();
        }
    }

    /** Tombstone without a title. */
    public void recordDelete(String taskId, Instant deletedAt) throws SQLException {
        recordDelete(taskId, deletedAt, null);
    }

    /** Full local clear of one task: delete row + tombstone with name. */
    public void deleteTaskAndLog(Task task) throws SQLException {
        Instant when = Instant.now();
        String title = task.getTaskName();
        deleteTaskRow(task.getID());
        recordDelete(task.getID(), when, title);
    }

    /**
     * Clear every local task: each id gets a tombstone so sync can tell
     * the server (and other devices) they are gone.
     */
    public void deleteAllTasksAndLogEach() throws SQLException {
        List<Task> all = fetchAll();
        Instant when = Instant.now();
        for (Task t : all) {
            deleteTaskRow(t.getID());
            recordDelete(t.getID(), when, t.getTaskName());
        }
    }

    /**
     * All local tombstones.
     * Each entry: [0]=id, [1]=deletedAt ISO string, [2]=title or "".
     */
    public List<String[]> fetchAllDeletes() throws SQLException {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, deleted_at, title FROM sync_deletes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String title = rs.getString(3);
                list.add(new String[]{
                        rs.getString(1),
                        rs.getString(2),
                        title != null ? title : ""
                });
            }
        }
        return list;
    }

    /** Just the set of deleted ids (fast membership checks during sync/load). */
    public Set<String> fetchDeletedIds() throws SQLException {
        Set<String> set = new HashSet<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM sync_deletes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) set.add(rs.getString(1));
        }
        return set;
    }

    /**
     * GC: permanently drop tombstones older than cutoff.
     * Call only when TaskRepository decided multi-device ack allows it.
     */
    public void purgeDeletesBefore(Instant cutoff) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM sync_deletes WHERE deleted_at < ?")) {
            ps.setString(1, cutoff.toString());
            ps.executeUpdate();
        }
    }

    /** Load every live local task into Task objects. */
    public List<Task> fetchAll() throws SQLException {
        List<Task> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM tasks");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapRow(rs));
        }
        return list;
    }

    /** One task by id, or null if not in local tasks. */
    public Task findById(String id) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM tasks WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
                return null;
            }
        }
    }

    /**
     * Tasks that are unscheduled in lifecycle OR have no placed_date
     * (side panel / recovery lists).
     */
    public List<Task> fetchUnscheduled() throws SQLException {
        String sql = """
            SELECT * FROM tasks
            WHERE lifecycle = 'UNSCHEDULED' OR placed_date IS NULL
            ORDER BY title
            """;
        List<Task> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapRow(rs));
        }
        return list;
    }

    /**
     * True if some local task already sits at this date+time
     * (simple occupancy check; not full duration overlap).
     */
    public boolean containsIdByDateTime(LocalDate placedDate, LocalTime placedTime) throws SQLException {
        String sql = "SELECT 1 FROM tasks WHERE placed_date = ? AND placed_time = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, placedDate.toString());
            ps.setString(2, placedTime.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Fill the 12 ? placeholders of upsert from a Task. */
    private void bind(PreparedStatement ps, Task task) throws SQLException {
        String state = task.getState() == 1 ? "Fixed"
                : task.getState() == 2 ? "Deadline" : "Flexible";
        TaskLifeCycle life = task.getLifeCycle() != null ? task.getLifeCycle() : TaskLifeCycle.UNSCHEDULED;
        Workflow flow = task.getWorkflow() != null ? task.getWorkflow() : Workflow.UNFINISHED;
        Instant placement = task.getPlacementUpdatedAt() != null ? task.getPlacementUpdatedAt() : Instant.now();
        Instant last = task.getLastUpdatedAt() != null ? task.getLastUpdatedAt() : Instant.now();

        ps.setString(1, task.getID());
        ps.setString(2, task.getTaskName());
        ps.setDouble(3, task.getDurationHours());
        ps.setString(4, state);
        ps.setString(5, task.getPlacedDate() != null ? task.getPlacedDate().toString() : null);
        ps.setString(6, task.getPlacedTime() != null ? task.getPlacedTime().toString() : null);
        if (task.getState() == 2 && task.getDeadLineDate() != null) {
            ps.setString(7, task.getDeadLineDate().toString());
            ps.setString(8, task.getDeadLineTime() != null ? task.getDeadLineTime().toString() : null);
        } else {
            ps.setString(7, null);
            ps.setString(8, null);
        }
        ps.setString(9, life.name());
        ps.setString(10, flow.name());
        ps.setString(11, placement.toString());
        ps.setString(12, last.toString());
    }

    /** One SQL row → Task (keeps id from DB; does not allocate a new UUID). */
    private Task mapRow(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("title");
        double duration = rs.getDouble("duration");
        String state = rs.getString("state");
        int numberState = "Fixed".equals(state) ? 1 : "Deadline".equals(state) ? 2 : 3;

        LocalDate placedDate = parseDate(rs.getString("placed_date"));
        LocalTime placedTime = parseTime(rs.getString("placed_time"));
        LocalDate deadlineDate = parseDate(rs.getString("deadline_date"));
        LocalTime deadlineTime = parseTime(rs.getString("deadline_time"));

        String lifeStr = rs.getString("lifecycle");
        String flowStr = rs.getString("workflow");
        TaskLifeCycle life = (lifeStr == null || lifeStr.isBlank())
                ? TaskLifeCycle.UNSCHEDULED : TaskLifeCycle.valueOf(lifeStr);
        Workflow flow = (flowStr == null || flowStr.isBlank())
                ? Workflow.UNFINISHED : Workflow.valueOf(flowStr);

        Task task = Task.loadTaskFromdb(
                id, name, duration, numberState,
                null, null,
                deadlineDate, deadlineTime,
                placedDate, placedTime
        );
        task.setLifeCycle(life);
        task.setWorkflow(flow);

        Instant p = parseInstant(rs.getString("placement_updated_at"));
        Instant l = parseInstant(rs.getString("last_updated_at"));
        if (p != null) task.setPlacementUpdatedAt(p);
        if (l != null) task.setLastUpdatedAt(l);
        else if (p != null) task.setLastUpdatedAt(p);
        return task;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        return LocalDate.parse(s);
    }

    private static LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        return LocalTime.parse(s);
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        return Instant.parse(s);
    }
}