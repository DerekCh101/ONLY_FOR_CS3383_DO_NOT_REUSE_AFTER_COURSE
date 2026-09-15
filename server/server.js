import http from "node:http";
import fs from "node:fs";
import { randomUUID, createHash } from "node:crypto";
import bcrypt from "bcrypt";
import { validateTask } from "./HelperMethods.js";
import {
    findUserByUsername,
    createUser,
    createSession,
    userFromToken,
    listTasks,
    upsertTask,
    deleteTask,
    listBlocked,
    upsertBlocked,
    deleteBlocked,
    getTypewriter,
    upsertTypewriter,
    hasTypewriterImage,
    putTypewriterImage,
    getTypewriterImage,
} from "./db.js";

function readBody(req) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        req.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
        req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
        req.on("error", reject);
    });
}

function send(res, code, obj) {
    res.statusCode = code;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify(obj));
}

function parseJson(raw) {
    const text = String(raw || "").trim();
    if (!text) return {};
    return JSON.parse(text);
}

function sha256Hex(s) {
    return createHash("sha256").update(String(s || ""), "utf8").digest("hex");
}

async function passwordMatches(stored, password) {
    const hash = String(stored || "");
    if (hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$")) {
        return bcrypt.compare(String(password), hash);
    }
    return hash === sha256Hex(password);
}

function normTime(t) {
    const s = String(t || "").trim();
    if (s.length === 5) return s + ":00";
    return s;
}

function isExceptionRow(b) {
    return b.is_exception === true || b.is_exception === "t" || b.is_exception === "true" || b.is_exception === 1;
}

function isDailyRow(b) {
    return !isExceptionRow(b) && !toYmd(b.specific_date);
}

function sameLayer(a, b) {
    if (isExceptionRow(a) !== isExceptionRow(b)) return false;
    if (isExceptionRow(a)) return toYmd(a.specific_date) === toYmd(b.specific_date);
    if (isDailyRow(a) && isDailyRow(b)) return true;
    if (isDailyRow(a) || isDailyRow(b)) return false;
    return toYmd(a.specific_date) === toYmd(b.specific_date);
}

function clockOverlap(a, b) {
    return normTime(a.start_time) < normTime(b.end_time)
        && normTime(b.start_time) < normTime(a.end_time);
}

function describeBlocked(b) {
    const when = isExceptionRow(b)
        ? "exception " + toYmd(b.specific_date)
        : isDailyRow(b) ? "every day" : toYmd(b.specific_date);
    return (b.title || "blocked") + " " + when + " " + b.start_time + "–" + b.end_time;
}

function blockedConflict(existing, row) {
    for (const b of existing) {
        if (String(b.id) === String(row.id)) continue;
        if (sameLayer(b, row) && clockOverlap(b, row)) {
            return "Overlaps existing: " + describeBlocked(b);
        }
    }
    return null;
}
function minutesOf(t) {
    const s = normTime(t);
    const p = String(s || "").split(":");
    if (p.length < 2) return null;
    const h = Number(p[0]);
    const m = Number(p[1]);
    if (!Number.isFinite(h) || !Number.isFinite(m)) return null;
    return h * 60 + m;
}

function rangesOverlap(a0, a1, b0, b1) {
    return a0 < b1 && b0 < a1;
}

function blockedAppliesOn(b, day) {
    if (isExceptionRow(b)) return toYmd(b.specific_date) === day;
    if (isDailyRow(b)) return true;
    return toYmd(b.specific_date) === day;
}

function taskWindow(task) {
    const day = toYmd(task.placed_date || task.placedDate);
    const start = minutesOf(task.placed_time || task.placedTime);
    const dur = Number(task.duration || 0);
    if (!day || start == null || !(dur > 0)) return null;
    return { day, start, end: start + dur };
}

