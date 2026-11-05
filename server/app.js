/** Browser UI for the same Node API the JavaFX client uses. */
const err = document.getElementById("err");
const errAdd = document.getElementById("errAdd");
const errBlocked = document.getElementById("errBlocked");
const auth = document.getElementById("auth");
const app = document.getElementById("app");

applyTheme();

let twBundle = emptyBundle();
let twOpen = null;
let lastTwSent = "";
let allTasks = [];
let allBlocked = [];
let weekOffset = 0;
let monthOffset = 0;
let taskWhen = "all";
let taskKind = "all";
let taskPage = 0;
const TASK_PAGE_SIZE = 12;
let reuseTaskId = "";
let pendingQuickParent = "";
let selectedTaskId = "";
let fillingDesc = false;
let secondsClock = null;
let findLevel = "year";
let findYear = null;
let findMonth = null;
let toastTimer = null;
let loginBusy = false;
let carrying = false;
const MONTHS = ["January","February","March","April","May","June","July","August","September","October","November","December"];

function emptyBundle() {
    return { diary: [], lists: [], pages: [] };
}

const ASHORE_PASSWORD_HINT =
    "Password needs 8+ characters, upper and lower case, a number, and a symbol.";

function ashorePasswordReject(raw) {
    const s = String(raw ?? "");
    if (s.length < 8) return ASHORE_PASSWORD_HINT;
    if (!/[a-z]/.test(s)) return ASHORE_PASSWORD_HINT;
    if (!/[A-Z]/.test(s)) return ASHORE_PASSWORD_HINT;
    if (!/[0-9]/.test(s)) return ASHORE_PASSWORD_HINT;
    if (!/[^A-Za-z0-9]/.test(s)) return ASHORE_PASSWORD_HINT;
    return null;
}
window.ashorePasswordReject = ashorePasswordReject;
window.ASHORE_PASSWORD_HINT = ASHORE_PASSWORD_HINT;

function esc(s) {
    return String(s ?? "").replace(/[&<>"']/g, (c) => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    }[c]));
}

function clipTitle(s) {
    const n = String(s ?? "");
    if (n.startsWith("AP1.")) return n;
    const t = n.trim();
    return t.length > 200 ? t.slice(0, 200) : t;
}

function clipDesc(s) {
    const n = String(s ?? "");
    if (n.startsWith("AP1.")) return n;
    return n.length > 8000 ? n.slice(0, 8000) : n;
}

function clipBody(s) {
    const n = String(s ?? "");
    if (n.startsWith("AP1.")) return n;
    return n.length > 100000 ? n.slice(0, 100000) : n;
}

function clipImageName(name) {
    const raw = String(name || "image.png");
    if (raw.length <= 120) return raw;
    const dot = raw.lastIndexOf(".");
    const ext = dot >= 0 ? raw.slice(dot) : "";
    return raw.slice(0, Math.max(1, 120 - ext.length)) + ext;
}

function labeledBlock(title, meta) {
    const left = document.createElement("div");
    const t = document.createElement("div");
    t.textContent = title == null ? "" : String(title);
    const m = document.createElement("div");
    m.className = "meta";
    m.textContent = meta == null ? "" : String(meta);
    left.appendChild(t);
    left.appendChild(m);
    return left;
}

function looksLikeHtml(s) {
    return /<\/?[a-z][\s\S]*>/i.test(String(s || ""));
}

function htmlToPlain(s) {
    const t = String(s || "");
    if (!looksLikeHtml(t)) return t;
    return t
        .replace(/<br\s*\/?>/gi, "\n")
        .replace(/<\/p>/gi, "\n")
        .replace(/<[^>]+>/g, "")
        .replace(/&nbsp;/g, " ")
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">")
        .replace(/&amp;/g, "&")
        .replace(/\n{3,}/g, "\n\n")
        .trim();
}

function showErr(msg) {
    if (err) err.textContent = msg || "";
    if (errAdd) errAdd.textContent = "";
    if (errBlocked) errBlocked.textContent = msg || "";
}

function setTwMsg(text) {
    const el = document.getElementById("twSaveMsg");
    if (el) el.textContent = text || "";
}

function toast(msg, bad) {
    const el = document.getElementById("toast");
    if (!el) return;
    el.hidden = false;
    el.textContent = msg || "";
    el.className = "toast on" + (bad ? " bad" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
        el.classList.remove("on");
        setTimeout(() => {
            if (!el.classList.contains("on")) el.hidden = true;
        }, 400);
    }, 2500);
}

function noteOk(msg) {
    setTwMsg(msg);
    toast(msg, false);
}

function token() {
    return localStorage.getItem("token") || "";
}

function setToken(t) {
    if (t) localStorage.setItem("token", t);
    else localStorage.removeItem("token");
}

function toYmd(v) {
    const s = String(v ?? "").trim();
    const m = s.match(/(\d{4})-(\d{2})-(\d{2})/);
    return m ? m[1] + "-" + m[2] + "-" + m[3] : "";
}

function withSeconds(t) {
    const s = String(t || "").trim();
    if (s.length === 5) return s + ":00";
    return s;
}

function todayYmd() {
    const d = new Date();
    const pad = (n) => String(n).padStart(2, "0");
    return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
}

function nid() {
    if (crypto.randomUUID) return crypto.randomUUID();
    const b = new Uint8Array(16);
    crypto.getRandomValues(b);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const h = Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
    return h.slice(0, 8) + "-" + h.slice(8, 12) + "-" + h.slice(12, 16)
        + "-" + h.slice(16, 20) + "-" + h.slice(20);
}

function setLoginBusy(on) {
    loginBusy = on;
    const btn = document.getElementById("btnLogin");
    if (btn) {
        btn.disabled = on;
        btn.textContent = on ? "Logging in…" : "Login";
    }
}

