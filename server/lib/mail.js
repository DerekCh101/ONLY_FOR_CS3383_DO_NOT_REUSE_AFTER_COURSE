/** Sends password-reset mail using host SMTP settings. */
/**
 * Password-reset mail.
 * Host/user/from: repo-root `.scheduler/smtp.txt`
 * App password: `.scheduler/smtp.env` as SMTP_PASS=... (or SMTP_PASS env).
 * Always print the token on the Node console. Never log the password.
 */

import { readFileSync, existsSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const REPO = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

export function smtpStatus() {
    const cfg = loadSmtp();
    const files = foundFiles();
    return {
        ready: !!cfg,
        host: cfg ? cfg.host : "",
        port: cfg ? cfg.port : 0,
        user: cfg ? cfg.user : "",
        from: cfg ? cfg.from : "",
        passSet: !!(cfg && cfg.pass),
        smtpTxt: files.txt || "(not found)",
        smtpEnv: files.env || "(not found)"
    };
}

export function logSmtpStatus() {
    const s = smtpStatus();
    console.log("SMTP", s.ready ? "ready" : "NOT ready — reset mail will not leave this PC");
    console.log("  host:", s.host || "(missing)", "port:", s.port || "(missing)");
    console.log("  user:", s.user || "(missing)", "from:", s.from || "(missing)");
    console.log("  pass:", s.passSet ? "set" : "MISSING");
    console.log("  smtp.txt:", s.smtpTxt);
    console.log("  smtp.env:", s.smtpEnv);
}

export function printReset(to, token, question) {
    const q = String(question || "").trim();
    console.log("PASSWORD RESET for", to || "(no email on file)");
    console.log("  token (8 min):", token);
    if (q) console.log("  question:", q);
    console.log("  Use it on the login Forgot password screen with the security answer.");
}

/** Console first. SMTP after the HTTP reply so the app does not sit on Gmail. */
export function sendResetMailLater(to, token, question) {
    const q = String(question || "").trim();
    const text =
        "Your Ashore Prospect reset token (valid 8 minutes):\n\n"
        + token
        + (q ? "\n\nYour security question:\n" + q : "")
        + "\n\nOn the login screen tap Forgot password, then enter this token "
        + "with your security-question answer.";
    setImmediate(() => {
        deliverResetMail(to, text).catch((e) => {
            console.error("SMTP send failed:", e.message || e);
        });
    });
}

export async function testSmtp() {
    const cfg = loadSmtp();
    if (!cfg) {
        logSmtpStatus();
        return { ok: false, error: "SMTP not ready (missing host, user, or pass)" };
    }
    const transport = makeTransport(cfg);
    try {
        await transport.verify();
        const info = await transport.sendMail({
            from: cfg.from,
            to: cfg.from,
            subject: "Ashore Prospect SMTP test",
            text: "If you can read this, reset mail can reach this same Gmail address."
        });
        console.log("SMTP test mailed to", cfg.from, "id", info && info.messageId);
        return { ok: true };
    } catch (e) {
        const msg = e && e.message ? e.message : String(e);
        console.error("SMTP test failed:", msg);
        return { ok: false, error: msg };
    }
}

function makeTransport(cfg, port) {
    const nodemailer = require("nodemailer");
    const p = Number(port || cfg.port || 587);
    return nodemailer.createTransport({
        host: cfg.host || "smtp.gmail.com",
        port: p,
        secure: p === 465,
        requireTLS: p === 587,
        auth: { user: cfg.user, pass: cfg.pass },
        connectionTimeout: 45000,
        greetingTimeout: 45000,
        socketTimeout: 60000,
        family: 4
    });
}

export async function sendResetMail(to, token, question) {
    printReset(to, token, question);
    sendResetMailLater(to, token, question);
    return { sent: false, printed: true, hint: "reset-token: " + token };
}

async function deliverResetMail(to, text) {
    const cfg = loadSmtp();
    if (!cfg) {
        logSmtpStatus();
        console.log("PASSWORD RESET mailed: no (SMTP not ready — use the token printed above)");
        return;
    }
    if (!to) {
        console.log("PASSWORD RESET mailed: no (account has no email)");
        return;
    }
    const gmail = /gmail\.com/i.test(cfg.host) || /gmail\.com/i.test(cfg.user);
    const ports = gmail ? [465, 587] : [cfg.port || 587];
    let lastErr;
    for (const port of ports) {
        try {
            console.log("SMTP sending", cfg.from, "->", to, "via", cfg.host + ":" + port);
            const transport = makeTransport(cfg, port);
            await transport.sendMail({
                from: cfg.from,
                to,
                subject: "Ashore Prospect password reset",
                text
            });
            console.log("PASSWORD RESET mailed", cfg.from, "->", to,
                cfg.from.toLowerCase() === String(to).toLowerCase()
                    ? "(same address — check Inbox and Spam)"
                    : "");
            return;
        } catch (e) {
            lastErr = e;
            console.error("SMTP port", port, "failed:", e.message || e);
        }
    }
    throw lastErr;
}

function loadSmtp() {
    applyEnvFile();
    const file = readSmtpFile();
    const host = (file.host || process.env.SMTP_HOST || "").trim();
    const user = (file.user || process.env.SMTP_USER || "").trim();
    const pass = stripPass(process.env.SMTP_PASS || file.pass || "");
    if (!host || !user || !pass) return null;
    return {
        host,
        port: Number(file.port || process.env.SMTP_PORT || 587),
        user,
        pass,
        from: (file.from || process.env.SMTP_FROM || user).trim()
    };
}

function stripPass(s) {
    return String(s || "").replace(/\s+/g, "").trim();
}

function schedulerCandidates(name) {
    return [
        join(REPO, ".scheduler", name),
        join(process.cwd(), ".scheduler", name),
        join(process.cwd(), "..", ".scheduler", name)
    ];
}

function foundFiles() {
    const txt = schedulerCandidates("smtp.txt").find((p) => existsSync(p));
    const env = schedulerCandidates("smtp.env").find((p) => existsSync(p));
    return { txt, env };
}

function applyEnvFile() {
    for (const p of schedulerCandidates("smtp.env")) {
        if (!existsSync(p)) continue;
        try {
            for (const line of readFileSync(p, "utf8").split(/\r?\n/)) {
                const s = line.trim();
                if (!s || s.startsWith("#")) continue;
                const i = s.indexOf("=");
                if (i < 1) continue;
                const k = s.slice(0, i).trim();
                const v = s.slice(i + 1).trim();
                if (k && !process.env[k]) process.env[k] = v;
            }
        } catch { /* ignore */ }
        return;
    }
}

function readSmtpFile() {
    for (const p of schedulerCandidates("smtp.txt")) {
        if (!existsSync(p)) continue;
        try {
            const out = {};
            for (const line of readFileSync(p, "utf8").split(/\r?\n/)) {
                const s = line.trim();
                if (!s || s.startsWith("#")) continue;
                const i = s.indexOf("=");
                if (i < 1) continue;
                out[s.slice(0, i).trim().toLowerCase()] = s.slice(i + 1).trim();
            }
            return out;
        } catch {
            return {};
        }
    }
    return {};
}