async function taskConflict(userId, task) {
    const win = taskWindow(task);
    if (!win) return null;

    const [tasks, blocked] = await Promise.all([
        listTasks(userId),
        listBlocked(userId)
    ]);

    for (const t of tasks) {
        if (String(t.id) === String(task.id)) continue;
        const other = taskWindow(t);
        if (!other || other.day !== win.day) continue;
        if (rangesOverlap(win.start, win.end, other.start, other.end)) {
            return "Overlaps task: " + (t.title || "task") + " " + other.day + " " +
                String(t.placed_time || "").slice(0, 5);
        }
    }

    for (const b of blocked) {
        if (!blockedAppliesOn(b, win.day)) continue;
        const b0 = minutesOf(b.start_time);
        const b1 = minutesOf(b.end_time);
        if (b0 == null || b1 == null) continue;
        if (rangesOverlap(win.start, win.end, b0, b1)) {
            return "Overlaps blocked: " + describeBlocked(b);
        }
    }
    return null;
}

function reqUrl(req) {
    return new URL(req.url, "http://127.0.0.1");
}

function pathname(req) {
    return reqUrl(req).pathname;
}

function toYmd(v) {
    const s = String(v ?? "").trim();
    if (!s) return "";
    const iso = s.match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (iso) return iso[1] + "-" + iso[2] + "-" + iso[3];
    const dmy = s.match(/^(\d{2})[\/.\-](\d{2})[\/.\-](\d{4})$/);
    if (dmy) return dmy[3] + "-" + dmy[2] + "-" + dmy[1];
    return s.slice(0, 10);
}

function isSha256(s) {
    return /^[a-f0-9]{64}$/.test(String(s || "").toLowerCase());
}

const HostNodeServerListenToWhereIP = process.env.HOST_LISTEN_IP || "0.0.0.0";
const HostNodeServerListenToWherePort = Number(process.env.HostNodeServerListenToWherePort || 3456);

