/** Client password rule: 8+ chars with lower, upper, digit, and symbol. */
package org.ashoreprospect.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PasswordRulesTest {

    @Test
    void acceptsStrongPassword() {
        assertNull(PasswordRules.reject("Abcdef1!"));
        assertNull(PasswordRules.reject("Str0ng#Pass"));
    }

    @Test
    void rejectsTooShort() {
        assertEquals(PasswordRules.HINT, PasswordRules.reject("Ab1!"));
        assertEquals(PasswordRules.HINT, PasswordRules.reject("Abcde1!"));
    }

    @Test
    void rejectsMissingLowercase() {
        assertEquals(PasswordRules.HINT, PasswordRules.reject("ABCDEF1!"));
    }

    @Test
    void rejectsMissingUppercase() {
        assertEquals(PasswordRules.HINT, PasswordRules.reject("abcdef1!"));
    }

    @Test
    void rejectsMissingDigit() {
        assertEquals(PasswordRules.HINT, PasswordRules.reject("Abcdefg!"));
    }

    @Test
    void rejectsMissingSymbol() {
        assertEquals(PasswordRules.HINT, PasswordRules.reject("Abcdef12"));
    }

    @Test
    void rejectsNull() {
        assertEquals(PasswordRules.HINT, PasswordRules.reject(null));
    }

    @Test
    void hintTextIsStable() {
        assertEquals("Password needs 8+ characters, upper and lower case, a number, and a symbol.",
                PasswordRules.HINT);
    }

    // ---------- plan M: remaining boundaries ----------

    @Test
    void exactlyEightCharactersIsTheCutoff() {
        assertNull(PasswordRules.reject("Abcde1!x"));
        assertEquals(PasswordRules.HINT, PasswordRules.reject("Abcde1!"));
    }

    @Test
    void unicodeAndEmojiCountAsASymbol() {
        assertNull(PasswordRules.reject("Abcdef1\u00e9"));
        assertNull(PasswordRules.reject("Abcdef1\uD83D\uDE00"));
    }

    @Test
    void spaceAndTabAlsoCountAsASymbol() {
        // PINNED: the rule is "anything that is not a-z, A-Z or 0-9 is a symbol",
        // so whitespace satisfies the symbol requirement.
        assertNull(PasswordRules.reject("Abcdef1 "));
        assertNull(PasswordRules.reject("Abcdef1\t"));
    }
}