async function send(method, path, body) {
    const headers = { "Content-Type": "application/json" };
    if (token()) headers.Authorization = "Bearer " + token();
    let payload = body;
    if (payload != null && window.AshoreVault) {
        payload = await window.AshoreVault.sealOutgoing(path, method, payload);
    }
    const res = await fetch(path, {
        method,
        headers,
        body: payload == null ? undefined : JSON.stringify(payload)
    });
    const text = await res.text();
    let json = null;
    try { json = JSON.parse(text); } catch (_) {}
    if (!res.ok) {
        if (res.status === 401 && path && path.indexOf("/account-status") < 0 && path.indexOf("/login") < 0) {
            wipeIfAccountDeleted();
        }
        if (res.status === 502 || res.status === 504 || /bad gateway|cloudflare/i.test(text)) {
            throw new Error(res.status + " tunnel/origin down — Node or cloudflared is not answering");
        }
        if (res.status === 429) {
            throw new Error((json && json.error) || "Too many image uploads just now. Wait a minute, then retry.");
        }
        throw new Error(res.status + " " + ((json && json.error) || text || "error"));
    }
    if (json && window.AshoreVault) {
        json = await window.AshoreVault.openIncoming(path, json);
    }
    return json;
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

function minutes(t) {
    const sec = secondsOf(t);
    return sec == null ? null : Math.floor(sec / 60);
}

function isExceptionRow(b) {
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

function isDailyRow(b) {
    return !isExceptionRow(b) && !toYmd(b.specific_date || b.specificDate) && weekdayOf(b) == null;
}

function datesCanMeet(a, b) {
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

function clockOverlap(a, b) {
    const a0 = secondsOf(a.start_time || a.startTime);
    const a1 = secondsOf(a.end_time || a.endTime);
    const b0 = secondsOf(b.start_time || b.startTime);
    const b1 = secondsOf(b.end_time || b.endTime);
    if (a0 == null || a1 == null || b0 == null || b1 == null) return false;
    if (a0 >= a1 || b0 >= b1) return false;
    return a0 < b1 && b0 < a1;
}

function describeBlocked(b) {
    const when = isExceptionRow(b)
        ? "exception " + (toYmd(b.specific_date) || weekdayName(b.weekday))
        : isDailyRow(b) ? "every day" : (toYmd(b.specific_date) || weekdayName(b.weekday));
    return (b.title || "blocked") + " " + when + " " + b.start_time + "–" + b.end_time;
}

function showEl(el, on) {
    if (!el) return;
    el.hidden = !on;
}

function exceptionLabel(every, range) {
    if (every) return "Exception (from every day)";
    if (range) return "Exception (from this range)";
    return "Exception (this date / weekdays)";
}

function syncBlockedDates() {
    const every = document.getElementById("bEvery");
    const exception = document.getElementById("bException");
    const range = document.getElementById("bRange");
    const from = document.getElementById("bFrom");
    const to = document.getElementById("bTo");
    if (!every || !exception || !from || !to) return;
    const isEvery = every.checked;
    const isRange = !!(range && range.checked);
    const isEx = exception.checked;
    const lab = document.getElementById("bExceptionLab");
    if (lab) lab.textContent = exceptionLabel(isEvery, isRange);
    showEl(document.getElementById("bWeekRow"), isEx);
    showEl(document.getElementById("bExceptionWrap"), true);
    showEl(document.getElementById("bRangeWrap"), true);
    const showFrom = !isEvery || isEx;
    const showTo = isRange || (isEvery && isEx);
    if (isEvery && !isEx) {
        from.value = "";
        to.value = "";
    } else if (!(isEvery && isEx && !isRange)) {
        if (!from.value) from.value = todayYmd();
        if (showTo && !to.value) to.value = from.value || todayYmd();
    }
    showEl(document.getElementById("bDateRow"), showFrom);
    showEl(document.getElementById("bFromLab"), showFrom);
    showEl(from, showFrom);
    const fromLab = document.getElementById("bFromLab");
    if (fromLab) fromLab.textContent = showTo ? "From" : "Date";
    showEl(document.getElementById("bToLab"), showTo);
    showEl(to, showTo);
}

function whenLabel(b) {
    if (isExceptionRow(b)) {
        return "exception " + (toYmd(b.specific_date) || weekdayName(b.weekday) || "date");
    }
    if (isDailyRow(b)) return "every day";
    return toYmd(b.specific_date) || weekdayName(b.weekday);
}

function blockedUntilMs(b) {
    const day = toYmd(b.specific_date);
    if (!day) return null;
    const endMin = minutes(b.end_time);
    const hh = endMin == null ? 23 : Math.floor(endMin / 60);
    const mm = endMin == null ? 59 : endMin % 60;
    const end = new Date(day + "T" + String(hh).padStart(2, "0") + ":" + String(mm).padStart(2, "0") + ":00");
    if (Number.isNaN(end.getTime())) return null;
    return end.getTime();
}

function blockedJustUpdated(b) {
    const raw = b.last_updated_at || b.lastUpdatedAt;
    if (!raw) return false;
    const t = Date.parse(raw);
    return Number.isFinite(t) && Date.now() - t < 5 * 60 * 1000;
}

async function expirePastBlocked(rows) {
    let wrote = false;
    for (const b of rows || []) {
        const until = blockedUntilMs(b);
        if (until == null || until > Date.now()) continue;
        if (blockedJustUpdated(b) || !b.id) continue;
        await send("DELETE", "/blocked/" + b.id);
        wrote = true;
    }
    return wrote;
}

function paintBlocked(rows) {
    allBlocked = rows || [];
    const list = document.getElementById("blocked");
    if (!list) return;
    list.innerHTML = "";
    if (!rows.length) {
        list.innerHTML = "<li><span class='meta'>(none)</span></li>";
        return;
    }
    const sorted = rows.slice().sort((a, b) => {
        const sa = minutes(a.start_time) ?? 0;
        const sb = minutes(b.start_time) ?? 0;
        if (sa !== sb) return sa - sb;
        return String(a.specific_date || "").localeCompare(String(b.specific_date || ""));
    });
    for (const b of sorted) {
        const li = document.createElement("li");
        const left = labeledBlock(
            (b.title || "blocked") + "  " + (b.start_time || "") + "–" + (b.end_time || ""),
            whenLabel(b)
        );
        const del = document.createElement("button");
        del.type = "button";
        del.className = "danger";
        del.textContent = "Delete";
        del.dataset.blockedId = b.id;
        li.appendChild(left);
        li.appendChild(del);
        list.appendChild(li);
    }
}

function isFinished(t) {
    const w = String(t.workflow || "").toUpperCase();
    return w === "FINISHED" || w === "DONE" || w === "COMPLETE" || w === "COMPLETED";
}

function taskHasEnded(t) {
    if (!t || isFinished(t)) return true;
    const day = toYmd(t.placed_date || t.placedDate);
    const start = minutes(t.placed_time || t.placedTime);
    if (!day || start == null) return false;
    const p = day.split("-").map(Number);
    const end = new Date(p[0], p[1] - 1, p[2], Math.floor(start / 60), start % 60, 0, 0);
    end.setMinutes(end.getMinutes() + taskMinutes(t));
    return end.getTime() <= Date.now();
}

function taskState(t) {
    return String(t.state || t.taskState || "").trim();
}

function hasDeadline(t) {
    return !!(toYmd(t.deadline_date || t.deadlineDate) || t.deadline_time || t.deadlineTime);
}

function isUnscheduled(t) {
    return !toYmd(t.placed_date || t.placedDate);
}

function lifeFromDate(date) {
    const day = toYmd(date);
    if (!day) return "UNSCHEDULED";
    if (day < todayYmd()) return "PAST";
    return "ONGOING";
}

function clockMs(ymd, hm) {
    const d = toYmd(ymd);
    const startMin = minutes(hm);
    if (!d || startMin == null) return null;
    const hh = String(Math.floor(startMin / 60)).padStart(2, "0");
    const mm = String(startMin % 60).padStart(2, "0");
    const start = new Date(d + "T" + hh + ":" + mm + ":00");
    return Number.isNaN(start.getTime()) ? null : start.getTime();
}

function taskEndMs(t) {
    const start = clockMs(t.placed_date || t.placedDate, t.placed_time || t.placedTime);
    if (start == null) return null;
    const hours = Number(t.duration || 0);
    const durMin = hours > 0 ? Math.round(hours * 60) : 30;
    return start + durMin * 60 * 1000;
}

function lifeOf(t) {
    if (isUnscheduled(t)) return "UNSCHEDULED";
    const end = taskEndMs(t);
    if (end != null) return end <= Date.now() ? "PAST" : "ONGOING";
    const raw = String(t.lifecycle || t.lifeCycle || "").toUpperCase();
    if (raw === "PAST" || raw === "ONGOING" || raw === "UNSCHEDULED") return raw;
    return lifeFromDate(t.placed_date || t.placedDate);
}

function taskStartKey(t) {
    const d = toYmd(t.placed_date || t.placedDate);
    if (!d) return "";
    return d + "T" + String(t.placed_time || t.placedTime || "00:00").slice(0, 5);
}

function sealedUi(s) {
    return String(s || "").startsWith("AP1.");
}

function publicTitle(t) {
    const n = String((t && (t.title || t.taskName)) || "").trim();
    if (!n || sealedUi(n)) return "";
    return n;
}

async function patchTaskDescription(t, description, descriptionDone) {
    const day = toYmd(t.placed_date);
    await send("POST", "/tasks", {
        id: t.id,
        title: t.title,
        duration: Number(t.duration || 0),
        state: t.state || "Flexible",
        placed_date: day,
        placedDate: day,
        placed_time: t.placed_time,
        placedTime: t.placed_time,
        deadline_date: t.deadline_date || null,
        deadline_time: t.deadline_time || null,
        lifecycle: lifeOf(t),
        workflow: isFinished(t) ? "FINISHED" : "UNFINISHED",
        last_updated_at: new Date().toISOString(),
        placement_updated_at: t.placement_updated_at || new Date().toISOString(),
        description: clipDesc(description),
        description_done: descriptionDone != null
            ? !!descriptionDone
            : (t.description_done === true || t.description_done === "true")
    });
}

function matchesWhen(t) {
    if (taskWhen === "all") return true;
    if (taskWhen === "unscheduled") return lifeOf(t) === "UNSCHEDULED";
    if (taskWhen === "past") return lifeOf(t) === "PAST";
    if (taskWhen === "ongoing") return lifeOf(t) === "ONGOING";
    return true;
}

function matchesKind(t) {
    if (taskKind === "all") return true;
    if (taskKind === "deadline") return hasDeadline(t);
    return taskState(t).toLowerCase() === String(taskKind).toLowerCase();
}

function taskWhenKey(t) {
    const day = toYmd(t && t.placed_date);
    if (!day) return "9999-12-31T99:99";
    const time = String(t.placed_time || "00:00").slice(0, 5);
    return day + "T" + time;
}

function filteredTasks() {
    return allTasks
        .filter((t) => !isQuick(t) && matchesWhen(t) && matchesKind(t))
        .sort((a, b) => {
            const c = taskWhenKey(a).localeCompare(taskWhenKey(b));
            if (c !== 0) return c;
            return String(a.title || "").localeCompare(String(b.title || ""));
        });
}

function markTabs() {
    document.querySelectorAll("[data-taskwhen]").forEach((b) => {
        const on = b.dataset.taskwhen === taskWhen;
        b.className = on ? "active" : "ghost";
    });
    document.querySelectorAll("[data-taskkind]").forEach((b) => {
        const on = b.dataset.taskkind === taskKind;
        b.className = on ? "active" : "ghost";
    });
}

function mondayOfOffset(offset) {
    const col = columnDateFor(todayYmd(), nowHm()) || todayYmd();
    const parts = String(col).split("-").map(Number);
    const base = new Date(parts[0], (parts[1] || 1) - 1, parts[2] || 1);
    const day = base.getDay();
    const back = day === 0 ? 6 : day - 1;
    const mon = new Date(base.getFullYear(), base.getMonth(), base.getDate() - back + offset * 7);
    mon.setHours(0, 0, 0, 0);
    return mon;
}

function fillAddFromTask(t) {
    if (!t) return;
    t = parentOf(t);
    if (t && isQuick(t)) return;
    const set = (id, v) => { const el = document.getElementById(id); if (el) el.value = v == null ? "" : v; };
    set("tTitle", t.title || "");
    set("tDur", String(t.duration || 1));
    const mins = taskMinutes(t);
    set("tDurMin", String(mins));
    const minOn = document.getElementById("tMinOn");
    if (minOn) {
        minOn.checked = mins % 30 !== 0;
        showMinuteDuration(minOn.checked);
    }
        const qbtn = document.getElementById("btnQuick");
    if (qbtn) {
        const canQuick = !isQuick(t) && t.placed_date && t.placed_time;
        qbtn.hidden = !canQuick;
        qbtn.dataset.parentId = canQuick ? String(t.id || "") : "";
        qbtn.textContent = canQuick
            ? "Add as quick event inside “" + (t.title || "task") + "”"
            : "Add as quick event";
    }
    syncDuration("minutes");
    const state = hasDeadline(t) ? "Deadline" : (String(t.state || "") === "Fixed" ? "Fixed" : "Flexible");
    set("tState", state);
    set("tDate", toYmd(t.placed_date) || "");
    set("tTime", (t.placed_time || "").slice(0, 5));
    set("tDesc", t.description || "");
    const done = document.getElementById("tDescDone");
    if (done) done.checked = t.description_done === true || t.description_done === "true";
    set("tDeadlineDate", toYmd(t.deadline_date || t.deadlineDate) || "");
    set("tDeadlineTime", String(t.deadline_time || t.deadlineTime || "18:00").slice(0, 5));
    if (typeof syncDeadlineUi === "function") syncDeadlineUi();
    reuseTaskId = t.id ? String(t.id) : "";
    showReuseHint();
    const title = document.getElementById("tTitle");
    if (title) title.focus();
    paintNestedQuicks(t);
}

function showReuseHint() {
    const el = document.getElementById("tReuseHint");
    const btn = document.getElementById("btnNewTask");
    if (el) el.textContent = reuseTaskId ? "Updating the same task (not a new one)." : "";
    if (btn) btn.style.display = reuseTaskId ? "" : "none";
}

function clearReuse() {
    reuseTaskId = "";
    pendingQuickParent = "";
    const qbtn = document.getElementById("btnQuick");
    if (qbtn) {
        qbtn.hidden = true;
        qbtn.dataset.parentId = "";
    }
    showReuseHint();
    paintNestedQuicks(null);
}

function fillAddSlot(ymd, hm, asNew) {
    if (asNew) clearReuse();
    const dateEl = document.getElementById("tDate");
    const timeEl = document.getElementById("tTime");
    if (dateEl && ymd) dateEl.value = ymd;
    if (timeEl && hm) timeEl.value = hm;
    const add = document.getElementById("tTitle");
    if (add) add.focus();
}

function ymdFromDate(d) {
    const pad = (n) => String(n).padStart(2, "0");
    return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
}

/** 00:00–05:30 belongs on the previous day's 06:00 column. */
function columnDateFor(ymd, hm) {
    const day = toYmd(ymd);
    if (!day) return "";
    const startMin = minutes(hm);
    if (startMin == null || startMin >= 6 * 60) return day;
    const d = new Date(day + "T12:00:00");
    d.setDate(d.getDate() - 1);
    return ymdFromDate(d);
}

function nowHm() {
    const n = new Date();
    return String(n.getHours()).padStart(2, "0") + ":" + String(n.getMinutes()).padStart(2, "0");
}

function weekDays() {
    const mon = mondayOfOffset(weekOffset);
    const days = [];
    for (let i = 0; i < 7; i++) {
        const d = new Date(mon);
        d.setDate(mon.getDate() + i);
        days.push(d);
    }
    return days;
}

let calMode = "week";

function taskMinutes(t) {
    const d = Number(t && t.duration);
    if (!Number.isFinite(d) || d <= 0) return 30;
    return Math.max(1, Math.round(d * 60));
}

function isQuick(t) {
    return !!(t && (t.parent_id || t.parentId));
}

function parentOf(t) {
    if (!t || !isQuick(t)) return t;
    const pid = String(t.parent_id || t.parentId || "");
    if (!pid) return t;
    const found = allTasks.find((x) => String(x.id) === pid);
    return found || t;
}

function quicksOf(parent) {
    if (!parent || isQuick(parent)) return [];
    const id = String(parent.id || "");
    return allTasks.filter((t) => String(t.parent_id || t.parentId || "") === id)
        .sort((a, b) => String(a.placed_time || "").localeCompare(String(b.placed_time || "")));
}

function paintNestedQuicks(parent) {
    const box = document.getElementById("tQuicks");
    if (!box) return;
    box.innerHTML = "";
    const nested = quicksOf(parent);
    if (!nested.length) return;
    const head = document.createElement("div");
    head.className = "meta";
    head.textContent = "During this task";
    box.appendChild(head);
    const parentDone = isFinished(parent);
    nested.forEach((q) => {
        const line = document.createElement("div");
        const clock = String(q.placed_time || "").slice(0, 5);
        const done = parentDone || isFinished(q) ? "done · " : "";
        line.textContent = done + clock + "  " + (q.title || "") + "  ·  " + taskMinutes(q) + " min";
        box.appendChild(line);
    });
}

function paintQuickCount(parent, host) {
    const n = quicksOf(parent).length;
    if (!n || !host) return;
    host.classList.add("has-quick");
    const badge = document.createElement("span");
    badge.className = "tg-quick-count";
    badge.textContent = String(n);
    badge.title = n === 1 ? "1 quick event" : n + " quick events";
    host.appendChild(badge);
}

function selectedTask() {
    if (!selectedTaskId) return null;
    let t = allTasks.find((x) => String(x.id) === String(selectedTaskId));
    if (!t) return null;
    t = parentOf(t);
    if (!t || isQuick(t)) return null;
    return t;
}

function markSelectedTask() {
    document.querySelectorAll("[data-pick-id]").forEach((n) => {
        n.classList.toggle("picked", selectedTaskId !== "" && n.dataset.pickId === selectedTaskId);
    });
}

function ensureDescPanel() {
    const add = document.getElementById("descAddField");
    if (add && add.dataset.hooked !== "1") {
        add.dataset.hooked = "1";
        add.addEventListener("keydown", (e) => {
            if (e.key === "Enter") {
                e.preventDefault();
                addDescItem();
            }
        });
    }
}

function parseDescLines(raw) {
    const out = [];
    if (raw == null || sealedUi(raw)) return out;
    const text = String(raw);
    if (!text.trim()) return out;
    const lines = text.split(/\r?\n/);
    const anyMark = lines.some((line) => {
        const t = String(line || "").trim();
        return t.startsWith("[x]") || t.startsWith("[X]") || t.startsWith("[ ]");
    });
    if (!anyMark) {
        out.push({ done: false, text: text.trim() });
        return out;
    }
    for (const line of lines) {
        const t = String(line || "").trim();
        if (!t) continue;
        if (t.startsWith("[x]") || t.startsWith("[X]")) out.push({ done: true, text: t.slice(3).trim() });
        else if (t.startsWith("[ ]")) out.push({ done: false, text: t.slice(3).trim() });
        else out.push({ done: false, text: t });
    }
    return out;
}

function descRow(done, text) {
    const row = document.createElement("div");
    row.className = "desc-row";
    const box = document.createElement("input");
    box.type = "checkbox";
    box.checked = !!done;
    box.addEventListener("change", persistDesc);
    const tf = document.createElement("input");
    tf.type = "text";
    tf.maxLength = 500;
    tf.value = text || "";
    tf.placeholder = "What to do…";
    tf.addEventListener("change", persistDesc);
    tf.addEventListener("blur", persistDesc);
    const rm = document.createElement("button");
    rm.type = "button";
    rm.className = "ghost";
    rm.textContent = "×";
    rm.addEventListener("click", () => {
        row.remove();
        persistDesc();
    });
    row.appendChild(box);
    row.appendChild(tf);
    row.appendChild(rm);
    return row;
}

function fillDescList(raw) {
    const box = document.getElementById("descList");
    if (!box) return;
    box.innerHTML = "";
    if (raw == null) return;
    for (const line of parseDescLines(raw)) box.appendChild(descRow(line.done, line.text));
}

function fillDescQuicks(parent) {
    const box = document.getElementById("descQuicks");
    const head = document.getElementById("descQuicksHead");
    if (box) box.innerHTML = "";
    if (head) head.hidden = !parent;
    if (!parent || !box) return;
    const nested = quicksOf(parent);
    if (!nested.length) {
        const empty = document.createElement("div");
        empty.className = "meta";
        empty.textContent = "None on this task.";
        box.appendChild(empty);
        return;
    }
    const parentDone = isFinished(parent);
    nested.forEach((q) => {
        const line = document.createElement("div");
        const clock = String(q.placed_time || "").slice(0, 5);
        const done = parentDone || isFinished(q) ? "done · " : "";
        line.textContent = done + clock + "  " + (publicTitle(q) || "(untitled)") + "  ·  " + taskMinutes(q) + " min";
        box.appendChild(line);
    });
}

function serializeDescList() {
    const box = document.getElementById("descList");
    if (!box) return { text: "", allDone: false };
    const parts = [];
    let any = false;
    let allDone = true;
    box.querySelectorAll(".desc-row").forEach((row) => {
        const check = row.querySelector('input[type="checkbox"]');
        const tf = row.querySelector('input[type="text"]');
        const t = tf ? tf.value.trim() : "";
        if (!t) return;
        any = true;
        const on = !!(check && check.checked);
        if (!on) allDone = false;
        parts.push((on ? "[x] " : "[ ] ") + t);
    });
    let text = parts.join("\n");
    if (text.length > 8000) text = text.slice(0, 8000);
    return { text, allDone: any && allDone };
}

function showTaskDescription(t) {
    if (t) t = parentOf(t);
    if (t && isQuick(t)) t = null;
    selectedTaskId = t && t.id ? String(t.id) : "";
    const on = !!t;
    const meta = document.getElementById("descMeta");
    const addField = document.getElementById("descAddField");
    const addBtn = document.getElementById("descAddBtn");
    const saveBtn = document.getElementById("descSave");
    const finishBtn = document.getElementById("descFinish");
    const listHead = document.getElementById("descListHead");
    const errEl = document.getElementById("descErr");
    if (errEl) errEl.textContent = "";
    if (meta) {
        if (!on) {
            meta.textContent = "Left-click a task to see it here.";
        } else {
            const kind = hasDeadline(t) ? "Deadline" : (taskState(t) === "Fixed" ? "Fixed" : "Flexible");
            const placed = (!toYmd(t.placed_date) || !t.placed_time)
                ? "not placed"
                : toYmd(t.placed_date) + " " + String(t.placed_time).slice(0, 5);
            const dlDate = toYmd(t.deadline_date || t.deadlineDate);
            const dlTime = String(t.deadline_time || t.deadlineTime || "").slice(0, 5);
            const deadline = hasDeadline(t)
                ? "\nDeadline " + (dlDate || "") + (dlTime ? " " + dlTime : "")
                : "";
            const mins = taskMinutes(t);
            const hours = Number(t.duration);
            const dur = mins + " min" + (mins % 30 === 0 && Number.isFinite(hours) ? " · " + hours + "h grid" : "");
            const wf = isFinished(t) ? "FINISHED" : "UNFINISHED";
            meta.textContent = (publicTitle(t) || "(untitled)")
                + "\n" + kind + " · " + dur + " · " + wf
                + "\nPlaced " + placed
                + deadline;
        }
    }
    if (addField) {
        addField.disabled = !on;
        if (!on) addField.value = "";
    }
    if (addBtn) addBtn.disabled = !on;
    if (saveBtn) saveBtn.disabled = !on;
    if (listHead) listHead.hidden = !on;
    const placed = on && toYmd(t.placed_date) && t.placed_time;
    if (finishBtn) {
        finishBtn.disabled = !placed;
        finishBtn.textContent = placed && isFinished(t) ? "Unfinish" : "Finish";
    }
    fillingDesc = true;
    try {
        fillDescList(on ? t.description : null);
        fillDescQuicks(on ? t : null);
    } finally {
        fillingDesc = false;
    }
    markSelectedTask();
}

function addDescItem() {
    const t = selectedTask();
    const field = document.getElementById("descAddField");
    const box = document.getElementById("descList");
    if (!t || !field || !box) return;
    let text = field.value.trim();
    if (!text) return;
    if (text.length > 500) text = text.slice(0, 500);
    box.appendChild(descRow(false, text));
    field.value = "";
    persistDesc();
}

function persistDesc() {
    if (fillingDesc || !selectedTask()) return;
    saveTaskDescription();
}

async function saveTaskDescription() {
    const t = selectedTask();
    const errEl = document.getElementById("descErr");
    if (errEl) errEl.textContent = "";
    if (!t) return;
    const packed = serializeDescList();
    try {
        await patchTaskDescription(t, packed.text, packed.allDone);
        t.description = packed.text;
        t.description_done = packed.allDone;
    } catch (ex) {
        if (errEl) errEl.textContent = ex.message || String(ex);
    }
}

function paintWeek() {
    const grid = document.getElementById("weekGrid");
    const month = document.getElementById("monthGrid");
    const label = document.getElementById("weekLabel");
    const title = document.getElementById("calTitle");
    if (!grid || !label) return;
    if (calMode === "month") {
        grid.classList.add("panel-hidden");
        if (month) {
            month.classList.remove("panel-hidden");
            paintMonth();
        }
        if (title) title.textContent = "Month";
        const jump = document.getElementById("btnThisWeek");
        if (jump) jump.textContent = "Jump to this month";
        ensureDescPanel();
        markSelectedTask();
        return;
    }
    grid.classList.remove("panel-hidden");
    if (month) month.classList.add("panel-hidden");
    if (title) title.textContent = "Week";
    const jump = document.getElementById("btnThisWeek");
    if (jump) jump.textContent = "Jump to this week";
    const days = weekDays();
    const names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
    label.textContent = ymdFromDate(days[0]) + "  →  " + ymdFromDate(days[6]);
    grid.innerHTML = "";
    const todayCol = columnDateFor(todayYmd(), nowHm());
    const nowMins = minutes(nowHm());
    function gridPlace(el, col, row, rowEnd) {
        el.style.gridColumn = String(col);
        el.style.gridRow = rowEnd != null ? (row + " / " + rowEnd) : String(row);
    }
    const corner = document.createElement("div");
    corner.className = "tg-head tg-time";
    gridPlace(corner, 1, 1);
    grid.appendChild(corner);
    days.forEach((d, i) => {
        const ymd = ymdFromDate(d);
        const head = document.createElement("div");
        head.className = "tg-head";
        head.textContent = names[i] + " " + ymd.slice(5);
        head.style.cursor = "pointer";
        head.addEventListener("click", () => fillAddSlot(ymd, ""));
        gridPlace(head, i + 2, 1);
        grid.appendChild(head);
    });
    for (let slot = 0; slot < 48; slot++) {
        const abs = 6 * 60 + slot * 30;
        const hh = String(Math.floor((abs % (24 * 60)) / 60)).padStart(2, "0");
        const mm = String(abs % 60).padStart(2, "0");
        const time = document.createElement("div");
        time.className = "tg-time";
        time.textContent = hh + ":" + mm;
        gridPlace(time, 1, slot + 2);
        grid.appendChild(time);
        days.forEach((d, i) => {
            const ymd = ymdFromDate(d);
            const cell = document.createElement("div");
            cell.className = "tg-cell";
            cell.dataset.abs = String(abs);
            cell.dataset.ymd = ymd;
            const past = ymd < todayCol || (ymd === todayCol && nowMins != null && abs + 30 <= nowMins);
            if (past) cell.classList.add("past");
            else if (ymd === todayCol) cell.classList.add("today");
            if (ymd >= todayCol) {
                cell.style.cursor = "pointer";
                cell.addEventListener("click", () => fillAddSlot(ymd, hh + ":" + mm, true));
            }
            gridPlace(cell, i + 2, slot + 2);
            grid.appendChild(cell);
        });
    }
    function weekCell(ymd, slot) {
        const abs = 6 * 60 + slot * 30;
        return grid.querySelector('.tg-cell[data-ymd="' + ymd + '"][data-abs="' + abs + '"]');
    }
    allTasks.forEach((t) => {
        if (!t || isQuick(t)) return;
        const ymd = columnDateFor(t.placed_date, t.placed_time);
        const col = days.findIndex((d) => ymdFromDate(d) === ymd);
        if (col < 0) return;
        const start = minutes(t.placed_time);
        if (start == null) return;
        let rel = start >= 6 * 60 ? start - 6 * 60 : 18 * 60 + start;
        const dur = taskMinutes(t);
        const startSlot = Math.floor(rel / 30);
        const endRel = rel + dur;
        let endSlot = Math.ceil(endRel / 30);
        if (endRel % 30 === 0) endSlot = endRel / 30;
        if (endSlot <= startSlot) endSlot = startSlot + 1;
        if (endSlot > 48) endSlot = 48;
        const ended = taskHasEnded(t);
        const block = document.createElement("div");
        block.className = "tg-occ-block" + (ended ? " ended" : "");
        gridPlace(block, col + 2, startSlot + 2, endSlot + 2);
        grid.appendChild(block);
        for (let slot = startSlot; slot < endSlot; slot++) {
            const cell = weekCell(ymd, slot);
            if (!cell) continue;
            cell.classList.add("occ");
            if (ended) cell.classList.add("ended");
            else cell.classList.remove("past");
        }
    });
    allTasks.forEach((t) => {
        if (!t || isQuick(t)) return;
        const ymd = columnDateFor(t.placed_date, t.placed_time);
        const col = days.findIndex((d) => ymdFromDate(d) === ymd);
        if (col < 0) return;
        const start = minutes(t.placed_time);
        if (start == null) return;
        let rel = start >= 6 * 60 ? start - 6 * 60 : 18 * 60 + start;
        const dur = taskMinutes(t);
        const slot = Math.floor(rel / 30);
        const into = rel % 30;
        const cell = weekCell(ymd, slot);
        if (!cell) return;
        const clock = String(t.placed_time || "").slice(0, 5);
        const title = publicTitle(t) || "(untitled)";
        if (wantRealtimeOverlay()) {
            const bar = document.createElement("button");
            bar.type = "button";
            bar.className = "tg-bar"
                + (isFinished(t) ? " done" : "")
                + (taskHasEnded(t) ? " past" : "");
            bar.style.top = (into / 30) * 18 + "px";
            bar.style.height = Math.max(10, (dur / 30) * 18) + "px";
            bar.textContent = clock + " " + title;
            bar.title = clock + " · " + dur + " min · " + (t.title || "");
            bar.dataset.pickId = String(t.id || "");
            bar.addEventListener("click", (e) => {
                e.stopPropagation();
                showTaskDescription(t);
            });
            cell.appendChild(bar);
            paintQuickCount(t, bar);
        } else {
            const name = document.createElement("button");
            name.type = "button";
            name.className = "tg-name" + (isFinished(t) ? " done" : "");
            name.textContent = clock + " " + title;
            name.title = clock + " · " + dur + " min · " + (t.title || "");
            name.dataset.pickId = String(t.id || "");
            name.addEventListener("click", (e) => {
                e.stopPropagation();
                showTaskDescription(t);
            });
            cell.appendChild(name);
            paintQuickCount(t, name);
        }
    });
    placeNowLine(grid, days);
    ensureNowClock();
    ensureDescPanel();
    markSelectedTask();
}

function nowLinePlace() {
    const nowMins = minutes(nowHm());
    if (nowMins == null) return null;
    const s = new Date().getSeconds();
    let rel = nowMins >= 6 * 60 ? nowMins - 6 * 60 : 18 * 60 + nowMins;
    const slot = Math.floor(rel / 30);
    const into = rel % 30;
    if (slot < 0 || slot >= 48) return null;
    return { slot, y: ((into * 60 + s) / (30 * 60)) * 18 };
}

function placeNowLine(grid, days) {
    if (!grid) return;
    grid.querySelectorAll(".tg-now-line").forEach((n) => n.remove());
    if (weekOffset !== 0) return;
    const today = todayYmd();
    if (days && !days.some((d) => ymdFromDate(d) === today)) return;
    const at = nowLinePlace();
    if (!at) return;
    const abs = 6 * 60 + at.slot * 30;
    grid.querySelectorAll('.tg-cell[data-abs="' + abs + '"]').forEach((cell) => {
        const line = document.createElement("div");
        line.className = "tg-now-line";
        line.style.top = at.y + "px";
        cell.appendChild(line);
    });
}

function paintMonth() {
    const grid = document.getElementById("monthGrid");
    const label = document.getElementById("weekLabel");
    if (!grid) return;
    const now = new Date();
    const monthDate = new Date(now.getFullYear(), now.getMonth() + monthOffset, 1);
    const y = monthDate.getFullYear();
    const m = monthDate.getMonth();
    if (label) label.textContent = monthDate.toLocaleString(undefined, { month: "long", year: "numeric" });
    grid.innerHTML = "";
    ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].forEach((n) => {
        const h = document.createElement("div");
        h.className = "meta";
        h.textContent = n;
        grid.appendChild(h);
    });
    const first = new Date(y, m, 1);
    let pad = (first.getDay() + 6) % 7;
    for (let i = 0; i < pad; i++) grid.appendChild(document.createElement("div"));
    const last = new Date(y, m + 1, 0).getDate();
    const today = todayYmd();
    for (let d = 1; d <= last; d++) {
        const ymd = y + "-" + String(m + 1).padStart(2, "0") + "-" + String(d).padStart(2, "0");
        const cell = document.createElement("div");
        cell.className = "month-cell";
        if (ymd === today) cell.style.outline = "2px solid var(--blue)";
        const n = document.createElement("div");
        n.className = "n";
        n.textContent = String(d);
        cell.appendChild(n);
        allTasks.filter((t) => !isQuick(t) && toYmd(t.placed_date) === ymd).forEach((t) => {
            const line = document.createElement("div");
            line.className = "t";
            line.textContent = (t.placed_time || "").slice(0, 5) + " " + (t.title || "");
            line.title = t.title || "";
            line.dataset.pickId = String(t.id || "");
            line.addEventListener("click", (e) => {
                e.stopPropagation();
                showTaskDescription(t);
            });
            cell.appendChild(line);
        });
        cell.addEventListener("click", (e) => {
            if (e.target === cell || e.target === n) fillAddSlot(ymd, "09:00", true);
        });
        grid.appendChild(cell);
    }
}

function applyTheme() {
    document.documentElement.classList.toggle("dark", localStorage.getItem("ashoreTheme") === "dark");
}

function wantRealtimeOverlay() {
    const v = localStorage.getItem("ashoreRealtimeOverlay");
    if (v == null) return true;
    return v === "true";
}

function setRealtimeOverlay(on) {
    localStorage.setItem("ashoreRealtimeOverlay", on ? "true" : "false");
}

function ensureNowClock() {
    const box = document.getElementById("realtimeOverlay");
    if (box && box.dataset.hooked !== "1") {
        box.dataset.hooked = "1";
        box.checked = wantRealtimeOverlay();
        box.addEventListener("change", () => {
            setRealtimeOverlay(box.checked);
            paintWeek();
        });
    }
    if (!secondsClock) secondsClock = setInterval(tickNowLines, 1000);
}

function tickNowLines() {
    const grid = document.getElementById("weekGrid");
    if (!grid || grid.classList.contains("panel-hidden")) return;
    const at = nowLinePlace();
    const lines = grid.querySelectorAll(".tg-now-line");
    if (weekOffset !== 0 || !at) {
        lines.forEach((n) => n.remove());
        return;
    }
    if (!lines.length) {
        placeNowLine(grid, weekDays());
        return;
    }
    const abs = String(6 * 60 + at.slot * 30);
    const parent = lines[0].parentElement;
    if (!parent || parent.dataset.abs !== abs) {
        placeNowLine(grid, weekDays());
        return;
    }
    lines.forEach((line) => {
        line.style.top = at.y + "px";
    });
}

function wantDark() {
    return localStorage.getItem("ashoreTheme") === "dark";
}

function setDark(on) {
    localStorage.setItem("ashoreTheme", on ? "dark" : "light");
    applyTheme();
}

const IDLE_MIN_KEY = "ashoreIdleMinutes";
const IDLE_AT_KEY = "ashoreIdleAt";
let idleWatchId = 0;
let idleBumpBound = false;

function idleMinutesPref() {
    const raw = localStorage.getItem(IDLE_MIN_KEY);
    if (raw == null || raw === "") return 0;
    if (raw === "0") return 0;
    const n = Number(raw);
    if (!Number.isFinite(n)) return 0;
    if (n <= 0) return 0;
    return Math.max(5, Math.min(180, n));
}

function setIdleMinutesPref(n) {
    localStorage.setItem(IDLE_MIN_KEY, String(n <= 0 ? 0 : Math.max(5, Math.min(180, n))));
}

function bumpIdleActivity() {
    sessionStorage.setItem(IDLE_AT_KEY, String(Date.now()));
}

function idleDue() {
    const m = idleMinutesPref();
    if (m <= 0) return false;
    const at = Number(sessionStorage.getItem(IDLE_AT_KEY) || Date.now());
    return Date.now() - at > m * 60 * 1000;
}

function startIdleWatch() {
    if (!sessionStorage.getItem(IDLE_AT_KEY)) bumpIdleActivity();
    if (idleDue()) {
        logoutWeb();
        toast("Signed out after idle time", true);
        return;
    }
    if (!idleBumpBound) {
        idleBumpBound = true;
        const bump = () => bumpIdleActivity();
        ["pointerdown", "keydown", "touchstart"].forEach((ev) => {
            document.addEventListener(ev, bump, { passive: true });
        });
    }
    if (idleWatchId) clearInterval(idleWatchId);
    idleWatchId = setInterval(() => {
        if (!token()) return;
        if (idleDue()) {
            logoutWeb();
            toast("Signed out after idle time", true);
        }
    }, 15000);
}

function stopIdleWatch() {
    if (idleWatchId) {
        clearInterval(idleWatchId);
        idleWatchId = 0;
    }
}

function toggleActionPanel(which) {
    const panels = {
        tasks: ["taskListHeading", "taskListPanel"],
        blocked: ["blockedHeading", "blockedPanel"]
    };
    Object.keys(panels).forEach((k) => {
        const ids = panels[k];
        const body = document.getElementById(ids[1]);
        const show = k === which && body && body.classList.contains("panel-hidden");
        ids.forEach((id) => {
            const el = document.getElementById(id);
            if (el) el.classList.toggle("panel-hidden", !show);
        });
    });
    const menu = document.getElementById("actionMenu");
    if (menu) menu.hidden = true;
}

function closeActionMenu() {
    const menu = document.getElementById("actionMenu");
    if (menu) menu.hidden = true;
}

function showMinuteDuration(on) {
    const wrap = document.getElementById("tDurMin");
    const box = document.getElementById("tMinOn");
    if (wrap) wrap.style.display = on ? "" : "none";
    if (box) box.checked = !!on;
}

function showEndTime(on) {
    const end = document.getElementById("tEnd");
    const box = document.getElementById("tEndOn");
    if (end) end.style.display = on ? "" : "none";
    if (box) box.checked = !!on;
    if (on) syncDuration("hours");
}

function minutesFromTime(hm) {
    const n = minutes(hm);
    return n == null ? null : n;
}

function hmFromMinutes(n) {
    let m = ((n % (24 * 60)) + 24 * 60) % (24 * 60);
    const hh = String(Math.floor(m / 60)).padStart(2, "0");
    const mm = String(m % 60).padStart(2, "0");
    return hh + ":" + mm;
}

let linkingDur = false;

function syncDuration(from) {
    if (linkingDur) return;
    linkingDur = true;
    const hEl = document.getElementById("tDur");
    const mEl = document.getElementById("tDurMin");
    const timeEl = document.getElementById("tTime");
    const endEl = document.getElementById("tEnd");
    let mins = mEl ? Number(mEl.value) : 60;
    if (from === "hours" && hEl) mins = Math.round(Number(hEl.value) * 60);
    if (from === "end" && timeEl && endEl) {
        const a = minutesFromTime(timeEl.value);
        const b = minutesFromTime(endEl.value);
        if (a != null && b != null) {
            mins = b - a;
            if (mins <= 0) mins += 24 * 60;
            showMinuteDuration(true);
        }
    }
    if (!Number.isFinite(mins) || mins < 1) mins = 1;
    if (mins > 24 * 60) mins = 24 * 60;
    if (mEl) mEl.value = String(mins);
    if (hEl) hEl.value = String(Math.round((mins / 60) * 1000) / 1000);
    if (from !== "end" && timeEl && endEl && timeEl.value) {
        const a = minutesFromTime(timeEl.value);
        if (a != null) endEl.value = hmFromMinutes(a + mins);
    }
    if (from === "minutes") showMinuteDuration(true);
    if (from === "end") {
        showMinuteDuration(mins % 30 !== 0);
        showEndTime(true);
    }
    linkingDur = false;
}

let addFormBound = false;

function bindAddForm() {
    if (addFormBound) return;
    addFormBound = true;
    const hEl = document.getElementById("tDur");
    const mEl = document.getElementById("tDurMin");
    const on = document.getElementById("tMinOn");
    const timeEl = document.getElementById("tTime");
    const endEl = document.getElementById("tEnd");
    const endOn = document.getElementById("tEndOn");
    if (hEl) hEl.addEventListener("input", () => syncDuration("hours"));
    if (mEl) mEl.addEventListener("input", () => syncDuration("minutes"));
    if (on) on.addEventListener("change", () => {
        showMinuteDuration(on.checked);
        syncDuration(on.checked ? "minutes" : "hours");
    });
    if (endOn) endOn.addEventListener("change", () => showEndTime(endOn.checked));
    if (timeEl) timeEl.addEventListener("input", () => syncDuration("hours"));
    if (endEl) endEl.addEventListener("input", () => syncDuration("end"));
    syncDuration("hours");
}

function paintTasks(rows) {
    if (rows) allTasks = rows;
    markTabs();
    paintWeek();
    const list = document.getElementById("tasks");
    if (!list) return;
    list.innerHTML = "";
    const shown = filteredTasks();
    const pager = document.getElementById("taskPager");
    const prev = document.getElementById("btnTaskPrev");
    const next = document.getElementById("btnTaskNext");
    const pageLabel = document.getElementById("taskPageLabel");
    if (!shown.length) {
        list.innerHTML = "<li><span class='meta'>(none in this filter)</span></li>";
        if (pager) pager.hidden = true;
        showTaskDescription(selectedTask());
        return;
    }
    const pages = Math.max(1, Math.ceil(shown.length / TASK_PAGE_SIZE));
    if (taskPage >= pages) taskPage = pages - 1;
    if (taskPage < 0) taskPage = 0;
    const from = taskPage * TASK_PAGE_SIZE;
    const slice = shown.slice(from, from + TASK_PAGE_SIZE);
    if (pager) {
        pager.hidden = shown.length <= TASK_PAGE_SIZE;
        if (pageLabel) {
            pageLabel.textContent = (from + 1) + "–" + (from + slice.length) + " of " + shown.length;
        }
        if (prev) prev.hidden = taskPage <= 0;
        if (next) next.hidden = from + TASK_PAGE_SIZE >= shown.length;
    }
    for (const t of slice) {
        const li = document.createElement("li");
        const left = document.createElement("div");
        const head = document.createElement("div");
        head.style.display = "flex";
        head.style.alignItems = "center";
        head.style.gap = "0.35rem";
        const notes = document.createElement("input");
        notes.type = "checkbox";
        notes.checked = t.description_done === true || t.description_done === "true";
        notes.title = "Description done";
        notes.addEventListener("click", (e) => e.stopPropagation());
        notes.addEventListener("change", async () => {
            try {
                await toggleDescDone(t.id, notes.checked);
            } catch (ex) {
                showErr(ex.message);
                toast(ex.message, true);
            }
        });
        const name = document.createElement("div");
        name.textContent = publicTitle(t) || "(untitled)";
        head.appendChild(notes);
        head.appendChild(name);
        const meta = document.createElement("div");
        meta.className = "meta";
        meta.textContent = (toYmd(t.placed_date) || "unscheduled")
            + (t.placed_time ? " " + t.placed_time : "")
            + " · " + (taskState(t) || "task")
            + (hasDeadline(t) ? " · deadline" : "")
            + " · " + lifeOf(t).toLowerCase()
            + " · " + (isFinished(t) ? "finished" : "unfinished")
            + (t.description && !sealedUi(t.description) ? " · " + String(t.description).slice(0, 80) : "");
        left.appendChild(head);
        left.appendChild(meta);
        left.style.cursor = "pointer";
        left.title = "Show in description";
        left.addEventListener("click", () => showTaskDescription(t));
        const actions = document.createElement("div");
        actions.className = "row";
        const done = document.createElement("button");
        done.type = "button";
        done.className = "ghost";
        done.textContent = isFinished(t) ? "Undo" : "Finish";
        done.dataset.taskToggle = t.id;
        const reuse = document.createElement("button");
        reuse.type = "button";
        reuse.className = "ghost";
        reuse.textContent = "Fill add";
        reuse.addEventListener("click", () => fillAddFromTask(t));
        const del = document.createElement("button");
        del.type = "button";
        del.className = "danger";
        del.textContent = "Delete";
        del.dataset.taskId = t.id;
        actions.appendChild(done);
        actions.appendChild(reuse);
        actions.appendChild(del);
        li.appendChild(left);
        li.appendChild(actions);
        list.appendChild(li);
    }
    showTaskDescription(selectedTask());
}

function setTaskWhen(v) {
    taskWhen = v || "all";
    taskPage = 0;
    paintTasks();
}

function setTaskKind(v) {
    taskKind = v || "all";
    taskPage = 0;
    paintTasks();
}

async function postTaskRow(t, extra) {
    const day = toYmd(t.placed_date);
    await send("POST", "/tasks", Object.assign({
        id: t.id,
        title: t.title,
        duration: Number(t.duration || 0),
        state: t.state || "Flexible",
        placed_date: day,
        placedDate: day,
        placed_time: t.placed_time,
        placedTime: t.placed_time,
        deadline_date: t.deadline_date || null,
        deadline_time: t.deadline_time || null,
        lifecycle: lifeOf(t),
        workflow: isFinished(t) ? "FINISHED" : "UNFINISHED",
        last_updated_at: new Date().toISOString(),
        placement_updated_at: t.placement_updated_at || new Date().toISOString(),
        description: t.description || "",
        description_done: t.description_done === true || t.description_done === "true",
        parent_id: t.parent_id || t.parentId || null
    }, extra || {}));
}

async function toggleTask(id) {
    let t = allTasks.find((x) => String(x.id) === String(id));
    if (!t) return;
    t = parentOf(t);
    if (!t || isQuick(t)) return;
    const finished = isFinished(t);
    await postTaskRow(t, { workflow: finished ? "UNFINISHED" : "FINISHED" });
    if (!finished) {
        for (const q of quicksOf(t)) {
            if (!isFinished(q)) await postTaskRow(q, { workflow: "FINISHED" });
        }
    }
    await loadAll();
}

async function toggleDescDone(id, done) {
    const t = allTasks.find((x) => String(x.id) === String(id));
    if (!t) return;
    const day = toYmd(t.placed_date);
    await send("POST", "/tasks", {
        id: t.id,
        title: t.title,
        duration: Number(t.duration || 0),
        state: t.state || "Flexible",
        placed_date: day,
        placedDate: day,
        placed_time: t.placed_time,
        placedTime: t.placed_time,
        deadline_date: t.deadline_date || null,
        deadline_time: t.deadline_time || null,
        lifecycle: lifeOf(t),
        workflow: isFinished(t) ? "FINISHED" : "UNFINISHED",
        last_updated_at: new Date().toISOString(),
        placement_updated_at: t.placement_updated_at || new Date().toISOString(),
        description: t.description || "",
        description_done: !!done
    });
    await loadAll();
}

function imageIds(note) {
    const imgs = note && note.images;
    if (!Array.isArray(imgs)) return [];
    const out = [];
    for (const im of imgs) {
        if (typeof im === "string") {
            if (/^[a-f0-9]{64}$/i.test(im)) out.push({ id: im.toLowerCase(), name: "", added_at: "" });
            continue;
        }
        const id = String((im && (im.id || im.sha256)) || "").toLowerCase();
        if (/^[a-f0-9]{64}$/.test(id)) {
            out.push({
                id,
                name: (im && im.name) || "",
                added_at: (im && (im.added_at || im.addedAt)) || ""
            });
        }
    }
    return out.slice(0, 20);
}

function diaryHasContent(n) {
    if (!n) return false;
    const date = toYmd(n.date);
    const title = String(n.title || "").trim();
    const body = String(n.body || "").trim();
    if (body) return true;
    if (imageIds(n).length) return true;
    if (title && title !== date) return true;
    return false;
}

function pageNotes() {
    return (twBundle.pages || []).filter((p) => p.kind !== "folder");
}

function diaryVisible() {
    return (twBundle.diary || [])
        .filter(diaryHasContent)
        .sort((a, b) => String(a.date || "").localeCompare(String(b.date || "")));
}

function diaryIndex() {
    const idx = {};
    for (const n of diaryVisible()) {
        const day = toYmd(n.date);
        if (!day) continue;
        const y = Number(day.slice(0, 4));
        const m = Number(day.slice(5, 7));
        if (!idx[y]) idx[y] = {};
        if (!idx[y][m]) idx[y][m] = [];
        idx[y][m].push(n);
    }
    return idx;
}

function markFindSteps() {
    const y = document.getElementById("stepYear");
    const m = document.getElementById("stepMonth");
    const d = document.getElementById("stepDay");
    const hint = document.getElementById("twFindHint");
    if (y) y.classList.toggle("on", findLevel === "year");
    if (m) m.classList.toggle("on", findLevel === "month");
    if (d) d.classList.toggle("on", findLevel === "day");
    if (!hint) return;
    if (findLevel === "year") hint.textContent = "Click a year to see its months.";
    else if (findLevel === "month") hint.textContent = "Click a month to see written days.";
    else hint.textContent = "Click Show to open a day, or Delete to remove it.";
}

function paintDiaryFind() {
    const list = document.getElementById("twDiaryList");
    const title = document.getElementById("twFindTitle");
    if (!list || !title) return;
    markFindSteps();
    list.innerHTML = "";
    const idx = diaryIndex();
    const years = Object.keys(idx).map(Number).sort((a, b) => b - a);

    if (findLevel === "year") {
        title.textContent = "Years with entries";
        if (!years.length) {
            list.innerHTML = "<li><span class='meta'>(none yet)</span></li>";
            return;
        }
        const thisY = new Date().getFullYear();
        years.forEach((y) => {
            let n = 0;
            Object.values(idx[y]).forEach((days) => { n += days.length; });
            const li = document.createElement("li");
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "find-link" + (y === thisY ? " now" : "");
            btn.dataset.twFindYear = String(y);
            const k = document.createElement("div");
            k.className = "k";
            k.textContent = String(y);
            const c = document.createElement("div");
            c.className = "n";
            c.textContent = n + " written day(s)" + (y === thisY ? " · this year" : "");
            btn.appendChild(k);
            btn.appendChild(c);
            li.appendChild(btn);
            list.appendChild(li);
        });
        return;
    }

    if (findLevel === "month") {
        title.textContent = findYear + " — months with entries";
        const months = Object.keys(idx[findYear] || {}).map(Number).sort((a, b) => a - b);
        if (!months.length) {
            list.innerHTML = "<li><span class='meta'>(none)</span></li>";
            return;
        }
        const thisY = new Date().getFullYear();
        const thisM = new Date().getMonth() + 1;
        months.forEach((m) => {
            const here = findYear === thisY && m === thisM;
            const li = document.createElement("li");
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "find-link" + (here ? " now" : "");
            btn.dataset.twFindMonth = String(m);
            const k = document.createElement("div");
            k.className = "k";
            k.textContent = MONTHS[m - 1];
            const c = document.createElement("div");
            c.className = "n";
            c.textContent = idx[findYear][m].length + " written day(s)"
                + (here ? " · this month" : "");
            btn.appendChild(k);
            btn.appendChild(c);
            li.appendChild(btn);
            list.appendChild(li);
        });
        return;
    }

    title.textContent = MONTHS[(findMonth || 1) - 1] + " " + findYear;
    const days = ((idx[findYear] || {})[findMonth] || []).slice()
        .sort((a, b) => String(a.date).localeCompare(String(b.date)));
    if (!days.length) {
        list.innerHTML = "<li><span class='meta'>(none)</span></li>";
        return;
    }
    const today = todayYmd();
    days.forEach((n) => {
        const li = document.createElement("li");
        const imgs = imageIds(n);
        const day = toYmd(n.date);
        const left = labeledBlock(
            n.title || n.date,
            (day || "")
                + (imgs.length ? " · " + imgs.length + " img" : "")
                + (day === today ? " · today" : "")
        );
        const actions = document.createElement("div");
        actions.className = "row";
        const show = document.createElement("button");
        show.type = "button";
        show.className = "ghost";
        show.textContent = "Show";
        show.dataset.twKind = "diary";
        show.dataset.twKey = n.date;
        const del = document.createElement("button");
        del.type = "button";
        del.className = "danger";
        del.textContent = "Delete";
        del.dataset.twDelDiary = n.date;
        actions.appendChild(show);
        actions.appendChild(del);
        li.appendChild(left);
        li.appendChild(actions);
        list.appendChild(li);
    });
}

function twFindBack() {
    if (findLevel === "day") {
        findLevel = "month";
        findMonth = null;
    } else if (findLevel === "month") {
        findLevel = "year";
        findYear = null;
    } else {
        findLevel = "year";
        findYear = null;
        findMonth = null;
    }
    paintDiaryFind();
}

async function deleteDiaryDay(day) {
    const date = toYmd(day);
    if (!date) return;
    if (!confirm("Delete diary " + date + "?")) return;
    twBundle.diary = (twBundle.diary || []).filter((n) => toYmd(n.date) !== date);
    if (twOpen && twOpen.kind === "diary" && toYmd(twOpen.key) === date) {
        twOpen = null;
        const view = document.getElementById("twView");
        if (view) view.style.display = "none";
    }
    await saveBundle();
    paintTwLists();
    noteOk("Deleted");
}

function hideTwView() {
    twOpen = null;
    const view = document.getElementById("twView");
    if (view) view.style.display = "none";
    setTwMsg("");
    syncTwAttachUi();
}

function currentTwTab() {
    const diary = document.getElementById("paneDiary");
    const lists = document.getElementById("paneLists");
    if (diary && diary.classList.contains("on")) return "diary";
    if (lists && lists.classList.contains("on")) return "lists";
    return "pages";
}

function setTab(name) {
    const paneDiary = document.getElementById("paneDiary");
    const panePages = document.getElementById("panePages");
    const paneLists = document.getElementById("paneLists");
    if (!paneDiary) return;
    paneDiary.classList.toggle("on", name === "diary");
    if (panePages) panePages.classList.toggle("on", name === "pages");
    if (paneLists) paneLists.classList.toggle("on", name === "lists");
    const tabDiary = document.getElementById("tabDiary");
    const tabPages = document.getElementById("tabPages");
    const tabLists = document.getElementById("tabLists");
    if (tabDiary) tabDiary.className = name === "diary" ? "active" : "ghost";
    if (tabPages) tabPages.className = name === "pages" ? "active" : "ghost";
    if (tabLists) tabLists.className = name === "lists" ? "active" : "ghost";
    hideTwView();
}

function paintTwLists() {
    const plist = document.getElementById("twPageList");
    const llist = document.getElementById("twLists");
    if (!plist || !llist) return;
    paintDiaryFind();
    plist.innerHTML = "";
    llist.innerHTML = "";

    const pages = pageNotes();
    if (!pages.length) plist.innerHTML = "<li><span class='meta'>(no pages)</span></li>";
    pages.forEach((n) => {
        const li = document.createElement("li");
        const imgs = imageIds(n);
        const left = labeledBlock(
            n.title || "page",
            "page" + (imgs.length ? " · " + imgs.length + " img" : "")
        );
        const actions = document.createElement("div");
        actions.className = "row";
        const show = document.createElement("button");
        show.type = "button";
        show.className = "ghost";
        show.textContent = "Show";
        show.dataset.twKind = "page";
        show.dataset.twKey = n.id;
        const del = document.createElement("button");
        del.type = "button";
        del.className = "danger";
        del.textContent = "Delete";
        del.dataset.twDelPage = n.id;
        actions.appendChild(show);
        actions.appendChild(del);
        li.appendChild(left);
        li.appendChild(actions);
        plist.appendChild(li);
    });

    const lists = twBundle.lists || [];
    if (!lists.length) llist.innerHTML = "<li><span class='meta'>(no lists)</span></li>";
    lists.forEach((n) => {
        const li = document.createElement("li");
        const left = labeledBlock(
            n.title || "list",
            ((n.items && n.items.length) || 0) + " items"
        );
        const actions = document.createElement("div");
        actions.className = "row";
        const show = document.createElement("button");
        show.type = "button";
        show.className = "ghost";
        show.textContent = "Show";
        show.dataset.twKind = "list";
        show.dataset.twKey = n.id;
        const del = document.createElement("button");
        del.type = "button";
        del.className = "danger";
        del.textContent = "Delete";
        del.dataset.twDelList = n.id;
        actions.appendChild(show);
        actions.appendChild(del);
        li.appendChild(left);
        li.appendChild(actions);
        llist.appendChild(li);
    });
}

function findDiary(date) {
    const day = toYmd(date);
    return (twBundle.diary || []).find((n) => toYmd(n.date) === day) || null;
}

function findPage(id) {
    return (twBundle.pages || []).find((n) => n.kind !== "folder" && String(n.id) === String(id)) || null;
}

function findList(id) {
    return (twBundle.lists || []).find((n) => String(n.id) === String(id)) || null;
}

function refreshTwLineNos() {
    const body = document.getElementById("twBody");
    const nos = document.getElementById("twLineNos");
    if (!body || !nos) return;
    const n = Math.max(1, String(body.value || "").split(/\r\n|\n/).length);
    let s = "";
    for (let i = 1; i <= n; i++) s += i + "\n";
    nos.textContent = s;
    nos.style.height = body.clientHeight + "px";
    nos.scrollTop = body.scrollTop;
}

function hookTwLineNos() {
    const body = document.getElementById("twBody");
    if (!body || body.dataset.lineNos === "1") return;
    body.dataset.lineNos = "1";
    body.addEventListener("input", refreshTwLineNos);
    body.addEventListener("scroll", () => {
        const nos = document.getElementById("twLineNos");
        if (nos) nos.scrollTop = body.scrollTop;
    });
    if (window.ResizeObserver) {
        new ResizeObserver(() => refreshTwLineNos()).observe(body);
    }
    refreshTwLineNos();
}

async function showTw(kind, key) {
    const view = document.getElementById("twView");
    const title = document.getElementById("twTitle");
    const meta = document.getElementById("twMeta");
    const body = document.getElementById("twBody");
    const imgs = document.getElementById("twImgs");
    if (!view || !title || !meta || !body || !imgs) return;
    if (twOpen) applyOpenNoteFields();
    view.style.display = "block";
    imgs.innerHTML = "";
    twOpen = { kind, key, rawBody: "", rawItems: null };
    setTwMsg("");

    try {
    if (kind === "diary") {
        const n = findDiary(key);
        if (!n) {
            title.value = "";
            body.value = "";
            meta.textContent = key + " · not found";
            return;
        }
        title.value = n.title || n.date || "";
        twOpen.rawBody = n.body || "";
        body.value = htmlToPlain(n.body || "");
        meta.textContent = "diary · " + n.date;
        await loadImages(n, imgs, meta, "diary · " + n.date);
        return;
    }
    if (kind === "page") {
        const n = findPage(key);
        if (!n) {
            title.value = "";
            body.value = "";
            meta.textContent = "page missing";
            return;
        }
        title.value = n.title || "";
        twOpen.rawBody = n.body || "";
        body.value = htmlToPlain(n.body || "");
        meta.textContent = "page";
        await loadImages(n, imgs, meta, "page");
        return;
    }
    const n = findList(key);
    if (!n) {
        title.value = "";
        body.value = "";
        meta.textContent = "list missing";
        return;
    }
    title.value = n.title || "";
    twOpen.rawItems = n.items || [];
    body.value = (n.items || []).map((it) =>
        (it.done ? "[x] " : "[ ] ") + htmlToPlain(it.text || "")).join("\n");
    meta.textContent = "list · one item per line, [x] = done";
    } finally {
        refreshTwLineNos();
        syncTwAttachUi();
    }
}

async function loadImages(note, imgs, meta, label) {
    const list = imageIds(note);
    if (!list.length) {
        meta.textContent = label + " · no images";
        return;
    }
    meta.textContent = label + " · loading " + list.length + " image(s)";
    for (const im of list) {
        try {
            const row = await send("GET", "/typewriter/images/" + im.id);
            if (!row || !row.data) continue;
            const wrap = document.createElement("div");
            wrap.className = "tw-img";
            const pic = document.createElement("img");
            pic.alt = im.name || im.id;
            pic.src = "data:" + (row.mime || "image/png") + ";base64," + row.data;
            const cap = document.createElement("div");
            cap.className = "meta";
            const when = im.added_at ? String(im.added_at).slice(0, 10) : "";
            cap.textContent = (im.name || "image") + (when ? " · " + when : "");
            const rm = document.createElement("button");
            rm.type = "button";
            rm.className = "ghost";
            rm.textContent = "Remove";
            rm.addEventListener("click", () => removeTwImage(im.id));
            wrap.appendChild(pic);
            wrap.appendChild(cap);
            wrap.appendChild(rm);
            imgs.appendChild(wrap);
        } catch (_) {
            const miss = document.createElement("div");
            miss.className = "meta";
            miss.textContent = "image missing: " + (im.name || im.id);
            imgs.appendChild(miss);
        }
    }
    meta.textContent = label + " · " + list.length + " image(s)";
}

function lastTwLocked() {
    const V = window.AshoreVault;
    if (V && V.sealed(lastTwSent)) return true;
    try {
        return twStillCipher(JSON.parse(lastTwSent || "{}"));
    } catch (_) {
        return false;
    }
}

async function saveBundle() {
    const content = JSON.stringify(twBundle);
    if (lastTwLocked() && content === JSON.stringify(emptyBundle())) {
        return;
    }
    lastTwSent = content;
    await send("POST", "/typewriter", {
        content,
        last_updated_at: new Date().toISOString()
    });
}

function twStillCipher(bundle) {
    const V = window.AshoreVault;
    if (!V || !bundle) return false;
    const notes = [].concat(bundle.diary || [], bundle.pages || []);
    if (notes.some((p) => V.stillCipher(p && p.title) || V.stillCipher(p && p.body))) return true;
    return (bundle.lists || []).some((l) => V.stillCipher(l && l.title)
        || (l.items || []).some((it) => V.stillCipher(it && it.text)));
}

function applyTypewriter(tw) {
    hideTwView();
    const raw = (tw && tw.content) || "";
    if (window.AshoreVault && window.AshoreVault.sealed(raw)) {
        twBundle = emptyBundle();
        lastTwSent = raw;
        paintTwLists();
        return;
    }
    let parsed = null;
    try { parsed = raw ? JSON.parse(raw) : null; } catch (_) { parsed = null; }
    if (parsed && typeof parsed === "object") {
        twBundle = {
            diary: Array.isArray(parsed.diary) ? parsed.diary : [],
            lists: Array.isArray(parsed.lists) ? parsed.lists : [],
            pages: Array.isArray(parsed.pages) ? parsed.pages : []
        };
    } else {
        twBundle = emptyBundle();
    }
    if (twStillCipher(twBundle) && !(window.AshoreVault && window.AshoreVault.enabled())) {
        lastTwSent = raw;
        twBundle = emptyBundle();
        paintTwLists();
        return;
    }
    lastTwSent = JSON.stringify(twBundle);
    paintTwLists();
}

async function findDiaryClick() {
    const dayEl = document.getElementById("twDay");
    const day = toYmd(dayEl && dayEl.value);
    if (!day) throw new Error("pick a diary date");
    const n = findDiary(day);
    if (!n || !diaryHasContent(n)) {
        if (!confirm("No written diary for " + day + ". Create it?")) return;
        if (!n) twBundle.diary.push({ date: day, id: nid(), title: day, body: "", images: [] });
    }
    setTab("diary");
    await showTw("diary", day);
}

async function addPage() {
    const titleEl = document.getElementById("twPageTitle");
    const title = clipTitle(((titleEl && titleEl.value) || "") || "page") || "page";
    const n = {
        id: nid(),
        parent_id: "root",
        kind: "note",
        title,
        body: "",
        images: [],
        pos: (twBundle.pages || []).length
    };
    twBundle.pages.push(n);
    if (titleEl) titleEl.value = "";
    await saveBundle();
    paintTwLists();
    setTab("pages");
    await showTw("page", n.id);
    noteOk("Saved");
}

async function addList() {
    const titleEl = document.getElementById("twListTitle");
    const title = clipTitle(((titleEl && titleEl.value) || "") || "list") || "list";
    const n = { id: nid(), title, items: [] };
    twBundle.lists.push(n);
    if (titleEl) titleEl.value = "";
    await saveBundle();
    paintTwLists();
    setTab("lists");
    await showTw("list", n.id);
    noteOk("Saved");
}

function parseListBody(text) {
    const lines = String(text || "").split(/\r?\n/).filter((s) => s.trim() !== "");
    if (lines.length > 500) toast("A list can have at most 500 items", true);
    return lines.slice(0, 500).map((line) => {
        const done = /^\s*\[x\]\s*/i.test(line);
        const item = line.replace(/^\s*\[(x| )\]\s*/i, "");
        return { text: item.length > 8000 ? item.slice(0, 8000) : item, done };
    });
}

async function saveOpenNote() {
    if (!twOpen) throw new Error("nothing open");
    applyOpenNoteFields();
    if (twOpen.kind === "diary") {
        const n = findDiary(twOpen.key);
        if (!n) throw new Error("diary missing");
        if (!diaryHasContent(n)) throw new Error("write something before saving a diary day");
    } else if (twOpen.kind === "page") {
        if (!findPage(twOpen.key)) throw new Error("page missing");
    } else if (twOpen.kind === "list") {
        if (!findList(twOpen.key)) throw new Error("list missing");
    } else {
        throw new Error("nothing open");
    }
    await saveBundle();
    paintTwLists();
    noteOk("Saved");
}

function syncTwAttachUi() {
    const row = document.getElementById("twAttachRow");
    if (!row) return;
    const on = !!(twOpen && (twOpen.kind === "diary" || twOpen.kind === "page"));
    row.style.display = on ? "flex" : "none";
    const hint = document.getElementById("twAttachHint");
    const note = openTwNote();
    const count = note ? imageIds(note).length : 0;
    if (hint) hint.textContent = count + " / 20 images · png, jpg, gif, webp · 8 MB each";
    const btn = document.getElementById("btnTwAttach");
    if (btn) btn.disabled = count >= 20;
}

function openTwNote() {
    if (!twOpen) return null;
    if (twOpen.kind === "diary") return findDiary(twOpen.key);
    if (twOpen.kind === "page") return findPage(twOpen.key);
    return null;
}

function applyOpenNoteFields() {
    if (!twOpen) return;
    const titleEl = document.getElementById("twTitle");
    const bodyEl = document.getElementById("twBody");
    const title = clipTitle(titleEl ? titleEl.value : "");
    const body = bodyEl ? bodyEl.value : "";
    if (twOpen.kind === "diary") {
        const n = findDiary(twOpen.key);
        if (!n) return;
        n.title = title;
        n.body = clipBody((looksLikeHtml(twOpen.rawBody) && body === htmlToPlain(twOpen.rawBody))
            ? twOpen.rawBody : body);
        return;
    }
    if (twOpen.kind === "page") {
        const n = findPage(twOpen.key);
        if (!n) return;
        n.title = title;
        n.body = clipBody((looksLikeHtml(twOpen.rawBody) && body === htmlToPlain(twOpen.rawBody))
            ? twOpen.rawBody : body);
        return;
    }
    if (twOpen.kind === "list") {
        const n = findList(twOpen.key);
        if (!n) return;
        n.title = title;
        const shown = (twOpen.rawItems || []).map((it) =>
            (it.done ? "[x] " : "[ ] ") + htmlToPlain(it.text || "")).join("\n");
        n.items = (body === shown) ? twOpen.rawItems : parseListBody(body);
    }
}

function twMimeFromName(name, fallback) {
    const n = String(name || "").toLowerCase();
    if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
    if (n.endsWith(".gif")) return "image/gif";
    if (n.endsWith(".webp")) return "image/webp";
    if (n.endsWith(".png")) return "image/png";
    return fallback || "";
}

async function sha256Hex(buf) {
    const hash = await crypto.subtle.digest("SHA-256", buf);
    return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function bufToB64(buf) {
    const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
    let s = "";
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
        s += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    }
    return btoa(s);
}

function u8Concat(parts) {
    let n = 0;
    for (const p of parts) n += p.length;
    const out = new Uint8Array(n);
    let o = 0;
    for (const p of parts) {
        out.set(p, o);
        o += p.length;
    }
    return out;
}

function u8Ascii(u8, start, end) {
    let s = "";
    for (let i = start; i < end; i++) s += String.fromCharCode(u8[i]);
    return s;
}

function u16be(u8, i) {
    return (u8[i] << 8) | u8[i + 1];
}

function u32be(u8, i) {
    return ((u8[i] << 24) | (u8[i + 1] << 16) | (u8[i + 2] << 8) | u8[i + 3]) >>> 0;
}

function u32le(u8, i) {
    return (u8[i] | (u8[i + 1] << 8) | (u8[i + 2] << 16) | (u8[i + 3] << 24)) >>> 0;
}

function putU32le(u8, i, v) {
    u8[i] = v & 0xff;
    u8[i + 1] = (v >>> 8) & 0xff;
    u8[i + 2] = (v >>> 16) & 0xff;
    u8[i + 3] = (v >>> 24) & 0xff;
}

function isPngU8(b) {
    return b.length >= 8 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47;
}

function isJpegU8(b) {
    return b.length >= 4 && b[0] === 0xff && b[1] === 0xd8;
}

function isGifU8(b) {
    return b.length >= 6 && b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46;
}

function isWebpU8(b) {
    return b.length >= 12
        && b[0] === 0x52 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x46
        && b[8] === 0x57 && b[9] === 0x45 && b[10] === 0x42 && b[11] === 0x50;
}

function renameImageExt(name, ext) {
    const raw = String(name || "image").replace(/[/\\]/g, "");
    const base = raw.replace(/\.[A-Za-z0-9]+$/, "");
    return (base || "image").slice(0, 100) + ext;
}

function stripPngU8(buf) {
    if (!isPngU8(buf)) return buf;
    const keep = new Set(["IHDR", "PLTE", "IDAT", "IEND", "tRNS"]);
    const out = [buf.subarray(0, 8)];
    let i = 8;
    while (i + 12 <= buf.length) {
        const len = u32be(buf, i);
        if (i + 12 + len > buf.length) break;
        const type = u8Ascii(buf, i + 4, i + 8);
        if (keep.has(type)) out.push(buf.subarray(i, i + 12 + len));
        i += 12 + len;
        if (type === "IEND") break;
    }
    return u8Concat(out);
}

function stripJpegU8(buf) {
    if (!isJpegU8(buf)) return buf;
    const out = [buf.subarray(0, 2)];
    let i = 2;
    while (i + 2 <= buf.length) {
        if (buf[i] !== 0xff) {
            out.push(buf.subarray(i));
            break;
        }
        let j = i;
        while (j < buf.length && buf[j] === 0xff) j++;
        if (j >= buf.length) break;
        const marker = buf[j];
        if (marker === 0xd9) {
            out.push(buf.subarray(i, j + 1));
            break;
        }
        if (marker === 0xda) {
            out.push(buf.subarray(i));
            break;
        }
        if (marker >= 0xd0 && marker <= 0xd7) {
            out.push(buf.subarray(i, j + 1));
            i = j + 1;
            continue;
        }
        if (j + 3 > buf.length) break;
        const len = u16be(buf, j + 1);
        const end = j + 1 + len;
        if (end > buf.length) break;
        const drop = marker === 0xfe || (marker >= 0xe1 && marker <= 0xef);
        if (!drop) out.push(buf.subarray(i, end));
        i = end;
    }
    return u8Concat(out);
}

function gifSkipSubBlocks(buf, start) {
    let i = start;
    while (i < buf.length) {
        const sz = buf[i];
        i += 1;
        if (sz === 0) return i - start;
        i += sz;
    }
    return i - start;
}

function stripGifU8(buf) {
    if (!isGifU8(buf) || buf.length < 13) return buf;
    const out = [];
    out.push(buf.subarray(0, 13));
    let i = 13;
    const packed = buf[10];
    if (packed & 0x80) {
        const gct = 3 * (1 << ((packed & 7) + 1));
        if (i + gct > buf.length) return buf;
        out.push(buf.subarray(i, i + gct));
        i += gct;
    }
    while (i < buf.length) {
        const b = buf[i];
        if (b === 0x3b) {
            out.push(buf.subarray(i, i + 1));
            break;
        }
        if (b === 0x2c) {
            if (i + 10 > buf.length) break;
            const imgPacked = buf[i + 9];
            let n = 10;
            if (imgPacked & 0x80) n += 3 * (1 << ((imgPacked & 7) + 1));
            n += 1;
            n += gifSkipSubBlocks(buf, i + n);
            out.push(buf.subarray(i, i + n));
            i += n;
            continue;
        }
        if (b === 0x21) {
            if (i + 2 > buf.length) break;
            const label = buf[i + 1];
            let n = 2;
            if (label === 0xf9) {
                n = 8;
                out.push(buf.subarray(i, i + n));
                i += n;
                continue;
            }
            n += gifSkipSubBlocks(buf, i + n);
            const keepLoop = label === 0xff && buf.length >= i + 13
                && u8Ascii(buf, i + 3, i + 11) === "NETSCAPE";
            if (keepLoop) out.push(buf.subarray(i, i + n));
            i += n;
            continue;
        }
        break;
    }
    return u8Concat(out);
}

function stripWebpU8(buf) {
    if (!isWebpU8(buf) || buf.length < 12) return buf;
    const chunks = [];
    let i = 12;
    while (i + 8 <= buf.length) {
        const fourcc = u8Ascii(buf, i, i + 4);
        const size = u32le(buf, i + 4);
        let total = 8 + size;
        if (total & 1) total += 1;
        if (i + total > buf.length) break;
        if (fourcc !== "EXIF" && fourcc !== "XMP " && fourcc !== "ICCP") {
            chunks.push(buf.subarray(i, i + total));
        }
        i += total;
    }
    const body = u8Concat(chunks);
    const out = new Uint8Array(12 + body.length);
    out.set(buf.subarray(0, 8), 0);
    putU32le(out, 4, body.length + 4);
    out.set(buf.subarray(8, 12), 8);
    out.set(body, 12);
    return out;
}

function stripImageMetaBrowser(input, name) {
    const buf = input instanceof Uint8Array ? input : new Uint8Array(input);
    if (isPngU8(buf)) {
        return { bytes: stripPngU8(buf), mime: "image/png", name: renameImageExt(name, ".png") };
    }
    if (isJpegU8(buf)) {
        return { bytes: stripJpegU8(buf), mime: "image/jpeg", name: renameImageExt(name, ".jpg") };
    }
    if (isGifU8(buf)) {
        return { bytes: stripGifU8(buf), mime: "image/gif", name: renameImageExt(name, ".gif") };
    }
    if (isWebpU8(buf)) {
        return { bytes: stripWebpU8(buf), mime: "image/webp", name: renameImageExt(name, ".webp") };
    }
    return null;
}

async function stripPhotoForUpload(file, mime) {
    void mime;
    const raw = new Uint8Array(await file.arrayBuffer());
    const cleaned = stripImageMetaBrowser(raw, file.name);
    if (!cleaned || !cleaned.bytes || cleaned.bytes.length < 24) {
        throw new Error((file.name || "image") + ": metadata wipe failed");
    }
    return { buf: cleaned.bytes, mime: cleaned.mime, name: cleaned.name };
}

function pickTwImages() {
    const input = document.getElementById("twImgFile");
    if (input) input.click();
}

async function attachTwImages(fileList) {
    applyOpenNoteFields();
    const note = openTwNote();
    if (!note) throw new Error("open a page or diary first");
    const files = Array.from(fileList || []);
    if (!files.length) return;
    const allow = new Set(["image/png", "image/jpeg", "image/gif", "image/webp"]);
    if (!Array.isArray(note.images)) note.images = [];
    if (imageIds(note).length >= 20) {
        toast("This note already has 20 images", true);
        return;
    }
    const kind = twOpen.kind === "page" ? "pages" : "diary";
    let added = 0;
    let skippedCap = false;
    let rateLimited = false;
    for (const file of files) {
        const mime = twMimeFromName(file.name, file.type);
        if (!allow.has(mime)) {
            throw new Error((file.name || "file") + " is not png/jpg/gif/webp");
        }
        const stripped = await stripPhotoForUpload(file, mime);
        if (stripped.buf.byteLength > 8 * 1024 * 1024) {
            throw new Error((file.name || "image") + " is over 8 MB");
        }
        const sha = await sha256Hex(stripped.buf);
        const imgName = clipImageName(stripped.name || file.name || "image.png");
        const alreadySha = note.images.some((im) => {
            const id = typeof im === "string" ? im : (im && (im.id || im.sha256));
            return String(id || "").toLowerCase() === sha;
        });
        if (alreadySha) continue;
        if (imageIds(note).length >= 20) {
            skippedCap = true;
            break;
        }
        try {
            const posted = await send("POST", "/typewriter/images", {
                sha256: sha,
                name: imgName,
                mime: stripped.mime || mime,
                kind,
                data: bufToB64(stripped.buf)
            });
            const storedSha = String((posted && (posted.sha256 || posted.sha)) || sha).toLowerCase();
            note.images.push({
                id: storedSha,
                sha256: storedSha,
                name: (posted && posted.name) || imgName,
                added_at: new Date().toISOString()
            });
            added += 1;
            continue;
        } catch (ex) {
            const msg = String(ex && ex.message ? ex.message : ex);
            if (/429|too many|wait a minute/i.test(msg)) {
                rateLimited = true;
                break;
            }
            throw ex;
        }
    }
    if (added) {
        await saveBundle();
        await showTw(twOpen.kind, twOpen.key);
    }
    if (rateLimited) toast("Too many image uploads just now. Wait a minute, then retry.", true);
    else if (skippedCap) toast("This note already has 20 images", true);
    else noteOk(added ? "Attached " + added + " image(s)" : "Already attached");
}

async function removeTwImage(id) {
    applyOpenNoteFields();
    const note = openTwNote();
    if (!note || !Array.isArray(note.images)) return;
    const want = String(id || "").toLowerCase();
    note.images = note.images.filter((im) => {
        const got = typeof im === "string" ? im : (im && (im.id || im.sha256));
        return String(got || "").toLowerCase() !== want;
    });
    await saveBundle();
    await showTw(twOpen.kind, twOpen.key);
    noteOk("Removed");
}

function hookTwAttach() {
    const btn = document.getElementById("btnTwAttach");
    const input = document.getElementById("twImgFile");
    if (btn && btn.dataset.hooked !== "1") {
        btn.dataset.hooked = "1";
        btn.addEventListener("click", pickTwImages);
    }
    if (input && input.dataset.hooked !== "1") {
        input.dataset.hooked = "1";
        input.addEventListener("change", async () => {
            try {
                await attachTwImages(input.files);
            } catch (ex) {
                showErr(ex && ex.message ? ex.message : String(ex));
            }
            input.value = "";
        });
    }
}

async function deleteOpenNote() {
    if (!twOpen) throw new Error("nothing open");
    const kind = twOpen.kind === "diary" ? "diary day" : twOpen.kind === "page" ? "page" : "list";
    if (!confirm("Delete this " + kind + "?")) return;
    if (twOpen.kind === "diary") {
        twBundle.diary = (twBundle.diary || []).filter((n) => toYmd(n.date) !== toYmd(twOpen.key));
    } else if (twOpen.kind === "page") {
        twBundle.pages = (twBundle.pages || []).filter((n) => String(n.id) !== String(twOpen.key));
    } else {
        twBundle.lists = (twBundle.lists || []).filter((n) => String(n.id) !== String(twOpen.key));
    }
    hideTwView();
    await saveBundle();
    paintTwLists();
    noteOk("Deleted");
}

async function deletePage(id) {
    if (!confirm("Delete this page?")) return;
    twBundle.pages = (twBundle.pages || []).filter((n) => String(n.id) !== String(id));
    if (twOpen && twOpen.kind === "page" && String(twOpen.key) === String(id)) hideTwView();
    await saveBundle();
    paintTwLists();
    noteOk("Deleted");
}

async function deleteList(id) {
    if (!confirm("Delete this list?")) return;
    twBundle.lists = (twBundle.lists || []).filter((n) => String(n.id) !== String(id));
    if (twOpen && twOpen.kind === "list" && String(twOpen.key) === String(id)) hideTwView();
    await saveBundle();
    paintTwLists();
    noteOk("Deleted");
}

async function loadAll() {
    const tab = currentTwTab();
    const reopen = twOpen ? { kind: twOpen.kind, key: twOpen.key } : null;
    if (twOpen) {
        applyOpenNoteFields();
        try {
            if (JSON.stringify(twBundle) !== lastTwSent) await saveBundle();
        } catch (_) { /* keep going so tasks still refresh */ }
    }
    const [tasksRes, blockedRes, tw] = await Promise.all([
        send("GET", "/tasks"),
        send("GET", "/blocked"),
        send("GET", "/typewriter")
    ]);
    paintTasks([].concat(tasksRes.tasks || [], tasksRes.nested || []));
    paintBlocked(blockedRes.blocked || []);
    applyTypewriter(tw);
    setTab(tab);
    if (reopen) await showTw(reopen.kind, reopen.key);
    if (!carrying) {
        carrying = true;
        try {
            const expired = await expirePastBlocked(blockedRes.blocked || []);
            if (expired) await loadAll();
        } catch (_) { /* keep the list that just painted */ }
        carrying = false;
    }
}

function setVal(id, value) {
    const el = document.getElementById(id);
    if (el) el.value = value;
}

function stillEncryptedVisible() {
    const V = window.AshoreVault;
    if (!V) return false;
    if ((allTasks || []).some((t) => V.stillCipher(t.title) || V.stillCipher(t.description))) return true;
    if ((allBlocked || []).some((b) => V.stillCipher(b.title))) return true;
    if (V.stillCipher(lastTwSent) || (twBundle.pages || []).some((p) => V.stillCipher(p.title) || V.stillCipher(p.body))) return true;
    if ((twBundle.diary || []).some((n) => V.stillCipher(n.title) || V.stillCipher(n.body))) return true;
    return false;
}

function finishVaultProbe() {
    const V = window.AshoreVault;
    if (!V) return;
    const saw = V.consumeSawSealed();
    if (saw && !stillEncryptedVisible()) V.setWanted(true);
}

async function unlockVault(uid, password) {
    const V = window.AshoreVault;
    const id = uid || localStorage.getItem("ashoreUserId") || (V && V.userId());
    if (!id || !password) return false;
    let nodeOk = false;
    if (V && V.canSubtle && V.canSubtle()) {
        try {
            await V.unlock(id, password);
        } catch (_) { /* Account Security fold reports this */ }
    }
    try {
        await send("POST", "/vault/unlock", { password: password });
        nodeOk = true;
        if (V) {
            V.setWanted(true);
            V.setUserId(id);
        }
    } catch (e) {
        if (!(V && V.enabled())) return false;
    }
    await loadAll();
    const still = stillEncryptedVisible();
    if (still && !nodeOk && !(V && V.enabled())) {
        if (V) V.lock();
        return false;
    }
    if (V && (nodeOk || V.enabled())) V.setWanted(true);
    return !still;
}

async function afterLogin(data, password) {
    setToken(data && data.token);
    if (data && data.id) localStorage.setItem("ashoreUserId", data.id);
    if (data && data.username) localStorage.setItem("ashoreUsername", data.username);
    const who = document.getElementById("who");
    const userEl = document.getElementById("user");
    if (who) who.textContent = "Logged in as " + ((data && data.username) || (userEl && userEl.value) || "");
    if (auth) auth.style.display = "none";
    if (app) app.style.display = "block";
    setVal("tDate", todayYmd());
    setVal("tTime", "09:00");
    setVal("bFrom", todayYmd());
    setVal("bTo", todayYmd());
    setVal("twDay", todayYmd());
    weekOffset = 0;
    monthOffset = 0;
    applyTheme();
    if (password || !sessionStorage.getItem(IDLE_AT_KEY)) bumpIdleActivity();
    startIdleWatch();
    bindAddForm();
    findLevel = "year";
    findYear = null;
    findMonth = null;
    syncBlockedDates();
    if (password && data && data.id) {
        await unlockVault(data.id, password);
    } else {
        await loadAll();
        finishVaultProbe();
    }
    if (wantReminders()) startReminderWatch();
    showPageFromHash();
    window.dispatchEvent(new Event("ashore-login"));
}

async function doLogin(register) {
    if (loginBusy) return;
    const userEl = document.getElementById("user");
    const passEl = document.getElementById("pass");
    const username = ((userEl && userEl.value) || "").trim();
    const password = (passEl && passEl.value) || "";
    showErr("");
    if (!username || !password) {
        showErr("username and password required");
        toast("username and password required", true);
        return;
    }
    setLoginBusy(true);
    try {
        const payload = { username, password };
        if (register) {
            const weak = ashorePasswordReject(password);
            if (weak) {
                showErr(weak);
                toast(weak, true);
                return;
            }
            payload.email = ((document.getElementById("email") || {}).value || "").trim();
            payload.question = ((document.getElementById("question") || {}).value || "").trim();
            payload.answer = ((document.getElementById("answer") || {}).value || "").trim();
            if (!payload.email || !payload.question || !payload.answer) {
                showErr("register needs email, security question, and answer");
                toast("register needs email, security question, and answer", true);
                return;
            }
        }
        const data = await send("POST", register ? "/register" : "/login", payload);
        await afterLogin(data, password);
    } catch (ex) {
        showErr(ex.message);
        toast(ex.message, true);
    } finally {
        setLoginBusy(false);
    }
}

function addDaysYmd(ymd, n) {
    const d = new Date(String(ymd || "") + "T12:00:00");
    if (Number.isNaN(d.getTime())) return ymd;
    d.setDate(d.getDate() + n);
    return ymdFromDate(d);
}

function rangesOverlap(a0, a1, b0, b1) {
    return a0 < b1 && b0 < a1;
}

function blockedAppliesOn(b, day) {
    const dated = toYmd(b.specific_date);
    if (dated) return dated === day;
    const w = weekdayOf(b);
    if (w != null) return w === isoDow(day);
    return isDailyRow(b);
}

function localTaskWindow(t) {
    const day = toYmd(t.placed_date || t.placedDate);
    const start = minutes(t.placed_time || t.placedTime);
    const dur = Number(t.duration || 0);
    if (!day || start == null || !(dur > 0)) return null;
    const durMin = Math.round(dur * 60);
    const col = columnDateFor(day, t.placed_time || t.placedTime);
    const grid0 = start >= 6 * 60 ? start - 6 * 60 : 18 * 60 + start;
    return { day, start, end: start + durMin, col, grid0, grid1: grid0 + durMin };
}

function gridPiecesLocal(win) {
    const colMin = 24 * 60;
    const out = [{ col: win.col, a: win.grid0, b: Math.min(win.grid1, colMin) }];
    if (win.grid1 > colMin) out.push({ col: addDaysYmd(win.col, 1), a: 0, b: win.grid1 - colMin });
    return out;
}

function localTaskClash(task) {
    const win = localTaskWindow(task);
    if (!win) return null;
    const pid = String(task.parent_id || task.parentId || "");
    if (pid) return null;
    for (const t of allTasks) {
        if (String(t.id) === String(task.id)) continue;
        const oid = String(t.parent_id || t.parentId || "");
        if (oid) continue;
        const other = localTaskWindow(t);
        if (!other) continue;
        for (const p of gridPiecesLocal(win)) {
            for (const q of gridPiecesLocal(other)) {
                if (p.col === q.col && rangesOverlap(p.a, p.b, q.a, q.b)) {
                    return "Overlaps task: " + (t.title || "task");
                }
            }
        }
    }
    return null;
}

async function addTask() {
    showErr("");
    const title = document.getElementById("tTitle").value.trim();
    const minOn = document.getElementById("tMinOn");
    const duration = (minOn && minOn.checked)
        ? Number(document.getElementById("tDurMin").value) / 60
        : Number(document.getElementById("tDur").value);
    const parentId = pendingQuickParent || "";
    if (parentId) reuseTaskId = "";
    const state = document.getElementById("tState").value;
    const date = toYmd(document.getElementById("tDate").value);
    let time = document.getElementById("tTime").value;
    if (!title) throw new Error("title required");
    if (title.length > 100) throw new Error("title max 100 characters");
    const description = document.getElementById("tDesc").value.trim();
    if (description.length > 8000) throw new Error("description max 8000 characters");
    if (!Number.isFinite(duration) || duration <= 0) throw new Error("duration must be > 0");
    if (duration > 24) throw new Error("duration must be <= 24 hours");
    if (Math.round(duration * 60) < 1) throw new Error("duration must be at least 1 minute");
    let deadlineDate = null;
    let deadlineTime = null;
    if (state === "Deadline") {
        deadlineDate = toYmd(document.getElementById("tDeadlineDate").value);
        deadlineTime = withSeconds(document.getElementById("tDeadlineTime").value);
        if (!deadlineDate || !deadlineTime) throw new Error("deadline date and time required");
    }
    if (state === "Fixed" && (!date || !time)) {
        throw new Error("Fixed tasks need a date and time");
    }
    const placedDate = date || null;
    const placedTime = time ? withSeconds(time) : null;
    const now = new Date().toISOString();
    const prev = reuseTaskId
        ? allTasks.find((x) => String(x.id) === String(reuseTaskId))
        : null;
    const samePlace = !!(prev
        && (toYmd(prev.placed_date) || "") === (placedDate || "")
        && withSeconds(prev.placed_time || "") === (placedTime || "")
        && Number(prev.duration || 0) === Number(duration));
    if (placedDate && placedTime && !samePlace) {
        const start = clockMs(placedDate, placedTime);
        if (start != null && start <= Date.now()) throw new Error("Cannot create a task in the past.");
    }
    if (state === "Deadline" && deadlineDate && deadlineTime) {
        const sameDeadline = !!(prev
            && toYmd(prev.deadline_date) === deadlineDate
            && withSeconds(prev.deadline_time || "") === withSeconds(deadlineTime));
        if (!sameDeadline) {
            const due = clockMs(deadlineDate, deadlineTime);
            if (due != null && due <= Date.now()) throw new Error("Cannot set deadline in the past.");
        }
    }
    if (parentId) {
        const parent = allTasks.find((x) => String(x.id) === String(parentId));
        if (!parent) throw new Error("Quick event needs its parent task");
        const pStart = clockMs(parent.placed_date, parent.placed_time);
        const qStart = clockMs(placedDate, placedTime);
        const pEnd = pStart == null ? null : pStart + taskMinutes(parent) * 60 * 1000;
        const qEnd = qStart == null ? null : qStart + Math.round(duration * 60) * 60 * 1000;
        if (pStart == null || qStart == null || qStart < pStart || qEnd > pEnd) {
            throw new Error("Quick event must stay inside the parent task");
        }
    }
    const clash = localTaskClash({
        id: reuseTaskId || "",
        title,
        duration,
        state,
        placed_date: placedDate,
        placed_time: placedTime,
        parent_id: parentId || null
    });
    if (clash) throw new Error(clash);
    await send("POST", "/tasks", {
        id: reuseTaskId || nid(),
        title,
        taskName: title,
        duration,
        state,
        placed_date: placedDate,
        placedDate: placedDate,
        placed_time: placedTime,
        placedTime: placedTime,
        deadline_date: deadlineDate,
        deadline_time: deadlineTime,
        lifecycle: placedDate ? lifeOf({
            placed_date: placedDate,
            placed_time: placedTime,
            duration
        }) : "UNSCHEDULED",
        workflow: prev && isFinished(prev) ? "FINISHED" : "UNFINISHED",
        last_updated_at: now,
        placement_updated_at: (prev && samePlace && prev.placement_updated_at)
            ? prev.placement_updated_at
            : now,
        description: parentId ? "" : description,
        description_done: parentId ? false : document.getElementById("tDescDone").checked,
        parent_id: parentId || null
    });
    document.getElementById("tTitle").value = "";
    document.getElementById("tDesc").value = "";
    document.getElementById("tDescDone").checked = false;
    reuseTaskId = "";
    pendingQuickParent = "";
    const qbtn = document.getElementById("btnQuick");
    if (qbtn) qbtn.hidden = true;
    showReuseHint();
    await loadAll();
}

function isoDow(ymd) {
    const d = new Date(ymd + "T00:00:00");
    const js = d.getDay();
    return js === 0 ? 7 : js;
}

const DOW_NAME = ["", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];

function weekdayName(w) {
    const n = Number(w);
    return DOW_NAME[n] || String(w || "");
}

function eachDate(from, to) {
    const out = [];
    let cur = new Date(from + "T00:00:00");
    const end = new Date(to + "T00:00:00");
    while (cur <= end) {
        out.push(ymdFromDate(cur));
        cur.setDate(cur.getDate() + 1);
    }
    return out;
}

async function addBlocked() {
    const titleRaw = document.getElementById("bTitle").value.trim() || "blocked";
    const title = titleRaw.length > 100 ? titleRaw.slice(0, 100) : titleRaw;
    const start = withSeconds(document.getElementById("bStart").value);
    const end = withSeconds(document.getElementById("bEnd").value);
    if (!start || !end) throw new Error("blocked start and end required");
    const a = minutes(start);
    const c = minutes(end);
    if (a == null || c == null || c <= a) throw new Error("end must be after start");

    const every = document.getElementById("bEvery").checked;
    const isException = document.getElementById("bException").checked;
    const rangeOn = !!(document.getElementById("bRange") && document.getElementById("bRange").checked);
    const from = toYmd(document.getElementById("bFrom").value);
    const toRaw = toYmd(document.getElementById("bTo").value);
    const to = (rangeOn || (every && isException)) ? (toRaw || from) : from;
    const chosen = isException
        ? Array.from(document.querySelectorAll(".bDow:checked"))
            .map((el) => Number(el.value))
            .filter((n) => n >= 1 && n <= 7)
        : [];
    const now = new Date().toISOString();

    const rows = [];
    if (isException) {
        if (rangeOn && !from) throw new Error("pick dates for this range");
        if (!from && !chosen.length) throw new Error("pick weekdays or a date for the exception");
        if (from) {
            if (to < from) throw new Error("end date is before start date");
            for (const day of eachDate(from, to || from)) {
                if (chosen.length && !chosen.includes(isoDow(day))) continue;
                rows.push({
                    id: nid(), title, weekday: null, specific_date: day,
                    start_time: start, end_time: end, last_updated_at: now, is_exception: true
                });
            }
            if (!rows.length) throw new Error("no dates in that range match the weekdays");
        } else {
            for (const w of chosen) {
                rows.push({
                    id: nid(), title, weekday: w, specific_date: null,
                    start_time: start, end_time: end, last_updated_at: now, is_exception: true
                });
            }
        }
    } else if (every) {
        rows.push({
            id: nid(), title, weekday: null, specific_date: null,
            start_time: start, end_time: end, last_updated_at: now, is_exception: false
        });
    } else {
        if (!from) throw new Error("pick a date");
        if ((to || from) < from) throw new Error("end date is before start date");
        for (const day of eachDate(from, to || from)) {
            rows.push({
                id: nid(), title, weekday: null, specific_date: day,
                start_time: start, end_time: end, last_updated_at: now, is_exception: false
            });
        }
    }

    for (const row of rows) {
        const until = blockedUntilMs(row);
        if (until != null && until <= Date.now()) {
            throw new Error("Cannot add a blocked time that has already ended.");
        }
    }

    const blockedRes = await send("GET", "/blocked");
    const existing = blockedRes.blocked || [];
    const all = existing.concat(rows);
    for (const row of rows) {
        for (const b of all) {
            if (b === row) continue;
            if (b && row && b.id != null && row.id != null && String(b.id) === String(row.id)) continue;
            if (datesCanMeet(row, b) && clockOverlap(row, b)) {
                throw new Error("Overlaps existing blocked time: " + describeBlocked(b));
            }
        }
    }

    for (const row of rows) {
        await send("POST", "/blocked", row);
    }
    document.getElementById("bTitle").value = "";
    await loadAll();
}

const bEvery = document.getElementById("bEvery");
const bException = document.getElementById("bException");
const bRange = document.getElementById("bRange");
if (bEvery) bEvery.addEventListener("change", () => {
    if (bEvery.checked && bRange) bRange.checked = false;
    syncBlockedDates();
});
if (bRange) bRange.addEventListener("change", () => {
    if (bRange.checked && bEvery) bEvery.checked = false;
    syncBlockedDates();
});
if (bException) bException.addEventListener("change", syncBlockedDates);
syncBlockedDates();

function syncDeadlineUi() {
    const wrap = document.getElementById("tDeadlineWrap");
    const state = document.getElementById("tState");
    if (!wrap || !state) return;
    wrap.style.display = state.value === "Deadline" ? "inline-flex" : "none";
}
const tState = document.getElementById("tState");
if (tState) tState.addEventListener("change", syncDeadlineUi);
syncDeadlineUi();
hookTwLineNos();
hookTwAttach();
syncTwAttachUi();

function showRegisterFields(on) {
    const extra = document.getElementById("regExtra");
    const hint = document.getElementById("regHint");
    if (extra) extra.classList.toggle("on", !!on);
    if (hint) hint.classList.toggle("on", !!on);
}

const btnLogin = document.getElementById("btnLogin");
const btnReg = document.getElementById("btnReg");
if (btnLogin) btnLogin.addEventListener("click", () => {
    showRegisterFields(false);
    doLogin(false);
});
if (btnReg) btnReg.addEventListener("click", () => {
    const extra = document.getElementById("regExtra");
    const open = extra && extra.classList.contains("on");
    if (!open) {
        showRegisterFields(true);
        const email = document.getElementById("email");
        if (email) email.focus();
        return;
    }
    doLogin(true);
});

document.addEventListener("click", async (e) => {
    const t = e.target instanceof Element ? e.target : e.target && e.target.parentElement;
    if (!t || typeof t.closest !== "function") return;
    if (t.closest("#btnLogin") || t.closest("#btnReg")) return;
    if (!t.closest(".action-wrap")) closeActionMenu();
    const whenBtn = t.closest("[data-taskwhen]");
    const kindBtn = t.closest("[data-taskkind]");
    try {
        if (whenBtn) {
            setTaskWhen(whenBtn.dataset.taskwhen);
            return;
        }
        if (kindBtn) {
            setTaskKind(kindBtn.dataset.taskkind);
            return;
        }
        if (t.closest("#btnTaskNext")) {
            taskPage += 1;
            paintTasks();
            return;
        }
        if (t.closest("#btnTaskPrev")) {
            taskPage = Math.max(0, taskPage - 1);
            paintTasks();
            return;
        }
        if (t.closest("#btnOut")) {
            logoutWeb();
        } else if (t.closest("[data-spa]")) {
            const page = t.closest("[data-spa]").dataset.spa;
            location.hash = page === "typewriter" ? "#/typewriter"
                : page === "account" ? "#/account" : "#/calendar";
        } else if (t.closest("#btnReload")) {
            await loadAll();
        } else if (t.closest("#btnAction")) {
            const menu = document.getElementById("actionMenu");
            if (menu) menu.hidden = !menu.hidden;
        } else if (t.closest("#actTaskList")) {
            toggleActionPanel("tasks");
        } else if (t.closest("#actBlocked")) {
            toggleActionPanel("blocked");
        } else if (t.closest("#actMonth")) {
            calMode = "month";
            closeActionMenu();
            paintWeek();
        } else if (t.closest("#actWeek")) {
            calMode = "week";
            closeActionMenu();
            paintWeek();
        } else if (t.closest("#btnPrevWeek")) {
            if (calMode === "month") monthOffset -= 1;
            else weekOffset -= 1;
            paintWeek();
        } else if (t.closest("#btnNextWeek")) {
            if (calMode === "month") monthOffset += 1;
            else weekOffset += 1;
            paintWeek();
        } else if (t.closest("#btnThisWeek")) {
            weekOffset = 0;
            monthOffset = 0;
            paintWeek();
        } else if (t.closest("#btnNewTask")) {
            clearReuse();
        } else if (t.closest("#descAddBtn")) {
            addDescItem();
        } else if (t.closest("#descSave")) {
            await saveTaskDescription();
        } else if (t.closest("#descFinish")) {
            const task = selectedTask();
            if (!task) throw new Error("click a task first");
            if (!toYmd(task.placed_date) || !task.placed_time) {
                throw new Error("Finish is only for placed tasks.");
            }
            await toggleTask(task.id);
        } else if (t.closest("#btnQuick")) {
            pendingQuickParent = t.closest("#btnQuick").dataset.parentId || "";
            if (!pendingQuickParent) throw new Error("open a task first, then add a quick event inside it");
            await addTask();
        } else if (t.closest("#btnAdd")) {
            pendingQuickParent = "";
            await addTask();
        } else if (t.closest("#btnAddBlocked")) {
            await addBlocked();
        } else if (t.closest("[data-twtab]")) {
            setTab(t.closest("[data-twtab]").dataset.twtab);
        } else if (t.closest("#btnTwFindBack")) {
            twFindBack();
        } else if (t.closest("[data-tw-find-year]")) {
            findYear = Number(t.closest("[data-tw-find-year]").dataset.twFindYear);
            findMonth = null;
            findLevel = "month";
            paintDiaryFind();
        } else if (t.closest("[data-tw-find-month]")) {
            findMonth = Number(t.closest("[data-tw-find-month]").dataset.twFindMonth);
            findLevel = "day";
            paintDiaryFind();
        } else if (t.closest("[data-tw-del-diary]")) {
            await deleteDiaryDay(t.closest("[data-tw-del-diary]").dataset.twDelDiary);
        } else if (t.closest("[data-tw-del-page]")) {
            await deletePage(t.closest("[data-tw-del-page]").dataset.twDelPage);
        } else if (t.closest("[data-tw-del-list]")) {
            await deleteList(t.closest("[data-tw-del-list]").dataset.twDelList);
        } else if (t.closest("#btnTwToday")) {
            const dayEl = document.getElementById("twDay");
            if (dayEl) dayEl.value = todayYmd();
            await findDiaryClick();
        } else if (t.closest("#btnTwFind")) {
            await findDiaryClick();
        } else if (t.closest("#btnTwAddPage")) {
            await addPage();
        } else if (t.closest("#btnTwAddList")) {
            await addList();
        } else if (t.closest("#btnTwSaveNote")) {
            await saveOpenNote();
        } else if (t.closest("#btnTwDelNote")) {
            await deleteOpenNote();
        } else if (t.closest("[data-task-toggle]")) {
            await toggleTask(t.closest("[data-task-toggle]").dataset.taskToggle);
        } else if (t.closest("[data-tw-kind]")) {
            const b = t.closest("[data-tw-kind]");
            await showTw(b.dataset.twKind, b.dataset.twKey);
        } else if (t.closest("[data-task-id]")) {
            const id = t.closest("[data-task-id]").dataset.taskId;
            const row = allTasks.find((x) => String(x.id) === String(id));
            const name = (row && row.title) ? row.title : "this task";
            if (!confirm("Delete “" + name + "”?")) return;
            if (row && !isQuick(row)) {
                for (const q of quicksOf(row)) {
                    if (q && q.id) await send("DELETE", "/tasks/" + q.id);
                }
            }
            await send("DELETE", "/tasks/" + id);
            await loadAll();
        } else if (t.closest("[data-blocked-id]")) {
            const id = t.closest("[data-blocked-id]").dataset.blockedId;
            const row = allBlocked.find((x) => String(x.id) === String(id));
            const name = (row && row.title) ? row.title : "this blocked time";
            if (!confirm("Delete blocked time “" + name + "”?")) return;
            await send("DELETE", "/blocked/" + id);
            await loadAll();
        }
    } catch (ex) {
        showErr(ex.message);
        toast(ex.message, true);
    }
});

window.addEventListener("online", () => toast("Online"));
window.addEventListener("offline", () => toast("Offline — using local cache", true));

function persistTwQuiet() {
    if (!twOpen || !token()) return;
    if (window.AshoreVault && window.AshoreVault.sealed(lastTwSent)) return;
    applyOpenNoteFields();
    const content = JSON.stringify(twBundle);
    if (content === lastTwSent) return;
    lastTwSent = content;
    const headers = { "Content-Type": "application/json", Authorization: "Bearer " + token() };
    const run = async () => {
        let packed = content;
        if (window.AshoreVault && window.AshoreVault.wanted()) {
            packed = await window.AshoreVault.seal(content);
        }
        fetch("/typewriter", {
            method: "POST",
            headers,
            body: JSON.stringify({
                content: packed,
                last_updated_at: new Date().toISOString()
            }),
            keepalive: true
        }).catch(() => {});
    };
    run();
}

window.addEventListener("pagehide", persistTwQuiet);
document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") persistTwQuiet();
});