http.createServer(async (req, res) => {
    const path = pathname(req);
    const parts = path.split("/").filter(Boolean);

    if (req.method === "GET" && (path === "/" || path === "/index.html")) {
        res.setHeader("Content-Type", "text/html; charset=utf-8");
        res.end(fs.readFileSync("./index.html", "utf8"));
        return;
    }

    if (req.method === "GET" && path === "/app.js") {
        res.setHeader("Content-Type", "text/javascript; charset=utf-8");
        res.end(fs.readFileSync("./app.js", "utf8"));
        return;
    }

    if (req.method === "GET" && path === "/tasks") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return;
        }
        try {
            send(res, 200, { tasks: await listTasks(user.id) });
        } catch (e) {
            send(res, 500, { error: String(e.message || e) });
        }
        return;
    }

    if (req.method === "GET" && path === "/blocked") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return;
        }
        try {
            send(res, 200, { blocked: await listBlocked(user.id) });
        } catch (e) {
            send(res, 500, { error: String(e.message || e) });
        }
        return;
    }

    if (req.method === "DELETE" && parts.length === 2 && parts[0] === "tasks") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return;
        }
        const removed = await deleteTask(user.id, parts[1]);
        if (!removed) {
            send(res, 404, { error: "task not found" });
            return;
        }
        send(res, 200, removed);
        return;
    }

    if (req.method === "DELETE" && parts.length === 2 && parts[0] === "blocked") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return;
        }
        const removed = await deleteBlocked(user.id, parts[1]);
        if (!removed) {
            send(res, 404, { error: "blocked not found" });
            return;
        }
        send(res, 200, removed);
        return;
    }

    if (req.method === "GET" && path === "/typewriter") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return;
        }
        try {
            send(res, 200, await getTypewriter(user.id));
        } catch (e) {
            console.error("GET /typewriter", e);
            send(res, 500, { error: String(e.message || e) });
        }
        return;
    }

    if (req.method === "POST" && path === "/typewriter") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return;
        }
        try {
            const raw = await readBody(req);
            const body = parseJson(raw);
            const content = body.content == null ? "" : String(body.content);
            const last = String(body.last_updated_at ?? body.lastUpdatedAt ?? new Date().toISOString());
            send(res, 200, await upsertTypewriter(user.id, content, last));
        } catch (e) {
            console.error("POST /typewriter", e);
            send(res, 400, { error: String(e.message || e) });
        }
        return;
    }

    if (req.method === "GET" && parts.length === 3 && parts[0] === "typewriter" && parts[1] === "images") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return;
        }
        const sha = String(parts[2] || "").toLowerCase();
        if (!isSha256(sha)) {
            send(res, 400, { error: "bad sha" });
            return;
        }
        try {
            const row = await getTypewriterImage(user.id, sha);
            if (!row) {
                send(res, 404, { error: "missing" });
                return;
            }
            const meta = reqUrl(req).searchParams.get("meta") === "1";
            if (meta) {
                send(res, 200, { exists: true, sha256: row.sha256, name: row.name, mime: row.mime });
                return;
            }
            const buf = Buffer.isBuffer(row.data) ? row.data : Buffer.from(row.data);
            send(res, 200, {
                sha256: row.sha256,
                name: row.name,
                mime: row.mime || "image/png",
                data: buf.toString("base64")
            });
        } catch (e) {
            send(res, 500, { error: String(e.message || e) });
        }
        return;
    }

    if (req.method === "POST" && path === "/typewriter/images") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return;
        }
        try {
            const raw = await readBody(req);
            const body = parseJson(raw);
            const sha = String(body.sha256 ?? body.sha ?? "").toLowerCase();
            const name = String(body.name ?? "image.png");
            const mime = String(body.mime ?? "image/png");
            if (!isSha256(sha)) {
                send(res, 400, { error: "sha256 required" });
                return;
            }
            if (await hasTypewriterImage(user.id, sha)) {
                send(res, 200, { ok: true, existed: true, sha256: sha });
                return;
            }
            if (!body.data) {
                send(res, 400, { error: "data required" });
                return;
            }
            const buf = Buffer.from(String(body.data), "base64");
            const got = createHash("sha256").update(buf).digest("hex");
            if (got !== sha) {
                send(res, 400, { error: "sha mismatch" });
                return;
            }
            await putTypewriterImage(user.id, sha, name, mime, buf);
            send(res, 200, { ok: true, existed: false, sha256: sha });
        } catch (e) {
            console.error("POST /typewriter/images", e);
            send(res, 400, { error: String(e.message || e) });
        }
        return;
    }

    if (req.method === "POST" && path === "/register") {
        try {
            const raw = await readBody(req);
            const body = parseJson(raw);
            const username = String(body.username ?? "").trim();
            const password = String(body.password ?? "").trim();
            if (!username || !password) {
                send(res, 400, { error: "username and password required" });
                return;
            }
            if (username.length > 40) {
                send(res, 400, { error: "username max 40 characters" });
                return;
            }
            if (password.length < 4) {
                send(res, 400, { error: "password min 4 characters" });
                return;
            }
            if (await findUserByUsername(username)) {
                send(res, 409, { error: "username already taken" });
                return;
            }
            const user = await createUser(
                randomUUID(),
                username,
                await bcrypt.hash(password, 12)
            );
            const token = await createSession(user.id);
            console.log("POST /register ok", username);
            send(res, 201, { id: user.id, username: user.username, token });
        } catch (e) {
            console.error("POST /register", e);
            send(res, 400, { error: String(e.message || e) });
        }
        return;
    }

    if (req.method === "POST" && path === "/login") {
        try {
            const raw = await readBody(req);
            console.log("POST /login bytes=" + raw.length);
            const body = parseJson(raw);
            const username = String(body.username ?? "").trim();
            const password = String(body.password ?? "").trim();
            if (!username || !password) {
                send(res, 400, { error: "username and password required" });
                return;
            }
            const user = await findUserByUsername(username);
            if (!user) {
                console.log("POST /login no user", username);
                send(res, 401, { error: "invalid username or password" });
                return;
            }
            if (!(await passwordMatches(user.password_hash, password))) {
                console.log("POST /login bad password", username);
                send(res, 401, { error: "invalid username or password" });
                return;
            }
            const token = await createSession(user.id);
            console.log("POST /login ok", username);
            send(res, 200, { id: user.id, username: user.username, token });
        } catch (e) {
            console.error("POST /login", e);
            send(res, 400, { error: String(e.message || e) });
        }
        return;
    }

    if (req.method === "POST" && path === "/tasks") {
        try {
            const raw = await readBody(req);
            const body = parseJson(raw);
            const authUser = await userFromToken(req);
            if (!authUser) {
                send(res, 401, { error: "login required" });
                return;
            }

            body.user_id = authUser.id;
            body.userId = authUser.id;
            body.placed_date = toYmd(body.placed_date || body.placedDate);
            body.placedDate = body.placed_date;
            body.deadline_date = toYmd(body.deadline_date || body.deadlineDate);
            if (body.deadline_date) body.deadlineDate = body.deadline_date;

            const err = validateTask(body);
            if (err) {
                send(res, 400, { error: err });
                return;
            }

            const task = {
                id: String(body.id).trim(),
                title: String(body.title).trim(),
                duration: typeof body.duration === "number"
                    ? body.duration
                    : Number(body.duration),
                state: String(body.state).trim(),
                placed_date: String(body.placed_date ?? "").trim() || null,
                placed_time: String(body.placed_time ?? "").trim() || null,
                deadline_date: String(body.deadline_date ?? "").trim() || null,
                deadline_time: String(body.deadline_time ?? "").trim() || null,
                lifecycle: String(body.lifecycle).trim(),
                workflow: String(body.workflow).trim(),
                placement_updated_at: String(
                    body.placementUpdatedAt ?? body.placement_updated_at ?? ""
                ).trim() || null,
                last_updated_at: String(
                    body.lastUpdatedAt ?? body.last_updated_at ?? ""
                ).trim() || null,
                user_id: authUser.id,
            };
            const clash = await taskConflict(authUser.id, task);
            if (clash) {
                send(res, 409, { error: clash });
                return;
            }
            await upsertTask(task);
            send(res, 201, task);
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return;
    }

    if (req.method === "POST" && path === "/blocked") {
        try {
            const raw = await readBody(req);
            const body = parseJson(raw);
            const authUser = await userFromToken(req);
            if (!authUser) {
                send(res, 401, { error: "login required" });
                return;
            }
            const isException = body.is_exception === true
                || body.is_exception === "true"
                || body.is_exception === 1;
            const specific = toYmd(body.specific_date || body.specificDate) || null;
            const start = String(body.start_time ?? "").trim() || null;
            const end = String(body.end_time ?? "").trim() || null;
            if (!start || !end) {
                send(res, 400, { error: "start_time and end_time required" });
                return;
            }
            if (isException && !specific) {
                send(res, 400, { error: "exception needs a date" });
                return;
            }
            const row = {
                id: String(body.id ?? randomUUID()).trim(),
                title: String(body.title ?? "").trim() || "blocked",
                weekday: body.weekday == null || body.weekday === "" ? null : Number(body.weekday),
                specific_date: specific,
                start_time: start,
                end_time: end,
                last_updated_at: String(
                    body.last_updated_at ?? new Date().toISOString()
                ).trim(),
                user_id: authUser.id,
                is_exception: isException,
            };
            const existing = await listBlocked(authUser.id);
            const clash = blockedConflict(existing, row);
            if (clash) {
                send(res, 400, { error: clash });
                return;
            }
            await upsertBlocked(row);
            send(res, 201, row);
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return;
    }

    send(res, 404, { error: "not found" });

}).listen(HostNodeServerListenToWherePort, HostNodeServerListenToWhereIP, () => {
    console.log("listening on http://" + HostNodeServerListenToWhereIP + ":" + HostNodeServerListenToWherePort);
});