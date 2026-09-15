import pg from "pg";

const { Pool } = pg;

const pool = new Pool({
    host: process.env.PGHOST || "127.0.0.1",
    port: Number(process.env.PGPORT || 5432),
    user: process.env.PGUSER || "postgres",
    password: process.env.PGPASSWORD || process.env.postgres_pw || "",
    database: process.env.PGDATABASE || "Scheduler"
});

const SCHEMA = "TASK";

async function query(sql, params) {
    return pool.query(sql, params);
}

function str(v) {
    if (v == null) return null;
    const s = String(v).trim();
    return s === "" || s === "null" ? null : s;
}

function first(obj, keys) {
    if (!obj) return null;
    for (const k of keys) {
        if (obj[k] != null && String(obj[k]).trim() !== "") return obj[k];
    }
    return null;
}

function bearerToken(req) {
    const h = req && req.headers ? (req.headers.authorization || req.headers.Authorization || "") : "";
    const s = String(h);
    if (s.toLowerCase().startsWith("bearer ")) return s.slice(7).trim();
    return "";
}

export async function ensureSchema() {
    await query(`CREATE SCHEMA IF NOT EXISTS ${SCHEMA}`);
    await query(`
        CREATE TABLE IF NOT EXISTS ${SCHEMA}.users (
            id TEXT PRIMARY KEY,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
    `);
    await query(`
        CREATE TABLE IF NOT EXISTS ${SCHEMA}.sessions (
            token TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
    `);
    await query(`
        CREATE TABLE IF NOT EXISTS ${SCHEMA}.tasks (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            title TEXT,
            duration INTEGER,
            state TEXT,
            placed_date DATE,
            placed_time TIME,
            deadline_date DATE,
            deadline_time TIME,
            lifecycle TEXT,
            workflow TEXT,
            last_updated_at TIMESTAMPTZ,
            placement_updated_at TIMESTAMPTZ
        )
    `);
    await query(`
        CREATE TABLE IF NOT EXISTS ${SCHEMA}.blocked (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            title TEXT,
            weekday INTEGER,
            specific_date DATE,
            start_time TIME,
            end_time TIME,
            last_updated_at TIMESTAMPTZ,
            is_exception BOOLEAN NOT NULL DEFAULT FALSE
        )
    `);
    await query(`
        CREATE TABLE IF NOT EXISTS ${SCHEMA}.typewriter (
            user_id TEXT PRIMARY KEY,
            content TEXT,
            last_updated_at TIMESTAMPTZ
        )
    `);
    await query(`
        CREATE TABLE IF NOT EXISTS ${SCHEMA}.tw_images (
            user_id TEXT NOT NULL,
            sha256 TEXT NOT NULL,
            name TEXT,
            mime TEXT,
            data BYTEA NOT NULL,
            last_updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            PRIMARY KEY (user_id, sha256)
        )
    `);
}

await ensureSchema();

export async function findUserByUsername(username) {
    const name = str(username);
    if (!name) return null;
    const r = await query(
        `SELECT id, username, password_hash FROM ${SCHEMA}.users WHERE username = $1`,
        [name]
    );
    return r.rows[0] || null;
}

export async function createUser(id, username, passwordHash) {
    const r = await query(
        `INSERT INTO ${SCHEMA}.users(id, username, password_hash)
         VALUES ($1,$2,$3)
         RETURNING id, username, password_hash`,
        [id, str(username), passwordHash]
    );
    return r.rows[0];
}

export async function createSession(userId) {
    const token = crypto.randomUUID();
    await query(
        `INSERT INTO ${SCHEMA}.sessions(token, user_id) VALUES ($1,$2)`,
        [token, userId]
    );
    return token;
}

export async function userFromToken(req) {
    const token = bearerToken(req);
    if (!token) return null;
    const r = await query(
        `SELECT u.id, u.username
         FROM ${SCHEMA}.sessions s
         JOIN ${SCHEMA}.users u ON u.id = s.user_id
         WHERE s.token = $1`,
        [token]
    );
    return r.rows[0] || null;
}

export async function listTasks(userId) {
    const r = await query(
        `SELECT * FROM ${SCHEMA}.tasks
         WHERE user_id = $1
         ORDER BY placed_date NULLS LAST, placed_time NULLS LAST, title`,
        [userId]
    );
    return r.rows;
}

export async function upsertTask(task) {
    const id = str(first(task, ["id"]));
    const userId = str(first(task, ["user_id", "userId"]));
    if (!id || !userId) throw new Error("task id and user_id required");
    await query(
        `INSERT INTO ${SCHEMA}.tasks(
            id, user_id, title, duration, state, placed_date, placed_time,
            deadline_date, deadline_time, lifecycle, workflow, last_updated_at, placement_updated_at
        ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
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
            last_updated_at = EXCLUDED.last_updated_at,
            placement_updated_at = EXCLUDED.placement_updated_at
        WHERE ${SCHEMA}.tasks.user_id = $2`,
        [
            id,
            userId,
            str(first(task, ["title"])) || "",
            Number(first(task, ["duration"]) || 0),
            str(first(task, ["state"])),
            str(first(task, ["placed_date", "placedDate"])),
            str(first(task, ["placed_time", "placedTime"])),
            str(first(task, ["deadline_date", "deadlineDate"])),
            str(first(task, ["deadline_time", "deadlineTime"])),
            str(first(task, ["lifecycle"])) || "ONGOING",
            str(first(task, ["workflow"])) || "UNFINISHED",
            str(first(task, ["last_updated_at", "lastUpdatedAt"])) || new Date().toISOString(),
            str(first(task, ["placement_updated_at", "placementUpdatedAt"])) || new Date().toISOString()
        ]
    );
    return task;
}

