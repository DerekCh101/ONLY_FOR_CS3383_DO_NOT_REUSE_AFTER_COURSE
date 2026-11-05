/** Rejects task/blocked inserts that overlap existing intervals. */
import { listTasks } from "../db.js";
import { toYmd } from "./httpUtil.js";

export function isExceptionRow(b) {
    const v = b && (b.is_exception ?? b.isException);
    return v === true || v === "t" || v === "true" || v === 1 || v === "1";
}

function weekdayOf(b) {
    if (b == null || b.weekday == null || b.weekday === "") return null;
    const n = Number(b.weekday);
    if (!Number.isFinite(n)) return null;
    if (n === 0) return 7;
    if (n >= 1 && n <= 7) return n;
    return null;
}

function isoDow(ymd) {
    const d = new Date(String(ymd || "") + "T00:00:00");
    if (Number.isNaN(d.getTime())) return null;
    const js = d.getDay();
    return js === 0 ? 7 : js;
}

export function isDailyRow(b) {
    return !isExceptionRow(b) && !toYmd(b.specific_date || b.specificDate) && weekdayOf(b) == null;
}

export function datesCanMeet(a, b) {
    if (a == null || b == null) return false;
    if (isExceptionRow(a) !== isExceptionRow(b)) return false;
    const da = toYmd(a.specific_date || a.specificDate);
    const db = toYmd(b.specific_date || b.specificDate);
    const wa = weekdayOf(a);
    const wb = weekdayOf(b);
    const openA = !da && wa == null;
    const openB = !db && wb == null;
    if (openA || openB) return true;
    if (da && db) return da === db;
    if (da) return wb != null && isoDow(da) === wb;
    if (db) return wa != null && isoDow(db) === wa;
    return wa != null && wb != null && wa === wb;
}

export function clockOverlap(a, b) {
    const a0 = secondsOf(a.start_time ?? a.startTime);
    const a1 = secondsOf(a.end_time ?? a.endTime);
    const b0 = secondsOf(b.start_time ?? b.startTime);
    const b1 = secondsOf(b.end_time ?? b.endTime);
    if (a0 == null || a1 == null || b0 == null || b1 == null) return false;
    if (a0 >= a1 || b0 >= b1) return false;
    return a0 < b1 && b0 < a1;
}

export function describeBlocked(b) {
    const names = ["", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
    const w = weekdayOf(b);
    const when = isExceptionRow(b)
        ? "exception " + (toYmd(b.specific_date || b.specificDate) || names[w] || "date")
        : isDailyRow(b) ? "every day"
            : (toYmd(b.specific_date || b.specificDate) || names[w] || "");
    return (b.title || "blocked") + " " + when + " "
        + (b.start_time || b.startTime) + "–" + (b.end_time || b.endTime);
}

export function blockedConflict(existing, row) {
    for (const b of existing) {
        if (b == null || row == null) continue;
        if (b.id != null && row.id != null && String(b.id) === String(row.id)) continue;
        if (datesCanMeet(b, row) && clockOverlap(b, row)) {
            return "Overlaps existing blocked time: " + describeBlocked(b);
        }
    }
    return null;
}

function secondsOf(t) {
    if (t == null || t === "") return null;
    if (t instanceof Date && !Number.isNaN(t.getTime())) {
        return t.getHours() * 3600 + t.getMinutes() * 60 + t.getSeconds();
    }
    const s = String(t).trim();
    const iso = s.match(/T(\d{1,2}):(\d{2})(?::(\d{2}))?/);
    const m = iso || s.match(/(\d{1,2}):(\d{2})(?::(\d{2}))?/);
    if (!m) return null;
    const h = Number(m[1]);
    const min = Number(m[2]);
    const sec = Number(m[3] || 0);
    if (!Number.isFinite(h) || !Number.isFinite(min) || h > 23 || min > 59 || sec > 59) return null;
    return h * 3600 + min * 60 + sec;
}

export function minutesOf(t) {
    const sec = secondsOf(t);
    return sec == null ? null : Math.floor(sec / 60);
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

function ymdToDays(ymd) {
    const d = new Date(String(ymd || "") + "T12:00:00");
    if (Number.isNaN(d.getTime())) return null;
    return Math.round(d.getTime() / 86400000);
}

function absStart(win) {
    const days = ymdToDays(win.day);
    if (days == null) return null;
    return days * 24 * 60 + win.start;
}

export function nestedFitsParent(nested, parent) {
    const p = taskWindow(parent);
    const q = taskWindow(nested);
    if (!p || !q) return false;
    const p0 = absStart(p);
    const q0 = absStart(q);
    if (p0 == null || q0 == null) return false;
    const p1 = p0 + (p.end - p.start);
    const q1 = q0 + (q.end - q.start);
    return q0 >= p0 && q1 <= p1;
}

export async function taskConflict(userId, task) {
    const win = taskWindow(task);
    if (!win) return null;

    const tasks = await listTasks(userId);
    const pid = String(task.parent_id || task.parentId || "");
    if (pid) {
        const parent = tasks.find((t) => String(t.id) === pid);
        if (!parent) return "Quick event needs its parent task";
        if (!nestedFitsParent(task, parent)) return "Quick event must stay inside the parent task";
        return null;
    }

    for (const t of tasks) {
        if (String(t.id) === String(task.id)) continue;
        const oid = String(t.parent_id || t.parentId || "");
        if (oid) continue;
        const other = taskWindow(t);
        if (!other || !taskWindowsOverlap(win, other)) continue;
        return "Overlaps task: " + (t.title || "task") + " " + other.day + " " +
            String(t.placed_time || "").slice(0, 5);
    }

    return null;
}