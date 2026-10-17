/** Browser UI for the same Node API the JavaFX client uses. */
const err = document.getElementById("err");
const errAdd = document.getElementById("errAdd");
const errBlocked = document.getElementById("errBlocked");
const auth = document.getElementById("auth");
const app = document.getElementById("app");

let twBundle = emptyBundle();
let twOpen = null;
let lastTwSent = "";
let allTasks = [];
let allBlocked = [];
let weekOffset = 0;
let taskWhen = "all";
let taskKind = "all";
let reuseTaskId = "";
let findLevel = "year";
let findYear = null;
let findMonth = null;
let toastTimer = null;
let loginBusy = false;
let carrying = false;
const carryToasted = new Set();
const MONTHS = ["January","February","March","April","May","June","July","August","September","October","November","December"];

function emptyBundle() {
    return { diary: [], lists: [], pages: [] };
}

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
    const res = await fetch(path, {
        method,
        headers,
        body: body == null ? undefined : JSON.stringify(body)
    });
    const text = await res.text();
    let json = null;
    try { json = JSON.parse(text); } catch (_) {}
    if (!res.ok) {
        if (res.status === 502 || res.status === 504 || /bad gateway|cloudflare/i.test(text)) {
            throw new Error(res.status + " tunnel/origin down — Node or cloudflared is not answering");
        }
        if (res.status === 429) {
            throw new Error((json && json.error) || "Too many image uploads just now. Wait a minute, then retry.");
        }
        throw new Error(res.status + " " + ((json && json.error) || text || "error"));
    }
    return json;
}

function minutes(t) {
    if (!t) return null;
    const p = String(t).split(":");
    return Number(p[0]) * 60 + Number(p[1] || 0);
}

function isExceptionRow(b) {
    return b.is_exception === true || b.is_exception === "t" || b.is_exception === "true" || b.is_exception === 1;
}

function weekdayOf(b) {
    if (b == null || b.weekday == null || b.weekday === "") return null;
    const n = Number(b.weekday);
    return Number.isFinite(n) ? n : null;
}

function isDailyRow(b) {
    return !isExceptionRow(b) && !toYmd(b.specific_date) && weekdayOf(b) == null;
}

function sameLayer(a, b) {
    if (isExceptionRow(a) !== isExceptionRow(b)) return false;
    const da = toYmd(a.specific_date);
    const db = toYmd(b.specific_date);
    if (da || db) return da === db;
    const wa = weekdayOf(a);
    const wb = weekdayOf(b);
    if (wa != null || wb != null) return wa === wb;
    return isDailyRow(a) && isDailyRow(b);
}

function clockOverlap(a, b) {
    return withSeconds(a.start_time) < withSeconds(b.end_time)
        && withSeconds(b.start_time) < withSeconds(a.end_time);
}

function describeBlocked(b) {
    const when = isExceptionRow(b)
        ? "exception " + (toYmd(b.specific_date) || weekdayName(b.weekday))
        : isDailyRow(b) ? "every day" : (toYmd(b.specific_date) || weekdayName(b.weekday));
    return (b.title || "blocked") + " " + when + " " + b.start_time + "–" + b.end_time;
}

