/** Same AP1. AES-GCM / PBKDF2 as Java Vault and server/vault.js. */
import { createHash, pbkdf2, createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

export const PREFIX = "AP1.";
const ITER = 120000;
const IV_LEN = 12;
const TAG_LEN = 16;

export function sealed(s) {
    return typeof s === "string" && s.startsWith(PREFIX);
}

export function derive(uid, password) {
    const salt = createHash("sha256").update("AP1|" + String(uid || ""), "utf8").digest();
    return new Promise((resolve, reject) => {
        pbkdf2(String(password || ""), salt, ITER, 32, "sha256", (err, key) => {
            if (err) reject(err);
            else resolve(key);
        });
    });
}

export function open(key, value) {
    if (value == null) return "";
    const raw = String(value);
    if (!key || !sealed(raw)) return raw;
    try {
        const all = Buffer.from(raw.slice(PREFIX.length), "base64url");
        if (all.length <= IV_LEN + TAG_LEN) return raw;
        const iv = all.subarray(0, IV_LEN);
        const tag = all.subarray(all.length - TAG_LEN);
        const ct = all.subarray(IV_LEN, all.length - TAG_LEN);
        const dec = createDecipheriv("aes-256-gcm", key, iv, { authTagLength: TAG_LEN });
        dec.setAuthTag(tag);
        return Buffer.concat([dec.update(ct), dec.final()]).toString("utf8");
    } catch {
        return raw;
    }
}

export function seal(key, plain) {
    if (!key) return plain == null ? "" : String(plain);
    if (plain == null || plain === "") return "";
    const raw = String(plain);
    if (sealed(raw)) return raw;
    const iv = randomBytes(IV_LEN);
    const c = createCipheriv("aes-256-gcm", key, iv, { authTagLength: TAG_LEN });
    const ct = Buffer.concat([c.update(raw, "utf8"), c.final()]);
    const tag = c.getAuthTag();
    return PREFIX + Buffer.concat([iv, ct, tag]).toString("base64url");
}

const WRAP_PREFIX = "VW1.";

export function wrapSessionKey(token, dek) {
    if (!token || !dek) return "";
    const wrapKey = createHash("sha256").update("ashore-session-vault|" + String(token), "utf8").digest();
    const iv = randomBytes(IV_LEN);
    const c = createCipheriv("aes-256-gcm", wrapKey, iv, { authTagLength: TAG_LEN });
    const raw = Buffer.isBuffer(dek) ? dek : Buffer.from(dek);
    const ct = Buffer.concat([c.update(raw), c.final()]);
    const tag = c.getAuthTag();
    return WRAP_PREFIX + Buffer.concat([iv, ct, tag]).toString("base64url");
}

export function unwrapSessionKey(token, wrap) {
    const s = String(wrap || "");
    if (!token || !s.startsWith(WRAP_PREFIX)) return null;
    try {
        const wrapKey = createHash("sha256").update("ashore-session-vault|" + String(token), "utf8").digest();
        const all = Buffer.from(s.slice(WRAP_PREFIX.length), "base64url");
        if (all.length <= IV_LEN + TAG_LEN) return null;
        const iv = all.subarray(0, IV_LEN);
        const tag = all.subarray(all.length - TAG_LEN);
        const ct = all.subarray(IV_LEN, all.length - TAG_LEN);
        const dec = createDecipheriv("aes-256-gcm", wrapKey, iv, { authTagLength: TAG_LEN });
        dec.setAuthTag(tag);
        return Buffer.concat([dec.update(ct), dec.final()]);
    } catch {
        return null;
    }
}
