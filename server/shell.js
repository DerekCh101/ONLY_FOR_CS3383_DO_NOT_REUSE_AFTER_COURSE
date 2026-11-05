/** React SPA chrome: Calendar / Typewriter / Account subpages. */
import { createElement as h, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import htm from "htm";

const html = htm.bind(h);

function parseHash() {
    const raw = (location.hash || "").replace(/^#\/?/, "");
    const parts = raw.split("/").filter(Boolean);
    const page = parts[0] === "typewriter" || parts[0] === "account" ? parts[0] : "calendar";
    const section = parts[1] === "security" || parts[1] === "notifications" || parts[1] === "device"
        ? parts[1] : "user";
    return { page, section };
}

function go(page, section) {
    const next = page === "account"
        ? "#/account" + (section && section !== "user" ? "/" + section : "")
        : page === "typewriter" ? "#/typewriter" : "#/calendar";
    if (location.hash !== next) location.hash = next;
    else window.dispatchEvent(new HashChangeEvent("hashchange"));
}

function Field({ label, children }) {
    return html`<div className="acct-block">
        <h3>${label}</h3>
        ${children}
    </div>`;
}

function Fold({ label, children }) {
    const [open, setOpen] = useState(false);
    return html`<div className="acct-block">
        <button type="button" className="acct-fold" aria-expanded=${open}
            onClick=${() => setOpen(!open)}>
            <span>${label}</span>
            <span className="chev">${open ? "▾" : "▸"}</span>
        </button>
        ${open ? html`<div className="acct-fold-body">${children}</div>` : null}
    </div>`;
}

function UserPage() {
    const [me, setMe] = useState(null);
    const [username, setUsername] = useState("");
    const [email, setEmail] = useState("");
    const [msg, setMsg] = useState("");
    const api = window.AshoreApp;

    async function refresh() {
        if (!api) return;
        try {
            const row = await api.send("GET", "/me");
            setMe(row);
            setUsername(row.username || "");
            setEmail(row.email || "");
        } catch (e) {
            setMsg(e.message || "Could not load profile");
        }
    }

    useEffect(() => { refresh(); }, []);

    async function saveUsername(ev) {
        ev.preventDefault();
        setMsg("Working…");
        try {
            const r = await api.send("POST", "/me/username", { username });
            setMsg("Username updated.");
            const who = document.getElementById("who");
            if (who) who.textContent = "Logged in as " + (r.username || username);
        } catch (e) {
            setMsg(e.message);
        }
    }

    async function saveEmail(ev) {
        ev.preventDefault();
        setMsg("Working…");
        try {
            await api.send("POST", "/me/email", { email });
            setMsg("Email saved.");
            refresh();
        } catch (e) {
            setMsg(e.message);
        }
    }

    return html`<div>
        <p className="hint">Signed in as ${(me && me.username) || "user"}.</p>
        <p className="meta">Email: ${(me && me.email) || "(none)"}</p>
        <${Field} label="Change username">
            <form className="acct-form" onSubmit=${saveUsername}>
                <input value=${username} onChange=${(e) => setUsername(e.target.value)} maxlength="40" />
                <button type="submit">Save username</button>
            </form>
        <//>
        <${Field} label="Change email">
            <form className="acct-form" onSubmit=${saveEmail}>
                <input type="email" value=${email} onChange=${(e) => setEmail(e.target.value)} />
                <button type="submit">Save email</button>
            </form>
        <//>
        <p className="hint">${msg}</p>
    </div>`;
}

function SecurityPage() {
    const [oldP, setOldP] = useState("");
    const [n1, setN1] = useState("");
    const [n2, setN2] = useState("");
    const [question, setQuestion] = useState("");
    const [answer, setAnswer] = useState("");
    const [sessions, setSessions] = useState([]);
    const [msg, setMsg] = useState("");
    const [vaultPw, setVaultPw] = useState("");
    const [delPw, setDelPw] = useState("");
    const api = window.AshoreApp;
    const V = window.AshoreVault;
    const encrypted = V && (V.wanted() || V.enabled());

    async function loadSessions() {
        try {
            const r = await api.send("GET", "/sessions");
            setSessions(r.sessions || []);
        } catch (e) {
            setMsg(e.message);
        }
    }

    async function loadMe() {
        try {
            const me = await api.send("GET", "/me");
            setQuestion(me.securityQuestion || "");
        } catch (_) {}
    }

    useEffect(() => { loadSessions(); loadMe(); }, []);

    async function changePassword(ev) {
        ev.preventDefault();
        if (n1 !== n2) {
            setMsg("New passwords do not match.");
            return;
        }
        const reject = window.ashorePasswordReject;
        const weak = typeof reject === "function" ? reject(n1) : null;
        if (weak) {
            setMsg(weak);
            return;
        }
        setMsg("Working…");
        try {
            const r = await api.send("POST", "/me/password", { oldPassword: oldP, newPassword: n1 });
            if (V && V.enabled() && api.unlockVault) {
                const uid = V.userId() || localStorage.getItem("ashoreUserId");
                await api.unlockVault(uid, n1);
            }
            setMsg(r && r.emailed
                ? "Password changed. Other sessions were signed out. A notice was sent to your email."
                : "Password changed. Other sessions were signed out.");
            setOldP(""); setN1(""); setN2("");
            loadSessions();
        } catch (e) {
            setMsg(e.message);
        }
    }

    async function saveQuestion(ev) {
        ev.preventDefault();
        setMsg("Working…");
        try {
            await api.send("POST", "/me/security", { question, answer });
            setMsg("Saved on the server.");
            setAnswer("");
        } catch (e) {
            setMsg(e.message);
        }
    }

    async function revoke(id, current) {
        setMsg("Working…");
        try {
            const r = await api.send("DELETE", "/sessions/" + id);
            if (current || (r && r.current === true)) {
                api.logout();
                return;
            }
            setMsg("Session revoked.");
            loadSessions();
        } catch (e) {
            setMsg(e.message);
        }
    }

    async function revokeOthers() {
        setMsg("Working…");
        try {
            await api.send("POST", "/sessions/revoke-others");
            setMsg("Other sessions ended.");
            loadSessions();
        } catch (e) {
            setMsg(e.message);
        }
    }

    async function unlock(ev) {
        ev.preventDefault();
        if (!api || !api.unlockVault) return;
        const uid = (V && V.userId()) || localStorage.getItem("ashoreUserId");
        setMsg("Working…");
        const ok = await api.unlockVault(uid, vaultPw);
        setMsg(ok ? "Unlocked. Titles and notes should read as plaintext." : "Could not unlock. Check the account password.");
    }

    async function deleteAccount(ev) {
        ev.preventDefault();
        if (!delPw) {
            setMsg("Password required.");
            return;
        }
        if (!window.confirm("Permanently delete this account and all its data? This cannot be undone.")) return;
        setMsg("Working…");
        try {
            await api.deleteAccount(delPw);
        } catch (e) {
            setMsg(e.message || "Could not delete account.");
        }
    }

    return html`<div>
        <p className="hint">${msg || "Open a row to edit it."}</p>
        <${Fold} label="Encrypt data at rest">
            <p className="hint">${encrypted
                ? "On for Postgres / pgAdmin only. Login already decrypts titles here — this is not a client lock. Use Unlock only if something still looks like AP1. text."
                : "Off. Turn this on from the host desktop Security options so pgAdmin cannot read task text. Clients decrypt automatically when they sign in."}</p>
            <form className="acct-form" onSubmit=${unlock}>
                <input type="password" placeholder="Account password" value=${vaultPw}
                    onChange=${(e) => setVaultPw(e.target.value)} />
                <button type="submit">Unlock if still ciphertext</button>
            </form>
        <//>
        <${Fold} label="Change password">
            <form className="acct-form" onSubmit=${changePassword}>
                <input type="password" placeholder="current password" value=${oldP} onChange=${(e) => setOldP(e.target.value)} />
                <input type="password" placeholder="new password (8+, A-z, number, symbol)" value=${n1} onChange=${(e) => setN1(e.target.value)} />
                <input type="password" placeholder="repeat new password" value=${n2} onChange=${(e) => setN2(e.target.value)} />
                <button type="submit">Change password</button>
            </form>
        <//>
        <${Fold} label="Security question">
            <form className="acct-form" onSubmit=${saveQuestion}>
                <input placeholder="Your own question" value=${question} onChange=${(e) => setQuestion(e.target.value)} />
                <input type="password" placeholder="Answer" value=${answer} onChange=${(e) => setAnswer(e.target.value)} />
                <button type="submit">Save question</button>
            </form>
        <//>
        <${Fold} label="Sessions">
            <p className="hint">This browser is marked current. Open a row to revoke that login.</p>
            <button type="button" className="ghost" onClick=${revokeOthers}>Sign out all other sessions</button>
            <ul className="acct-sessions">
                ${sessions.map((s) => html`<li key=${s.id}>
                    <span>${s.current ? "This device · " : ""}${s.device || "Unknown device"}
                        <span className="meta"> ${s.lastSeenAt || s.last_seen_at || ""}</span>
                    </span>
                    <button type="button" className="danger" onClick=${() => revoke(s.id, s.current)}>Revoke</button>
                </li>`)}
            </ul>
        <//>
        <${Fold} label="Delete this account">
            <p className="hint">Permanently removes tasks, Typewriter notes, images, and logins on the server. Other devices wipe their local copy the next time they open and reach the internet. Needs your password.</p>
            <form className="acct-form" onSubmit=${deleteAccount}>
                <input type="password" placeholder="Account password" value=${delPw}
                    onChange=${(e) => setDelPw(e.target.value)} />
                <button type="submit" className="danger">Delete this account</button>
            </form>
        <//>
    </div>`;
}

const PRESET_LEADS = [0, 5, 10, 30, 60, 120, 1440];

const IDLE_CHOICES = [0, 5, 10, 15, 30, 45, 60, 90, 120, 150, 180];

function DevicePage() {
    const api = window.AshoreApp;
    const [idle, setIdle] = useState(api && api.idleMinutes ? api.idleMinutes() : 0);
    const [dark, setDark] = useState(api && api.wantDark ? api.wantDark() : false);

    function changeIdle(ev) {
        const n = Number(ev.target.value);
        setIdle(n);
        if (api && api.setIdleMinutes) api.setIdleMinutes(n);
    }

    function changeDark(ev) {
        const on = ev.target.checked;
        setDark(on);
        if (api && api.setDark) api.setDark(on);
    }

    return html`<div>
        <p className="hint">This browser only. Idle logout signs you out after no pointer or keyboard. Default is Never. Refresh does not log you out.</p>
        <${Field} label="Idle logout">
            <select value=${String(idle)} onChange=${changeIdle}>
                ${IDLE_CHOICES.map((n) => html`<option key=${n} value=${String(n)}>
                    ${n === 0 ? "Never" : n + " minutes"}
                </option>`)}
            </select>
            <p className="hint">May conflict with staying signed in — idle signs you out after quiet time.</p>
        <//>
        <${Field} label="Appearance">
            <label className="chk">
                <input type="checkbox" checked=${dark} onChange=${changeDark} />
                Dark theme
            </label>
        <//>
    </div>`;
}

function NotificationsPage() {
    const api = window.AshoreApp;
    const [on, setOn] = useState(api ? api.wantReminders() : true);
    const [sound, setSound] = useState(api ? api.wantReminderSound() : true);
    const [leads, setLeads] = useState(api ? api.reminderLeads() : [0, 5]);
    const [custom, setCustom] = useState("");
    const [perm, setPerm] = useState(typeof Notification === "undefined" ? "unsupported" : Notification.permission);
    const extras = leads.filter((n) => PRESET_LEADS.indexOf(n) < 0);

    function persist(next) {
        setLeads(next);
        if (api) api.setReminderLeads(next);
    }

    function toggleMaster(ev) {
        const next = ev.target.checked;
        setOn(next);
        if (api) api.setReminders(next);
        if (next && typeof Notification !== "undefined" && Notification.permission === "default") {
            Notification.requestPermission().then((p) => setPerm(p));
        }
    }

    function toggleSound(ev) {
        const next = ev.target.checked;
        setSound(next);
        if (api) api.setReminderSound(next);
    }

    function toggleLead(mins, checked) {
        const next = leads.filter((n) => n !== mins);
        if (checked) next.push(mins);
        persist(next);
    }

    function addCustom(ev) {
        ev.preventDefault();
        const n = Number.parseInt(custom, 10);
        if (!Number.isFinite(n) || n < 1 || n > 20160) return;
        if (leads.indexOf(n) < 0) persist(leads.concat([n]));
        setCustom("");
    }

    function clearCustom() {
        persist(leads.filter((n) => PRESET_LEADS.indexOf(n) >= 0));
    }

    return html`<div>
        <p className="hint">Tick how far ahead to be reminded for placed unfinished tasks. Browser pop-up plus an in-tab toast. Sound is optional. Desktop Windows popups are separate (Account → Notifications in JavaFX).</p>
        <label className="chk">
            <input type="checkbox" checked=${on} onChange=${toggleMaster} />
            Pop-up reminders
        </label>
        <label className="chk">
            <input type="checkbox" checked=${sound} disabled=${!on} onChange=${toggleSound} />
            Play a sound with the reminder
        </label>
        <p className="meta">Lead times</p>
        <div className="lead-ticks">
            ${PRESET_LEADS.map((mins) => html`<label className="chk" key=${mins}>
                <input type="checkbox" checked=${leads.indexOf(mins) >= 0}
                    onChange=${(e) => toggleLead(mins, e.target.checked)} />
                ${mins === 0 ? "When it starts" : (api ? api.formatLead(mins) : mins + " min") + " before"}
            </label>`)}
        </div>
        <p className="meta">${extras.length
            ? "Also: " + extras.map((n) => (api ? api.formatLead(n) : n + " min") + " before").join(", ")
            : "Custom times you add show here."}</p>
        <form className="lead-add" onSubmit=${addCustom}>
            <input placeholder="Custom minutes (e.g. 15)" value=${custom}
                onChange=${(e) => setCustom(e.target.value)} />
            <button type="submit">Add</button>
            <button type="button" className="ghost" onClick=${clearCustom}>Remove custom times</button>
        </form>
        <p className="meta">Browser permission: ${perm}</p>
    </div>`;
}

function Account({ section, setSection }) {
    const items = [
        ["user", "User info"],
        ["security", "Security"],
        ["notifications", "Notifications"],
        ["device", "Device"]
    ];
    return html`<div className="account-split">
        <nav className="account-nav">
            ${items.map(([id, label]) => html`<button
                key=${id}
                type="button"
                className=${section === id ? "active" : "ghost"}
                onClick=${() => { setSection(id); go("account", id); }}
            >${label}</button>`)}
        </nav>
        <div className="account-main">
            ${section === "security" ? html`<${SecurityPage} />`
                : section === "notifications" ? html`<${NotificationsPage} />`
                : section === "device" ? html`<${DevicePage} />`
                : html`<${UserPage} />`}
        </div>
    </div>`;
}

function Shell() {
    const start = parseHash();
    const [page, setPage] = useState(start.page);
    const [section, setSection] = useState(start.section);

    useEffect(() => {
        function onHash() {
            const next = parseHash();
            setPage(next.page);
            setSection(next.section);
            if (window.AshoreApp) window.AshoreApp.showPage(next.page);
        }
        window.addEventListener("hashchange", onHash);
        onHash();
        return () => window.removeEventListener("hashchange", onHash);
    }, []);

    const tabs = [
        ["calendar", "Calendar"],
        ["typewriter", "Typewriter"]
    ];

    return html`<div className="spa-tabs">
        ${tabs.map(([id, label]) => html`<button
            key=${id}
            type="button"
            className=${page === id ? "active" : "ghost"}
            onClick=${() => go(id, id === "account" ? section : null)}
        >${label}</button>`)}
    </div>`;
}

function mount() {
    const nav = document.getElementById("spaNav");
    const account = document.getElementById("accountRoot");
    if (nav) createRoot(nav).render(html`<${Shell} />`);
    if (account) {
        function AccountHost() {
            const start = parseHash();
            const [section, setSection] = useState(start.section);
            useEffect(() => {
                function onHash() {
                    const next = parseHash();
                    setSection(next.section);
                }
                window.addEventListener("hashchange", onHash);
                return () => window.removeEventListener("hashchange", onHash);
            }, []);
            return html`<${Account} section=${section} setSection=${setSection} />`;
        }
        createRoot(account).render(html`<${AccountHost} />`);
    }
}

mount();
