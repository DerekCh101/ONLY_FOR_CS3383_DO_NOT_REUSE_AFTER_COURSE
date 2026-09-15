import pg from "pg";
import { randomUUID } from "node:crypto";

const { Pool } = pg;

pg.types.setTypeParser(1082, (v) => (v == null ? null : String(v).slice(0, 10)));
pg.types.setTypeParser(1083, (v) => (v == null ? null : String(v)));

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

function toYmd(v) {
    const s = String(v ?? "").trim();
    const m = s.match(/(\d{4})-(\d{2})-(\d{2})/);
    return m ? m[1] + "-" + m[2] + "-" + m[3] : "";
}

function parseBundle(raw) {
    const empty = { diary: [], lists: [], pages: [] };
    try {
        const o = raw ? JSON.parse(String(raw)) : null;
        if (!o || typeof o !== "object") return empty;
        return {
            diary: Array.isArray(o.diary) ? o.diary : [],
            lists: Array.isArray(o.lists) ? o.lists : [],
            pages: Array.isArray(o.pages) ? o.pages : []
        };
    } catch (_) {
        return empty;
    }
}

function imageText(note) {
    const imgs = note && note.images;
    if (!Array.isArray(imgs) || !imgs.length) return "";
    const out = [];
    for (const im of imgs) {
        if (typeof im === "string") {
            if (/^[a-f0-9]{64}$/i.test(im)) out.push(im.toLowerCase());
            continue;
        }
        const id = String((im && (im.id || im.sha256)) || "").toLowerCase();
        if (/^[a-f0-9]{64}$/.test(id)) out.push(id);
    }
    return out.join(",");
}

function imageArr(text) {
    return String(text || "")
        .split(",")
        .map((s) => s.trim().toLowerCase())
        .filter((s) => /^[a-f0-9]{64}$/.test(s))
        .map((id) => ({ id, name: "" }));
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
    await query(`
        CREATE TABLE IF NOT EXISTS ${SCHEMA}.tw_diary (
            user_id VARCHAR(36) NOT NULL,
            day DATE NOT NULL,
            title TEXT,
            body TEXT,
            images TEXT,
            PRIMARY KEY (user_id, day)
        )
    `);
    await query(`
        CREATE TABLE IF NOT EXISTS ${SCHEMA}.tw_lists (
            id VARCHAR(36) PRIMARY KEY,
            user_id VARCHAR(36) NOT NULL,
            title TEXT,
            pos INTEGER
        )
    `);
    await query(`
        CREATE TABLE IF NOT EXISTS ${SCHEMA}.tw_list_items (
            list_id VARCHAR(36) NOT NULL,
            user_id VARCHAR(36) NOT NULL,
            pos INTEGER NOT NULL,
            text TEXT,
            done BOOLEAN NOT NULL DEFAULT false,
            PRIMARY KEY (list_id, pos)
        )
    `);
    await query(`
        CREATE TABLE IF NOT EXISTS ${SCHEMA}.tw_pages (
            id VARCHAR(36) PRIMARY KEY,
            user_id VARCHAR(36) NOT NULL,
            parent_id VARCHAR(36),
            kind VARCHAR(16) NOT NULL,
            title TEXT,
            body TEXT,
            images TEXT,
            pos INTEGER
        )
    `);
    await query(`
        CREATE TABLE IF NOT EXISTS ${SCHEMA}.tw_meta (
            user_id VARCHAR(36) PRIMARY KEY,
            last_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )
    `);
}

await ensureSchema();

export async function findUserByUsername(username) {
    const name = str(username);
    if (!name) return null;
    const r = await query(
        `SELECT id::text AS id, username, password_hash FROM ${SCHEMA}.users WHERE username = $1`,
        [name]
    );
    return r.rows[0] || null;
}

export async function createUser(id, username, passwordHash) {
    const r = await query(
        `INSERT INTO ${SCHEMA}.users(id, username, password_hash)
         VALUES ($1,$2,$3)
             RETURNING id::text AS id, username, password_hash`,
        [id, str(username), passwordHash]
    );
    return r.rows[0];
}

export async function createSession(userId) {
    const token = randomUUID();
    await query(
        `INSERT INTO ${SCHEMA}.sessions(token, user_id) VALUES ($1,$2)`,
        [token, String(userId)]
    );
    return token;
}

export async function userFromToken(req) {
    const token = bearerToken(req);
    if (!token) return null;
    const r = await query(
        `SELECT u.id::text AS id, u.username
         FROM ${SCHEMA}.sessions s
         JOIN ${SCHEMA}.users u ON u.id::text = s.user_id::text
         WHERE s.token::text = $1`,
        [token]
    );
    return r.rows[0] || null;
}

