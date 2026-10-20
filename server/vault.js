/** Browser Vault: same AP1. AES-GCM / PBKDF2 as Java org.ashoreprospect.crypto.Vault. */
(function (global) {
    const PREFIX = "AP1.";
    const ITER = 120000;
    const IV_LEN = 12;

    let key = null;
    let userId = null;
    let wanted = false;
    let sawSealed = false;

    function sealed(s) {
        return typeof s === "string" && s.startsWith(PREFIX);
    }

    function markSealed(s) {
        if (sealed(s)) sawSealed = true;
        return s;
    }

    function consumeSawSealed() {
        const v = sawSealed;
        sawSealed = false;
        return v;
    }

    function hasSubtle() {
        return !!(global.isSecureContext && global.crypto && global.crypto.subtle);
    }

    async function derive(uid, password) {
        if (!hasSubtle()) {
            throw new Error("This page is not a secure context (use https or 127.0.0.1).");
        }
        const enc = new TextEncoder();
        const salt = await crypto.subtle.digest("SHA-256", enc.encode("AP1|" + uid));
        const base = await crypto.subtle.importKey(
            "raw",
            enc.encode(password),
            "PBKDF2",
            false,
            ["deriveKey"]
        );
        return crypto.subtle.deriveKey(
            { name: "PBKDF2", salt: salt, iterations: ITER, hash: "SHA-256" },
            base,
            { name: "AES-GCM", length: 256 },
            false,
            ["encrypt", "decrypt"]
        );
    }

    function b64urlToBytes(s) {
        let t = String(s || "").replace(/-/g, "+").replace(/_/g, "/");
        while (t.length % 4) t += "=";
        const bin = atob(t);
        const out = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
        return out;
    }

    function bytesToB64url(bytes) {
        const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
        let bin = "";
        const chunk = 0x8000;
        for (let i = 0; i < arr.length; i += chunk) {
            bin += String.fromCharCode.apply(null, arr.subarray(i, i + chunk));
        }
        return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    }

    async function open(value) {
        if (value == null) return "";
        markSealed(value);
        if (!sealed(value) || !key) return value;
        try {
            const all = b64urlToBytes(value.slice(PREFIX.length));
            if (all.length <= IV_LEN) return value;
            const iv = all.slice(0, IV_LEN);
            const ct = all.slice(IV_LEN);
            const pt = await crypto.subtle.decrypt(
                { name: "AES-GCM", iv: iv, tagLength: 128 },
                key,
                ct
            );
            return new TextDecoder().decode(pt);
        } catch (_) {
            return value;
        }
    }

    async function seal(plain) {
        if (!key || !wanted) return plain == null ? "" : plain;
        if (plain == null || plain === "") return "";
        if (sealed(plain)) return plain;
        const iv = crypto.getRandomValues(new Uint8Array(IV_LEN));
        const ct = await crypto.subtle.encrypt(
            { name: "AES-GCM", iv: iv, tagLength: 128 },
            key,
            new TextEncoder().encode(String(plain))
        );
        const packed = new Uint8Array(IV_LEN + ct.byteLength);
        packed.set(iv, 0);
        packed.set(new Uint8Array(ct), IV_LEN);
        return PREFIX + bytesToB64url(packed);
    }

    async function unlock(uid, password) {
        if (!uid || !password) return false;
        if (!hasSubtle()) return false;
        userId = String(uid);
        key = await derive(userId, password);
        return true;
    }

    function lock() {
        key = null;
        wanted = false;
    }

    function eraseLocal() {
        lock();
        userId = null;
    }

    async function openTask(t) {
        if (!t) return t;
        t.title = await open(t.title);
        t.taskName = t.title;
        t.description = await open(t.description);
        return t;
    }

    async function openIncoming(path, json) {
        if (!json) return json;
        if (json.tasks && Array.isArray(json.tasks)) {
            json.tasks = await Promise.all(json.tasks.map(openTask));
        }
        if (json.nested && Array.isArray(json.nested)) {
            json.nested = await Promise.all(json.nested.map(openTask));
        }
        if (json.blocked && Array.isArray(json.blocked)) {
            json.blocked = await Promise.all(json.blocked.map(async (b) => {
                b.title = await open(b.title);
                return b;
            }));
        }
        if (typeof json.content === "string") {
            json.content = await openTypewriter(json.content);
        }
        return json;
    }

    async function walkTw(node, fn) {
        if (Array.isArray(node)) {
            for (const x of node) await walkTw(x, fn);
            return;
        }
        if (!node || typeof node !== "object") return;
        if (typeof node.title === "string") node.title = await fn(node.title);
        if (typeof node.body === "string") node.body = await fn(node.body);
        if (typeof node.text === "string") node.text = await fn(node.text);
        for (const k of Object.keys(node)) {
            if (k === "title" || k === "body" || k === "text") continue;
            const v = node[k];
            if (v && typeof v === "object") await walkTw(v, fn);
        }
    }

    async function openTypewriter(raw) {
        const s = String(raw == null ? "" : raw);
        if (sealed(s)) return open(s);
        try {
            const o = JSON.parse(s);
            await walkTw(o, open);
            return JSON.stringify(o);
        } catch (_) {
            return open(s);
        }
    }

    async function sealTypewriter(raw) {
        const s = String(raw == null ? "" : raw);
        if (sealed(s)) return s;
        try {
            const o = JSON.parse(s);
            await walkTw(o, seal);
            return JSON.stringify(o);
        } catch (_) {
            return seal(s);
        }
    }

    async function sealOutgoing(path, method, body) {
        if (!body || method === "GET" || !wanted || !key) return body;
        const out = Object.assign({}, body);
        if (path === "/tasks" || (path && path.indexOf("/tasks") === 0 && method === "POST")) {
            if ("title" in out) out.title = await seal(out.title);
            if ("taskName" in out) out.taskName = await seal(out.taskName);
            if ("description" in out) out.description = await seal(out.description);
        }
        if (path === "/blocked" && method === "POST") {
            if ("title" in out) out.title = await seal(out.title);
        }
        if (path === "/typewriter" && method === "POST" && typeof out.content === "string") {
            out.content = await sealTypewriter(out.content);
        }
        return out;
    }

    function stillCipher(value) {
        return sealed(value);
    }

    global.AshoreVault = {
        PREFIX: PREFIX,
        sealed: sealed,
        stillCipher: stillCipher,
        open: open,
        seal: seal,
        unlock: unlock,
        lock: lock,
        eraseLocal: eraseLocal,
        openTask: openTask,
        openIncoming: openIncoming,
        sealOutgoing: sealOutgoing,
        consumeSawSealed: consumeSawSealed,
        enabled: function () { return !!key; },
        wanted: function () { return wanted; },
        setWanted: function (on) { wanted = !!on; },
        userId: function () { return userId; },
        setUserId: function (id) { userId = id ? String(id) : null; },
        canSubtle: hasSubtle
    };
})(window);