function showPage(page) {
    const name = page === "typewriter" || page === "account" ? page : "calendar";
    document.querySelectorAll("[data-page]").forEach((el) => {
        el.classList.toggle("on", el.dataset.page === name);
    });
    document.querySelectorAll("[data-spa]").forEach((el) => {
        const on = el.dataset.spa === name;
        el.classList.toggle("active", on);
        el.classList.toggle("ghost", !on);
    });
}

function showPageFromHash() {
    const raw = (location.hash || "").replace(/^#\/?/, "");
    const page = raw.split("/")[0];
    showPage(page);
}

function logoutWeb() {
    stopReminderWatch();
    stopIdleWatch();
    if (window.AshoreVault) window.AshoreVault.lock();
    setToken("");
    localStorage.removeItem("ashoreUserId");
    localStorage.removeItem("ashoreUsername");
    if (app) app.style.display = "none";
    if (auth) auth.style.display = "block";
}

function wipeLocalWeb() {
    stopReminderWatch();
    stopIdleWatch();
    if (window.AshoreVault && window.AshoreVault.eraseLocal) window.AshoreVault.eraseLocal();
    else if (window.AshoreVault) window.AshoreVault.lock();
    setToken("");
    const keep = { ashoreTheme: true };
    for (let i = localStorage.length - 1; i >= 0; i--) {
        const k = localStorage.key(i);
        if (!k) continue;
        if (k === "token" || (k.startsWith("ashore") && !keep[k])) {
            localStorage.removeItem(k);
        }
    }
    try { sessionStorage.removeItem("ashoreIdleAt"); } catch (_) {}
    allTasks = [];
    allBlocked = [];
    twBundle = emptyBundle();
    twOpen = null;
    lastTwSent = "";
    selectedTaskId = "";
    reuseTaskId = "";
    pendingQuickParent = "";
    if (app) app.style.display = "none";
    if (auth) auth.style.display = "block";
}

async function wipeIfAccountDeleted() {
    const uid = localStorage.getItem("ashoreUserId");
    if (!uid) return false;
    try {
        const res = await fetch("/account-status?userId=" + encodeURIComponent(uid));
        const json = await res.json();
        if (json && json.deleted) {
            wipeLocalWeb();
            toast("This account was deleted. Local data on this browser was cleared.", true);
            return true;
        }
    } catch (_) {}
    return false;
}

async function deleteAccount(password) {
    await send("POST", "/me/delete", { password: password || "" });
    wipeLocalWeb();
}

function wantReminders() {
    const v = localStorage.getItem("ashoreReminders");
    return v !== "false";
}

function setReminders(on) {
    localStorage.setItem("ashoreReminders", on ? "true" : "false");
    if (on) startReminderWatch();
    else stopReminderWatch();
}

function parseLeads(raw) {
    const out = [];
    const seen = new Set();
    String(raw || "").split(/[,\s]+/).forEach((part) => {
        if (!part) return;
        const n = Number.parseInt(part, 10);
        if (!Number.isFinite(n) || n < 0 || n > 20160 || seen.has(n)) return;
        seen.add(n);
        out.push(n);
    });
    return out;
}

function reminderLeads() {
    const raw = localStorage.getItem("ashoreReminderLeads");
    if (raw == null || raw === "") return [0, 5];
    const parsed = parseLeads(raw);
    return parsed.length ? parsed : [0, 5];
}

function setReminderLeads(leads) {
    localStorage.setItem("ashoreReminderLeads", parseLeads((leads || []).join(",")).join(","));
}

function wantReminderSound() {
    return localStorage.getItem("ashoreReminderSound") !== "false";
}

function setReminderSound(on) {
    localStorage.setItem("ashoreReminderSound", on ? "true" : "false");
}

function formatLead(minutes) {
    const n = Number(minutes) || 0;
    if (n <= 0) return "when it starts";
    if (n % 1440 === 0) {
        const d = n / 1440;
        return d + (d === 1 ? " day" : " days");
    }
    if (n % 60 === 0) {
        const h = n / 60;
        return h + (h === 1 ? " hour" : " hours");
    }
    return n + " min";
}

function beepReminder() {
    try {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) return;
        const ctx = new Ctx();
        const o = ctx.createOscillator();
        const g = ctx.createGain();
        o.frequency.value = 880;
        g.gain.value = 0.05;
        o.connect(g);
        g.connect(ctx.destination);
        o.start();
        setTimeout(() => {
            try { o.stop(); } catch (_) {}
            try { ctx.close(); } catch (_) {}
        }, 160);
    } catch (_) {}
}

