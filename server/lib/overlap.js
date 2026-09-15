import { listTasks, listBlocked } from "../db.js";
import { toYmd, normTime } from "./httpUtil.js";

export function isExceptionRow(b) {
    return b.is_exception === true || b.is_exception === "t" || b.is_exception === "true" || b.is_exception === 1;
}

export function isDailyRow(b) {
    return !isExceptionRow(b) && !toYmd(b.specific_date);
}

export function sameLayer(a, b) {
    if (isExceptionRow(a) !== isExceptionRow(b)) return false;
    if (isExceptionRow(a)) return toYmd(a.specific_date) === toYmd(b.specific_date);
    if (isDailyRow(a) && isDailyRow(b)) return true;
    if (isDailyRow(a) || isDailyRow(b)) return false;
    return toYmd(a.specific_date) === toYmd(b.specific_date);
}

export function clockOverlap(a, b) {
    return normTime(a.start_time) < normTime(b.end_time)
        && normTime(b.start_time) < normTime(a.end_time);
}

export function describeBlocked(b) {
    const when = isExceptionRow(b)
        ? "exception " + toYmd(b.specific_date)
        : isDailyRow(b) ? "every day" : toYmd(b.specific_date);
    return (b.title || "blocked") + " " + when + " " + b.start_time + "–" + b.end_time;
}

export function blockedConflict(existing, row) {
    for (const b of existing) {
        if (String(b.id) === String(row.id)) continue;
        if (sameLayer(b, row) && clockOverlap(b, row)) {
            return "Overlaps existing: " + describeBlocked(b);
        }
    }
    return null;
}

export function minutesOf(t) {
    const s = normTime(t);
    const p = String(s || "").split(":");
    if (p.length < 2) return null;
    const h = Number(p[0]);
    const m = Number(p[1]);
    if (!Number.isFinite(h) || !Number.isFinite(m)) return null;
    return h * 60 + m;
}

export function rangesOverlap(a0, a1, b0, b1) {
    return a0 < b1 && b0 < a1;
}

export function blockedAppliesOn(b, day) {
    if (isExceptionRow(b)) return toYmd(b.specific_date) === day;
    if (isDailyRow(b)) return true;
    return toYmd(b.specific_date) === day;
}

export function taskWindow(task) {
    const day = toYmd(task.placed_date || task.placedDate);
    const start = minutesOf(task.placed_time || task.placedTime);
    const dur = Number(task.duration || 0);
    if (!day || start == null || !(dur > 0)) return null;
    return { day, start, end: start + dur };
}

export async function taskConflict(userId, task) {
    const win = taskWindow(task);
    if (!win) return null;

    const [tasks, blocked] = await Promise.all([
        listTasks(userId),
        listBlocked(userId)
    ]);

    for (const t of tasks) {
        if (String(t.id) === String(task.id)) continue;
        const other = taskWindow(t);
        if (!other || other.day !== win.day) continue;
        if (rangesOverlap(win.start, win.end, other.start, other.end)) {
            return "Overlaps task: " + (t.title || "task") + " " + other.day + " " +
                String(t.placed_time || "").slice(0, 5);
        }
    }

    for (const b of blocked) {
        if (!blockedAppliesOn(b, win.day)) continue;
        const b0 = minutesOf(b.start_time);
        const b1 = minutesOf(b.end_time);
        if (b0 == null || b1 == null) continue;
        if (rangesOverlap(win.start, win.end, b0, b1)) {
            return "Overlaps blocked: " + describeBlocked(b);
        }
    }
    return null;
}