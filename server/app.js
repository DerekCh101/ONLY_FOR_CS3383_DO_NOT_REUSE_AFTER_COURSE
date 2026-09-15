const err = document.getElementById("err");
const errAdd = document.getElementById("errAdd");
const errBlocked = document.getElementById("errBlocked");
const auth = document.getElementById("auth");
const app = document.getElementById("app");

let twBundle = emptyBundle();
let twOpen = null;
let allTasks = [];
let weekOffset = 0;
let taskWhen = "all";
let taskKind = "all";
let findLevel = "year";
let findYear = null;
let findMonth = null;
let toastTimer = null;
let loginBusy = false;
const MONTHS = ["January","February","March","April","May","June","July","August","September","October","November","December"];

function emptyBundle() {
    return { diary: [], lists: [], pages: [] };
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
    return (crypto.randomUUID && crypto.randomUUID()) || String(Date.now()) + Math.random();
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

function isDailyRow(b) {
    return !isExceptionRow(b) && !toYmd(b.specific_date);
}

function sameLayer(a, b) {
    if (isExceptionRow(a) !== isExceptionRow(b)) return false;
    if (isExceptionRow(a)) return toYmd(a.specific_date) === toYmd(b.specific_date);
    if (isDailyRow(a) && isDailyRow(b)) return true;
    if (isDailyRow(a) || isDailyRow(b)) return false;
    return toYmd(a.specific_date) === toYmd(b.specific_date);
}

function clockOverlap(a, b) {
    return withSeconds(a.start_time) < withSeconds(b.end_time)
        && withSeconds(b.start_time) < withSeconds(a.end_time);
}

function describeBlocked(b) {
    const when = isExceptionRow(b)
        ? "exception " + toYmd(b.specific_date)
        : isDailyRow(b) ? "every day" : toYmd(b.specific_date);
    return (b.title || "blocked") + " " + when + " " + b.start_time + "–" + b.end_time;
}

function syncBlockedDates() {
    const every = document.getElementById("bEvery");
    const exception = document.getElementById("bException");
    const from = document.getElementById("bFrom");
    const to = document.getElementById("bTo");
    if (!every || !exception || !from || !to) return;
    if (exception.checked) every.checked = false;
    every.disabled = exception.checked;
    const needDate = exception.checked || !every.checked;
    from.disabled = !needDate;
    to.disabled = !needDate;
}

function whenLabel(b) {
    if (isExceptionRow(b)) return "exception " + toYmd(b.specific_date);
    if (isDailyRow(b)) return "every day";
    return toYmd(b.specific_date) || String(b.weekday ?? "");
}

function paintBlocked(rows) {
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
        const left = document.createElement("div");
        left.innerHTML =
            "<div>" + (b.title || "blocked") +
            "  " + (b.start_time || "") + "–" + (b.end_time || "") + "</div>" +
            "<div class='meta'>" + whenLabel(b) + "</div>";
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
    const w = String(t.workflow || t.lifecycle || "").toUpperCase();
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

function matchesWhen(t) {
    const day = toYmd(t.placed_date || t.placedDate);
    const today = todayYmd();
    if (taskWhen === "all") return true;
    if (taskWhen === "unscheduled") return isUnscheduled(t);
    if (taskWhen === "past") return isFinished(t) || (!!day && day < today);
    if (taskWhen === "ongoing") return !isFinished(t) && !isUnscheduled(t);
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
    const now = new Date();
    const day = now.getDay();
    const back = day === 0 ? 6 : day - 1;
    const mon = new Date(now.getFullYear(), now.getMonth(), now.getDate() - back + offset * 7);
    mon.setHours(0, 0, 0, 0);
    return mon;
}

function ymdFromDate(d) {
    const pad = (n) => String(n).padStart(2, "0");
    return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
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
    const today = todayYmd();
    days.forEach((d, i) => {
        const ymd = ymdFromDate(d);
        const col = document.createElement("div");
        col.className = "day-col" + (ymd === today ? " today" : "");
        const head = document.createElement("div");
        head.className = "day-head";
        head.textContent = names[i] + " " + ymd.slice(5);
        col.appendChild(head);
        const items = allTasks.filter((t) => toYmd(t.placed_date) === ymd)
            .sort((a, b) => String(a.placed_time || "").localeCompare(String(b.placed_time || "")));
        if (!items.length) {
            const empty = document.createElement("div");
            empty.className = "meta";
            empty.textContent = "—";
            col.appendChild(empty);
        }
        items.forEach((t) => {
            const b = document.createElement("button");
            b.type = "button";
            b.className = "chip" + (isFinished(t) ? " done" : "");
            b.dataset.taskToggle = t.id;
            b.innerHTML = "<div class='t'>" + (t.placed_time || "").slice(0, 5) + " " + (t.title || "(untitled)") + "</div>"
                + "<div class='meta'>" + (isFinished(t) ? "finished" : "unfinished") + "</div>";
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
        left.innerHTML =
            "<div>" + (t.title || "(untitled)") + "</div>" +
            "<div class='meta'>" +
            (toYmd(t.placed_date) || "unscheduled") +
            (t.placed_time ? " " + t.placed_time : "") +
            " · " + (taskState(t) || "task") +
            (hasDeadline(t) ? " · deadline" : "") +
            " · " + (isFinished(t) ? "finished" : "unfinished") +
            "</div>";
        const actions = document.createElement("div");
        actions.className = "row";
        const done = document.createElement("button");
        done.type = "button";
        done.className = "ghost";
        done.textContent = isFinished(t) ? "Undo" : "Finish";
        done.dataset.taskToggle = t.id;
        const del = document.createElement("button");
        del.type = "button";
        del.className = "danger";
        del.textContent = "Delete";
        del.dataset.taskId = t.id;
        actions.appendChild(done);
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
    await send("POST", "/tasks", {
        id: t.id,
        title: t.title,
        duration: Number(t.duration || 0),
        state: t.state || "Flexible",
        placed_date: toYmd(t.placed_date),
        placedDate: toYmd(t.placed_date),
        placed_time: t.placed_time,
        placedTime: t.placed_time,
        deadline_date: t.deadline_date || null,
        deadline_time: t.deadline_time || null,
        lifecycle: "ONGOING",
        workflow: finished ? "UNFINISHED" : "FINISHED",
        last_updated_at: new Date().toISOString(),
        placement_updated_at: t.placement_updated_at || new Date().toISOString()
    });
    await loadAll();
}

function imageIds(note) {
    const imgs = note && note.images;
    if (!Array.isArray(imgs)) return [];
    const out = [];
    for (const im of imgs) {
        if (typeof im === "string") {
            if (/^[a-f0-9]{64}$/i.test(im)) out.push({ id: im.toLowerCase(), name: "" });
            continue;
        }
        const id = String((im && (im.id || im.sha256)) || "").toLowerCase();
        if (/^[a-f0-9]{64}$/.test(id)) out.push({ id, name: (im && im.name) || "" });
    }
    return out;
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
        years.forEach((y) => {
            let n = 0;
            Object.values(idx[y]).forEach((days) => { n += days.length; });
            const li = document.createElement("li");
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "find-link";
            btn.dataset.twFindYear = String(y);
            btn.innerHTML = "<div class='k'>" + y + "</div><div class='n'>" + n + " written day(s)</div>";
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
        months.forEach((m) => {
            const li = document.createElement("li");
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "find-link";
            btn.dataset.twFindMonth = String(m);
            btn.innerHTML = "<div class='k'>" + MONTHS[m - 1] + "</div><div class='n'>" + idx[findYear][m].length + " written day(s)</div>";
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
    days.forEach((n) => {
        const li = document.createElement("li");
        const left = document.createElement("div");
        const imgs = imageIds(n);
        left.innerHTML = "<div>" + (n.title || n.date) + "</div>" +
            "<div class='meta'>" + n.date + (imgs.length ? " · " + imgs.length + " img" : "") + "</div>";
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
        const left = document.createElement("div");
        const imgs = imageIds(n);
        left.innerHTML = "<div>" + (n.title || "page") + "</div>" +
            "<div class='meta'>page" + (imgs.length ? " · " + imgs.length + " img" : "") + "</div>";
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
        const left = document.createElement("div");
        left.innerHTML = "<div>" + (n.title || "list") + "</div>" +
            "<div class='meta'>" + ((n.items && n.items.length) || 0) + " items</div>";
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

async function showTw(kind, key) {
    const view = document.getElementById("twView");
    const title = document.getElementById("twTitle");
    const meta = document.getElementById("twMeta");
    const body = document.getElementById("twBody");
    const imgs = document.getElementById("twImgs");
    if (!view || !title || !meta || !body || !imgs) return;
    view.style.display = "block";
    imgs.innerHTML = "";
    twOpen = { kind, key };
    setTwMsg("");

    if (kind === "diary") {
        const n = findDiary(key);
        if (!n) {
            title.value = "";
            body.value = "";
            meta.textContent = key + " · not found";
            return;
        }
        title.value = n.title || n.date || "";
        body.value = n.body || "";
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
        body.value = n.body || "";
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
    body.value = (n.items || []).map((it) => (it.done ? "[x] " : "[ ] ") + (it.text || "")).join("\n");
    meta.textContent = "list · one item per line, [x] = done";
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
            const pic = document.createElement("img");
            pic.alt = im.name || im.id;
            pic.src = "data:" + (row.mime || "image/png") + ";base64," + row.data;
            imgs.appendChild(pic);
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
    await send("POST", "/typewriter", {
        content: JSON.stringify(twBundle),
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
    const title = ((titleEl && titleEl.value) || "").trim() || "page";
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
    const title = ((titleEl && titleEl.value) || "").trim() || "list";
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
    return String(text || "").split(/\r?\n/).filter((s) => s.trim() !== "").map((line) => {
        const done = /^\s*\[x\]\s*/i.test(line);
        return { text: line.replace(/^\s*\[(x| )\]\s*/i, ""), done };
    });
}

async function saveOpenNote() {
    if (!twOpen) throw new Error("nothing open");
    const title = document.getElementById("twTitle").value;
    const body = document.getElementById("twBody").value;
    if (twOpen.kind === "diary") {
        const n = findDiary(twOpen.key);
        if (!n) throw new Error("diary missing");
        n.title = title;
        n.body = body;
        if (!diaryHasContent(n)) throw new Error("write something before saving a diary day");
    } else if (twOpen.kind === "page") {
        const n = findPage(twOpen.key);
        if (!n) throw new Error("page missing");
        n.title = title;
        n.body = body;
    } else {
        const n = findList(twOpen.key);
        if (!n) throw new Error("list missing");
        n.title = title;
        n.items = parseListBody(body);
    }
    await saveBundle();
    paintTwLists();
    noteOk("Saved");
}

async function deleteOpenNote() {
    if (!twOpen) throw new Error("nothing open");
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
    const [tasksRes, blockedRes, tw] = await Promise.all([
        send("GET", "/tasks"),
        send("GET", "/blocked"),
        send("GET", "/typewriter")
    ]);
    paintTasks(tasksRes.tasks || []);
    paintBlocked(blockedRes.blocked || []);
    applyTypewriter(tw);
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
        const data = await send("POST", register ? "/register" : "/login", { username, password });
        await afterLogin(data);
    } catch (ex) {
        showErr(ex.message);
        toast(ex.message, true);
    } finally {
        setLoginBusy(false);
    }
}

async function addTask() {
    showErr("");
    const title = document.getElementById("tTitle").value.trim();
    const duration = Number(document.getElementById("tDur").value);
    const state = document.getElementById("tState").value;
    const date = toYmd(document.getElementById("tDate").value);
    let time = document.getElementById("tTime").value;
    if (!title || !date || !time || !(duration > 0)) {
        throw new Error("title, duration, date, time required");
    }
    time = withSeconds(time);
    const now = new Date().toISOString();
    await send("POST", "/tasks", {
        id: nid(),
        title,
        taskName: title,
        duration,
        state,
        placed_date: date,
        placedDate: date,
        placed_time: time,
        placedTime: time,
        deadline_date: null,
        deadline_time: null,
        lifecycle: "ONGOING",
        workflow: "UNFINISHED",
        last_updated_at: now,
        placement_updated_at: now
    });
    document.getElementById("tTitle").value = "";
    await loadAll();
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
    const title = document.getElementById("bTitle").value.trim() || "blocked";
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
    const now = new Date().toISOString();

    const rows = [];
    if (!isException && every) {
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

const btnLogin = document.getElementById("btnLogin");
const btnReg = document.getElementById("btnReg");
if (btnLogin) btnLogin.addEventListener("click", () => { doLogin(false); });
if (btnReg) btnReg.addEventListener("click", () => { doLogin(true); });

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
            await send("DELETE", "/tasks/" + t.closest("[data-task-id]").dataset.taskId);
            await loadAll();
        } else if (t.closest("[data-blocked-id]")) {
            await send("DELETE", "/blocked/" + t.closest("[data-blocked-id]").dataset.blockedId);
            await loadAll();
        }
    } catch (ex) {
        showErr(ex.message);
        toast(ex.message, true);
    }
});