const reminderSent = new Set();
let reminderTimer = null;

function startReminderWatch() {
    if (reminderTimer) return;
    reminderTimer = setInterval(tickReminders, 20000);
    tickReminders();
}

function stopReminderWatch() {
    if (reminderTimer) {
        clearInterval(reminderTimer);
        reminderTimer = null;
    }
    reminderSent.clear();
}

function tickReminders() {
    if (!wantReminders() || !token()) return;
    const leads = reminderLeads();
    if (!leads.length) return;
    const maxLead = Math.max.apply(null, leads);
    const now = Date.now();
    const sound = wantReminderSound();
    for (const t of allTasks) {
        if (!t || isFinished(t)) continue;
        const day = toYmd(t.placed_date || t.placedDate);
        const time = String(t.placed_time || t.placedTime || "").slice(0, 5);
        if (!day || time.length < 4) continue;
        const start = Date.parse(day + "T" + time + ":00");
        if (!Number.isFinite(start)) continue;
        const mins = Math.round((start - now) / 60000);
        if (mins > maxLead) continue;
        if (sealedUi(t.title) || sealedUi(t.description)) continue;
        const name = publicTitle(t) || "Task";
        for (const lead of leads) {
            const hit = lead <= 0
                ? mins <= 0 && mins > -2
                : mins <= lead && mins > lead - 2 && mins > 0;
            if (!hit) continue;
            const key = String(t.id) + "|" + start + "|" + lead;
            if (reminderSent.has(key)) continue;
            reminderSent.add(key);
            const title = lead <= 0 ? "Task now" : "Starting soon";
            const body = lead <= 0
                ? name + " is on (" + time + ")"
                : name + " starts in " + formatLead(lead) + " (" + time + ")";
            toast(title + " — " + body);
            if (typeof Notification !== "undefined" && Notification.permission === "granted") {
                try { new Notification(title, { body: body, silent: !sound }); } catch (_) {}
            } else if (typeof Notification !== "undefined" && Notification.permission === "default") {
                Notification.requestPermission();
            }
            if (sound) beepReminder();
        }
    }
}

window.AshoreApp = {
    send: send,
    toast: toast,
    loadAll: loadAll,
    logout: logoutWeb,
    showPage: showPage,
    unlockVault: unlockVault,
    wantReminders: wantReminders,
    setReminders: setReminders,
    reminderLeads: reminderLeads,
    setReminderLeads: setReminderLeads,
    wantReminderSound: wantReminderSound,
    setReminderSound: setReminderSound,
    formatLead: formatLead,
    deleteAccount: deleteAccount,
    wipeIfAccountDeleted: wipeIfAccountDeleted,
    idleMinutes: idleMinutesPref,
    setIdleMinutes: setIdleMinutesPref,
    wantDark: wantDark,
    setDark: setDark
};

window.addEventListener("hashchange", showPageFromHash);
showPageFromHash();
window.addEventListener("online", () => { wipeIfAccountDeleted(); });

(async function boot() {
    if (await wipeIfAccountDeleted()) return;
    if (!token()) return;
    try {
        const me = await send("GET", "/me");
        await afterLogin({
            token: token(),
            id: me.id,
            username: me.username
        });
    } catch (_) {
        if (!(await wipeIfAccountDeleted())) setToken("");
    }
})();