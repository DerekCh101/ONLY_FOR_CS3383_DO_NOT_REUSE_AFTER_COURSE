const UUID_RE =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const TIME_RE = /^\d{2}:\d{2}(:\d{2})?$/;

const STATES = new Set(["Fixed", "Deadline", "Flexible"]);
const LIFECYCLES = new Set(["UNSCHEDULED", "PENDING", "ONGOING", "PAST"]);
const WORKFLOWS = new Set(["UNFINISHED", "FINISHED"]);

function isIsoInstant(s) {
    return !Number.isNaN(Date.parse(s));
}

function isValidDate(s) {
    if (!DATE_RE.test(s)) return false;
    const d = new Date(s + "T00:00:00");
    return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s;
}

function isValidTime(s) {
    if (!TIME_RE.test(s)) return false;
    const [hh, mm, ss = "00"] = s.split(":");
    const h = Number(hh), m = Number(mm), sec = Number(ss);
    return h >= 0 && h <= 23 && m >= 0 && m <= 59 && sec >= 0 && sec <= 59;
}

function parseDuration(raw) {
    if (typeof raw === "number") return raw;
    if (typeof raw !== "string" || raw.trim() === "") return NaN;
    return Number(raw.trim());
}

/**
 * Shape / enum / date checks only.
 * Does NOT enforce blocked time (user may place Flexible on blocked slots;
 * the Java scheduler is what obeys blocked time).
 *
 * @returns {string|null} error, or null if OK
 */
export function validateTask(body) {
    if (!body || typeof body !== "object" || Array.isArray(body)) {
        return "body must be a JSON object";
    }

    const id = String(body.id ?? "").trim();
    const title = String(body.title ?? "").trim();
    const duration = parseDuration(body.duration);
    const state = String(body.state ?? "").trim();
    const placedDate = String(body.placed_date ?? "").trim();
    const placedTime = String(body.placed_time ?? "").trim();
    const deadlineDate = String(body.deadline_date ?? "").trim();
    const deadlineTime = String(body.deadline_time ?? "").trim();
    const lifecycle = String(body.lifecycle ?? "").trim();
    const workflow = String(body.workflow ?? "").trim();
    const placementUpdatedAt = String(
        body.placementUpdatedAt ?? body.placement_updated_at ?? ""
    ).trim();
    const lastUpdatedAt = String(
        body.lastUpdatedAt ?? body.last_updated_at ?? ""
    ).trim();
    const userId = String(body.userId ?? body.user_id ?? "").trim();

    if (!id) return "id required";
    if (!UUID_RE.test(id)) return "id must be a UUID";

    if (!title) return "title required";
    if (title.length > 100) return "title max 100 characters";

    if (!Number.isFinite(duration)) return "duration required and must be a number";
    if (duration <= 0) return "duration must be > 0";
    if (duration > 24) return "duration must be <= 24 hours";
    if (Math.round(duration * 2) !== duration * 2) {
        return "duration must be in 0.5 hour steps";
    }

    if (!state) return "state required";
    if (!STATES.has(state)) return "state must be Fixed, Deadline, or Flexible";

    if (!lifecycle) return "lifecycle required";
    if (!LIFECYCLES.has(lifecycle)) {
        return "lifecycle must be UNSCHEDULED, PENDING, ONGOING, or PAST";
    }

    if (!workflow) return "workflow required";
    if (!WORKFLOWS.has(workflow)) return "workflow must be UNFINISHED or FINISHED";

    const scheduled = lifecycle !== "UNSCHEDULED";
    if (scheduled) {
        if (!placedDate) return "scheduled task must have placed_date";
        if (!placedTime) return "scheduled task must have placed_time";
    }
    if (placedDate && !isValidDate(placedDate)) return "placed_date must be YYYY-MM-DD";
    if (placedTime && !isValidTime(placedTime)) return "placed_time must be HH:mm or HH:mm:ss";
    if ((placedDate && !placedTime) || (!placedDate && placedTime)) {
        return "placed_date and placed_time must both be set or both empty";
    }

    if (state === "Deadline") {
        if (!deadlineDate || !deadlineTime) {
            return "Deadline task must have deadline_date and deadline_time";
        }
    }
    if (deadlineDate && !isValidDate(deadlineDate)) return "deadline_date must be YYYY-MM-DD";
    if (deadlineTime && !isValidTime(deadlineTime)) return "deadline_time must be HH:mm or HH:mm:ss";
    if ((deadlineDate && !deadlineTime) || (!deadlineDate && deadlineTime)) {
        return "deadline_date and deadline_time must both be set or both empty";
    }

    if (placementUpdatedAt && !isIsoInstant(placementUpdatedAt)) {
        return "placementUpdatedAt must be an ISO timestamp";
    }
    if (lastUpdatedAt && !isIsoInstant(lastUpdatedAt)) {
        return "lastUpdatedAt must be an ISO timestamp";
    }

    if (!userId) return "user_id required";
    if (!UUID_RE.test(userId)) return "user_id must be a UUID";

    return null;
}