function syncBlockedDates() {
    const every = document.getElementById("bEvery");
    const exception = document.getElementById("bException");
    const from = document.getElementById("bFrom");
    const to = document.getElementById("bTo");
    if (!every || !exception || !from || !to) return;
    const needDate = exception.checked || !every.checked;
    from.disabled = !needDate;
    to.disabled = !needDate;
    const lab = exception.closest("label");
    if (lab) {
        const box = exception;
        lab.textContent = "";
        lab.appendChild(box);
        lab.appendChild(document.createTextNode(every.checked
            ? " Exception (free this date / weekdays)"
            : " Exception (free this range / weekdays)"));
    }
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

function sameSession(a, b) {
    return String(a.title || "").toLowerCase() === String(b.title || "").toLowerCase()
        && String(a.category || "").toLowerCase() === String(b.category || "").toLowerCase()
        && String(a.subcategory || "").toLowerCase() === String(b.subcategory || "").toLowerCase();
}

function stripCarryNotes(text) {
    if (!text) return "";
    return String(text).split(/\r?\n/).filter((line) => {
        const t = line.trim();
        return !t.startsWith("Leftover from ") && !t.startsWith("Unfinished leftover");
    }).join("\n").trim();
}

function findNextSameSession(past) {
    const pastStart = taskStartKey(past);
    let next = null;
    let nextStart = "";
    for (const t of allTasks) {
        if (String(t.id) === String(past.id)) continue;
        if (!sameSession(past, t)) continue;
        const start = taskStartKey(t);
        if (!start || (pastStart && start <= pastStart)) continue;
        if (!next || start < nextStart) {
            next = t;
            nextStart = start;
        }
    }
    return next;
}

async function patchTaskDescription(t, description) {
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
        category: t.category || "",
        subcategory: t.subcategory || "",
        description: clipDesc(description),
        description_done: t.description_done === true || t.description_done === "true"
    });
}

