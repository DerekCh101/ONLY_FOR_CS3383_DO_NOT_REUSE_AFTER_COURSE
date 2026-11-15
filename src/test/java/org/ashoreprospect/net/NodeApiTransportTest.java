/**
 * {@code NodeApi}'s transport: {@code request}, {@code postAuth}, the thin verb wrappers, and the
 * session bookkeeping around them.
 *
 * <p>No live Node is needed. {@link FakeNode} is an {@code HttpServer} on {@code 127.0.0.1:0}, so
 * the port is ephemeral and no packet can leave the machine. It records the verb, path, headers,
 * and body of every request, which is exactly what a transport test has to assert.
 *
 * <p>Two calls are deliberately <em>not</em> exercised: nothing here waits on the 12-second
 * {@code forgotPassword} read timeout, and neither {@code register} nor {@code authenticate(true)}
 * is called with a live server, since both refuse before opening a socket (covered by
 * {@link NodeApiTest}).
 *
 * <p>{@code .scheduler/session.txt} and {@code login.txt} are user data and are snapshotted and
 * restored around every test.
 */
package org.ashoreprospect.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("os-env")
class NodeApiTransportTest {

    private FakeNode node;
    private NodeApi api;

    private Path sessionFile;
    private Path prefsFile;
    private byte[] savedSession;
    private byte[] savedPrefs;

    @BeforeEach
    void setUp() throws Exception {
        sessionFile = Endpoints.dir().resolve("session.txt");
        prefsFile = Endpoints.dir().resolve("login.txt");
        savedSession = Files.isRegularFile(sessionFile) ? Files.readAllBytes(sessionFile) : null;
        savedPrefs = Files.isRegularFile(prefsFile) ? Files.readAllBytes(prefsFile) : null;

        node = new FakeNode();
        node.pointNodeAt();
        api = signedIn();
    }

    /** A fresh {@code NodeApi} that already holds a session, without writing any file of its own. */
    private static NodeApi signedIn() {
        NodeApi a = new NodeApi();
        NodeApi.AuthResult r = new NodeApi.AuthResult();
        r.token = "test-token";
        r.userId = "test-user";
        r.username = "tester";
        a.applySession(r, true);
        return a;
    }

    @AfterEach
    void tearDown() throws Exception {
        node.close();
        restore(sessionFile, savedSession);
        restore(prefsFile, savedPrefs);
    }

    // ---------- request: the shared transport ----------

    @Test
    void request_sendsTheDeviceLabelAsTheUserAgentAndAcceptsJson() throws Exception {
        new NodeApi().isAccountDeleted("u1");

        FakeNode.Call call = node.only();
        assertEquals("GET", call.method);
        assertEquals("/account-status", call.path);
        assertEquals("userId=u1", call.query, "the id is URL-encoded into the query");
        assertEquals("application/json", call.header("Accept"));
        assertNull(call.header("Authorization"), "this call is not authenticated");

        // PINNED: HttpURLConnection's setRequestProperty takes a String and writes one byte per
        // char, so every non-ASCII char in deviceLabel() arrives as mojibake. The label uses a
        // middle dot, so a real request carries "JavaFX Â· Windows 11 Â· HOST". The same label in
        // a JSON body is written as UTF-8 and is fine, so only the header is affected.
        assertTrue(NodeApi.deviceLabel().contains("\u00b7"));
        assertEquals(mojibake(NodeApi.deviceLabel()), call.header("User-Agent"),
                "the User-Agent is the device label with each non-ASCII char widened to 0x80+cp");
    }

    @Test
    void request_refusesAnAuthenticatedCallWithNoToken() {
        NodeApi signedOut = new NodeApi();

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> signedOut.getTasks());