export async function deleteTask(userId, id) {
    const r = await query(
        `DELETE FROM ${SCHEMA}.tasks WHERE id = $1 AND user_id = $2 RETURNING *`,
        [id, userId]
    );
    return r.rows[0] || null;
}

export async function listBlocked(userId) {
    const r = await query(
        `SELECT * FROM ${SCHEMA}.blocked
         WHERE user_id = $1
         ORDER BY start_time NULLS LAST, specific_date NULLS LAST`,
        [userId]
    );
    return r.rows;
}

export async function upsertBlocked(row) {
    const id = str(first(row, ["id"]));
    const userId = str(first(row, ["user_id", "userId"]));
    if (!id || !userId) throw new Error("blocked id and user_id required");
    const rawEx = first(row, ["is_exception", "isException"]);
    const isException = rawEx === true || rawEx === "t" || rawEx === "true" || rawEx === 1 || rawEx === "1";
    const weekday = first(row, ["weekday"]);
    await query(
        `INSERT INTO ${SCHEMA}.blocked(
            id, user_id, title, weekday, specific_date, start_time, end_time, last_updated_at, is_exception
        ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
        ON CONFLICT (id) DO UPDATE SET
            title = EXCLUDED.title,
            weekday = EXCLUDED.weekday,
            specific_date = EXCLUDED.specific_date,
            start_time = EXCLUDED.start_time,
            end_time = EXCLUDED.end_time,
            last_updated_at = EXCLUDED.last_updated_at,
            is_exception = EXCLUDED.is_exception
        WHERE ${SCHEMA}.blocked.user_id = $2`,
        [
            id,
            userId,
            str(first(row, ["title"])) || "blocked",
            weekday == null || weekday === "" ? null : Number(weekday),
            str(first(row, ["specific_date", "specificDate"])),
            str(first(row, ["start_time", "startTime"])),
            str(first(row, ["end_time", "endTime"])),
            str(first(row, ["last_updated_at", "lastUpdatedAt"])) || new Date().toISOString(),
            isException
        ]
    );
    return row;
}

export async function deleteBlocked(userId, id) {
    const r = await query(
        `DELETE FROM ${SCHEMA}.blocked WHERE id = $1 AND user_id = $2 RETURNING *`,
        [id, userId]
    );
    return r.rows[0] || null;
}

export async function getTypewriter(userId) {
    const r = await query(
        `SELECT content, last_updated_at FROM ${SCHEMA}.typewriter WHERE user_id = $1`,
        [userId]
    );
    if (!r.rowCount) return { content: "", last_updated_at: null };
    return r.rows[0];
}

export async function upsertTypewriter(userId, content, lastUpdatedAt) {
    const incoming = str(lastUpdatedAt) || new Date().toISOString();
    const cur = await getTypewriter(userId);
    if (cur.last_updated_at) {
        const remoteMs = new Date(cur.last_updated_at).getTime();
        const inMs = new Date(incoming).getTime();
        if (!Number.isNaN(remoteMs) && !Number.isNaN(inMs) && remoteMs > inMs) {
            return cur;
        }
    }
    await query(
        `INSERT INTO ${SCHEMA}.typewriter(user_id, content, last_updated_at)
         VALUES ($1,$2,$3)
         ON CONFLICT (user_id) DO UPDATE SET
            content = EXCLUDED.content,
            last_updated_at = EXCLUDED.last_updated_at`,
        [userId, content == null ? "" : String(content), incoming]
    );
    return { content: content == null ? "" : String(content), last_updated_at: incoming };
}

export async function hasTypewriterImage(userId, sha) {
    const r = await query(
        `SELECT 1 FROM ${SCHEMA}.tw_images WHERE user_id = $1 AND sha256 = $2`,
        [userId, sha]
    );
    return r.rowCount > 0;
}

export async function putTypewriterImage(userId, sha, name, mime, buf) {
    await query(
        `INSERT INTO ${SCHEMA}.tw_images(user_id, sha256, name, mime, data, last_updated_at)
         VALUES ($1,$2,$3,$4,$5, now())
         ON CONFLICT (user_id, sha256) DO NOTHING`,
        [userId, sha, name || "image.png", mime || "image/png", buf]
    );
}

export async function getTypewriterImage(userId, sha) {
    const r = await query(
        `SELECT sha256, name, mime, data FROM ${SCHEMA}.tw_images
         WHERE user_id = $1 AND sha256 = $2`,
        [userId, sha]
    );
    return r.rows[0] || null;
}