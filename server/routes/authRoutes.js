/** Login, register, sessions, account edits, password reset. */
import { randomUUID, createHash } from "node:crypto";
import bcrypt from "bcrypt";
import {
    findUserByUsername,
    findUserByUsernameAndEmail,
    findUserById,
    createUser,
    createSession,
    setSessionVaultWrap,
    userFromToken,
    updateUsername,
    updateUserEmail,
    updateUserPassword,
    updateUserSecurity,
    deleteSessionsForUser,
    deleteSessionById,
    listSessions,
    createResetToken,
    takeResetToken,
    isAccountDeleted,
    deleteAccountFully
} from "../db.js";
import { printReset, sendResetMailLater, sendPasswordChangedMailLater } from "../lib/mail.js";
import { removeUserFiles } from "../lib/userFiles.js";
import { readBody, send, parseJson, pathname, reqUrl } from "../lib/httpUtil.js";
import { derive, wrapSessionKey } from "../lib/vaultCrypto.js";
import { rememberVault, forgetVault, forgetVaultUser, vaultToken } from "../lib/vaultSession.js";
import { passwordReject } from "../lib/passwordRules.js";

const forgotWait = new Map();

async function bindSessionVault(token, uid, password) {
    if (!token || !uid || !password) return;
    const key = await derive(uid, password);
    rememberVault(token, uid, key);
    await setSessionVaultWrap(token, wrapSessionKey(token, key));
}

function sha256Hex(s) {
    return createHash("sha256").update(String(s || ""), "utf8").digest("hex");
}

function normAnswer(s) {
    return String(s || "").trim().toLowerCase().replace(/\s+/g, " ");
}

async function passwordMatches(stored, password) {
    const hash = String(stored || "");
    if (hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$")) {
        return bcrypt.compare(String(password), hash);
    }
    return hash === sha256Hex(password);
}

async function answerMatches(stored, answer) {
    const hash = String(stored || "");
    const a = normAnswer(answer);
    if (!hash || !a) return false;
    if (hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$")) {
        return bcrypt.compare(a, hash);
    }
    return false;
}

function profileOf(user) {
    return {
        id: user.id,
        username: user.username,
        email: user.email || "",
        hasSecurity: !!(user.has_security || user.security_answer_hash),
        securityQuestion: user.security_question || ""
    };
}

function deviceOf(req, body) {
    const fromBody = body && (body.device || body.userAgent);
    if (fromBody) return String(fromBody).slice(0, 120);
    const ua = req.headers && (req.headers["user-agent"] || req.headers["User-Agent"]);
    return ua ? String(ua).slice(0, 120) : "unknown";
}

function bearerOf(req) {
    const h = req.headers ? (req.headers.authorization || req.headers.Authorization || "") : "";
    const s = String(h);
    if (s.toLowerCase().startsWith("bearer ")) return s.slice(7).trim();
    return "";
}

