/** Rejects task/blocked inserts that overlap existing intervals. */
import { listTasks, listBlocked } from "../db.js";
import { toYmd, normTime } from "./httpUtil.js";

export function isExceptionRow(b) {
    return b.is_exception === true || b.is_exception === "t" || b.is_exception === "true" || b.is_exception === 1;
}

function weekdayOf(b) {
    if (b == null || b.weekday == null || b.weekday === "") return null;
    const n = Number(b.weekday);
    return Number.isFinite(n) ? n : null;
}

function isoDow(ymd) {
    const d = new Date(String(ymd || "") + "T00:00:00");
    if (Number.isNaN(d.getTime())) return null;
    const js = d.getDay();
    return js === 0 ? 7 : js;
}

export function isDailyRow(b) {
    return !isExceptionRow(b) && !toYmd(b.specific_date) && weekdayOf(b) == null;
}

export function sameLayer(a, b) {
    if (isExceptionRow(a) !== isExceptionRow(b)) return false;
    const da = toYmd(a.specific_date);
    const db = toYmd(b.specific_date);
    if (da || db) return da === db;
    const wa = weekdayOf(a);
    const wb = weekdayOf(b);
    if (wa != null || wb != null) return wa === wb;
    return isDailyRow(a) && isDailyRow(b);
}

export function clockOverlap(a, b) {
    return normTime(a.start_time) < normTime(b.end_time)
        && normTime(b.start_time) < normTime(a.end_time);
}

export function describeBlocked(b) {
    const names = ["", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
    const w = weekdayOf(b);
    const when = isExceptionRow(b)
        ? "exception " + (toYmd(b.specific_date) || names[w] || "date")
        : isDailyRow(b) ? "every day"
            : (toYmd(b.specific_date) || names[w] || "");
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
    const dated = toYmd(b.specific_date);
    if (dated) return dated === day;
    const w = weekdayOf(b);
    if (w != null) return w === isoDow(day);
    return isDailyRow(b);
}

function addDays(ymd, n) {
    const d = new Date(String(ymd || "") + "T12:00:00");
    if (Number.isNaN(d.getTime())) return ymd;
    d.setDate(d.getDate() + n);
    const pad = (x) => String(x).padStart(2, "0");
    return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
}

function columnOf(day, startMin) {
    return startMin < 6 * 60 ? addDays(day, -1) : day;
}

function gridOffset(startMin) {
    return startMin >= 6 * 60 ? startMin - 6 * 60 : 18 * 60 + startMin;
}

const COL_MIN = 24 * 60;

export function taskWindow(task) {
    const day = toYmd(task.placed_date || task.placedDate);
    const start = minutesOf(task.placed_time || task.placedTime);
    const dur = Number(task.duration || 0);
    if (!day || start == null || !(dur > 0)) return null;
    const durMin = Math.round(dur * 60);
    const col = columnOf(day, start);
    const grid0 = gridOffset(start);
    return {
        day,
        start,
        end: start + durMin,
        col,
        grid0,
        grid1: grid0 + durMin
    };
}

function gridPieces(win) {
    const pieces = [{ col: win.col, a: win.grid0, b: Math.min(win.grid1, COL_MIN) }];
    if (win.grid1 > COL_MIN) {
        pieces.push({ col: addDays(win.col, 1), a: 0, b: win.grid1 - COL_MIN });
    }
    return pieces;
}

function taskWindowsOverlap(a, b) {
    for (const p of gridPieces(a)) {
        for (const q of gridPieces(b)) {
            if (p.col === q.col && rangesOverlap(p.a, p.b, q.a, q.b)) return true;
        }
    }
    return false;
}

function clockOnDay(win, day) {
    if (day === win.day) return { a: win.start, b: Math.min(win.end, COL_MIN) };
    if (win.end > COL_MIN && day === addDays(win.day, 1)) {
        return { a: 0, b: win.end - COL_MIN };
    }
    return null;
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
        if (!other || !taskWindowsOverlap(win, other)) continue;
        return "Overlaps task: " + (t.title || "task") + " " + other.day + " " +
            String(t.placed_time || "").slice(0, 5);
    }

    // Fixed and Deadline may occupy blocked time. Flexible may not.
    const state = String(task.state ?? "").trim().toLowerCase();
    const flexible = state === "flexible" || state === "3";
    if (!flexible) return null;

    const days = [win.day];
    if (win.end > COL_MIN) days.push(addDays(win.day, 1));

    for (const day of days) {
        const clock = clockOnDay(win, day);
        if (!clock) continue;
        for (const b of blocked) {
            if (isExceptionRow(b)) continue;
            if (!blockedAppliesOn(b, day)) continue;
            const b0 = minutesOf(b.start_time);
            const b1 = minutesOf(b.end_time);
            if (b0 == null || b1 == null) continue;
            if (!rangesOverlap(clock.a, clock.b, b0, b1)) continue;
            const freed = blocked.some((ex) => {
                if (!isExceptionRow(ex) || !blockedAppliesOn(ex, day)) return false;
                const e0 = minutesOf(ex.start_time);
                const e1 = minutesOf(ex.end_time);
                return e0 != null && e1 != null && rangesOverlap(clock.a, clock.b, e0, e1);
            });
            if (freed) continue;
            return "Overlaps blocked: " + describeBlocked(b);
        }
    }
    return null;
}