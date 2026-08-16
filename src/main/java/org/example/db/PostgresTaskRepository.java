package org.example.db;

import org.example.*;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks only to PostgreSQL (the shared "cloud" DB on your home PC).
 * Does NOT talk to SQLite. TaskRepository calls this when online.
 *
 * Schema under TASK:
 *   tasks        – real calendar tasks
 *   sync_deletes – ids that were deleted (so other devices can remove them too)
 *   devices      – each laptop/desktop install that has synced
 */
public class PostgresTaskRepository {

    /** JDBC URL, e.g. jdbc:postgresql://100.x.x.x:5432/Scheduler */
    private final String url;
    /** DB login name, usually "postgres" */
    private final String user;
    /** DB password (from env var, not hard-coded in source) */
    private final String password;

    /**
     * Stores how to connect. Does not open a connection yet.
     */
    public PostgresTaskRepository(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /**
     * Opens a new connection to Postgres.
     * Caller should close it (try-with-resources).
     * Throws if Postgres is down, wrong password, or network/Tailscale issue.
     */
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * Creates schema/tables if missing, and adds any new columns on old DBs.
     * Safe to run every startup (IF NOT EXISTS / ADD COLUMN IF NOT EXISTS).
     */
    public void initialize() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {

            // Namespace so tables are TASK.tasks, not public.tasks
            st.execute("CREATE SCHEMA IF NOT EXISTS TASK");

            // Main task table: one row per task (UUID id)
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

            // Upgrade path if table already existed from an older app version
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS lifecycle VARCHAR(20)");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS workflow VARCHAR(20)");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS placement_updated_at TIMESTAMPTZ DEFAULT NOW()");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS last_updated_at TIMESTAMPTZ DEFAULT NOW()");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS user_id VARCHAR(36)");
            st.executeUpdate("UPDATE TASK.tasks SET lifecycle = 'UNSCHEDULED' WHERE lifecycle IS NULL");
            st.executeUpdate("UPDATE TASK.tasks SET workflow = 'UNFINISHED' WHERE workflow IS NULL");
            st.executeUpdate("UPDATE TASK.tasks SET placement_updated_at = NOW() WHERE placement_updated_at IS NULL");
            st.executeUpdate("UPDATE TASK.tasks SET last_updated_at = COALESCE(last_updated_at, placement_updated_at, NOW()) WHERE last_updated_at IS NULL");