export async function listTasks(userId) {
    const r = await query(
        `SELECT
             id::text AS id,
             user_id::text AS user_id,
             title,
             duration,
             state,
             placed_date::text AS placed_date,
             placed_time::text AS placed_time,
             deadline_date::text AS deadline_date,
             deadline_time::text AS deadline_time,
             lifecycle,
             workflow,
             last_updated_at,
             placement_updated_at
         FROM ${SCHEMA}.tasks
         WHERE user_id::text = $1
         ORDER BY placed_date NULLS LAST, placed_time NULLS LAST, title`,
        [String(userId)]
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
                                 WHERE ${SCHEMA}.tasks.user_id::text = $2`,
        [
            id,
            userId,
            str(first(task, ["title"])) || "",
            Number(first(task, ["duration"]) || 0),
            str(first(task, ["state"])),
            str(first(task, ["placed_date", "placedDate"]))?.slice(0, 10) || null,
            str(first(task, ["placed_time", "placedTime"])),
            str(first(task, ["deadline_date", "deadlineDate"]))?.slice(0, 10) || null,
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
        `DELETE FROM ${SCHEMA}.tasks WHERE id::text = $1 AND user_id::text = $2 RETURNING *`,
        [String(id), String(userId)]
    );
    return r.rows[0] || null;
}

export async function listBlocked(userId) {
    const r = await query(
        `SELECT * FROM ${SCHEMA}.blocked
         WHERE user_id::text = $1
         ORDER BY start_time NULLS LAST, specific_date NULLS LAST`,
        [String(userId)]
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
        WHERE ${SCHEMA}.blocked.user_id::text = $2`,
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
        `DELETE FROM ${SCHEMA}.blocked WHERE id::text = $1 AND user_id::text = $2 RETURNING *`,
        [String(id), String(userId)]
    );
    return r.rows[0] || null;
}

async function loadStructured(userId) {
    const uid = String(userId);
    const bundle = { diary: [], lists: [], pages: [] };

    const diary = await query(
        `SELECT day::text AS day, title, body, images
         FROM ${SCHEMA}.tw_diary WHERE user_id::text = $1 ORDER BY day`,
        [uid]
    );
    for (const row of diary.rows) {
        const day = toYmd(row.day);
        if (!day) continue;
        bundle.diary.push({
            date: day,
            id: day,
            title: row.title || day,
            body: row.body || "",
            images: imageArr(row.images)
        });
    }

    const lists = await query(
        `SELECT id::text AS id, title, pos
         FROM ${SCHEMA}.tw_lists WHERE user_id::text = $1 ORDER BY pos NULLS LAST, title`,
        [uid]
    );
    const items = await query(
        `SELECT list_id::text AS list_id, text, done, pos
         FROM ${SCHEMA}.tw_list_items WHERE user_id::text = $1 ORDER BY pos`,
        [uid]
    );
    const byList = new Map();
    for (const row of lists.rows) {
        const list = { id: row.id, title: row.title || "list", items: [] };
        byList.set(row.id, list);
        bundle.lists.push(list);
    }
    for (const row of items.rows) {
        const list = byList.get(row.list_id);
        if (!list) continue;
        list.items.push({
            text: row.text || "",
            done: row.done === true || row.done === "t" || row.done === "true"
        });
    }

    const pages = await query(
        `SELECT id::text AS id, parent_id, kind, title, body, images, pos
         FROM ${SCHEMA}.tw_pages WHERE user_id::text = $1 ORDER BY pos NULLS LAST, title`,
        [uid]
    );
    for (const row of pages.rows) {
        bundle.pages.push({
            id: row.id,
            parent_id: row.parent_id || null,
            kind: row.kind || "note",
            title: row.title || "page",
            body: row.body || "",
            images: imageArr(row.images),
            pos: row.pos == null ? 0 : Number(row.pos)
        });
    }
    return bundle;
}

