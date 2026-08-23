package org.example.db;

import org.example.*;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks ONLY to PostgreSQL (the shared server DB).
 * TaskRepository decides when to call this (when online / Tailscale works).
 *
 * Tables under schema TASK:
 *   tasks        – live tasks
 *   sync_deletes – tombstones (id was deleted; do not resurrect)
 *   devices      – each laptop/desktop install, for sync ack + GC
 *
 * Most methods: open connection → run SQL → close (try-with-resources).
 */
public class PostgresTaskRepository {

    /** jdbc:postgresql://host:5432/DatabaseName */
    private final String url;
    /** Postgres login name, e.g. postgres */
    private final String user;
    /** From env var — not hardcoded */
    private final String password;

    public PostgresTaskRepository(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** Open one JDBC connection to Postgres. Caller must close it (try-with-resources). */
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * Create schema/tables if missing, and add any new columns on old DBs.
     * Safe to call every startup (IF NOT EXISTS / ADD COLUMN IF NOT EXISTS).
     */
    public void initialize() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {

            st.execute("CREATE SCHEMA IF NOT EXISTS TASK");

            // Live tasks table
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS TASK.tasks (
                    id VARCHAR(36) PRIMARY KEY,
                    title VARCHAR(100),
                    duration double precision,
                    placed_date DATE,
                    placed_time TIME,
                    state VARCHAR(20) NOT NULL CHECK (state IN ('Fixed', 'Deadline', 'Flexible')),
                    deadline_date DATE,
                    deadline_time TIME,
                    lifecycle VARCHAR(20),
                    workflow VARCHAR(20),
                    placement_updated_at TIMESTAMPTZ DEFAULT NOW(),
                    last_updated_at TIMESTAMPTZ DEFAULT NOW(),
                    user_id VARCHAR(36),
                    CHECK (
                        state != 'Deadline'
                        OR (deadline_date IS NOT NULL AND deadline_time IS NOT NULL)
                    )
                )
                """);

            // Migrations for DBs created before these columns existed
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS lifecycle VARCHAR(20)");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS workflow VARCHAR(20)");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS placement_updated_at TIMESTAMPTZ DEFAULT NOW()");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS last_updated_at TIMESTAMPTZ DEFAULT NOW()");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS user_id VARCHAR(36)");
            st.executeUpdate("UPDATE TASK.tasks SET lifecycle = 'UNSCHEDULED' WHERE lifecycle IS NULL");
            st.executeUpdate("UPDATE TASK.tasks SET workflow = 'UNFINISHED' WHERE workflow IS NULL");
            st.executeUpdate("UPDATE TASK.tasks SET placement_updated_at = NOW() WHERE placement_updated_at IS NULL");
            st.executeUpdate("UPDATE TASK.tasks SET last_updated_at = COALESCE(last_updated_at, placement_updated_at, NOW()) WHERE last_updated_at IS NULL");

            // Tombstone log: "this task id was deleted"
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS TASK.sync_deletes (
                    id VARCHAR(36) PRIMARY KEY,
                    deleted_at TIMESTAMPTZ NOT NULL,
                    title VARCHAR(100),
                    user_id VARCHAR(36)
                )
                """);
            st.executeUpdate("ALTER TABLE TASK.sync_deletes ADD COLUMN IF NOT EXISTS title VARCHAR(100)");
            st.executeUpdate("ALTER TABLE TASK.sync_deletes ADD COLUMN IF NOT EXISTS user_id VARCHAR(36)");

            // One row per app install (laptop, desktop, …)
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS TASK.devices (
                    device_id VARCHAR(36) PRIMARY KEY,
                    user_id VARCHAR(36),
                    display_name TEXT,
                    platform TEXT,
                    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    last_task_sync_at TIMESTAMPTZ,
                    last_delete_sync_at TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    revoked_at TIMESTAMPTZ
                )
                """);
        }
    }

    /** Return true if this id already has a row in TASK.tasks. */
    public boolean existsTaskId(String id) throws SQLException {
        if (id == null || id.isBlank()) return false;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM TASK.tasks WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next(); // true = found a row
            }
        }
    }

    /** Return true if this id is tombstoned (was deleted). */
    public boolean isDeletedId(String id) throws SQLException {
        if (id == null || id.isBlank()) return false;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM TASK.sync_deletes WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Register or refresh this device on the server.
     * ON CONFLICT: update name/platform, bump last_seen, clear revoked_at
     * (device came back).
     */
    public void upsertDevice(String deviceId, String displayName, String platform) throws SQLException {
        String sql = """
            INSERT INTO TASK.devices (device_id, display_name, platform, last_seen_at, created_at)
            VALUES (?, ?, ?, NOW(), NOW())
            ON CONFLICT (device_id) DO UPDATE SET
                display_name = COALESCE(EXCLUDED.display_name, TASK.devices.display_name),
                platform = COALESCE(EXCLUDED.platform, TASK.devices.platform),
                last_seen_at = NOW(),
                revoked_at = NULL
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setString(2, displayName);
            ps.setString(3, platform);
            ps.executeUpdate();
        }
    }

    /** Mark: this device finished pulling/pushing delete tombstones. */
    public void touchDeleteSync(String deviceId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE TASK.devices SET last_delete_sync_at = NOW(), last_seen_at = NOW() WHERE device_id = ?")) {
            ps.setString(1, deviceId);
            ps.executeUpdate();
        }
    }

    /** Mark: this device finished pulling/pushing tasks. */
    public void touchTaskSync(String deviceId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE TASK.devices SET last_task_sync_at = NOW(), last_seen_at = NOW() WHERE device_id = ?")) {
            ps.setString(1, deviceId);
            ps.executeUpdate();
        }
    }

    /**
     * For tombstone GC: among active (not revoked, seen recently) devices,
     * if at least 2 are active and ALL of them have last_delete_sync_at set,
     * return the earliest of those ack times = safe to delete older tombstones.
     * Otherwise return null (do not GC).
     */
    public Instant minDeleteAckAmongActive(int staleAfterMonths) throws SQLException {
        String sql = """
            SELECT
              COUNT(*) FILTER (
                WHERE revoked_at IS NULL
                  AND last_seen_at > NOW() - (? || ' months')::INTERVAL
              ) AS active_cnt,
              COUNT(*) FILTER (
                WHERE revoked_at IS NULL
                  AND last_seen_at > NOW() - (? || ' months')::INTERVAL
                  AND last_delete_sync_at IS NOT NULL
              ) AS acked_cnt,
              MIN(last_delete_sync_at) FILTER (
                WHERE revoked_at IS NULL
                  AND last_seen_at > NOW() - (? || ' months')::INTERVAL
                  AND last_delete_sync_at IS NOT NULL
              ) AS min_ack
            FROM TASK.devices
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, staleAfterMonths);
            ps.setInt(2, staleAfterMonths);
            ps.setInt(3, staleAfterMonths);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int active = rs.getInt("active_cnt");
                int acked = rs.getInt("acked_cnt");
                // Need multi-device agreement before purging delete log
                if (active < 2 || acked < active) return null;
                Timestamp t = rs.getTimestamp("min_ack");
                return t != null ? t.toInstant() : null;
            }
        }
    }

    /** Soft-disable devices that have not checked in for too long. */
    public void revokeStaleDevices(int staleAfterMonths) throws SQLException {
        String sql = """
            UPDATE TASK.devices
            SET revoked_at = NOW()
            WHERE revoked_at IS NULL
              AND last_seen_at < NOW() - (? || ' months')::INTERVAL
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, staleAfterMonths);
            ps.executeUpdate();
        }
    }

    /**
     * If NO device has been active for abandonedAfterMonths, wipe all
     * server task/delete/device data. Returns true if wipe happened.
     */
    public boolean wipeIfAbandoned(int abandonedAfterMonths) throws SQLException {
        String countSql = """
            SELECT COUNT(*) FROM TASK.devices
            WHERE revoked_at IS NULL
              AND last_seen_at > NOW() - (? || ' months')::INTERVAL
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(countSql)) {
            ps.setInt(1, abandonedAfterMonths);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) return false; // someone still active
            }
        }
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM TASK.tasks");
            st.execute("DELETE FROM TASK.sync_deletes");
            st.execute("DELETE FROM TASK.devices");
        }
        return true;
    }

    /**
     * Insert task, or if id already exists, overwrite columns (upsert).
     * This is the main "save task to server" method.
     */
    public void upsert(Task task) throws SQLException {
        String sql = """
            INSERT INTO TASK.tasks (
                id, title, duration, state,
                placed_date, placed_time,
                deadline_date, deadline_time,
                lifecycle, workflow,
                placement_updated_at, last_updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                title = EXCLUDED.title,
                duration = EXCLUDED.duration,
                state = EXCLUDED.state,
                placed_date = EXCLUDED.placed_date,
                placed_time = EXCLUDED.placed_time,
                deadline_date = EXCLUDED.deadline_date,
                deadline_time = EXCLUDED.deadline_time,
                lifecycle = EXCLUDED.lifecycle,
                workflow = EXCLUDED.workflow,
                placement_updated_at = EXCLUDED.placement_updated_at,
                last_updated_at = EXCLUDED.last_updated_at
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, task); // fill the ? placeholders from Task fields
            ps.executeUpdate();
        }
    }

    /** Only change workflow + last_updated_at for one task (e.g. mark FINISHED). */
    public void updateWorkflow(Task task) throws SQLException {
        String sql = "UPDATE TASK.tasks SET workflow = ?, last_updated_at = ? WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Workflow flow = task.getWorkflow() != null ? task.getWorkflow() : Workflow.UNFINISHED;
            Instant last = task.getLastUpdatedAt() != null ? task.getLastUpdatedAt() : Instant.now();
            ps.setString(1, flow.name());
            ps.setTimestamp(2, Timestamp.from(last));
            ps.setString(3, task.getID());
            ps.executeUpdate();
        }
    }

    /** Delete the live row only (does NOT write tombstone by itself). */
    public void deleteTaskRow(String id) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM TASK.tasks WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    /**
     * Write or refresh a tombstone in sync_deletes.
     * If id already deleted, keep the later deleted_at and keep title if present.
     */
    public void recordDelete(String id, Instant deletedAt, String title) throws SQLException {
        String sql = """
            INSERT INTO TASK.sync_deletes (id, deleted_at, title) VALUES (?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                deleted_at = GREATEST(TASK.sync_deletes.deleted_at, EXCLUDED.deleted_at),
                title = COALESCE(EXCLUDED.title, TASK.sync_deletes.title)
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setTimestamp(2, Timestamp.from(deletedAt));
            if (title != null && !title.isBlank()) {
                ps.setString(3, title);
            } else {
                ps.setNull(3, Types.VARCHAR);
            }
            ps.executeUpdate();
        }
    }

    /** Same as recordDelete but without a title. */
    public void recordDelete(String id, Instant deletedAt) throws SQLException {
        recordDelete(id, deletedAt, null);
    }

    /** Full clear of one task on server: remove row + add tombstone with name. */
    public void deleteTaskAndLog(Task task) throws SQLException {
        Instant when = Instant.now();
        deleteTaskRow(task.getID());
        recordDelete(task.getID(), when, task.getTaskName());
    }

    /**
     * Clear ALL tasks on server: for each id, delete row and write tombstone.
     * Used by "reset all" style operations.
     */
    public void deleteAllTasksAndLog() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, title FROM TASK.tasks")) {
            List<String[]> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new String[]{rs.getString(1), rs.getString(2)});
            }
            Instant when = Instant.now();
            for (String[] row : rows) {
                deleteTaskRow(row[0]);
                recordDelete(row[0], when, row[1]);
            }
        }
    }

    /**
     * Load every tombstone from server.
     * Each element: [0]=id String, [1]=deletedAt Instant, [2]=title String or null
     */
    public List<Object[]> fetchAllDeletes() throws SQLException {
        List<Object[]> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, deleted_at, title FROM TASK.sync_deletes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Object[]{
                        rs.getString(1),
                        rs.getTimestamp(2).toInstant(),
                        rs.getString(3)
                });
            }
        }
        return list;
    }

    /**
     * GC helper: permanently remove tombstones older than cutoff.
     * Only call when minDeleteAckAmongActive said it is safe.
     */
    public void purgeDeletesBefore(Instant cutoff) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM TASK.sync_deletes WHERE deleted_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            ps.executeUpdate();
        }
    }

    /** Load every live task from server into Task objects. */
    public List<Task> fetchAll() throws SQLException {
        List<Task> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM TASK.tasks");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapRow(rs));
        }
        return list;
    }

    /** Put Task fields into the 12 ? placeholders of the upsert INSERT. */
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
        if (task.getPlacedDate() != null) ps.setDate(5, Date.valueOf(task.getPlacedDate()));
        else ps.setNull(5, Types.DATE);
        if (task.getPlacedTime() != null) ps.setTime(6, Time.valueOf(task.getPlacedTime()));
        else ps.setNull(6, Types.TIME);
        // Deadline columns only meaningful for Deadline tasks
        if (task.getState() == 2 && task.getDeadLineDate() != null) {
            ps.setDate(7, Date.valueOf(task.getDeadLineDate()));
            if (task.getDeadLineTime() != null) ps.setTime(8, Time.valueOf(task.getDeadLineTime()));
            else ps.setNull(8, Types.TIME);
        } else {
            ps.setNull(7, Types.DATE);
            ps.setNull(8, Types.TIME);
        }
        ps.setString(9, life.name());
        ps.setString(10, flow.name());
        ps.setTimestamp(11, Timestamp.from(placement));
        ps.setTimestamp(12, Timestamp.from(last));
    }

    /** Turn one SQL ResultSet row into a Task (load path — keeps DB id). */
    private Task mapRow(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("title");
        double duration = rs.getDouble("duration");
        String state = rs.getString("state");
        int numberState = "Fixed".equals(state) ? 1 : "Deadline".equals(state) ? 2 : 3;

        Date sqlPlacedDate = rs.getDate("placed_date");
        Time sqlPlacedTime = rs.getTime("placed_time");
        LocalDate placedDate = sqlPlacedDate != null ? sqlPlacedDate.toLocalDate() : null;
        LocalTime placedTime = sqlPlacedTime != null ? sqlPlacedTime.toLocalTime() : null;

        Date sqlDeadlineDate = rs.getDate("deadline_date");
        Time sqlDeadlineTime = rs.getTime("deadline_time");
        LocalDate deadlineDate = sqlDeadlineDate != null ? sqlDeadlineDate.toLocalDate() : null;
        LocalTime deadlineTime = sqlDeadlineTime != null ? sqlDeadlineTime.toLocalTime() : null;

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
        Timestamp p = rs.getTimestamp("placement_updated_at");
        if (p != null) task.setPlacementUpdatedAt(p.toInstant());
        Timestamp l = rs.getTimestamp("last_updated_at");
        if (l != null) task.setLastUpdatedAt(l.toInstant());
        else if (p != null) task.setLastUpdatedAt(p.toInstant());
        return task;
    }
}