async function carryUnfinishedPast() {
    const hold = "Unfinished leftover — no later session of this category/sub/name yet.";
    let wrote = false;
    for (const past of allTasks) {
        if (lifeOf(past) !== "PAST" || isFinished(past)) continue;
        const cat = String(past.category || "").trim();
        const sub = String(past.subcategory || "").trim();
        if (!cat && !sub) continue;
        const next = findNextSameSession(past);
        if (!next) {
            const desc = String(past.description || "");
            if (!desc.includes(hold)) {
                await patchTaskDescription(past, desc ? desc + "\n" + hold : hold);
                wrote = true;
            }
            const id = String(past.id);
            if (!carryToasted.has(id)) {
                carryToasted.add(id);
                toast("Unfinished leftover: no later session for “"
                    + (past.title || "") + "”. Use Fill add for a follow-up.", true);
            }
            continue;
        }
        const note = "Leftover from " + (toYmd(past.placed_date) || "") + " “" + (past.title || "") + "”.";
        const nextDesc = String(next.description || "");
        if (nextDesc.includes(note)) continue;
        const leftover = stripCarryNotes(past.description);
        const extra = leftover ? note + "\n" + leftover : note;
        await patchTaskDescription(next, nextDesc ? nextDesc + "\n" + extra : extra);
        wrote = true;
    }
    return wrote;
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

function filteredTasks() {
    return allTasks.filter((t) => matchesWhen(t) && matchesKind(t));
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
    const set = (id, v) => { const el = document.getElementById(id); if (el) el.value = v == null ? "" : v; };
    set("tTitle", t.title || "");
    set("tDur", String(t.duration || 1));
    const state = hasDeadline(t) ? "Deadline" : (String(t.state || "") === "Fixed" ? "Fixed" : "Flexible");
    set("tState", state);
    set("tDate", toYmd(t.placed_date) || "");
    set("tTime", (t.placed_time || "").slice(0, 5));
    set("tCat", t.category || "");
    set("tSub", t.subcategory || "");
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
}

function showReuseHint() {
    const el = document.getElementById("tReuseHint");
    const btn = document.getElementById("btnNewTask");
    if (el) el.textContent = reuseTaskId ? "Updating the same task (not a new one)." : "";
    if (btn) btn.style.display = reuseTaskId ? "" : "none";
}

function clearReuse() {
    reuseTaskId = "";
    showReuseHint();
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

function paintWeek() {
    const grid = document.getElementById("weekGrid");
    const label = document.getElementById("weekLabel");
    if (!grid || !label) return;
    const days = weekDays();
    const names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
    label.textContent = ymdFromDate(days[0]) + "  →  " + ymdFromDate(days[6]);
    grid.innerHTML = "";
    const todayCol = columnDateFor(todayYmd(), nowHm());
    days.forEach((d, i) => {
        const ymd = ymdFromDate(d);
        const col = document.createElement("div");
        col.className = "day-col" + (ymd === todayCol ? " today" : "");
        const head = document.createElement("div");
        head.className = "day-head";
        head.textContent = names[i] + " " + ymd.slice(5);
        head.title = "Use this date in Add task";
        head.style.cursor = "pointer";
        head.addEventListener("click", () => fillAddSlot(ymd, ""));
        col.appendChild(head);
        const items = allTasks.filter((t) => columnDateFor(t.placed_date, t.placed_time) === ymd)
            .sort((a, b) => String(a.placed_time || "").localeCompare(String(b.placed_time || "")));
        if (!items.length) {
            const empty = document.createElement("div");
            empty.className = "meta";
            empty.textContent = "—";
            if (ymd < todayCol) {
                empty.title = "Past day — add is closed";
            } else {
                empty.title = "Add a task on this date";
                empty.style.cursor = "pointer";
                empty.addEventListener("click", () => fillAddSlot(ymd, "06:00", true));
            }
            col.appendChild(empty);
        }
        items.forEach((t) => {
            const b = document.createElement("button");
            b.type = "button";
            b.className = "chip" + (isFinished(t) ? " done" : "");
            b.title = "Fill Add task with this item (title, description, category)";
            b.addEventListener("click", () => fillAddFromTask(t));
            const tEl = document.createElement("div");
            tEl.className = "t";
            tEl.textContent = (t.placed_time || "").slice(0, 5) + " " + (t.title || "(untitled)");
            const metaEl = document.createElement("div");
            metaEl.className = "meta";
            metaEl.textContent = lifeOf(t).toLowerCase()
                + " · " + (isFinished(t) ? "finished" : "unfinished")
                + (t.category ? " · " + t.category : "")
                + (t.subcategory ? " / " + t.subcategory : "");
            b.appendChild(tEl);
            b.appendChild(metaEl);
            col.appendChild(b);
        });
        grid.appendChild(col);
    });
}

function paintTasks(rows) {
    if (rows) allTasks = rows;
    markTabs();
    paintWeek();
    const list = document.getElementById("tasks");
    if (!list) return;
    list.innerHTML = "";
    const shown = filteredTasks();
    if (!shown.length) {
        list.innerHTML = "<li><span class='meta'>(none in this filter)</span></li>";
        return;
    }
    for (const t of shown) {
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
        name.textContent = t.title || "(untitled)";
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
            + (t.category ? " · " + t.category : "")
            + (t.subcategory ? " / " + t.subcategory : "")
            + (t.description ? " · " + String(t.description).slice(0, 80) : "");
        left.appendChild(head);
        left.appendChild(meta);
        left.style.cursor = "pointer";
        left.title = "Fill Add task (title, description, category)";
        left.addEventListener("click", () => fillAddFromTask(t));
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
}

function setTaskWhen(v) {
    taskWhen = v || "all";
    paintTasks();
}

function setTaskKind(v) {
    taskKind = v || "all";
    paintTasks();
}

async function toggleTask(id) {
    const t = allTasks.find((x) => String(x.id) === String(id));
    if (!t) return;
    const finished = isFinished(t);
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
        workflow: finished ? "UNFINISHED" : "FINISHED",
        last_updated_at: new Date().toISOString(),
        placement_updated_at: t.placement_updated_at || new Date().toISOString(),
        category: t.category || "",
        subcategory: t.subcategory || "",
        description: t.description || "",
        description_done: t.description_done === true || t.description_done === "true"
    });
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
        category: t.category || "",
        subcategory: t.subcategory || "",
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

async function saveBundle() {
    const content = JSON.stringify(twBundle);
    lastTwSent = content;
    await send("POST", "/typewriter", {
        content,
        last_updated_at: new Date().toISOString()
    });
}

function applyTypewriter(tw) {
    hideTwView();
    const raw = (tw && tw.content) || "";
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
    const bytes = new Uint8Array(buf);
    let s = "";
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
        s += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    }
    return btoa(s);
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
        const buf = await file.arrayBuffer();
        if (buf.byteLength > 8 * 1024 * 1024) {
            throw new Error((file.name || "image") + " is over 8 MB");
        }
        const sha = await sha256Hex(buf);
        const imgName = clipImageName(file.name || "image.png");
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
            await send("POST", "/typewriter/images", {
                sha256: sha,
                name: imgName,
                mime,
                kind,
                data: bufToB64(buf)
            });
        } catch (ex) {
            const msg = String(ex && ex.message ? ex.message : ex);
            if (/429|too many|wait a minute/i.test(msg)) {
                rateLimited = true;
                break;
            }
            throw ex;
        }
        note.images.push({
            id: sha,
            sha256: sha,
            name: imgName,
            added_at: new Date().toISOString()
        });
        added += 1;
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
    paintTasks(tasksRes.tasks || []);
    paintBlocked(blockedRes.blocked || []);
    applyTypewriter(tw);
    setTab(tab);
    if (reopen) await showTw(reopen.kind, reopen.key);
    if (!carrying) {
        carrying = true;
        try {
            const expired = await expirePastBlocked(blockedRes.blocked || []);
            const carried = await carryUnfinishedPast();
            if (expired || carried) await loadAll();
        } catch (_) { /* keep the list that just painted */ }
        carrying = false;
    }
}

