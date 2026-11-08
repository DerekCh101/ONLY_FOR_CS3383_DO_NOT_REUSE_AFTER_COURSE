/** Mirror of Java PasswordRulesTest — the two implementations must agree. */
import { test } from "node:test";
import assert from "node:assert/strict";
import { PASSWORD_HINT, passwordReject } from "./passwordRules.js";

test("accepts strong passwords", () => {
    assert.equal(passwordReject("Abcdef1!"), null);
    assert.equal(passwordReject("Str0ng#Pass"), null);
});

test("rejects too short", () => {
    assert.equal(passwordReject("Ab1!"), PASSWORD_HINT);
    assert.equal(passwordReject("Abcde1!"), PASSWORD_HINT);
});

test("rejects missing lowercase", () => {
    assert.equal(passwordReject("ABCDEF1!"), PASSWORD_HINT);
});

test("rejects missing uppercase", () => {
    assert.equal(passwordReject("abcdef1!"), PASSWORD_HINT);
});

test("rejects missing digit", () => {
    assert.equal(passwordReject("Abcdefg!"), PASSWORD_HINT);
});

test("rejects missing symbol", () => {
    assert.equal(passwordReject("Abcdef12"), PASSWORD_HINT);
});

test("rejects null and undefined", () => {
    assert.equal(passwordReject(null), PASSWORD_HINT);
    assert.equal(passwordReject(undefined), PASSWORD_HINT);
});

test("hint text matches the Java constant", () => {
    assert.equal(
        PASSWORD_HINT,
        "Password needs 8+ characters, upper and lower case, a number, and a symbol."
    );
});
