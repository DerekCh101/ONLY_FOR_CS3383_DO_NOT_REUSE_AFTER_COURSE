const err = document.getElementById("err");
const errAdd = document.getElementById("errAdd");
const errBlocked = document.getElementById("errBlocked");
const auth = document.getElementById("auth");
const app = document.getElementById("app");

let twRawContent = "";
let twNotes = [];

function showErr(msg) {
    err.textContent = msg;
    if (errAdd) errAdd.textContent = "";
    if (errBlocked) errBlocked.textContent = msg || "";
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
    return m ? m[1] + "-" + m[2] + "-" + m[3] : s;
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

function paintTasks(rows) {
    const list = document.getElementById("tasks");
    list.innerHTML = "";
    if (!rows.length) {
        list.innerHTML = "<li><span class='meta'>(none)</span></li>";
        return;
    }
    for (const t of rows) {
        const li = document.createElement("li");
        const left = document.createElement("div");
        left.innerHTML =
            "<div>" + (t.title || "(untitled)") + "</div>" +
            "<div class='meta'>" +
            (t.placed_date ? String(t.placed_date).slice(0, 10) : "unscheduled") +
            (t.placed_time ? " " + t.placed_time : "") +
            "</div>";
        const del = document.createElement("button");
        del.type = "button";
        del.className = "danger";
        del.textContent = "Delete";
        del.dataset.taskId = t.id;
        li.appendChild(left);
        li.appendChild(del);
        list.appendChild(li);
    }
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

function collectTwNotes(bundle) {
    const notes = [];
    if (!bundle || typeof bundle !== "object") return notes;
    const diary = Array.isArray(bundle.diary) ? bundle.diary : [];
    for (const n of diary) {
        notes.push({
            kind: "diary",
            id: n.id || n.date || "",
            date: n.date || "",
            title: n.title || n.date || "diary",
            body: n.body || "",
            images: imageIds(n)
        });
    }
    const pages = Array.isArray(bundle.pages) ? bundle.pages : [];
    for (const n of pages) {
        if (n.kind === "folder") continue;
        notes.push({
            kind: "page",
            id: n.id || "",
            date: "",
            title: n.title || "page",
            body: n.body || "",
            images: imageIds(n)
        });
    }
    return notes;
}

function paintTwList() {
    const list = document.getElementById("twList");
    const view = document.getElementById("twView");
    list.innerHTML = "";
    if (view) view.style.display = "none";
    if (!twNotes.length) {
        list.innerHTML = "<li><span class='meta'>(no typed notes)</span></li>";
        return;
    }
    twNotes.forEach((n, i) => {
        const li = document.createElement("li");
        const left = document.createElement("div");
        left.innerHTML =
            "<div>" + (n.title || "(untitled)") + "</div>" +
            "<div class='meta'>" + (n.kind === "diary" ? (n.date || "diary") : "page") +
            (n.images.length ? " · " + n.images.length + " img" : "") + "</div>";
        const show = document.createElement("button");
        show.type = "button";
        show.className = "ghost";
        show.textContent = "Show";
        show.dataset.twIndex = String(i);
        li.appendChild(left);
        li.appendChild(show);
        list.appendChild(li);
    });
}

async function showTwNote(index) {
    const n = twNotes[index];
    const view = document.getElementById("twView");
    const title = document.getElementById("twTitle");
    const meta = document.getElementById("twMeta");
    const body = document.getElementById("twBody");
    const imgs = document.getElementById("twImgs");
    if (!n || !view) return;
    view.style.display = "block";
    title.textContent = n.title || "(untitled)";
    meta.textContent = (n.kind === "diary" ? n.date : "page") +
        (n.images.length ? " · loading " + n.images.length + " image(s)" : " · no images");
    body.textContent = n.body || "";
    imgs.innerHTML = "";
    for (const im of n.images) {
        try {
            const row = await send("GET", "/typewriter/images/" + im.id);
            if (!row || !row.data) continue;
            const pic = document.createElement("img");
            pic.alt = im.name || im.id;
            pic.src = "data:" + (row.mime || "image/png") + ";base64," + row.data;
            imgs.appendChild(pic);
        } catch (e) {
            const miss = document.createElement("div");
            miss.className = "meta";
            miss.textContent = "image missing: " + (im.name || im.id);
            imgs.appendChild(miss);
        }
    }
    meta.textContent = (n.kind === "diary" ? n.date : "page") +
        (n.images.length ? " · " + n.images.length + " image(s)" : " · no images");
}

function applyTypewriter(tw) {
    twRawContent = (tw && tw.content) || "";
    const box = document.getElementById("tw");
    twNotes = [];
    let parsed = null;
    try {
        parsed = twRawContent ? JSON.parse(twRawContent) : null;
    } catch (_) {
        parsed = null;
    }
    if (parsed && typeof parsed === "object" && (parsed.diary || parsed.pages || parsed.lists)) {
        twNotes = collectTwNotes(parsed);
        if (box) box.value = "";
    } else if (box) {
        box.value = twRawContent;
    }
    paintTwList();
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

async function afterLogin(data) {
    setToken(data.token);
    document.getElementById("who").textContent =
        "Logged in as " + (data.username || document.getElementById("user").value);
    auth.style.display = "none";
    app.style.display = "block";
    document.getElementById("tDate").value = todayYmd();
    document.getElementById("tTime").value = "09:00";
    document.getElementById("bFrom").value = todayYmd();
    document.getElementById("bTo").value = todayYmd();
    syncBlockedDates();
    await loadAll();
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
        id: (crypto.randomUUID && crypto.randomUUID()) || String(Date.now()),
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
        const y = cur.getFullYear();
        const m = String(cur.getMonth() + 1).padStart(2, "0");
        const d = String(cur.getDate()).padStart(2, "0");
        out.push(y + "-" + m + "-" + d);
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
            id: crypto.randomUUID(),
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
                id: crypto.randomUUID(),
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

document.getElementById("bEvery").addEventListener("change", syncBlockedDates);
document.getElementById("bException").addEventListener("change", syncBlockedDates);

document.addEventListener("click", async (e) => {
    const t = e.target instanceof Element ? e.target : e.target && e.target.parentElement;
    if (!t) return;
    try {
        if (t.closest("#btnLogin")) {
            showErr("");
            await afterLogin(await send("POST", "/login", {
                username: document.getElementById("user").value.trim(),
                password: document.getElementById("pass").value
            }));
        } else if (t.closest("#btnReg")) {
            showErr("");
            await afterLogin(await send("POST", "/register", {
                username: document.getElementById("user").value.trim(),
                password: document.getElementById("pass").value
            }));
        } else if (t.closest("#btnOut")) {
            setToken("");
            app.style.display = "none";
            auth.style.display = "block";
        } else if (t.closest("#btnReload")) {
            await loadAll();
        } else if (t.closest("#btnAdd")) {
            await addTask();
        } else if (t.closest("#btnAddBlocked")) {
            await addBlocked();
        } else if (t.closest("#btnTwSave")) {
            const box = document.getElementById("tw");
            const typed = box ? box.value : "";
            const content = twNotes.length ? twRawContent : typed;
            await send("POST", "/typewriter", {
                content,
                last_updated_at: new Date().toISOString()
            });
            await loadAll();
        } else if (t.dataset && t.dataset.twIndex != null) {
            await showTwNote(Number(t.dataset.twIndex));
        } else if (t.closest("button") && t.closest("button").dataset.twIndex != null) {
            await showTwNote(Number(t.closest("button").dataset.twIndex));
        } else if (t.closest("button") && t.closest("button").dataset.taskId) {
            await send("DELETE", "/tasks/" + t.closest("button").dataset.taskId);
            await loadAll();
        } else if (t.closest("button") && t.closest("button").dataset.blockedId) {
            await send("DELETE", "/blocked/" + t.closest("button").dataset.blockedId);
            await loadAll();
        }
    } catch (ex) {
        showErr(ex.message);
    }
});