function setVal(id, value) {
    const el = document.getElementById(id);
    if (el) el.value = value;
}

async function afterLogin(data) {
    setToken(data && data.token);
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
    findLevel = "year";
    findYear = null;
    findMonth = null;
    syncBlockedDates();
    await loadAll();
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
        await afterLogin(data);
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
    for (const t of allTasks) {
        if (String(t.id) === String(task.id)) continue;
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
    const state = String(task.state || "").toLowerCase();
    if (state !== "flexible" && state !== "3") return null;
    const days = [win.day];
    if (win.end > 24 * 60) days.push(addDaysYmd(win.day, 1));
    for (const day of days) {
        const clock = day === win.day
            ? { a: win.start, b: Math.min(win.end, 24 * 60) }
            : { a: 0, b: win.end - 24 * 60 };
        for (const b of allBlocked) {
            if (isExceptionRow(b) || !blockedAppliesOn(b, day)) continue;
            const b0 = minutes(b.start_time);
            const b1 = minutes(b.end_time);
            if (b0 == null || b1 == null || !rangesOverlap(clock.a, clock.b, b0, b1)) continue;
            const freed = allBlocked.some((ex) => {
                if (!isExceptionRow(ex) || !blockedAppliesOn(ex, day)) return false;
                const e0 = minutes(ex.start_time);
                const e1 = minutes(ex.end_time);
                return e0 != null && e1 != null && rangesOverlap(clock.a, clock.b, e0, e1);
            });
            if (!freed) return "Overlaps blocked: " + describeBlocked(b);
        }
    }
    return null;
}

async function addTask() {
    showErr("");
    const title = document.getElementById("tTitle").value.trim();
    const duration = Number(document.getElementById("tDur").value);
    const state = document.getElementById("tState").value;
    const date = toYmd(document.getElementById("tDate").value);
    let time = document.getElementById("tTime").value;
    if (!title) throw new Error("title required");
    if (title.length > 100) throw new Error("title max 100 characters");
    const category = document.getElementById("tCat").value.trim();
    const subcategory = document.getElementById("tSub").value.trim();
    const description = document.getElementById("tDesc").value.trim();
    if (category.length > 80) throw new Error("category max 80 characters");
    if (subcategory.length > 80) throw new Error("subcategory max 80 characters");
    if (description.length > 8000) throw new Error("description max 8000 characters");
    if (!Number.isFinite(duration) || duration <= 0) throw new Error("duration must be > 0");
    if (duration > 24) throw new Error("duration must be <= 24 hours");
    if (Math.round(duration * 2) !== duration * 2) throw new Error("duration must be in 0.5 hour steps");
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
    const clash = localTaskClash({
        id: reuseTaskId || "",
        title,
        duration,
        state,
        placed_date: placedDate,
        placed_time: placedTime
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
        category,
        subcategory,
        description,
        description_done: document.getElementById("tDescDone").checked
    });
    document.getElementById("tTitle").value = "";
    document.getElementById("tCat").value = "";
    document.getElementById("tSub").value = "";
    document.getElementById("tDesc").value = "";
    document.getElementById("tDescDone").checked = false;
    reuseTaskId = "";
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
    const from = toYmd(document.getElementById("bFrom").value);
    const to = toYmd(document.getElementById("bTo").value) || from;
    const chosen = Array.from(document.querySelectorAll(".bDow:checked"))
        .map((el) => Number(el.value))
        .filter((n) => n >= 1 && n <= 7);
    const now = new Date().toISOString();

    const rows = [];
    if (isException && every && chosen.length) {
        for (const w of chosen) {
            rows.push({
                id: nid(), title, weekday: w, specific_date: null,
                start_time: start, end_time: end, last_updated_at: now, is_exception: true
            });
        }
    } else if (!isException && every && chosen.length) {
        for (const w of chosen) {
            rows.push({
                id: nid(), title, weekday: w, specific_date: null,
                start_time: start, end_time: end, last_updated_at: now, is_exception: false
            });
        }
    } else if (!isException && every) {
        rows.push({
            id: nid(),
            title,
            weekday: null,
            specific_date: null,
            start_time: start,
            end_time: end,
            last_updated_at: now,
            is_exception: false
        });
    } else {
        if (!from) throw new Error("pick a date");
        if (to < from) throw new Error("end date is before start date");
        for (const day of eachDate(from, to)) {
            if (chosen.length && !chosen.includes(isoDow(day))) continue;
            rows.push({
                id: nid(),
                title,
                weekday: null,
                specific_date: day,
                start_time: start,
                end_time: end,
                last_updated_at: now,
                is_exception: isException
            });
        }
        if (!rows.length) throw new Error("no dates in that range match the weekdays");
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
            if (b === row || b.id === row.id) continue;
            if (sameLayer(row, b) && clockOverlap(row, b)) {
                throw new Error("Overlaps existing: " + describeBlocked(b));
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
if (bEvery) bEvery.addEventListener("change", syncBlockedDates);
if (bException) bException.addEventListener("change", syncBlockedDates);

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
        if (t.closest("#btnOut")) {
            setToken("");
            if (app) app.style.display = "none";
            if (auth) auth.style.display = "block";
        } else if (t.closest("#btnReload")) {
            await loadAll();
        } else if (t.closest("#btnPrevWeek")) {
            weekOffset -= 1;
            paintWeek();
        } else if (t.closest("#btnNextWeek")) {
            weekOffset += 1;
            paintWeek();
        } else if (t.closest("#btnThisWeek")) {
            weekOffset = 0;
            paintWeek();
        } else if (t.closest("#btnNewTask")) {
            clearReuse();
        } else if (t.closest("#btnAdd")) {
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
    applyOpenNoteFields();
    const content = JSON.stringify(twBundle);
    if (content === lastTwSent) return;
    lastTwSent = content;
    const headers = { "Content-Type": "application/json", Authorization: "Bearer " + token() };
    fetch("/typewriter", {
        method: "POST",
        headers,
        body: JSON.stringify({
            content,
            last_updated_at: new Date().toISOString()
        }),
        keepalive: true
    }).catch(() => {});
}

window.addEventListener("pagehide", persistTwQuiet);
document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") persistTwQuiet();
});