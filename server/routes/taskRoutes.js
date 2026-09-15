import { validateTask } from "../HelperMethods.js";
import { userFromToken, listTasks, upsertTask, deleteTask } from "../db.js";
import { readBody, send, parseJson, pathname, toYmd } from "../lib/httpUtil.js";
import { taskConflict } from "../lib/overlap.js";

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
            send(res, 200, { tasks: await listTasks(user.id) });
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
            const clash = await taskConflict(authUser.id, task);
            if (clash) {
                send(res, 409, { error: clash });
                return true;
            }
            await upsertTask(task);
            send(res, 201, task);
        } catch (e) {
            send(res, 400, { error: String(e.message || e) });
        }
        return true;
    }

    return false;
}