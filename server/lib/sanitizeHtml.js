/** Strip script-ish markup. Keep inner Typewriter HTML (align / bold / color). Unwrap editor shells. */

const DROP_TAGS = /<\/?(?:script|iframe|object|embed|link|meta|base|form|input|textarea|button|svg|math|video|audio|source|applet|style|template)(?:\s[^>]*)?>/gi;
const ON_ATTR = /\s+on[a-z]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi;
const JS_URL = /\s(?:href|src|xlink:href)\s*=\s*(['"]?)\s*javascript:[^'"\s>]*/gi;
const CSS_EXPR = /expression\s*\(|url\s*\(\s*['"]?\s*javascript:/gi;

export function looksLikeEditorDump(raw) {
    const s = String(raw || "");
    if (!s) return false;
    return /contenteditable|ashore-ln|ashore-tw-theme|<\s*html[\s>]|<\s*body[\s>]|\\u003chtml|u003chtml|\\u003cbody|u003cbody/i.test(s);
}

export function unwrapEditorInner(raw) {
    let s = decodeEscapes(raw);
    if (!s) return "";
    s = stripEditorChrome(s);
    for (let i = 0; i < 8; i++) {
        const inner = bodyInnerOnce(s);
        if (inner === s) break;
        s = inner;
    }
    return stripEditorChrome(s).trim();
}

export function editorHtmlToPlain(raw) {
    return htmlToPlain(unwrapEditorInner(raw)).trim();
}

export function sanitizeHtml(html) {
    if (html == null) return "";
    let s = looksLikeEditorDump(html) ? unwrapEditorInner(html) : String(html);
    s = s.replace(DROP_TAGS, "");
    s = s.replace(ON_ATTR, "");
    s = s.replace(JS_URL, "");
    s = s.replace(CSS_EXPR, "");
    return s;
}

export function sanitizeTypewriterContent(content, previous) {
    if (content == null) return "";
    const raw = String(content);
    if (raw.startsWith("AP1.")) return raw;
    let prevObj = null;
    if (previous && !String(previous).startsWith("AP1.")) {
        try { prevObj = JSON.parse(String(previous)); } catch { prevObj = null; }
    }
    try {
        const obj = JSON.parse(raw);
        walk(obj, prevObj);
        return JSON.stringify(obj);
    } catch {
        return sanitizeHtml(raw);
    }
}

function sealedField(s) {
    return String(s ?? "").startsWith("AP1.");
}

function walk(node, prev) {
    if (Array.isArray(node)) {
        const prevArr = Array.isArray(prev) ? prev : [];
        for (let i = 0; i < node.length; i++) {
            const p = prevArr.find((x) => x && node[i] && x.id && node[i].id && String(x.id) === String(node[i].id))
                || prevArr[i];
            walk(node[i], p);
        }
        return;
    }
    if (!node || typeof node !== "object") return;
    if (typeof node.body === "string" && !sealedField(node.body)) {
        node.body = cleanField(node.body, prev && typeof prev.body === "string" ? prev.body : "");
        if (node.body.length > 100000) node.body = node.body.slice(0, 100000);
    }
    if (typeof node.text === "string" && !sealedField(node.text)) {
        node.text = cleanField(node.text, prev && typeof prev.text === "string" ? prev.text : "");
        if (node.text.length > 8000) node.text = node.text.slice(0, 8000);
    }
    if (typeof node.title === "string" && !sealedField(node.title)) {
        const t = sanitizeHtml(node.title);
        node.title = t.length > 200 ? t.slice(0, 200) : t;
    }
    if (Array.isArray(node.images) && node.images.length > 20) {
        node.images = node.images.slice(0, 20);
    }
    const prevMap = prev && typeof prev === "object" ? prev : {};
    for (const [k, v] of Object.entries(node)) {
        if (k === "body" || k === "text" || k === "title" || k === "images") continue;
        walk(v, prevMap[k]);
    }
}

function cleanField(incoming, existing) {
    const raw = String(incoming || "");
    if (!raw) return "";
    const cleaned = sanitizeHtml(raw);
    if (looksLikeEditorDump(raw) && !editorHtmlToPlain(cleaned) && existing) {
        return sanitizeHtml(existing);
    }
    return cleaned;
}

function decodeEscapes(raw) {
    let s = String(raw ?? "");
    s = s.replace(/\\u([0-9a-fA-F]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
    if (/u003c(?:html|body|p|div|br)/i.test(s) && !/<html|<body|<p|<div/i.test(s)) {
        s = s.replace(/u00([0-9a-fA-F]{2})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
    }
    return s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, "\"");
}

function stripEditorChrome(html) {
    return String(html || "")
        .replace(/<div[^>]*\bid\s*=\s*(['"]?)ashore-ln\1[^>]*>[\s\S]*?<\/div>/gi, "")
        .replace(/<style[^>]*\bid\s*=\s*(['"]?)ashore-tw-theme\1[^>]*>[\s\S]*?<\/style>/gi, "");
}

function bodyInnerOnce(html) {
    const s = String(html || "").trim();
    if (!s) return "";
    const lower = s.toLowerCase();
    let at = lower.indexOf("<body");
    if (at >= 0) {
        const gt = s.indexOf(">", at);
        const end = lower.lastIndexOf("</body>");
        if (gt >= 0 && end > gt) return s.slice(gt + 1, end).trim();
    }
    at = lower.indexOf("<html");
    if (at >= 0) {
        const gt = s.indexOf(">", at);
        const end = lower.lastIndexOf("</html>");
        if (gt >= 0 && end > gt) return s.slice(gt + 1, end).trim();
    }
    return s;
}

function htmlToPlain(html) {
    let s = String(html || "");
    s = s.replace(/<script[\s\S]*?<\/script>/gi, "")
        .replace(/<style[\s\S]*?<\/style>/gi, "")
        .replace(/<head[\s\S]*?<\/head>/gi, "")
        .replace(/<br\s*\/?>/gi, "\n")
        .replace(/<\/p>/gi, "\n")
        .replace(/<\/div>/gi, "\n")
        .replace(/<\/li>/gi, "\n")
        .replace(/<\/h[1-6]>/gi, "\n")
        .replace(/<\/tr>/gi, "\n")
        .replace(/<[^>]+>/g, "")
        .replace(/&nbsp;/g, " ")
        .replace(/&amp;/g, "&")
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">")
        .replace(/&quot;/g, "\"")
        .replace(/&[a-zA-Z]+;/g, " ");
    return s.replace(/[ \t]+\n/g, "\n").replace(/\n{3,}/g, "\n\n").replace(/\n+$/g, "");
}
