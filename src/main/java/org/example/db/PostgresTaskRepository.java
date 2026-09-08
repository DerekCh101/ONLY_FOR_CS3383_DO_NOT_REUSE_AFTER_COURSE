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
 * PostgreSQL only. TaskRepository calls this when online.
 *
 * TASK.tasks / sync_deletes / devices / blocked_times / sync_deletes_blocked
 */
public class PostgresTaskRepository {

    private final String url;
    private final String user;
    private final String password;

    public PostgresTaskRepository(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public void initialize() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {

            st.execute("CREATE SCHEMA IF NOT EXISTS TASK");

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

            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS lifecycle VARCHAR(20)");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS workflow VARCHAR(20)");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS placement_updated_at TIMESTAMPTZ DEFAULT NOW()");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS last_updated_at TIMESTAMPTZ DEFAULT NOW()");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS user_id VARCHAR(36)");
            st.executeUpdate("UPDATE TASK.tasks SET lifecycle = 'UNSCHEDULED' WHERE lifecycle IS NULL");
            st.executeUpdate("UPDATE TASK.tasks SET workflow = 'UNFINISHED' WHERE workflow IS NULL");
            st.executeUpdate("UPDATE TASK.tasks SET placement_updated_at = NOW() WHERE placement_updated_at IS NULL");
            st.executeUpdate("UPDATE TASK.tasks SET last_updated_at = COALESCE(last_updated_at, placement_updated_at, NOW()) WHERE last_updated_at IS NULL");

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

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS TASK.blocked_times (
                    id VARCHAR(36) PRIMARY KEY,
                    title VARCHAR(100),
                    weekday SMALLINT,
                    specific_date DATE,
                    start_time TIME NOT NULL,
                    end_time TIME NOT NULL,
                    last_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    user_id VARCHAR(36),
                    is_exception BOOLEAN NOT NULL DEFAULT false,
                    CHECK (weekday IS NULL OR weekday BETWEEN 0 AND 6),
                    CHECK (
                        (weekday IS NULL AND specific_date IS NULL)
                        OR (weekday IS NOT NULL AND specific_date IS NULL)
                        OR (weekday IS NULL AND specific_date IS NOT NULL)
                    )
                )
                """);
            st.executeUpdate("""
                ALTER TABLE TASK.blocked_times
                ADD COLUMN IF NOT EXISTS is_exception BOOLEAN NOT NULL DEFAULT false
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS TASK.sync_deletes_blocked (
                    id VARCHAR(36) PRIMARY KEY,
                    deleted_at TIMESTAMPTZ NOT NULL,
                    title VARCHAR(100)
                )
                """);
        }
    }

    public boolean existsTaskId(String id) throws SQLException {
        if (id == null || id.isBlank()) return false;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM TASK.tasks WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

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

    public void upsertDevice(String deviceId, String displayName, String platform) throws SQLException {
        String uid = currentUserId();
        String sql = """
            INSERT INTO TASK.devices (device_id, user_id, display_name, platform, last_seen_at, created_at)
            VALUES (?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (device_id) DO UPDATE SET
                user_id = COALESCE(EXCLUDED.user_id, TASK.devices.user_id),
                display_name = COALESCE(EXCLUDED.display_name, TASK.devices.display_name),
                platform = COALESCE(EXCLUDED.platform, TASK.devices.platform),
                last_seen_at = NOW(),
                revoked_at = NULL
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setString(2, uid);
            ps.setString(3, displayName);
            ps.setString(4, platform);
            ps.executeUpdate();
        }
    }

    public void touchDeleteSync(String deviceId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE TASK.devices SET last_delete_sync_at = NOW(), last_seen_at = NOW() WHERE device_id = ?")) {
            ps.setString(1, deviceId);
            ps.executeUpdate();
        }
    }

    public void touchTaskSync(String deviceId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE TASK.devices SET last_task_sync_at = NOW(), last_seen_at = NOW() WHERE device_id = ?")) {
            ps.setString(1, deviceId);
            ps.executeUpdate();
        }
    }

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
                if (active < 2 || acked < active) return null;
                Timestamp t = rs.getTimestamp("min_ack");
                return t != null ? t.toInstant() : null;
            }
        }
    }

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
                if (rs.getInt(1) > 0) return false;
            }
        }
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM TASK.tasks");
            st.execute("DELETE FROM TASK.sync_deletes");
            st.execute("DELETE FROM TASK.blocked_times");
            st.execute("DELETE FROM TASK.sync_deletes_blocked");
            st.execute("DELETE FROM TASK.devices");
        }
        return true;
    }

    public void upsert(Task task) throws SQLException {
        String sql = """
            INSERT INTO TASK.tasks (
                id, title, duration, state,
                placed_date, placed_time,
                deadline_date, deadline_time,
                lifecycle, workflow,
                placement_updated_at, last_updated_at,
                user_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                last_updated_at = EXCLUDED.last_updated_at,
                user_id = EXCLUDED.user_id
            WHERE TASK.tasks.user_id IS NULL
               OR TASK.tasks.user_id = EXCLUDED.user_id
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, task);
            ps.executeUpdate();
        }
    }

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

    public void deleteTaskRow(String id) throws SQLException {
        String uid = currentUserId();
        String sql = (uid == null || uid.isBlank())
                ? "DELETE FROM TASK.tasks WHERE id = ?"
                : "DELETE FROM TASK.tasks WHERE id = ? AND (user_id = ? OR user_id IS NULL)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            if (uid != null && !uid.isBlank()) ps.setString(2, uid);
            int n = ps.executeUpdate();
            System.out.println("postgres deleteTaskRow id=" + id + " rows=" + n);
        }
    }

    public void unrecordDelete(String id) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM TASK.sync_deletes WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    public void recordDelete(String id, Instant deletedAt, String title) throws SQLException {
        String sql = """
            INSERT INTO TASK.sync_deletes (id, deleted_at, title, user_id) VALUES (?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                deleted_at = GREATEST(TASK.sync_deletes.deleted_at, EXCLUDED.deleted_at),
                title = COALESCE(EXCLUDED.title, TASK.sync_deletes.title),
                user_id = COALESCE(EXCLUDED.user_id, TASK.sync_deletes.user_id)
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setTimestamp(2, Timestamp.from(deletedAt));
            if (title != null && !title.isBlank()) ps.setString(3, title);
            else ps.setNull(3, Types.VARCHAR);
            String uid = currentUserId();
            if (uid != null && !uid.isBlank()) ps.setString(4, uid);
            else ps.setNull(4, Types.VARCHAR);
            ps.executeUpdate();
        }
    }

    public void recordDelete(String id, Instant deletedAt) throws SQLException {
        recordDelete(id, deletedAt, null);
    }

    public void deleteTaskAndLog(Task task) throws SQLException {
        Instant when = Instant.now();
        deleteTaskRow(task.getID());
        recordDelete(task.getID(), when, task.getTaskName());
    }

    public void deleteAllTasksAndLog() throws SQLException {
        String uid = currentUserId();
        if (uid == null || uid.isBlank()) return;
        List<String[]> rows = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, title FROM TASK.tasks WHERE user_id = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        }
        Instant when = Instant.now();
        for (String[] row : rows) {
            deleteTaskRow(row[0]);
            recordDelete(row[0], when, row[1]);
        }
    }

    public List<Object[]> fetchAllDeletes() throws SQLException {
        String uid = currentUserId();
        List<Object[]> list = new ArrayList<>();
        String sql = (uid == null || uid.isBlank())
                ? "SELECT id, deleted_at, title FROM TASK.sync_deletes WHERE false"
                : "SELECT id, deleted_at, title FROM TASK.sync_deletes WHERE user_id = ? OR user_id IS NULL";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (uid != null && !uid.isBlank()) ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Object[]{
                            rs.getString(1),
                            rs.getTimestamp(2).toInstant(),
                            rs.getString(3)
                    });
                }
            }
        }
        return list;
    }

    public void purgeDeletesBefore(Instant cutoff) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM TASK.sync_deletes WHERE deleted_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            ps.executeUpdate();
        }
    }

    public List<Task> fetchAll() throws SQLException {
        String uid = currentUserId();
        List<Task> list = new ArrayList<>();
        String sql = (uid == null || uid.isBlank())
                ? "SELECT * FROM TASK.tasks WHERE false"
                : "SELECT * FROM TASK.tasks WHERE user_id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (uid != null && !uid.isBlank()) ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        }
        return list;
    }

    public void upsertBlocked(BlockedTime b) throws SQLException {
        String sql = """
            INSERT INTO TASK.blocked_times (
                id, title, weekday, specific_date, start_time, end_time,
                last_updated_at, user_id, is_exception
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                title = EXCLUDED.title,
                weekday = EXCLUDED.weekday,
                specific_date = EXCLUDED.specific_date,
                start_time = EXCLUDED.start_time,
                end_time = EXCLUDED.end_time,
                last_updated_at = EXCLUDED.last_updated_at,
                user_id = EXCLUDED.user_id,
                is_exception = EXCLUDED.is_exception
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindBlocked(ps, b);
            ps.executeUpdate();
        }
    }

    public List<BlockedTime> fetchAllBlocked() throws SQLException {
        String uid = currentUserId();
        List<BlockedTime> list = new ArrayList<>();
        String sql = (uid == null || uid.isBlank())
                ? "SELECT * FROM TASK.blocked_times WHERE false"
                : "SELECT * FROM TASK.blocked_times WHERE user_id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (uid != null && !uid.isBlank()) ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapBlocked(rs));
            }
        }
        return list;
    }

    public void deleteBlockedRow(String id) throws SQLException {
        String uid = currentUserId();
        String sql = (uid == null || uid.isBlank())
                ? "DELETE FROM TASK.blocked_times WHERE id = ? AND false"
                : "DELETE FROM TASK.blocked_times WHERE id = ? AND user_id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            if (uid != null && !uid.isBlank()) ps.setString(2, uid);
            ps.executeUpdate();
        }
    }

    public void recordBlockedDelete(String id, Instant deletedAt, String title) throws SQLException {
        String sql = """
            INSERT INTO TASK.sync_deletes_blocked (id, deleted_at, title) VALUES (?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                deleted_at = GREATEST(TASK.sync_deletes_blocked.deleted_at, EXCLUDED.deleted_at),
                title = COALESCE(EXCLUDED.title, TASK.sync_deletes_blocked.title)
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setTimestamp(2, Timestamp.from(deletedAt));
            if (title != null && !title.isBlank()) ps.setString(3, title);
            else ps.setNull(3, Types.VARCHAR);
            ps.executeUpdate();
        }
    }

    public void deleteBlockedAndLog(BlockedTime b) throws SQLException {
        Instant when = Instant.now();
        deleteBlockedRow(b.getId());
        recordBlockedDelete(b.getId(), when, b.getTitle());
    }

    public List<Object[]> fetchAllBlockedDeletes() throws SQLException {
        List<Object[]> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, deleted_at, title FROM TASK.sync_deletes_blocked");
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

    public Set<String> fetchBlockedDeletedIds() throws SQLException {
        Set<String> set = new HashSet<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM TASK.sync_deletes_blocked");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) set.add(rs.getString(1));
        }
        return set;
    }

    public void purgeBlockedDeletesBefore(Instant cutoff) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM TASK.sync_deletes_blocked WHERE deleted_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            ps.executeUpdate();
        }
    }

    private static String currentUserId() {
        try {
            return org.example.net.Api.INSTANCE.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private void bind(PreparedStatement ps, Task task) throws SQLException {
        String state = task.getState() == 1 ? "Fixed"
                : task.getState() == 2 ? "Deadline" : "Flexible";
        TaskLifeCycle life = task.getLifeCycle() != null ? task.getLifeCycle() : TaskLifeCycle.UNSCHEDULED;
        Workflow flow = task.getWorkflow() != null ? task.getWorkflow() : Workflow.UNFINISHED;
        Instant placement = task.getPlacementUpdatedAt() != null ? task.getPlacementUpdatedAt() : Instant.now();
        Instant last = task.getLastUpdatedAt() != null ? task.getLastUpdatedAt() : Instant.now();
        String uid = currentUserId();

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
        if (uid != null && !uid.isBlank()) ps.setString(13, uid);
        else ps.setNull(13, Types.VARCHAR);
    }

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

    private void bindBlocked(PreparedStatement ps, BlockedTime b) throws SQLException {
        Instant last = b.getLastUpdatedAt() != null ? b.getLastUpdatedAt() : Instant.now();
        String uid = b.getUserId();
        if (uid == null || uid.isBlank()) uid = currentUserId();
        ps.setString(1, b.getId());
        ps.setString(2, b.getTitle());
        if (b.getWeekday() != null) ps.setInt(3, toWeekdayInt(b.getWeekday()));
        else ps.setNull(3, Types.SMALLINT);
        if (b.getSpecificDate() != null) ps.setDate(4, Date.valueOf(b.getSpecificDate()));
        else ps.setNull(4, Types.DATE);
        ps.setTime(5, Time.valueOf(b.getStartTime()));
        ps.setTime(6, Time.valueOf(b.getEndTime()));
        ps.setTimestamp(7, Timestamp.from(last));
        if (uid != null && !uid.isBlank()) ps.setString(8, uid);
        else ps.setNull(8, Types.VARCHAR);
        ps.setBoolean(9, b.isException());
    }

    private BlockedTime mapBlocked(ResultSet rs) throws SQLException {
        int wd = rs.getInt("weekday");
        Integer weekday = rs.wasNull() ? null : wd;
        Date d = rs.getDate("specific_date");
        Timestamp last = rs.getTimestamp("last_updated_at");
        BlockedTime b = new BlockedTime(
                rs.getString("id"),
                rs.getString("title"),
                toDayOfWeek(weekday),
                d != null ? d.toLocalDate() : null,
                rs.getTime("start_time").toLocalTime(),
                rs.getTime("end_time").toLocalTime(),
                last != null ? last.toInstant() : Instant.now(),
                rs.getString("user_id")
        );
        try {
            b.setException(rs.getBoolean("is_exception"));
        } catch (SQLException ignored) {
            b.setException(false);
        }
        return b;
    }

    private static int toWeekdayInt(Object weekday) {
        if (weekday instanceof Integer i) return i;
        if (weekday instanceof java.time.DayOfWeek d) return d.getValue() % 7;
        return Integer.parseInt(weekday.toString());
    }

    private static java.time.DayOfWeek toDayOfWeek(Integer weekday) {
        if (weekday == null) return null;
        if (weekday == 0) return java.time.DayOfWeek.SUNDAY;
        return java.time.DayOfWeek.of(weekday);
    }
}

