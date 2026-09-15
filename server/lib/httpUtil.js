export function readBody(req) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        req.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
        req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
        req.on("error", reject);
    });
}

export function send(res, code, obj) {
    res.statusCode = code;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify(obj));
}

export function parseJson(raw) {
    const text = String(raw || "").trim();
    if (!text) return {};
    return JSON.parse(text);
}

export function reqUrl(req) {
    return new URL(req.url, "http://127.0.0.1");
}

export function pathname(req) {
    return reqUrl(req).pathname;
}

export function toYmd(v) {
    const s = String(v ?? "").trim();
    if (!s) return "";
    const iso = s.match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (iso) return iso[1] + "-" + iso[2] + "-" + iso[3];
    const dmy = s.match(/^(\d{2})[\/.\-](\d{2})[\/.\-](\d{4})$/);
    if (dmy) return dmy[3] + "-" + dmy[2] + "-" + dmy[1];
    return s.slice(0, 10);
}

export function normTime(t) {
    const s = String(t || "").trim();
    if (s.length === 5) return s + ":00";
    return s;
}

export function isSha256(s) {
    return /^[a-f0-9]{64}$/.test(String(s || "").toLowerCase());
}