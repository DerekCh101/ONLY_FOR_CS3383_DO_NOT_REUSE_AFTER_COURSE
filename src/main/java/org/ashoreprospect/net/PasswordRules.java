/** Client-side password rules. Same text as Node {@code passwordRules.js}. */
package org.ashoreprospect.net;

public final class PasswordRules {

    public static final String HINT =
            "Password needs 8+ characters, upper and lower case, a number, and a symbol.";

    private PasswordRules() {}

    public static String reject(String raw) {
        if (raw == null || raw.length() < 8) return HINT;
        boolean lower = false, upper = false, digit = false, symbol = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 'a' && c <= 'z') lower = true;
            else if (c >= 'A' && c <= 'Z') upper = true;
            else if (c >= '0' && c <= '9') digit = true;
            else symbol = true;
        }
        if (!lower || !upper || !digit || !symbol) return HINT;
        return null;
    }
}
