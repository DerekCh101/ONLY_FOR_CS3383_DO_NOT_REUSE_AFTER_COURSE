package com.opwait.ashoreprospect

import com.opwait.ashoreprospect.ui.shouldReloadEditor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlEditorReloadTest {
    @Test
    fun identicalHtmlDoesNotReload() {
        val html = "<p>hello</p>"
        assertFalse(shouldReloadEditor(html, html, 0L))
    }

    @Test
    fun normalizeEchoSamePlainTextDoesNotReload() {
        val fromWebView = "<p>hello world</p>"
        val afterNormalize = "<p>hello world</p>\n"
        assertFalse(shouldReloadEditor(afterNormalize, fromWebView, 0L))
    }

    @Test
    fun tagOnlyDifferenceSameWordsDoesNotReload() {
        val a = "<p>line one</p><p>line two</p>"
        val b = "<div>line one</div><div>line two</div>"
        assertFalse(shouldReloadEditor(b, a, System.currentTimeMillis()))
    }

    @Test
    fun recentTypingBlocksBehindParentHtml() {
        val typed = "<p>abcdefghij</p>"
        val older = "<p>abcde</p>"
        assertFalse(shouldReloadEditor(older, typed, System.currentTimeMillis()))
        // Still blocked a few seconds after the last key (local-first window).
        assertFalse(shouldReloadEditor(older, typed, System.currentTimeMillis() - 4_000L))
    }

    @Test
    fun realExternalChangeAfterIdleDoesReload() {
        val local = "<p>local draft</p>"
        val remote = "<p>completely different host text</p>"
        assertTrue(shouldReloadEditor(remote, local, 0L))
    }

    @Test
    fun idlePastTypingWindowAllowsExternalReload() {
        val local = "<p>local draft</p>"
        val remote = "<p>host replaced this page</p>"
        assertTrue(shouldReloadEditor(remote, local, System.currentTimeMillis() - 6_000L))
    }
}
