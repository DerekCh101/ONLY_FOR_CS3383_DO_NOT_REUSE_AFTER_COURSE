/** Per-user upload folder for Typewriter images. */
import { mkdir, writeFile, rm } from "node:fs/promises";
import { join, basename, dirname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "user-files");
const KINDS = new Set(["diary", "pages"]);
const ALLOW_EXT = new Set([".png", ".jpg", ".jpeg", ".gif", ".webp"]);
const ALLOW_MIME = new Set(["image/png", "image/jpeg", "image/gif", "image/webp"]);

export function safeUsername(username) {
    const u = String(username || "").trim();
    if (!/^[A-Za-z0-9._-]{1,40}$/.test(u)) {
        throw new Error("unsafe username folder");
    }
    return u;
}

export function safeKind(kind) {
    const k = String(kind || "diary").toLowerCase();
    if (!KINDS.has(k)) throw new Error("kind must be diary or pages");
    return k;
}

export function safeImageExt(name, mime) {
    const m = String(mime || "").toLowerCase();
    if (m && !ALLOW_MIME.has(m)) throw new Error("mime not allowed");
    const base = basename(String(name || "image.png")).toLowerCase();
    const dot = base.lastIndexOf(".");
    const ext = dot >= 0 ? base.slice(dot) : "";
    if (!ALLOW_EXT.has(ext)) throw new Error("file extension not allowed");
    if (base.includes("..") || base.includes("/") || base.includes("\\")) {
        throw new Error("bad file name");
    }
    return ext;
}

/** Writes server/user-files/{username}/{diary|pages}/{sha}{ext}. Never takes a path from the client. */
export async function saveUserImage(username, kind, sha, buf, origName, mime) {
    const user = safeUsername(username);
    const dirKind = safeKind(kind);
    const ext = safeImageExt(origName, mime);
    if (!/^[a-f0-9]{64}$/.test(String(sha || ""))) throw new Error("bad sha");
    const dir = join(ROOT, user, dirKind);
    const dest = resolve(dir, sha + ext);
    const rootResolved = resolve(ROOT) + sep;
    if (!dest.startsWith(rootResolved)) {
        throw new Error("path escaped user-files");
    }
    await mkdir(dir, { recursive: true });
    await writeFile(dest, buf);
    return dest;
}

/** Remove server/user-files/{username} after account deletion. */
export async function removeUserFiles(username) {
    const user = safeUsername(username);
    const dir = resolve(ROOT, user);
    const rootResolved = resolve(ROOT) + sep;
    if (!dir.startsWith(rootResolved)) {
        throw new Error("path escaped user-files");
    }
    await rm(dir, { recursive: true, force: true });
}
