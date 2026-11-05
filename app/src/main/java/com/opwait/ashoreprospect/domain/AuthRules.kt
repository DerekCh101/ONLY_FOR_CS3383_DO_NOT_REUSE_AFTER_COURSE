package com.opwait.ashoreprospect.domain

/** Client-side copy of Node /register and /reset rules. Do not invent extra fields. */
object AuthRules {
    const val PASSWORD_MIN = 8
    const val QUESTION_MIN = 8
    const val ANSWER_MIN = 4
    const val USERNAME_MAX = 40
    const val FORGOT_COOLDOWN_MS = 60_000L
    const val RESET_TOKEN_MINUTES = 8

    fun emailLooksReal(email: String): Boolean {
        val e = email.trim()
        return e.contains('@') && e.contains('.')
    }

    fun registerError(
        username: String,
        password: String,
        email: String,
        question: String,
        answer: String,
    ): String? {
        val user = username.trim()
        val q = question.trim()
        val a = answer.trim()
        if (user.isEmpty() || password.isEmpty() || email.trim().isEmpty() || q.isEmpty() || a.isEmpty()) {
            return "username, password, email, question, and answer are required"
        }
        if (user.length > USERNAME_MAX) return "username max $USERNAME_MAX characters"
        if (password.length < PASSWORD_MIN) return "password min $PASSWORD_MIN characters"
        if (!emailLooksReal(email)) return "a real email is required to recover the account"
        if (q.length < QUESTION_MIN) return "security question too short"
        if (a.length < ANSWER_MIN) return "security answer too short"
        return null
    }

    fun resetError(token: String, answer: String, newPassword: String): String? {
        if (token.trim().isEmpty()) return "reset token required"
        if (answer.trim().length < ANSWER_MIN) return "security answer too short"
        if (newPassword.length < PASSWORD_MIN) return "new password min $PASSWORD_MIN characters"
        return null
    }
}
