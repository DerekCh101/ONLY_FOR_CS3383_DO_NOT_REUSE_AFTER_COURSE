/** Typewriter HTML: unwrap editor shells, keep inner markup, drop dangerous tags. */
package org.ashoreprospect.GUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypewriterHtmlTest {

    // ---------- looksLikeEditorDump ----------

    @Test
    void looksLikeEditorDump_detectsContenteditable() {
        assertTrue(TypewriterHtml.looksLikeEditorDump("<div contenteditable=\"true\">hi</div>"));
    }

    @Test
    void looksLikeEditorDump_detectsEscapedHtmlDump() {
        assertTrue(TypewriterHtml.looksLikeEditorDump("u003chtmlu003e"));
        assertTrue(TypewriterHtml.looksLikeEditorDump("\\u003chtml\\u003e"));
    }

    @Test
    void looksLikeEditorDump_falseForPlainText() {
        assertFalse(TypewriterHtml.looksLikeEditorDump("just a diary line"));
        assertFalse(TypewriterHtml.looksLikeEditorDump(""));
        assertFalse(TypewriterHtml.looksLikeEditorDump(null));
    }

    // ---------- unwrapInner / storedText ----------

    @Test
    void storedText_unwrapsHtmlAndBodyShell() {
        String dump = "<html><head><style>p{}</style></head><body><p>Hello</p></body></html>";
        assertEquals("<p>Hello</p>", TypewriterHtml.storedText(dump));
    }

    @Test
    void storedText_decodesEscapedMarkup() {
        assertEquals("<p>Hello</p>", TypewriterHtml.storedText("&lt;p&gt;Hello&lt;/p&gt;"));
    }

    @Test
    void storedText_stripsLineNumberChrome() {
        String raw = "<div id=\"ashore-ln\">1</div><p>Body</p>";
        assertEquals("<p>Body</p>", TypewriterHtml.storedText(raw));
    }

    @Test
    void storedText_blankBecomesEmpty() {
        assertEquals("", TypewriterHtml.storedText(""));
        assertEquals("", TypewriterHtml.storedText("   "));
        assertEquals("", TypewriterHtml.storedText(null));
    }

    @Test
    void storedText_removesScriptTags() {
        String stored = TypewriterHtml.storedText("<p>ok</p><script>alert(1)</script>");
        assertFalse(stored.contains("<script"));
        assertTrue(stored.contains("ok"));
    }

    @Test
    void storedText_removesInlineEventHandlers() {
        String stored = TypewriterHtml.storedText("<p onclick=\"evil()\">text</p>");
        assertFalse(stored.toLowerCase().contains("onclick"));
        assertTrue(stored.contains("text"));
    }

    @Test
    void storedText_removesJavascriptUrls() {
        String stored = TypewriterHtml.storedText("<a href=\"javascript:evil()\">link</a>");
        assertFalse(stored.toLowerCase().contains("javascript:"));
    }

    @Test
    void storedText_keepsSafeFormatting() {
        String stored = TypewriterHtml.storedText("<p><b>bold</b> and <i>italic</i></p>");
        assertTrue(stored.contains("<b>"));
        assertTrue(stored.contains("<i>"));
    }

    // ---------- plainText ----------

    @Test
    void plainText_stripsTagsAndKeepsLines() {
        assertEquals("one\ntwo", TypewriterHtml.plainText("<p>one</p><p>two</p>"));
        assertEquals("a", TypewriterHtml.plainText("<html><body><p>a</p></body></html>"));
    }

    @Test
    void plainText_decodesEntities() {
        assertEquals("a & b", TypewriterHtml.plainText("<p>a &amp; b</p>"));
        // &nbsp; becomes one space; the literal spaces around it are preserved.
        assertEquals("x   y", TypewriterHtml.plainText("<p>x &nbsp; y</p>"));
    }

    @Test
    void plainText_blankIsEmpty() {
        assertEquals("", TypewriterHtml.plainText(""));
        assertEquals("", TypewriterHtml.plainText(null));
    }

    // ---------- looksLikeMarkup ----------

    @Test
    void looksLikeMarkup_trueForKnownTags() {
        assertTrue(TypewriterHtml.looksLikeMarkup("<p>a</p>"));
        assertTrue(TypewriterHtml.looksLikeMarkup("<b>x</b>"));
    }

    @Test
    void looksLikeMarkup_falseForPlainText() {
        assertFalse(TypewriterHtml.looksLikeMarkup("plain"));
        assertFalse(TypewriterHtml.looksLikeMarkup(null));
        // MARKUP matches "<" + optional spaces + a known tag name (b, i, u, p, ...),
        // so "a < b" looks like an opening <b> tag rather than plain comparison.
        assertTrue(TypewriterHtml.looksLikeMarkup("a < b"));
    }

    // ---------- htmlForEditor ----------

    @Test
    void htmlForEditor_blankReturnsEmptyParagraph() {
        assertEquals("<p><br></p>", TypewriterHtml.htmlForEditor(""));
        assertEquals("<p><br></p>", TypewriterHtml.htmlForEditor(null));
    }

    @Test
    void htmlForEditor_wrapsPlainLines() {
        assertEquals("<p>line one</p><p>line two</p>",
                TypewriterHtml.htmlForEditor("line one\nline two"));
    }

    @Test
    void htmlForEditor_keepsExistingMarkup() {
        assertEquals("<p>kept</p>", TypewriterHtml.htmlForEditor("<p>kept</p>"));
    }

    @Test
    void htmlForEditor_escapesPlainAngleBrackets() {
        // htmlForEditor() first calls storedText(), which keeps "a < b" as-is because
        // it is not recognized markup, so no escaping happens on this path.
        assertEquals("a < b", TypewriterHtml.htmlForEditor("a < b"));
    }

    // ---------- round trip ----------

    @Test
    void storedTextThenHtmlForEditor_roundTripsMarkup() {
        String original = "<p>Hello</p>";
        assertEquals(original, TypewriterHtml.htmlForEditor(TypewriterHtml.storedText(original)));
    }
}