        assertEquals("login required", thrown.getMessage());
        assertEquals(0, node.callCount(), "the guard fires before any socket is opened");
    }

    @Test
    void request_attachesTheBearerToAnAuthenticatedCall() throws Exception {
        api.getTasks();

        assertEquals("Bearer test-token", node.only().header("Authorization"));
    }

    @Test
    void request_mapsANonTwoHundredToTheServerMessage() throws Exception {
        node.replyWith(500, "{\"message\":\"database is on fire\"}");

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> api.getMe());

        assertEquals("database is on fire", thrown.getMessage());
    }

    @Test
    void request_fallsBackToTheErrorFieldThenToAStatusCode() throws Exception {
        node.replyWith(400, "{\"error\":\"bad request shape\"}");
        assertEquals("bad request shape",
                assertThrows(IllegalStateException.class, () -> api.getMe()).getMessage());

        node.replyWith(503, "");
        assertEquals("Server error 503",
                assertThrows(IllegalStateException.class, () -> api.getMe()).getMessage());
    }

    @Test
    void request_turnsA401IntoASignInAgainMessage() throws Exception {
        node.replyWith(401, "{\"message\":\"login expired\"}");

        assertEquals("Your login expired. Sign in again, then retry.",
                assertThrows(IllegalStateException.class, () -> api.getMe()).getMessage());
    }

    @Test
    void request_turnsARapidUploadResponseIntoAWaitMessage() throws Exception {
        node.replyWith(429, "");

        assertEquals("Too many image uploads just now. Wait a minute, then retry.",
                assertThrows(IllegalStateException.class, () -> api.getMe()).getMessage());
    }

    @Test
    void request_callsAMissingEndpointAMissingEndpoint() throws Exception {
        node.replyWith(404, "");

        String message = assertThrows(IllegalStateException.class, () -> api.getMe()).getMessage();
        assertTrue(message.contains("/me"), message);
        assertTrue(message.contains("does not have"), message);
    }

    @Test
    void request_propagatesAConnectionRefused() throws Exception {
        node.close();

        // A dead Node is a network failure the caller handles, not a translated HTTP error.
        assertThrows(Exception.class, () -> api.getMe());
    }

    // ---------- the verb wrappers ----------

    @Test
    void getTasks_mergesTheTopLevelArrayWithTheNestedOne() throws Exception {
        node.replyWith(200, "{\"tasks\":[{\"id\":\"a\"}],\"nested\":[{\"id\":\"b\"}]}");

        List<Map<String, String>> tasks = api.getTasks();

        assertEquals(2, tasks.size());
        assertEquals("a", tasks.get(0).get("id"));
        assertEquals("b", tasks.get(1).get("id"));
        assertEquals("/tasks", node.only().path);
    }

    @Test
    void getBlocked_readsTheBlockedArray() throws Exception {
        node.replyWith(200, "{\"blocked\":[{\"id\":\"1\"},{\"id\":\"2\"}]}");

        List<Map<String, String>> blocked = api.getBlocked();

        assertEquals(2, blocked.size());
        assertEquals("/blocked", node.only().path);
    }

    @Test
    void postTask_sendsTheBodyVerbatimToTasks() throws Exception {
        String body = "{\"id\":\"t1\",\"duration\":1.0}";
        node.replyWith(200, "{\"ok\":true}");

        Map<String, String> reply = api.postTask(body);

        FakeNode.Call call = node.only();
        assertEquals("POST", call.method);
        assertEquals("/tasks", call.path);
        assertEquals(body, call.body, "the wrapper does not re-serialise the JSON");
        assertTrue(call.header("Content-Type").contains("application/json"), call.header("Content-Type"));
        assertEquals("true", reply.get("ok"));
    }

    @Test
    void postBlocked_sendsTheBodyVerbatimToBlocked() throws Exception {
        node.replyWith(200, "{\"ok\":true}");

        api.postBlocked("{\"id\":\"b1\"}");

        FakeNode.Call call = node.only();
        assertEquals("POST", call.method);
        assertEquals("/blocked", call.path);
        assertEquals("{\"id\":\"b1\"}", call.body);
    }

    @Test
    void deleteTask_and_deleteBlocked_putTheIdInThePath() throws Exception {
        api.deleteTask("t-1");
        FakeNode.Call first = node.only();
        assertEquals("DELETE /tasks/t-1", first.method + " " + first.path);
        assertTrue(first.body.isEmpty(), "a DELETE carries no body");

        api.deleteBlocked("b-1");
        FakeNode.Call second = node.calls().get(1);
        assertEquals("DELETE /blocked/b-1", second.method + " " + second.path);
    }

    @Test
    void anEmptyObjectReplyYieldsAnEmptyMap() throws Exception {
        node.replyWith(200, "{}");

        assertTrue(api.deleteTask("t-1").isEmpty());
    }

    // ---------- postAuth ----------

    @Test
    void postAuth_postsCredentialsAndTheDeviceLabel() throws Exception {
        node.replyWith(200, "{\"token\":\"tok\",\"userId\":\"u1\",\"username\":\"will\"}");

        NodeApi.AuthResult r = api.authenticate(false, "will", "pw");

        FakeNode.Call call = node.only();
        assertEquals("POST", call.method);
        assertEquals("/login", call.path);
        assertTrue(call.body.contains("\"username\":\"will\""), call.body);
        assertTrue(call.body.contains("\"password\":\"pw\""), call.body);
        assertTrue(call.body.contains("\"device\":"), call.body);
        assertNull(call.header("Authorization"), "logging in does not need a bearer");

        assertEquals("tok", r.token);
        assertEquals("u1", r.userId);
        assertEquals("will", r.username);
    }

    @Test
    void postAuth_escapesQuotesAndNewlinesInTheCredentials() throws Exception {
        node.replyWith(200, "{\"token\":\"t\",\"userId\":\"u\"}");

        api.authenticate(false, "we\"ird\\name", "line\nbreak");

        String body = node.only().body;
        assertTrue(body.contains("we\\\"ird\\\\name"), body);
        assertTrue(body.contains("line\\nbreak"), body);
    }

    @Test
    void postAuth_acceptsTheAlternativeKeySpellings() throws Exception {
        node.replyWith(200, "{\"accessToken\":\"tok2\",\"user_id\":\"u2\",\"name\":\"nick\"}");

        NodeApi.AuthResult r = api.authenticate(false, "sent-user", "pw");

        assertEquals("tok2", r.token, "accessToken is a documented alias for token");
        assertEquals("u2", r.userId, "user_id is a documented alias for userId");
        assertEquals("nick", r.username, "name beats the submitted username");
    }

    @Test
    void postAuth_defaultsTheUsernameToWhatWasSubmitted() throws Exception {
        node.replyWith(200, "{\"token\":\"t\",\"id\":\"u\"}");

        assertEquals("typed-name", api.authenticate(false, "typed-name", "pw").username);
    }

    @Test
    void postAuth_throwsWhenTheReplyHasNoUserId() throws Exception {
        node.replyWith(200, "{\"token\":\"t\"}");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> api.authenticate(false, "will", "pw"));

        assertEquals("login response had no user id", thrown.getMessage());
    }

    @Test
    void postAuth_letsABadLoginSurfaceAsTheServersMessage() throws Exception {
        node.replyWith(401, "{\"message\":\"no such user\"}");

        assertEquals("no such user", assertThrows(IllegalStateException.class,
                () -> api.authenticate(false, "nobody", "pw")).getMessage());
    }

    // ---------- session bookkeeping ----------

    @Test
    void applySession_remembersTheUserAndReplaysTheBearerOnLaterCalls() throws Exception {
        NodeApi fresh = new NodeApi();
        node.replyWith(200, "{\"token\":\"tok\",\"userId\":\"u1\",\"username\":\"will\"}");
        fresh.applySession(fresh.authenticate(false, "will", "pw"));

        node.resetRecording();
        node.replyWith(200, "{\"tasks\":[]}");
        fresh.getTasks();

        assertEquals("tok", fresh.getToken());
        assertEquals("u1", fresh.getUserId());
        assertEquals("will", fresh.getUsername());
        assertEquals("Bearer tok", node.only().header("Authorization"));
        assertTrue(LocalSession.wantRemember(), "remember-me stays on");
        assertEquals("will", LocalSession.lastUsername());
        assertNotNull(LocalSession.load(), "the token is written to session.txt");
    }

    @Test
    void applySession_withoutRememberDropsTheStoredToken() throws Exception {
        LocalSession.save("old", "old-user", "old-token");
        NodeApi.AuthResult r = new NodeApi.AuthResult();
        r.token = "fresh";
        r.userId = "u2";
        r.username = "new";

        api.applySession(r, false);

        assertEquals("fresh", api.getToken(), "the in-memory session is still live");
        assertNull(LocalSession.load(), "but nothing is left on disk to restore");
        assertEquals("new", LocalSession.lastUsername(), "the name is still remembered");
    }

    @Test
    void logout_forgetsTheStoredTokenAndTheRamSession() throws Exception {
        node.replyWith(200, "{\"token\":\"tok\",\"userId\":\"u1\",\"username\":\"will\"}");
        api.applySession(api.authenticate(false, "will", "pw"));

        api.logout();

        assertNull(api.getToken());
        assertNull(api.getUserId());
        assertNull(api.getUsername());
        assertNull(LocalSession.load(), "a full logout forgets this PC");
        assertThrows(IllegalStateException.class, () -> api.getTasks(),
                "and the next authenticated call is refused");
    }

    @Test
    void logoutKeepRemembered_clearsRamButLeavesTheFileForTheNextLaunch() throws Exception {
        node.replyWith(200, "{\"token\":\"tok\",\"userId\":\"u1\",\"username\":\"will\"}");
        api.applySession(api.authenticate(false, "will", "pw"));

        api.logoutKeepRemembered();

        assertNull(api.getToken());
        assertNotNull(LocalSession.load(), "an idle logout keeps remember-me");
        LocalSession.save("u1", "will", "tok");
        assertTrue(new NodeApi().restoreSession(), "so a fresh NodeApi can pick it back up");
    }

    @Test
    void restoreSession_isFalseWhenNothingIsRemembered() throws Exception {
        LocalSession.clear();
        LocalSession.savePrefs(false, "will");

        assertFalse(new NodeApi().restoreSession());
    }

    private static void restore(Path file, byte[] content) throws Exception {
        if (content == null) {
            Files.deleteIfExists(file);
        } else {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        }
    }

    /**
     * How {@code HttpURLConnection} mangles a header value: the string's UTF-8 bytes are read back
     * as one ISO-8859-1 char each, so every non-ASCII char shows up widened (U+00B7 becomes the
     * pair U+00C2 U+00B7).
     */
    private static String mojibake(String header) {
        return new String(header.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1);
    }
}
