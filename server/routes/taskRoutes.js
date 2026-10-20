/** HTTP list/upsert/delete for calendar tasks. */
import { validateTask } from "../HelperMethods.js";
import { userFromToken, listTasks, upsertTask, deleteTask } from "../db.js";
import { readBody, send, parseJson, pathname, toYmd, normTime } from "../lib/httpUtil.js";
import { taskConflict } from "../lib/overlap.js";
import { revealPayload, hideTask } from "../lib/vaultSession.js";

function clockMs(ymd, hm) {
    const day = toYmd(ymd);
    const s = String(hm || "").trim();
    const p = s.split(":");
    if (!day || p.length < 2) return null;
    const t = new Date(day + "T" + String(p[0]).padStart(2, "0") + ":" + String(p[1]).padStart(2, "0") + ":00");
    return Number.isNaN(t.getTime()) ? null : t.getTime();
}

export async function handleTasks(req, res) {
    const path = pathname(req);
    const parts = path.split("/").filter(Boolean);

    if (req.method === "GET" && path === "/tasks") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return true;
        }
        try {
            const rows = await listTasks(user.id);
            const tasks = [];
            const nested = [];
            for (const t of rows || []) {
                if (!t) continue;
                if (t.parent_id || t.parentId) nested.push(t);
                else tasks.push(t);
            }
            send(res, 200, await revealPayload(req, { tasks, nested }));
        } catch (e) {
            send(res, 500, { error: String(e.message || e) });
        }
        return true;
    }

    if (req.method === "DELETE" && parts.length === 2 && parts[0] === "tasks") {
        const user = await userFromToken(req);
        if (!user) {
            send(res, 401, { error: "login required" });
            return true;
        }
        const removed = await deleteTask(user.id, parts[1]);
        if (!removed) {
            send(res, 404, { error: "task not found" });
            return true;
        }
        send(res, 200, removed);
        return true;
    }

    if (req.method === "POST" && path === "/tasks") {
        try {
            const raw = await readBody(req);
            const body = parseJson(raw);
            const authUser = await userFromToken(req);
            if (!authUser) {
                send(res, 401, { error: "login required" });
                return true;
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
                return true;
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
            if (Object.prototype.hasOwnProperty.call(body, "description")) {
                task.description = body.description;
            }
            if (Object.prototype.hasOwnProperty.call(body, "description_done")
                    || Object.prototype.hasOwnProperty.call(body, "descriptionDone")) {
                task.description_done = body.description_done ?? body.descriptionDone;
            }
            if (Object.prototype.hasOwnProperty.call(body, "parent_id")
                    || Object.prototype.hasOwnProperty.call(body, "parentId")) {
                task.parent_id = body.parent_id ?? body.parentId;
            }
            const existing = (await listTasks(authUser.id))
                .find((row) => String(row.id) === String(task.id));
            const sameStart = !!(existing
                && toYmd(existing.placed_date) === toYmd(task.placed_date)
                && normTime(existing.placed_time || "") === normTime(task.placed_time || ""));
            const start = clockMs(task.placed_date, task.placed_time);
            if (!sameStart && start != null && start <= Date.now()) {
                send(res, 400, { error: "Cannot create a task in the past." });
                return true;
            }
            if (task.state === "Deadline") {
                const sameDeadline = !!(existing
                    && toYmd(existing.deadline_date) === toYmd(task.deadline_date)
                    && normTime(existing.deadline_time || "") === normTime(task.deadline_time || ""));
                const due = clockMs(task.deadline_date, task.deadline_time);
                if (!sameDeadline && due != null && due <= Date.now()) {
                    send(res, 400, { error: "Cannot set deadline in the past." });
                    return true;
                }
            }
            const clash = await taskConflict(authUser.id, task);
            if (clash) {
                send(res, 409, { error: clash });
                return true;
            }
            await hideTask(req, task);
            await upsertTask(task);
            send(res, 201, (await revealPayload(req, { tasks: [task] })).tasks[0]);
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    return false;
}