/** Per-session DEK so a logged-in client sees plaintext; Postgres still stores AP1. */
import { open, seal, unwrapSessionKey } from "./vaultCrypto.js";
import { getSessionVault, userHasSealedData } from "../db.js";

const sessions = new Map();

export function vaultToken(req) {
    const h = req && req.headers ? (req.headers.authorization || req.headers.Authorization || "") : "";
    const s = String(h);
    if (s.toLowerCase().startsWith("bearer ")) return s.slice(7).trim();
    return "";
}

export function rememberVault(token, uid, key) {
    if (!token || !key) return;
    sessions.set(String(token), { uid: String(uid || ""), key });
}

export function forgetVault(token) {
    if (token) sessions.delete(String(token));
}

export function forgetVaultUser(uid, keepToken) {
    const id = String(uid || "");
    const keep = keepToken ? String(keepToken) : "";
    for (const [t, row] of sessions) {
        if (row.uid === id && t !== keep) sessions.delete(t);
    }
}

export function vaultKey(req) {
    const t = vaultToken(req);
    const row = t ? sessions.get(t) : null;
    return row ? row.key : null;
}

export async function ensureVault(req) {
    let key = vaultKey(req);
    if (key) return key;
    const t = vaultToken(req);
    if (!t) return null;
    const row = await getSessionVault(t);
    if (!row || !row.vault_wrap) return null;
    key = unwrapSessionKey(t, row.vault_wrap);
    if (!key) return null;
    rememberVault(t, row.uid, key);
    return key;
}

async function writesSealed(req) {
    const t = vaultToken(req);
    if (!t) return false;
    const mem = sessions.get(t);
    const uid = mem ? mem.uid : null;
    const row = uid ? null : await getSessionVault(t);
    const id = uid || (row && row.uid);
    if (!id) return false;
    return userHasSealedData(id);
}

export async function revealPayload(req, json) {
    const key = await ensureVault(req);
    if (!key || !json) return json;
    if (Array.isArray(json.tasks)) {
        json.tasks.forEach((t) => openTask(key, t));
    }
    if (Array.isArray(json.nested)) {
        json.nested.forEach((t) => openTask(key, t));
    }
    if (Array.isArray(json.blocked)) {
        json.blocked.forEach((b) => {
            if (b) b.title = open(key, b.title);
        });
    }
    if (typeof json.content === "string") {
        json.content = openTypewriter(key, json.content);
    }
    return json;
}

function walkTw(key, node, fn) {
    if (Array.isArray(node)) {
        node.forEach((x) => walkTw(key, x, fn));
        return;
    }
    if (!node || typeof node !== "object") return;
    if (typeof node.title === "string") node.title = fn(key, node.title);
    if (typeof node.body === "string") node.body = fn(key, node.body);
    if (typeof node.text === "string") node.text = fn(key, node.text);
    Object.keys(node).forEach((k) => {
        if (k === "title" || k === "body" || k === "text") return;
        const v = node[k];
        if (v && typeof v === "object") walkTw(key, v, fn);
    });
}

function openTypewriter(key, raw) {
    const s = String(raw == null ? "" : raw);
    if (s.startsWith("AP1.")) return open(key, s);
    try {
        const o = JSON.parse(s);
        walkTw(key, o, open);
        return JSON.stringify(o);
    } catch {
        return open(key, s);
    }
}

export async function hideTask(req, task) {
    const key = await ensureVault(req);
    if (!key || !task || !(await writesSealed(req))) return task;
    if ("title" in task) task.title = seal(key, task.title);
    if ("taskName" in task) task.taskName = seal(key, task.taskName);
    if ("description" in task) task.description = seal(key, task.description);
    return task;
}

export async function hideBlocked(req, row) {
    const key = await ensureVault(req);
    if (!key || !row || !(await writesSealed(req))) return row;
    if ("title" in row) row.title = seal(key, row.title);
    return row;
}

export async function hideTypewriter(req, content) {
    const key = await ensureVault(req);
    if (!key || content == null || !(await writesSealed(req))) return content;
    const raw = String(content);
    if (raw.startsWith("AP1.")) return raw;
    try {
        const o = JSON.parse(raw);
        walkTw(key, o, seal);
        return JSON.stringify(o);
    } catch {
        return seal(key, raw);
    }
}

function openTask(key, t) {
    if (!t) return t;
    t.title = open(key, t.title);
    t.taskName = t.title;
    t.description = open(key, t.description);
    return t;
}
