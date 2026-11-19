package org.ashoreprospect.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoginViewTest {

    @Test
    void registrationRequiresEveryFieldBeforeCheckingPasswordStrength() {
        String required = "Username, password, email, security question, and answer are all required.";
        assertEquals(required, LoginView.registrationError(null, "weak", "a@b.test", "question", "answer"));
        assertEquals(required, LoginView.registrationError("user", " ", "a@b.test", "question", "answer"));
        assertEquals(required, LoginView.registrationError("user", "weak", "", "question", "answer"));
        assertEquals(required, LoginView.registrationError("user", "weak", "a@b.test", "\t", "answer"));
        assertEquals(required, LoginView.registrationError("user", "weak", "a@b.test", "question", null));
    }

    @Test
    void registrationRejectsWeakPasswordsAndAcceptsCompleteValidInput() {
        assertEquals(PasswordRules.HINT,
                LoginView.registrationError("user", "weak", "a@b.test", "question", "answer"));
        assertNull(LoginView.registrationError("user", "Strong1!", "a@b.test", "question", "answer"));
    }
}
