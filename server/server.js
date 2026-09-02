import http from "node:http";
import fs from "node:fs";
import { createHash, randomUUID } from "node:crypto";
import { validateTask } from "./HelperMethods.js";
import {
    findUserByUsername,
    createUser,
    createSession,
    userFromToken,
    listTasks,
    upsertTask,
    deleteTask,
} from "./db.js";

function readBody(req) {
    return new Promise((resolve, reject) => {
        let raw = "";
        req.on("data", (chunk) => (raw += chunk));
        req.on("end", () => resolve(raw));
        req.on("error", reject);
    });
}

function send(res, code, obj) {
    res.statusCode = code;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify(obj));
}

http.createServer(async (req, res) => {
    if (req.method === "GET" && (req.url === "/" || req.url === "/index.html")) {
        res.setHeader("Content-Type", "text/html; charset=utf-8");
        res.end(fs.readFileSync("./index.html", "utf8"));
        return;
    }

    {
        const url = new URL(req.url, "http://127.0.0.1");
        if (req.method === "GET" && url.pathname === "/tasks") {
            const user = await userFromToken(req);
            if (!user) {
                send(res, 401, { error: "login required" });
                return;
            }
            send(res, 200, { tasks: await listTasks(user.id) });
            return;
        }
    }

    {
        const url = new URL(req.url, "http://127.0.0.1");
        const parts = url.pathname.split("/").filter(Boolean);
        if (req.method === "DELETE" && parts.length === 2 && parts[0] === "tasks") {
            const id = parts[1];
            const user = await userFromToken(req);
            if (!user) {
                send(res, 401, { error: "login required" });
                return;
            }
            const removed = await deleteTask(user.id, id);
            if (!removed) {
                send(res, 404, { error: "task not found" });
                return;
            }
            send(res, 200, removed);
            return;
        }
    }

    if (req.method === "POST" && req.url === "/register") {
        try {
            const raw = await readBody(req);
            const body = JSON.parse(raw || "{}");
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
                createHash("sha256").update(password).digest("hex")
            );
            const token = await createSession(user.id);
            send(res, 201, { id: user.id, username: user.username, token });
        } catch (e) {
            send(res, 400, { error: "invalid json" });
        }
        return;
    }

    if (req.method === "POST" && req.url === "/tasks") {
        try {
            const raw = await readBody(req);
            const body = JSON.parse(raw || "{}");
            const authUser = await userFromToken(req);
            if (!authUser) {
                send(res, 401, { error: "login required" });
                return;
            }
            body.user_id = authUser.id;
            body.userId = authUser.id;

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

            await upsertTask(task);
            send(res, 201, task);
        } catch (e) {
            send(res, 400, { error: "invalid json" });
        }
        return;
    }

    if (req.method === "POST" && req.url === "/login") {
        try {
            const raw = await readBody(req);
            const body = JSON.parse(raw || "{}");
            const username = String(body.username ?? "").trim();
            const password = String(body.password ?? "").trim();
            if (!username || !password) {
                send(res, 400, { error: "username and password required" });
                return;
            }
            const hash = createHash("sha256").update(password).digest("hex");
            const user = await findUserByUsername(username);
            if (!user || user.password_hash !== hash) {
                send(res, 401, { error: "invalid username or password" });
                return;
            }
            const token = await createSession(user.id);
            send(res, 200, { id: user.id, username: user.username, token });
        } catch (e) {
            send(res, 400, { error: "invalid json" });
        }
        return;
    }

    send(res, 404, { error: "not found" });
}).listen(3456, "127.0.0.1", () => {
    console.log("listening on http://127.0.0.1:3456");
});