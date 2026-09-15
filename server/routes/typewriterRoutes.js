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
            send(res, 200, await getTypewriter(user.id));
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
            const content = body.content == null ? "" : String(body.content);
            const last = String(body.last_updated_at ?? body.lastUpdatedAt ?? new Date().toISOString());
            send(res, 200, await upsertTypewriter(user.id, content, last));
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
            const name = String(body.name ?? "image.png");
            const mime = String(body.mime ?? "image/png");
            if (!isSha256(sha)) {
                send(res, 400, { error: "sha256 required" });
                return true;
            }
            if (await hasTypewriterImage(user.id, sha)) {
                send(res, 200, { ok: true, existed: true, sha256: sha });
                return true;
            }
            if (!body.data) {
                send(res, 400, { error: "data required" });
                return true;
            }
            const buf = Buffer.from(String(body.data), "base64");
            const got = createHash("sha256").update(buf).digest("hex");
            if (got !== sha) {
                send(res, 400, { error: "sha mismatch" });
                return true;
            }
            await putTypewriterImage(user.id, sha, name, mime, buf);
            send(res, 200, { ok: true, existed: false, sha256: sha });
        } catch (e) {
            console.error("POST /typewriter/images", e);
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    return false;
}