            // Delete log: "task id X was deleted at time T" for multi-device sync
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS TASK.sync_deletes (
                    id VARCHAR(36) PRIMARY KEY,
                    deleted_at TIMESTAMPTZ NOT NULL,
                    user_id VARCHAR(36)
                )
                """);

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

    /**
     * Registers this machine in TASK.devices, or refreshes last_seen_at if it already exists.
     * Clearing revoked_at means "this device is active again."
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

    /**
     * After delete-log sync finishes, mark this device's last_delete_sync_at = now.
     * Used later so GC knows "this device has applied deletes up to at least this time."
     */
    public void touchDeleteSync(String deviceId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE TASK.devices SET last_delete_sync_at = NOW(), last_seen_at = NOW() WHERE device_id = ?")) {
            ps.setString(1, deviceId);
            ps.executeUpdate();
        }
    }

    /**
     * After task upsert/merge sync finishes, mark last_task_sync_at = now.
     */
    public void touchTaskSync(String deviceId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE TASK.devices SET last_task_sync_at = NOW(), last_seen_at = NOW() WHERE device_id = ?")) {
            ps.setString(1, deviceId);
            ps.executeUpdate();
        }
    }

    /**
     * Among devices that still look "active" (seen recently, not revoked),
     * return the earliest last_delete_sync_at.
     * Delete-log rows older than that are safe to purge (every active device already saw them).
     * Returns null if no device has an ack yet.
     */
    /**
     * Safe GC cutoff: only when EVERY active device has a non-null last_delete_sync_at.
     * Then returns MIN(ack). If any active device never synced deletes, returns null (no purge).
     */
    /**
     * Purge only when ≥2 active devices AND every active device has acked deletes.
     * Single-device → null → no GC (tombstones stay for the other machine).
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
                // B: need 2+ devices; all of them must have acked
                if (active < 2 || acked < active) return null;
                Timestamp t = rs.getTimestamp("min_ack");
                return t != null ? t.toInstant() : null;
            }
        }
    }
    /**
     * Soft-disable devices that have not checked in for staleAfterMonths.
     * Does NOT delete calendar tasks — only marks the device so it no longer blocks GC.
     */
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
     * If NO device has been active for abandonedAfterMonths, treat account as abandoned:
     * delete all tasks, delete logs, and devices from Postgres.
     * @return true if a wipe happened, false if at least one active device exists
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
                int active = rs.getInt(1);
                if (active > 0) return false;
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
     * Insert task or update if id already exists (UPSERT).
     * This is the normal "save task to server" path.
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
            bind(ps, task);
            ps.executeUpdate();
        }
    }

    /**
     * Only updates workflow (FINISHED / UNFINISHED) and last_updated_at.
     * Cheaper than full upsert when the user toggles "done".
     */
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

    /**
     * Removes the task row only. Does NOT write sync_deletes.
     * Prefer deleteTaskAndLog when the user intentionally deleted a task.
     */
    public void deleteTaskRow(String id) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM TASK.tasks WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    /**
     * Writes (or updates) one delete-log entry: "this id is deleted as of deletedAt".
     * Other devices read this and remove the same id locally.
     */
    public void recordDelete(String id, Instant deletedAt) throws SQLException {
        String sql = """
            INSERT INTO TASK.sync_deletes (id, deleted_at) VALUES (?, ?)
            ON CONFLICT (id) DO UPDATE SET
                deleted_at = GREATEST(TASK.sync_deletes.deleted_at, EXCLUDED.deleted_at)
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setTimestamp(2, Timestamp.from(deletedAt));
            ps.executeUpdate();
        }
    }

    /**
     * Full user delete on server: remove task row AND log the id in sync_deletes.
     */
    public void deleteTaskAndLog(Task task) throws SQLException {
        Instant when = Instant.now();
        deleteTaskRow(task.getID());
        recordDelete(task.getID(), when);
    }

    /**
     * Clears every task on the server and logs each id as deleted
     * so other devices can drop them too.
     */
    public void deleteAllTasksAndLog() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM TASK.tasks")) {
            List<String> ids = new ArrayList<>();
            while (rs.next()) ids.add(rs.getString(1));
            Instant when = Instant.now();
            for (String id : ids) {
                deleteTaskRow(id);
                recordDelete(id, when);
            }
        }
    }

    /**
     * Reads all delete-log rows.
     * Each element is Object[]{ id (String), deletedAt (Instant) }.
     */
    public List<Object[]> fetchAllDeletes() throws SQLException {
        List<Object[]> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT id, deleted_at FROM TASK.sync_deletes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Object[]{rs.getString(1), rs.getTimestamp(2).toInstant()});
            }
        }
        return list;
    }

    /**
     * Garbage-collect old delete-log rows older than cutoff.
     * Call only after computing cutoff from minDeleteAckAmongActive.
     */
    public void purgeDeletesBefore(Instant cutoff) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM TASK.sync_deletes WHERE deleted_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            ps.executeUpdate();
        }
    }

    /**
     * Loads every task row into Java Task objects (for merge/sync).
     */
    public List<Task> fetchAll() throws SQLException {
        List<Task> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM TASK.tasks");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapRow(rs));
        }
        return list;
    }

    /**
     * Fills a PreparedStatement's ? placeholders from a Task (insert/upsert).
     * Private helper — not called from outside this class.
     */
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

    /**
     * Turns one SQL ResultSet row into a Task object.
     * Private helper used by fetchAll.
     */
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