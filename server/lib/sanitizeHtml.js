/** Strip script-ish HTML from Typewriter JSON; leave AP1. ciphertext alone. */
/** Defensive strip of script-ish markup. Keeps HTMLEditor tags (b/i/font/h1–h6/span). */

const DROP_TAGS = /<\/?(?:script|iframe|object|embed|link|meta|base|form|input|textarea|button|svg|math|video|audio|source|applet|style|template)(?:\s[^>]*)?>/gi;
const ON_ATTR = /\s+on[a-z]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi;
const JS_URL = /\s(?:href|src|xlink:href)\s*=\s*(['"]?)\s*javascript:[^'"\s>]*/gi;
const CSS_EXPR = /expression\s*\(|url\s*\(\s*['"]?\s*javascript:/gi;

export function sanitizeHtml(html) {
    if (html == null) return "";
    let s = String(html);
    s = s.replace(DROP_TAGS, "");
    s = s.replace(ON_ATTR, "");
    s = s.replace(JS_URL, "");
    s = s.replace(CSS_EXPR, "");
    return s;
}

export function sanitizeTypewriterContent(content) {
    if (content == null) return "";
    const raw = String(content);
    if (raw.startsWith("AP1.")) return raw;
    try {
        const obj = JSON.parse(raw);
        walk(obj);
        return JSON.stringify(obj);
    } catch {
        return sanitizeHtml(raw);
    }
}

function walk(node) {
    if (Array.isArray(node)) {
        for (const x of node) walk(x);
        return;
    }
    if (!node || typeof node !== "object") return;
    if (typeof node.body === "string") {
        const b = sanitizeHtml(node.body);
        node.body = b.length > 100000 ? b.slice(0, 100000) : b;
    }
    if (typeof node.text === "string") {
        const t = sanitizeHtml(node.text);
        node.text = t.length > 8000 ? t.slice(0, 8000) : t;
    }
    if (typeof node.title === "string") {
        const t = sanitizeHtml(node.title);
        node.title = t.length > 200 ? t.slice(0, 200) : t;
    }
    if (Array.isArray(node.images) && node.images.length > 20) {
        node.images = node.images.slice(0, 20);
    }
    for (const v of Object.values(node)) walk(v);
}
