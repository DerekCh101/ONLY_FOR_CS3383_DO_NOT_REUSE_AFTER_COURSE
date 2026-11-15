/**
 * Pure statics of {@link NodeApi}: the error-message translation a user actually reads,
 * the {@code AuthResult} value holder, and the two guard paths that return before any
 * connection is attempted.
 *
 * <p>No server, no Docker, no socket. The transport half ({@code request}, {@code postAuth}
 * and everything they reach) is deliberately left untested — it requires a live Node, so
 * {@code NodeApi} stays out of the JaCoCo gate.
 */
package org.ashoreprospect.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeApiTest {

    private static final String PW = "/change-password";
    private static final String NOT_ON_NODE =
            "Change password is not on this Node yet. Restart Node on the host PC "
                    + "(the process listening on port 3456), then try again.";
    private static final String EXPIRED = "Your login expired. Sign in again, then retry.";
    private static final String TOO_MANY_IMAGES =
            "Too many image uploads just now. Wait a minute, then retry.";

    private static String notFoundOn(String path) {
        return "The server does not have " + path
                + ". Restart Node on the host PC after updating, then try again.";
    }

    // ---------------------------------------------------------------- friendlyError

    @Test
    void passwordPathWith404IsTheNotOnThisNodeMessage() {
        assertEquals(NOT_ON_NODE, NodeApi.friendlyError(PW, 404, "Cannot POST /change-password"));
    }

    @Test
    void passwordPathWithAnyCodeAndNotFoundMessageTakesThe404Branch() {
        // The message substring wins over the code: 200 is not an error code at all.
        assertEquals(NOT_ON_NODE, NodeApi.friendlyError(PW, 200, "not found"));
        assertEquals(NOT_ON_NODE, NodeApi.friendlyError(PW, 500, "no route"));
    }

    @Test
    void passwordPathWithWrongCurrentPasswordIsExplicit() {
        assertEquals("Current password is wrong.",
                NodeApi.friendlyError(PW, 400, "current password is wrong"));
        assertEquals("Current password is wrong.",
                NodeApi.friendlyError(PW, 401, "wrong"));
    }

    @Test
    void passwordPolicyMessagesMapToTheSharedHint() {
        assertEquals(PasswordRules.HINT, NodeApi.friendlyError(PW, 400, "min 8"));
        assertEquals(PasswordRules.HINT, NodeApi.friendlyError(PW, 400, "too short"));
        assertEquals(PasswordRules.HINT,
                NodeApi.friendlyError(PW, 400, "needs an upper case letter"));
        assertEquals(PasswordRules.HINT, NodeApi.friendlyError(PW, 400, "add a symbol"));
        assertEquals(PasswordRules.HINT, NodeApi.friendlyError(PW, 400, "need 8+ chars"));
    }

    @Test
    void meDeleteWith401IsAPasswordFailureNotASessionFailure() {
        assertEquals("Password is wrong.", NodeApi.friendlyError("/me/delete", 401, null));
        // The same 401 on another path stays a session failure.
        assertEquals(EXPIRED, NodeApi.friendlyError("/tasks", 401, null));
    }

    @Test
    void plain401MapsToExpiredOnlyForBlankOrLoginMessages() {
        assertEquals(EXPIRED, NodeApi.friendlyError("/tasks", 401, ""));
        assertEquals(EXPIRED, NodeApi.friendlyError("/tasks", 401, "login required"));
        assertEquals(EXPIRED, NodeApi.friendlyError("/tasks", 401, "Please LOGIN again"));
        // An unrelated message is passed through verbatim, not translated.
        assertEquals("quota exceeded", NodeApi.friendlyError("/tasks", 401, "quota exceeded"));
    }

    @Test
    void throttleCodeAndMessageMapToTheUploadNotice() {
        assertEquals(TOO_MANY_IMAGES, NodeApi.friendlyError("/typewriter/images", 429, null));
        assertEquals(TOO_MANY_IMAGES,
                NodeApi.friendlyError("/typewriter/images", 500, "Too many image uploads"));
    }

    @Test
    void unknown404NamesThePathAndSuggestsARestart() {
        assertEquals(notFoundOn("/does-not-exist"),
                NodeApi.friendlyError("/does-not-exist", 404, null));
        // "not found" as the exact message also lands here, whatever the code.
        assertEquals(notFoundOn("/tagged"), NodeApi.friendlyError("/tagged", 410, "Not Found"));
    }

    @Test
    void lastResortUsesTheCodeWhenTheMessageIsBlank() {
        assertEquals("Server error 500", NodeApi.friendlyError("/tasks", 500, null));
        assertEquals("Server error 500", NodeApi.friendlyError("/tasks", 500, "   "));
        // A non-blank message is returned trimmed.
        assertEquals("gone wrong", NodeApi.friendlyError("/tasks", 500, "  gone wrong  "));
    }

    @Test
    void nullPathNeverThrows() {
        assertEquals(EXPIRED, NodeApi.friendlyError(null, 401, null));
        assertEquals(TOO_MANY_IMAGES, NodeApi.friendlyError(null, 429, null));
        assertEquals(notFoundOn(null), NodeApi.friendlyError(null, 404, null));
        assertEquals("boom", NodeApi.friendlyError(null, 500, "boom"));
        // A null path is never mistaken for a password or delete path.
        assertEquals("Server error 400", NodeApi.friendlyError(null, 400, null));
    }

    // ---------------------------------------------------------------- guard paths

    @Test
    void authCallWithoutATokenFailsBeforeAnyConnection() {
        NodeApi api = new NodeApi();
        IllegalStateException e = assertThrows(IllegalStateException.class, api::getTasks);
        assertEquals("login required", e.getMessage());
        assertThrows(IllegalStateException.class, api::getBlocked);
        assertThrows(IllegalStateException.class, api::getMe);
    }

    @Test
    void blankAccountIdIsNeverLookedUp() {
        NodeApi api = new NodeApi();
        assertFalse(api.isAccountDeleted(null));
        assertFalse(api.isAccountDeleted(""));
        assertFalse(api.isAccountDeleted("   "));
    }

    // ---------------------------------------------------------------- value types

    @Test
    void authResultIsAPlainThreeFieldHolder() {
        NodeApi.AuthResult r = new NodeApi.AuthResult();
        assertNull(r.token);
        assertNull(r.userId);
        assertNull(r.username);

        r.token = "t0k";
        r.userId = "u-1";
        r.username = "will";
        assertEquals("t0k", r.token);
        assertEquals("u-1", r.userId);
        assertEquals("will", r.username);
    }

    @Test
    void deviceLabelIsOsAndHostWithFixedSeparator() {
        String label = NodeApi.deviceLabel();
        assertNotNull(label);
        assertFalse(label.isBlank());
        // Asserted loosely: os.name and the hostname differ per runner.
        assertTrue(label.startsWith("JavaFX \u00B7 "), label);
        assertTrue(label.matches("^JavaFX \u00B7 .+ \u00B7 .+$"), label);
    }
}
