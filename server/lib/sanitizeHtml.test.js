/** Script stripping, editor-shell unwrapping, and JSON content sanitising. */
import { test } from "node:test";
import assert from "node:assert/strict";
import {
    looksLikeEditorDump,
    unwrapEditorInner,
    editorHtmlToPlain,
    sanitizeHtml,
    sanitizeTypewriterContent
} from "./sanitizeHtml.js";

// ---------- looksLikeEditorDump ----------

test("looksLikeEditorDump spots editor shells", () => {
    assert.equal(looksLikeEditorDump('<div contenteditable="true">x</div>'), true);
    assert.equal(looksLikeEditorDump('<div id="ashore-ln">1</div>'), true);
    assert.equal(looksLikeEditorDump("<html><body>x</body></html>"), true);
    assert.equal(looksLikeEditorDump("u003chtmlu003e"), true);
    assert.equal(looksLikeEditorDump("\\u003cbody\\u003e"), true);
});

test("looksLikeEditorDump rejects plain text", () => {
    assert.equal(looksLikeEditorDump("just a line"), false);
    assert.equal(looksLikeEditorDump(""), false);
    assert.equal(looksLikeEditorDump(null), false);
});

// ---------- unwrapEditorInner ----------

test("unwrapEditorInner extracts body content", () => {
    const dump = "<html><head><title>t</title></head><body><p>Hello</p></body></html>";
    assert.equal(unwrapEditorInner(dump), "<p>Hello</p>");
});

test("unwrapEditorInner decodes HTML entities", () => {
    assert.equal(unwrapEditorInner("&lt;p&gt;Hi&lt;/p&gt;"), "<p>Hi</p>");
});

test("unwrapEditorInner decodes \\u escapes", () => {
    assert.equal(unwrapEditorInner("\\u003cp\\u003eHi\\u003c/p\\u003e"), "<p>Hi</p>");
});

test("unwrapEditorInner strips the line-number gutter", () => {
    const dump = '<div id="ashore-ln"><span>1</span></div><p>Body</p>';
    assert.equal(unwrapEditorInner(dump), "<p>Body</p>");
});

test("unwrapEditorInner strips the theme style block", () => {
    const dump = '<style id="ashore-tw-theme">p{color:red}</style><p>Body</p>';
    assert.equal(unwrapEditorInner(dump), "<p>Body</p>");
});

test("unwrapEditorInner returns empty for blank input", () => {
    assert.equal(unwrapEditorInner(""), "");
    assert.equal(unwrapEditorInner(null), "");
});

// ---------- editorHtmlToPlain ----------

test("editorHtmlToPlain flattens markup into text", () => {
    assert.equal(editorHtmlToPlain("<html><body><p>one</p><p>two</p></body></html>"), "one\ntwo");
    assert.equal(editorHtmlToPlain("<p>a &amp; b</p>"), "a & b");
    assert.equal(editorHtmlToPlain("<p>x&nbsp;y</p>"), "x y");
});

test("editorHtmlToPlain drops script and style bodies", () => {
    assert.equal(editorHtmlToPlain("<p>ok</p><script>alert(1)</script>"), "ok");
});

// ---------- sanitizeHtml ----------

test("sanitizeHtml removes dangerous tags but keeps text", () => {
    const out = sanitizeHtml("<p>safe</p><script>alert(1)</script><iframe src='x'></iframe>");
    assert.ok(!out.includes("<script"));
    assert.ok(!out.includes("<iframe"));
    assert.ok(out.includes("safe"));
});

test("sanitizeHtml removes inline event handlers", () => {
    const out = sanitizeHtml('<p onclick="evil()" onmouseover="x()">text</p>');
    assert.equal(out.toLowerCase().includes("onclick"), false);
    assert.equal(out.toLowerCase().includes("onmouseover"), false);
    assert.ok(out.includes("text"));
});

test("sanitizeHtml removes javascript: URLs", () => {
    const out = sanitizeHtml('<a href="javascript:evil()">link</a>');
    assert.equal(out.toLowerCase().includes("javascript:"), false);
    assert.ok(out.includes("link"));
});