export async function handleAuth(req, res) {
    const path = pathname(req);

    if (req.method === "POST" && path === "/register") {
        try {
            const raw = await readBody(req);
            const body = parseJson(raw);
            const username = String(body.username ?? "").trim();
            const password = String(body.password ?? "").trim();
            const email = String(body.email ?? "").trim().toLowerCase();
            const question = String(body.question ?? body.securityQuestion ?? "").trim();
            const answer = normAnswer(body.answer ?? body.securityAnswer ?? "");
            if (!username || !password) {
                send(res, 400, { error: "username and password required" });
                return true;
            }
            if (username.length > 40) {
                send(res, 400, { error: "username max 40 characters" });
                return true;
            }
            const weak = passwordReject(password);
            if (weak) {
                send(res, 400, { error: weak });
                return true;
            }
            if (!email.includes("@") || !email.includes(".")) {
                send(res, 400, { error: "a real email is required to recover the account" });
                return true;
            }
            if (question.length < 8) {
                send(res, 400, { error: "security question too short" });
                return true;
            }
            if (answer.length < 4) {
                send(res, 400, { error: "security answer too short" });
                return true;
            }
            if (await findUserByUsername(username)) {
                send(res, 409, { error: "username already taken" });
                return true;
            }
            const user = await createUser(
                randomUUID(),
                username,
                await bcrypt.hash(password, 12),
                email,
                question,
                await bcrypt.hash(answer, 12)
            );
            const token = await createSession(user.id, deviceOf(req, body));
            await bindSessionVault(token, user.id, password);
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
            const token = await createSession(user.id, deviceOf(req, body));
            await bindSessionVault(token, user.id, password);
            console.log("POST /login ok", username);
            send(res, 200, { id: user.id, username: user.username, token });
        } catch (e) {
            console.error("POST /login", e);
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "POST" && path === "/vault/unlock") {
        const me = await userFromToken(req);
        if (!me) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            const body = parseJson(await readBody(req));
            const password = String(body.password ?? "").trim();
            if (!password) {
                send(res, 400, { error: "password required" });
                return true;
            }
            const user = await findUserById(me.id);
            if (!user || !(await passwordMatches(user.password_hash, password))) {
                send(res, 403, { error: "Could not unlock. Check the account password." });
                return true;
            }
            await bindSessionVault(vaultToken(req) || bearerOf(req), me.id, password);
            send(res, 200, { ok: true });
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "GET" && path === "/account-status") {
        const userId = String(reqUrl(req).searchParams.get("userId") || "").trim();
        try {
            send(res, 200, { deleted: await isAccountDeleted(userId) });
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "POST" && path === "/me/delete") {
        const me = await userFromToken(req);
        if (!me) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            const body = parseJson(await readBody(req));
            const password = String(body.password ?? body.oldPassword ?? "").trim();
            if (!password) {
                send(res, 400, { error: "password required" });
                return true;
            }
            const user = await findUserById(me.id);
            if (!user || !(await passwordMatches(user.password_hash, password))) {
                send(res, 401, { error: "password is wrong" });
                return true;
            }
            forgetVaultUser(me.id);
            const username = await deleteAccountFully(me.id);
            if (username) {
                try {
                    await removeUserFiles(username);
                } catch (e) {
                    console.error("user-files delete", e);
                }
            }
            send(res, 200, { ok: true, deleted: true });
        } catch (e) {
            console.error("POST /me/delete", e);
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "GET" && path === "/me") {
        const me = await userFromToken(req);
        if (!me) {
            send(res, 401, { error: "login required" });
            return true;
        }
        send(res, 200, profileOf(me));
        return true;
    }

    if (req.method === "POST" && path === "/me/username") {
        const me = await userFromToken(req);
        if (!me) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            const body = parseJson(await readBody(req));
            const username = String(body.username ?? "").trim();
            await updateUsername(me.id, username);
            send(res, 200, { ok: true, username });
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "GET" && path === "/sessions") {
        const me = await userFromToken(req);
        if (!me) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            send(res, 200, { sessions: await listSessions(me.id, bearerOf(req)) });
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "POST" && path === "/sessions/revoke-others") {
        const me = await userFromToken(req);
        if (!me) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            await deleteSessionsForUser(me.id, bearerOf(req));
            forgetVaultUser(me.id, bearerOf(req));
            send(res, 200, { ok: true });
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    const sessionParts = path.split("/").filter(Boolean);
    if (req.method === "DELETE" && sessionParts.length === 2 && sessionParts[0] === "sessions") {
        const me = await userFromToken(req);
        if (!me) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            const gone = await deleteSessionById(me.id, sessionParts[1]);
            if (!gone) {
                send(res, 404, { error: "session not found" });
                return true;
            }
            forgetVault(gone.token);
            send(res, 200, { ok: true, current: gone.token === bearerOf(req) });
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "POST" && path === "/me/email") {
        const me = await userFromToken(req);
        if (!me) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            const body = parseJson(await readBody(req));
            const email = String(body.email ?? "").trim().toLowerCase();
            if (!email.includes("@") || !email.includes(".")) {
                send(res, 400, { error: "a real email is required to recover the account" });
                return true;
            }
            await updateUserEmail(me.id, email);
            send(res, 200, { ok: true, email });
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "POST" && path === "/me/security") {
        const me = await userFromToken(req);
        if (!me) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            const body = parseJson(await readBody(req));
            const question = String(body.question ?? body.securityQuestion ?? "").trim();
            const answer = normAnswer(body.answer ?? body.securityAnswer ?? "");
            if (question.length < 8) {
                send(res, 400, { error: "security question too short" });
                return true;
            }
            if (answer.length < 4) {
                send(res, 400, { error: "security answer too short" });
                return true;
            }
            await updateUserSecurity(me.id, question, await bcrypt.hash(answer, 12));
            send(res, 200, { ok: true, hasSecurity: true, securityQuestion: question });
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "POST" && (path === "/change-password" || path === "/me/password")) {
        const me = await userFromToken(req);
        if (!me) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            const body = parseJson(await readBody(req));
            const oldPassword = String(body.oldPassword ?? body.password ?? "").trim();
            const newPassword = String(body.newPassword ?? "").trim();
            const weak = passwordReject(newPassword);
            if (weak) {
                send(res, 400, { error: weak });
                return true;
            }
            const user = await findUserById(me.id);
            if (!user || !(await passwordMatches(user.password_hash, oldPassword))) {
                send(res, 401, { error: "current password is wrong" });
                return true;
            }
            await updateUserPassword(me.id, await bcrypt.hash(newPassword, 12));
            const keep = (req.headers.authorization || "").replace(/^Bearer\s+/i, "").trim();
            await deleteSessionsForUser(me.id, keep);
            forgetVaultUser(me.id, keep);
            await bindSessionVault(keep, me.id, newPassword);
            const email = String(user.email || "").trim();
            const mailed = email.includes("@");
            if (mailed) {
                sendPasswordChangedMailLater(email, user.username);
            }
            send(res, 200, { ok: true, emailed: mailed });
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "GET" && path === "/challenge-question") {
        const me = await userFromToken(req);
        if (!me) {
            send(res, 401, { error: "login required" });
            return true;
        }
        send(res, 200, {
            hasSecurity: !!me.has_security,
            securityQuestion: me.security_question || ""
        });
        return true;
    }

    if (req.method === "POST" && path === "/challenge-answer") {
        const me = await userFromToken(req);
        if (!me) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            const body = parseJson(await readBody(req));
            const user = await findUserById(me.id);
            const ok = user && await answerMatches(user.security_answer_hash, body.answer);
            if (!ok) {
                send(res, 401, { error: "wrong answer" });
                return true;
            }
            send(res, 200, { ok: true });
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "POST" && path === "/forgot") {
        try {
            const body = parseJson(await readBody(req));
            const username = String(body.username ?? "").trim();
            const email = String(body.email ?? "").trim().toLowerCase();
            const key = username + "|" + email;
            const last = forgotWait.get(key) || 0;
            if (Date.now() - last < 60000) {
                console.log("PASSWORD RESET rate-limited for", username || "(no user)");
                send(res, 200, { ok: true });
                return true;
            }
            forgotWait.set(key, Date.now());
            const user = await findUserByUsernameAndEmail(username, email);
            const out = { ok: true };
            if (user && user.security_answer_hash) {
                const token = await createResetToken(user.id);
                printReset(user.email, token, user.security_question);
                send(res, 200, out);
                sendResetMailLater(user.email, token, user.security_question);
                return true;
            } else {
                console.log("PASSWORD RESET no matching account with a security question");
            }
            send(res, 200, out);
        } catch (e) {
            send(res, 200, { ok: true });
        }
        return true;
    }

    if (req.method === "POST" && path === "/reset") {
        try {
            const body = parseJson(await readBody(req));
            const token = String(body.token ?? body.resetToken ?? "").trim();
            const answer = body.answer ?? body.securityAnswer ?? "";
            const newPassword = String(body.newPassword ?? "").trim();
            const weak = passwordReject(newPassword);
            if (weak) {
                send(res, 400, { error: weak });
                return true;
            }
            const row = await takeResetToken(token);
            if (!row) {
                send(res, 400, { error: "reset token invalid or expired" });
                return true;
            }
            const user = await findUserById(row.user_id);
            if (!user || !user.security_answer_hash) {
                send(res, 400, { error: "reset needs a security question" });
                return true;
            }
            if (!(await answerMatches(user.security_answer_hash, answer))) {
                send(res, 401, { error: "wrong security answer" });
                return true;
            }
            await updateUserPassword(user.id, await bcrypt.hash(newPassword, 12));
            await deleteSessionsForUser(user.id);
            send(res, 200, { ok: true });
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    return false;
}
