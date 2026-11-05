/** HTTP get/put for Typewriter bundle and images. */
import { createHash } from "node:crypto";
import {
    userFromToken,
    getTypewriter,
    upsertTypewriter,
    hasTypewriterImage,
    putTypewriterImage,
    getTypewriterImage,
} from "../db.js";
import { readBody, send, parseJson, pathname, reqUrl, isSha256 } from "../lib/httpUtil.js";
import { saveUserImage } from "../lib/userFiles.js";
import { stripImageMeta } from "../lib/stripImageMeta.js";
import { sanitizeTypewriterContent } from "../lib/sanitizeHtml.js";
import { revealPayload, hideTypewriter } from "../lib/vaultSession.js";

/** New image writes per user in a rolling minute. Existing hashes do not count. */
const imageWriteTimes = new Map();
const IMAGE_WRITE_WINDOW_MS = 60000;
const IMAGE_WRITE_MAX = 30;

function tooManyNewImages(userId) {
    const now = Date.now();
    const prev = (imageWriteTimes.get(userId) || []).filter((t) => now - t < IMAGE_WRITE_WINDOW_MS);
    if (prev.length >= IMAGE_WRITE_MAX) {
        imageWriteTimes.set(userId, prev);
        return true;
    }
    prev.push(now);
    imageWriteTimes.set(userId, prev);
    return false;
}

export async function handleTypewriter(req, res) {
    const path = pathname(req);
    const parts = path.split("/").filter(Boolean);

    if (req.method === "GET" && path === "/typewriter") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            const payload = await revealPayload(req, await getTypewriter(user.id));
            if (payload && typeof payload.content === "string" && !payload.content.startsWith("AP1.")) {
                payload.content = sanitizeTypewriterContent(payload.content);
            }
            send(res, 200, payload);
        } catch (e) {
            console.error("GET /typewriter", e);
            send(res, 500, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "POST" && path === "/typewriter") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            const raw = await readBody(req);
            const body = parseJson(raw);
            const incoming = body.content == null ? "" : String(body.content);
            let previous = "";
            try {
                const cur = await revealPayload(req, await getTypewriter(user.id));
                if (cur && typeof cur.content === "string" && !cur.content.startsWith("AP1.")) {
                    previous = cur.content;
                }
            } catch { /* keep incoming */ }
            const cleaned = sanitizeTypewriterContent(incoming, previous);
            const content = await hideTypewriter(req, cleaned);
            const last = String(body.last_updated_at ?? body.lastUpdatedAt ?? new Date().toISOString());
            send(res, 200, await revealPayload(req, await upsertTypewriter(user.id, content, last)));
        } catch (e) {
            console.error("POST /typewriter", e);
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "GET" && parts.length === 3 && parts[0] === "typewriter" && parts[1] === "images") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return true;
        }
        const sha = String(parts[2] || "").toLowerCase();
        if (!isSha256(sha)) {
            send(res, 400, { error: "bad sha" });
            return true;
        }
        try {
            const row = await getTypewriterImage(user.id, sha);
            if (!row) {
                send(res, 404, { error: "missing" });
                return true;
            }
            const meta = reqUrl(req).searchParams.get("meta") === "1";
            if (meta) {
                send(res, 200, { exists: true, sha256: row.sha256, name: row.name, mime: row.mime });
                return true;
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
        return true;
    }

    if (req.method === "POST" && path === "/typewriter/images") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            const raw = await readBody(req);
            const body = parseJson(raw);
            const sha = String(body.sha256 ?? body.sha ?? "").toLowerCase();
            let name = String(body.name ?? "image.png");
            if (name.length > 120) {
                const dot = name.lastIndexOf(".");
                const ext = dot >= 0 ? name.slice(dot) : "";
                name = name.slice(0, Math.max(1, 120 - ext.length)) + ext;
            }
            const mime = String(body.mime ?? "image/png");
            const kind = String(body.kind ?? body.folder ?? "diary");
            if (!isSha256(sha)) {
                send(res, 400, { error: "sha256 required" });
                return true;
            }
            if (!body.data) {
                send(res, 400, { error: "data required" });
                return true;
            }
            const dataStr = String(body.data);
            if (dataStr.length > 11 * 1024 * 1024) {
                send(res, 413, { error: "image too large" });
                return true;
            }
            const buf = Buffer.from(dataStr, "base64");
            if (buf.length > 8 * 1024 * 1024) {
                send(res, 413, { error: "image too large (8 MB max)" });
                return true;
            }
            const cleaned = stripImageMeta(buf, name, mime);
            if (!cleaned.bytes || cleaned.bytes.length < 24
                    || cleaned.mime === "application/octet-stream") {
                send(res, 400, { error: "could not strip image metadata" });
                return true;
            }
            const cleanSha = createHash("sha256").update(cleaned.bytes).digest("hex");
            if (await hasTypewriterImage(user.id, cleanSha)) {
                send(res, 200, {
                    ok: true, existed: true, sha256: cleanSha,
                    name: cleaned.name, mime: cleaned.mime
                });
                return true;
            }
            if (tooManyNewImages(user.id)) {
                send(res, 429, { error: "too many image uploads, wait a minute" });
                return true;
            }
            if (cleaned.bytes.length > 8 * 1024 * 1024) {
                send(res, 413, { error: "image too large (8 MB max)" });
                return true;
            }
            try {
                await saveUserImage(user.username, kind, cleanSha, cleaned.bytes, cleaned.name, cleaned.mime);
            } catch (diskErr) {
                console.error("user image folder write", diskErr);
                send(res, 400, { error: String(diskErr.message || diskErr) });
                return true;
            }
            await putTypewriterImage(user.id, cleanSha, cleaned.name, cleaned.mime, cleaned.bytes);
            send(res, 200, {
                ok: true, existed: false, sha256: cleanSha,
                name: cleaned.name, mime: cleaned.mime
            });
        } catch (e) {
            console.error("POST /typewriter/images", e);
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    return false;
}