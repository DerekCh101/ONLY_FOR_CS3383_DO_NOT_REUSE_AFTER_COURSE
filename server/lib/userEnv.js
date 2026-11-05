/** Load Ashore Prospect user env from OS config — never the git working tree. */
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

export function userEnvFile() {
    if (process.platform === "win32") {
        const appdata = process.env.APPDATA;
        if (appdata) return join(appdata, "ashore-prospect", "user.env");
        return join(homedir(), "AppData", "Roaming", "ashore-prospect", "user.env");
    }
    const xdg = process.env.XDG_CONFIG_HOME;
    const base = xdg && String(xdg).trim() ? xdg : join(homedir(), ".config");
    return join(base, "ashore-prospect", "user.env");
}

/** Fill process.env from the user-config file when a key is not already set. */
export function applyUserEnv() {
    const p = userEnvFile();
    if (!existsSync(p)) return p;
    try {
        for (const line of readFileSync(p, "utf8").split(/\r?\n/)) {
            const s = line.trim();
            if (!s || s.startsWith("#")) continue;
            const i = s.indexOf("=");
            if (i < 1) continue;
            const k = s.slice(0, i).trim();
            const v = s.slice(i + 1);
            if (k && !process.env[k]) process.env[k] = v;
        }
    } catch { /* ignore */ }
    return p;
}
