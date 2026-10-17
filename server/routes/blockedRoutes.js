/** HTTP list/upsert/delete for blocked time. */
import { randomUUID } from "node:crypto";
import { userFromToken, listBlocked, upsertBlocked, deleteBlocked } from "../db.js";
import { readBody, send, parseJson, pathname, toYmd } from "../lib/httpUtil.js";
import { blockedConflict } from "../lib/overlap.js";

export async function handleBlocked(req, res) {
    const path = pathname(req);
    const parts = path.split("/").filter(Boolean);

    if (req.method === "GET" && path === "/blocked") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            send(res, 200, { blocked: await listBlocked(user.id) });
        } catch (e) {
            send(res, 500, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "DELETE" && parts.length === 2 && parts[0] === "blocked") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return true;
        }
        const removed = await deleteBlocked(user.id, parts[1]);
        if (!removed) {
            send(res, 404, { error: "blocked not found" });
            return true;
        }
        send(res, 200, removed);
        return true;
    }

    if (req.method === "POST" && path === "/blocked") {
        try {
            const raw = await readBody(req);
            const body = parseJson(raw);
            const authUser = await userFromToken(req);
            if (!authUser) {
                send(res, 401, { error: "login required" });
                return true;
            }
            const isException = body.is_exception === true
                || body.is_exception === "true"
                || body.is_exception === 1;
            const specific = toYmd(body.specific_date || body.specificDate) || null;
            const start = String(body.start_time ?? "").trim() || null;
            const end = String(body.end_time ?? "").trim() || null;
            if (!start || !end) {
                send(res, 400, { error: "start_time and end_time required" });
                return true;
            }
            const weekday = body.weekday == null || body.weekday === "" ? null : Number(body.weekday);
            const hasWeekday = weekday != null && weekday >= 1 && weekday <= 7;
            if (isException && !specific && !hasWeekday) {
                send(res, 400, { error: "exception needs a date or weekday" });
                return true;
            }
            if (weekday != null && !hasWeekday) {
                send(res, 400, { error: "weekday must be 1–7 (Mon–Sun)" });
                return true;
            }
            let title = String(body.title ?? "").trim() || "blocked";
            if (!title.startsWith("AP1.") && title.length > 100) title = title.slice(0, 100);
            const row = {
                id: String(body.id ?? randomUUID()).trim(),
                title,
                weekday: hasWeekday ? weekday : null,
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
            const already = existing.some((b) => String(b.id) === String(row.id));
            if (specific && !already) {
                const p = String(end).split(":");
                const hh = String(p[0] || "23").padStart(2, "0");
                const mm = String(p[1] || "59").padStart(2, "0");
                const until = new Date(specific + "T" + hh + ":" + mm + ":00");
                if (!Number.isNaN(until.getTime()) && until.getTime() <= Date.now()) {
                    send(res, 400, { error: "Cannot add a blocked time that has already ended." });
                    return true;
                }
            }
            const clash = blockedConflict(existing, row);
            if (clash) {
                send(res, 400, { error: clash });
                return true;
            }
            await upsertBlocked(row);
            send(res, 201, row);
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    return false;
}