test("sanitizeHtml strips CSS expression and javascript url()", () => {
    const a = sanitizeHtml('<p style="width:expression(alert(1))">x</p>');
    const b = sanitizeHtml('<p style="background:url(javascript:evil())">x</p>');
    assert.equal(a.toLowerCase().includes("expression("), false);
    assert.equal(b.toLowerCase().includes("javascript:"), false);
});

test("sanitizeHtml keeps safe formatting", () => {
    const out = sanitizeHtml('<p style="text-align:center"><b>bold</b> and <i>italic</i></p>');
    assert.ok(out.includes("<b>"));
    assert.ok(out.includes("<i>"));
    assert.ok(out.includes("text-align:center"));
});

test("sanitizeHtml unwraps editor dumps before cleaning", () => {
    const out = sanitizeHtml('<html><body><p contenteditable="true">Hi</p></body></html>');
    assert.equal(out.includes("<html"), false);
    assert.equal(out.includes("<body"), false);
    assert.ok(out.includes("Hi"));
});

test("sanitizeHtml returns empty for null", () => {
    assert.equal(sanitizeHtml(null), "");
    assert.equal(sanitizeHtml(undefined), "");
});

// ---------- sanitizeTypewriterContent ----------

test("sanitizeTypewriterContent passes sealed content through untouched", () => {
    const sealed = "AP1.payload-with-<script>";
    assert.equal(sanitizeTypewriterContent(sealed), sealed);
});

test("sanitizeTypewriterContent cleans JSON node bodies", () => {
    const input = JSON.stringify({ id: 1, body: "<p>ok</p><script>x</script>" });
    const out = JSON.parse(sanitizeTypewriterContent(input));
    assert.equal(out.body.includes("<script"), false);
    assert.ok(out.body.includes("ok"));
});

test("sanitizeTypewriterContent keeps sealed node bodies", () => {
    const sealed = "AP1.sealed-body";
    const input = JSON.stringify({ id: 1, body: sealed });
    assert.equal(JSON.parse(sanitizeTypewriterContent(input)).body, sealed);
});

test("sanitizeTypewriterContent clips oversized text and body", () => {
    const input = JSON.stringify({ id: 1, body: "a".repeat(120000), text: "b".repeat(9000) });
    const out = JSON.parse(sanitizeTypewriterContent(input));
    assert.equal(out.body.length, 100000);
    assert.equal(out.text.length, 8000);
});

test("sanitizeTypewriterContent caps images at 20", () => {
    const images = Array.from({ length: 25 }, (_, i) => ({ id: i }));
    const out = JSON.parse(sanitizeTypewriterContent(JSON.stringify({ id: 1, images })));
    assert.equal(out.images.length, 20);
});

test("sanitizeTypewriterContent recurses into nested arrays", () => {
    const input = JSON.stringify({
        id: 1,
        blocks: [{ id: 2, body: "<b>x</b><script>bad</script>" }]
    });
    const out = JSON.parse(sanitizeTypewriterContent(input));
    assert.equal(out.blocks[0].body.includes("<script"), false);
    assert.ok(out.blocks[0].body.includes("<b>x</b>"));
});

test("sanitizeTypewriterContent falls back to sanitizeHtml for non-JSON", () => {
    const out = sanitizeTypewriterContent("<p>hi</p><script>bad</script>");
    assert.equal(out.includes("<script"), false);
    assert.ok(out.includes("hi"));
});

test("sanitizeTypewriterContent returns empty for null", () => {
    assert.equal(sanitizeTypewriterContent(null), "");
});

test("sanitizeTypewriterContent restores previous body when a dump cleans to nothing", () => {
    const previous = JSON.stringify({ id: 1, body: "<p>old body</p>" });
    const incoming = JSON.stringify({ id: 1, body: '<html><body><div id="ashore-ln"></div></body></html>' });
    const out = JSON.parse(sanitizeTypewriterContent(incoming, previous));
    assert.ok(out.body.includes("old body"));
});
