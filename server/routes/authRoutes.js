import { randomUUID, createHash } from "node:crypto";
import bcrypt from "bcrypt";
import { findUserByUsername, createUser, createSession } from "../db.js";
import { readBody, send, parseJson, pathname } from "../lib/httpUtil.js";

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

export async function handleAuth(req, res) {
    const path = pathname(req);

    if (req.method === "POST" && path === "/register") {
        try {
            const raw = await readBody(req);
            const body = parseJson(raw);
            const username = String(body.username ?? "").trim();
            const password = String(body.password ?? "").trim();
            if (!username || !password) {
                send(res, 400, { error: "username and password required" });
                return true;
            }
            if (username.length > 40) {
                send(res, 400, { error: "username max 40 characters" });
                return true;
            }
            if (password.length < 4) {
                send(res, 400, { error: "password min 4 characters" });
                return true;
            }
            if (await findUserByUsername(username)) {
                send(res, 409, { error: "username already taken" });
                return true;
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
        return true;
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
                return true;
            }
            const user = await findUserByUsername(username);
            if (!user) {
                console.log("POST /login no user", username);
                send(res, 401, { error: "invalid username or password" });
                return true;
            }
            if (!(await passwordMatches(user.password_hash, password))) {
                console.log("POST /login bad password", username);
                send(res, 401, { error: "invalid username or password" });
                return true;
            }
            const token = await createSession(user.id);
            console.log("POST /login ok", username);
            send(res, 200, { id: user.id, username: user.username, token });
        } catch (e) {
            console.error("POST /login", e);
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    return false;
}