/** AP1. AES-GCM sealing, session-key wrapping, and wrong-key fallbacks. */
import { test } from "node:test";
import assert from "node:assert/strict";
import {
    PREFIX,
    sealed,
    derive,
    seal,
    open,
    wrapSessionKey,
    unwrapSessionKey
} from "./vaultCrypto.js";

const UID = "user-123";
const PASSWORD = "Abcdef1!";

test("sealed() only matches the AP1. prefix", () => {
    assert.equal(sealed("AP1.abc"), true);
    assert.equal(sealed("plain"), false);
    assert.equal(sealed("ap1.abc"), false);
    assert.equal(sealed(null), false);
    assert.equal(sealed(123), false);
});

test("seal() prefixes ciphertext and leaves plaintext alone without a key", () => {
    return derive(UID, PASSWORD).then((key) => {
        const cipher = seal(key, "hello");
        assert.ok(cipher.startsWith(PREFIX));
        assert.equal(seal(null, "hello"), "hello");
        assert.equal(seal(key, ""), "");
        assert.equal(seal(key, null), "");
    });
});

test("seal() is idempotent for already-sealed input", async () => {
    const key = await derive(UID, PASSWORD);
    const once = seal(key, "hello");
    assert.equal(seal(key, once), once);
});

test("seal()/open() round trip", async () => {
    const key = await derive(UID, PASSWORD);
    assert.equal(open(key, seal(key, "round trip")), "round trip");
});

test("seal() uses a fresh IV each time", async () => {
    const key = await derive(UID, PASSWORD);
    assert.notEqual(seal(key, "same"), seal(key, "same"));
});

test("open() returns ciphertext unchanged when no key is supplied", async () => {
    const key = await derive(UID, PASSWORD);
    const cipher = seal(key, "secret");
    assert.equal(open(null, cipher), cipher);
});

test("open() returns plaintext unchanged", async () => {
    const key = await derive(UID, PASSWORD);
    assert.equal(open(key, "not sealed"), "not sealed");
    assert.equal(open(key, null), "");
});

test("open() with the wrong key falls back to the ciphertext", async () => {
    const key = await derive(UID, PASSWORD);
    const other = await derive(UID, "Wrong123!");
    const cipher = seal(key, "secret");
    assert.equal(open(other, cipher), cipher);
});

test("open() rejects a truncated payload without throwing", async () => {
    const key = await derive(UID, PASSWORD);
    assert.equal(open(key, PREFIX + "abc"), PREFIX + "abc");
});

test("derive() depends on both user id and password", async () => {
    const a = await derive(UID, PASSWORD);
    const b = await derive("other-user", PASSWORD);
    const c = await derive(UID, "Other123!");
    assert.notDeepEqual(a, b);
    assert.notDeepEqual(a, c);
    assert.deepEqual(a, await derive(UID, PASSWORD));
});

test("session key wraps and unwraps with the same token", async () => {
    const key = await derive(UID, PASSWORD);
    const token = "session-token";
    const wrap = wrapSessionKey(token, key);
    assert.ok(wrap.startsWith("VW1."));
    const back = unwrapSessionKey(token, wrap);
    assert.deepEqual(Buffer.from(back), Buffer.from(key));
});

test("session key does not unwrap with a different token", async () => {
    const key = await derive(UID, PASSWORD);
    const wrap = wrapSessionKey("token-a", key);
    assert.equal(unwrapSessionKey("token-b", wrap), null);
    assert.equal(unwrapSessionKey("", wrap), null);
    assert.equal(unwrapSessionKey("token-a", "garbage"), null);
});

test("wrapSessionKey returns empty for missing inputs", () => {
    assert.equal(wrapSessionKey("", "x"), "");
    assert.equal(wrapSessionKey("token", null), "");
});
