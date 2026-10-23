/** Same password rules on Node and the web/JavaFX clients. */

export const PASSWORD_HINT =
    "Password needs 8+ characters, upper and lower case, a number, and a symbol.";

export function passwordReject(raw) {
    const s = String(raw ?? "");
    if (s.length < 8) return PASSWORD_HINT;
    if (!/[a-z]/.test(s)) return PASSWORD_HINT;
    if (!/[A-Z]/.test(s)) return PASSWORD_HINT;
    if (!/[0-9]/.test(s)) return PASSWORD_HINT;
    if (!/[^A-Za-z0-9]/.test(s)) return PASSWORD_HINT;
    return null;
}
