import pg from "pg";
import { createHash, randomBytes } from "node:crypto";

const pool = new pg.Pool({
    host: "127.0.0.1",
    port: 5432,
    database: "task_scheduler",
    user: "postgres",
    password: process.env.PGPASSWORD || "postgres",
});

function hashToken(token) {
    return createHash("sha256").update(token).digest("hex");
}

export async function findUserByUsername(username) {
    const r = await pool.query(
        "SELECT id, username, password_hash FROM users WHERE username = $1",
        [username]
    );
    return r.rows[0] || null;
}

export async function createUser(id, username, passwordHash) {
    await pool.query(
        "INSERT INTO users (id, username, password_hash) VALUES ($1, $2, $3)",
        [id, username, passwordHash]
    );
    return { id, username };
}

export async function createSession(userId) {
    await pool.query("DELETE FROM sessions WHERE user_id = $1", [userId]);
    const token = randomBytes(32).toString("hex");
    await pool.query(
        "INSERT INTO sessions (token_hash, user_id) VALUES ($1, $2)",
        [hashToken(token), userId]
    );
    return token;
}

export async function userFromToken(req) {
    const h = String(req.headers.authorization ?? "");
    const m = /^Bearer\s+(\S+)$/i.exec(h);
    if (!m) return null;
    const r = await pool.query(
        `SELECT u.id, u.username
         FROM sessions s
         JOIN users u ON u.id = s.user_id
         WHERE s.token_hash = $1`,
        [hashToken(m[1])]
    );
    return r.rows[0] || null;
}

export async function listTasks(userId) {
    const r = await pool.query(
        "SELECT * FROM tasks WHERE user_id = $1 ORDER BY placed_date NULLS LAST, placed_time NULLS LAST",
        [userId]
    );
    return r.rows;
}

export async function upsertTask(task) {
    await pool.query(
        `INSERT INTO tasks (
            id, user_id, title, duration, state,
            placed_date, placed_time, deadline_date, deadline_time,
            lifecycle, workflow, placement_updated_at, last_updated_at
         ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
         ON CONFLICT (id) DO UPDATE SET
            user_id = EXCLUDED.user_id,
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
         WHERE tasks.user_id = EXCLUDED.user_id`,
        [
            task.id,
            task.user_id,
            task.title,
            task.duration,
            task.state,
            task.placed_date,
            task.placed_time,
            task.deadline_date,
            task.deadline_time,
            task.lifecycle,
            task.workflow,
            task.placement_updated_at,
            task.last_updated_at,
        ]
    );
}

export async function deleteTask(userId, taskId) {
    const r = await pool.query(
        "DELETE FROM tasks WHERE id = $1 AND user_id = $2 RETURNING *",
        [taskId, userId]
    );
    return r.rows[0] || null;
}