async function saveStructured(userId, bundle, at) {
    const uid = String(userId);
    await query(`DELETE FROM ${SCHEMA}.tw_list_items WHERE user_id::text = $1`, [uid]);
    await query(`DELETE FROM ${SCHEMA}.tw_lists WHERE user_id::text = $1`, [uid]);
    await query(`DELETE FROM ${SCHEMA}.tw_pages WHERE user_id::text = $1`, [uid]);
    await query(`DELETE FROM ${SCHEMA}.tw_diary WHERE user_id::text = $1`, [uid]);

    for (const note of bundle.diary || []) {
        const day = toYmd(note && note.date);
        if (!day) continue;
        const body = String((note && note.body) || "");
        const title = String((note && note.title) || day);
        const images = imageText(note);
        if (!body.trim() && !images && title === day) continue;
        await query(
            `INSERT INTO ${SCHEMA}.tw_diary(user_id, day, title, body, images)
             VALUES ($1,$2,$3,$4,$5)`,
            [uid, day, title, body, images]
        );
    }

    let li = 0;
    for (const list of bundle.lists || []) {
        const id = str(list && list.id);
        if (!id) continue;
        await query(
            `INSERT INTO ${SCHEMA}.tw_lists(id, user_id, title, pos)
             VALUES ($1,$2,$3,$4)`,
            [id, uid, str(list.title) || "list", li++]
        );
        let ii = 0;
        for (const item of list.items || []) {
            await query(
                `INSERT INTO ${SCHEMA}.tw_list_items(list_id, user_id, pos, text, done)
                 VALUES ($1,$2,$3,$4,$5)`,
                [id, uid, ii++, String((item && item.text) || ""), !!(item && item.done)]
            );
        }
    }

    let pi = 0;
    for (const page of bundle.pages || []) {
        if (!page || page.kind === "folder") continue;
        const id = str(page.id);
        if (!id) continue;
        await query(
            `INSERT INTO ${SCHEMA}.tw_pages(id, user_id, parent_id, kind, title, body, images, pos)
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`,
            [
                id,
                uid,
                str(page.parent_id) || "root",
                str(page.kind) || "note",
                str(page.title) || "page",
                String(page.body || ""),
                imageText(page),
                page.pos == null ? pi : Number(page.pos)
            ]
        );
        pi += 1;
    }

    await query(
        `INSERT INTO ${SCHEMA}.tw_meta(user_id, last_updated_at)
         VALUES ($1,$2::timestamptz)
         ON CONFLICT (user_id) DO UPDATE SET last_updated_at = EXCLUDED.last_updated_at`,
        [uid, at]
    );
}

export async function getTypewriter(userId) {
    const structured = await loadStructured(userId);
    const hasStructured =
        structured.diary.length || structured.lists.length || structured.pages.length;
    if (hasStructured) {
        const meta = await query(
            `SELECT last_updated_at FROM ${SCHEMA}.tw_meta WHERE user_id::text = $1`,
            [String(userId)]
        );
        return {
            content: JSON.stringify(structured),
            last_updated_at: meta.rows[0] ? meta.rows[0].last_updated_at : null
        };
    }
    const r = await query(
        `SELECT content, last_updated_at FROM ${SCHEMA}.typewriter WHERE user_id::text = $1`,
        [String(userId)]
    );
    if (!r.rowCount) {
        return { content: JSON.stringify({ diary: [], lists: [], pages: [] }), last_updated_at: null };
    }
    return {
        content: JSON.stringify(parseBundle(r.rows[0].content)),
        last_updated_at: r.rows[0].last_updated_at
    };
}

export async function upsertTypewriter(userId, content, lastUpdatedAt) {
    const incoming = str(lastUpdatedAt) || new Date().toISOString();
    const bundle = parseBundle(content);
    const raw = JSON.stringify(bundle);
    await query(
        `INSERT INTO ${SCHEMA}.typewriter(user_id, content, last_updated_at)
         VALUES ($1,$2,$3)
         ON CONFLICT (user_id) DO UPDATE SET
            content = EXCLUDED.content,
            last_updated_at = EXCLUDED.last_updated_at`,
        [String(userId), raw, incoming]
    );
    await saveStructured(userId, bundle, incoming);
    return { content: raw, last_updated_at: incoming };
}

export async function hasTypewriterImage(userId, sha) {
    const r = await query(
        `SELECT 1 FROM ${SCHEMA}.tw_images WHERE user_id::text = $1 AND sha256 = $2`,
        [String(userId), sha]
    );
    return r.rowCount > 0;
}

export async function putTypewriterImage(userId, sha, name, mime, buf) {
    await query(
        `INSERT INTO ${SCHEMA}.tw_images(user_id, sha256, name, mime, data, last_updated_at)
         VALUES ($1,$2,$3,$4,$5, now())
             ON CONFLICT (user_id, sha256) DO NOTHING`,
        [String(userId), sha, name || "image.png", mime || "image/png", buf]
    );
}

export async function getTypewriterImage(userId, sha) {
    const r = await query(
        `SELECT sha256, name, mime, data FROM ${SCHEMA}.tw_images
         WHERE user_id::text = $1 AND sha256 = $2`,
        [String(userId), sha]
    );
    return r.rows[0] || null;
}