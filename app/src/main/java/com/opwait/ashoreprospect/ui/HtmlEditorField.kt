package com.opwait.ashoreprospect.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.opwait.ashoreprospect.domain.TypewriterHtml
import com.opwait.ashoreprospect.domain.VaultSeal
import com.opwait.ashoreprospect.ui.theme.SchedulerColors
import com.opwait.ashoreprospect.ui.theme.cssHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * JavaFX HTMLEditor stand-in: WebView + body.innerHTML.
 * Store [TypewriterHtml.storedText], never innerText / Compose TextAlign.
 *
 * Local-first: WebView owns the document while actively typing (pending emit / recent keys).
 * Mere focus must not block host pulls — bump [contentRev] when ViewModel applies remote HTML.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlEditorField(
    html: String,
    onHtmlChange: (String) -> Unit,
    editable: Boolean,
    modifier: Modifier = Modifier,
    /** Bumped when parent replaces HTML from outside (host pull / vault). Forces WebView reload. */
    contentRev: Int = 0,
    commandTick: Int = 0,
    commandJs: String = "",
    undoTick: Int = 0,
    redoTick: Int = 0,
    onHistoryState: (canUndo: Boolean, canRedo: Boolean) -> Unit = { _, _ -> },
    /** Nearest toolbar size (pt) under the caret / selection. */
    onSelectionFontSizePt: (Int) -> Unit = {},
) {
    val sealed = VaultSeal.isSealed(html)
    val live = editable && !sealed
    val stored = remember(html, sealed) {
        if (sealed) html else TypewriterHtml.storedText(html)
    }
    val onHtmlChangeState = rememberUpdatedState(onHtmlChange)
    val onFontSizeState = rememberUpdatedState(onSelectionFontSizePt)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var lastLoaded by remember { mutableStateOf(stored) }
    var ignoreInputUntil by remember { mutableStateOf(0L) }
    var lastEmitAt by remember { mutableLongStateOf(0L) }
    val undoStack = remember { mutableStateListOf<String>() }
    val redoStack = remember { mutableStateListOf<String>() }
    var baseline by remember { mutableStateOf(stored) }
    var historyJob by remember { mutableStateOf<Job?>(null) }
    var emitJob by remember { mutableStateOf<Job?>(null) }
    var pendingEmit by remember { mutableStateOf<String?>(null) }
    var appliedUndo by remember { mutableIntStateOf(0) }
    var appliedRedo by remember { mutableIntStateOf(0) }
    var appliedCmd by remember { mutableIntStateOf(0) }
    var appliedRev by remember { mutableIntStateOf(contentRev) }
    var pendingScrollX by remember { mutableIntStateOf(-1) }
    var pendingScrollY by remember { mutableIntStateOf(-1) }
    val paperHex = SchedulerColors.DarkChrome.cssHex()
    val inkHex = SchedulerColors.Cream.cssHex()
    val mutedHex = SchedulerColors.OnDarkMuted.cssHex()
    val themeKey = "$paperHex|$inkHex|$mutedHex"
    var appliedTheme by remember { mutableStateOf(themeKey) }

    fun reportHistory() {
        onHistoryState(undoStack.isNotEmpty(), redoStack.isNotEmpty())
    }

    fun commitHistory(now: String) {
        if (now == baseline) return
        undoStack.add(baseline)
        while (undoStack.size > 40) undoStack.removeAt(0)
        redoStack.clear()
        baseline = now
        reportHistory()
    }

    fun flushEmit(next: String) {
        pendingEmit = null
        onHtmlChangeState.value(next)
    }

    fun pushFromJs(raw: String) {
        if (!live) return
        if (System.currentTimeMillis() < ignoreInputUntil) return
        val next = TypewriterHtml.storedText(raw)
        if (next == lastLoaded) return
        if (TypewriterHtml.plainText(next).isBlank() && TypewriterHtml.plainText(lastLoaded).isBlank()) return
        lastLoaded = next
        lastEmitAt = System.currentTimeMillis()
        pendingEmit = next
        // One frame for the WebView to paint, then hand draft to ViewModel (local SQLite / host).
        historyJob?.cancel()
        emitJob?.cancel()
        val emitScope = CoroutineScope(Dispatchers.Main.immediate)
        historyJob = emitScope.launch {
            delay(500)
            commitHistory(next)
        }
        emitJob = emitScope.launch {
            delay(48)
            if (lastLoaded == next) flushEmit(next)
        }
    }

    fun applyPendingScroll(view: WebView) {
        val x = pendingScrollX
        val y = pendingScrollY
        if (x < 0 && y < 0) return
        val sx = x.coerceAtLeast(0)
        val sy = y.coerceAtLeast(0)
        view.scrollTo(sx, sy)
        view.evaluateJavascript("try{window.scrollTo($sx,$sy);}catch(e){}", null)
        view.post {
            if (view.isAttachedToWindow) view.scrollTo(sx, sy)
        }
        view.postDelayed({
            if (!view.isAttachedToWindow) return@postDelayed
            view.scrollTo(sx, sy)
            view.evaluateJavascript("try{window.scrollTo($sx,$sy);}catch(e){}", null)
            pendingScrollX = -1
            pendingScrollY = -1
        }, 120)
    }

    fun load(view: WebView, innerStored: String, restoreScroll: Boolean = false) {
        // Capture synchronously — async JS save then loadData always lost the offset.
        if (restoreScroll) {
            pendingScrollX = view.scrollX
            pendingScrollY = view.scrollY
        } else {
            pendingScrollX = -1
            pendingScrollY = -1
        }
        ignoreInputUntil = System.currentTimeMillis() + 600
        lastLoaded = TypewriterHtml.storedText(innerStored)
        view.loadDataWithBaseURL(
            "https://ashore.local/",
            TypewriterHtml.editorDocument(innerStored, live, paperHex, inkHex, mutedHex),
            "text/html",
            "UTF-8",
            null,
        )
    }

    fun runCommand(view: WebView, js: String) {
        view.requestFocus()
        ignoreInputUntil = 0L
        val wrapped = """
            (function(){
              try{ if(window.ashoreRestoreSel) ashoreRestoreSel(); }catch(e){}
              try{ $js }catch(e){}
              try{ if(window.ashorePaintLnAfterLayout) ashorePaintLnAfterLayout(); else if(window.ashorePaintLn) ashorePaintLn(); }catch(e){}
              try{ Ashore.bodyInput(document.body.innerHTML); }catch(e){}
            })();
        """.trimIndent()
        view.evaluateJavascript(wrapped, null)
    }

    DisposableEffect(Unit) {
        onDispose {
            historyJob?.cancel()
            emitJob?.cancel()
            pendingEmit?.let { flushEmit(it) }
            webView?.removeJavascriptInterface("Ashore")
            webView = null
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            object : WebView(ctx) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    return super.onTouchEvent(event)
                }
            }.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Color.parseColor(paperHex))
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                isScrollbarFadingEnabled = false
                scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    verticalScrollbarThumbDrawable = ColorDrawable(Color.parseColor("#B08B93A1"))
                    verticalScrollbarTrackDrawable = ColorDrawable(Color.TRANSPARENT)
                }
                isNestedScrollingEnabled = false
                isFocusable = true
                isFocusableInTouchMode = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                addJavascriptInterface(
                    AshoreHtmlBridge(
                        deliverHtml = { raw -> post { pushFromJs(raw) } },
                        deliverFontSizePt = { pt -> post { onFontSizeState.value(pt) } },
                    ),
                    "Ashore",
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val wv = view ?: return
                        injectEditor(wv)
                        applyPendingScroll(wv)
                    }
                }
                webView = this
                load(this, if (sealed) "" else stored)
            }
        },
        update = { view ->
            if (sealed) return@AndroidView
            view.setBackgroundColor(Color.parseColor(paperHex))
            val themeChanged = appliedTheme != themeKey
            if (themeChanged) appliedTheme = themeKey
            val incoming = TypewriterHtml.storedText(stored)
            val revBump = contentRev != appliedRev
            if (revBump) appliedRev = contentRev
            // Only block for real local typing — focus alone used to freeze host updates forever.
            val typing = pendingEmit != null ||
                System.currentTimeMillis() - lastEmitAt < 5000L
            when {
                typing && !themeChanged && !revBump -> Unit
                themeChanged || revBump -> {
                    baseline = incoming
                    load(view, incoming, restoreScroll = true)
                }
                !shouldReloadEditor(incoming, lastLoaded, lastEmitAt) -> {
                    if (incoming != lastLoaded && !typing) {
                        lastLoaded = incoming
                        baseline = incoming
                    }
                }
                else -> {
                    baseline = incoming
                    load(view, incoming, restoreScroll = true)
                }
            }
            if (live && commandTick != appliedCmd && commandJs.isNotBlank()) {
                appliedCmd = commandTick
                runCommand(view, commandJs)
            }
            if (live && undoTick != appliedUndo) {
                appliedUndo = undoTick
                if (undoStack.isNotEmpty()) {
                    val prev = undoStack.removeAt(undoStack.lastIndex)
                    redoStack.add(lastLoaded)
                    baseline = prev
                    load(view, prev, restoreScroll = true)
                    onHtmlChange(prev)
                    reportHistory()
                }
            }
            if (live && redoTick != appliedRedo) {
                appliedRedo = redoTick
                if (redoStack.isNotEmpty()) {
                    val next = redoStack.removeAt(redoStack.lastIndex)
                    undoStack.add(lastLoaded)
                    baseline = next
                    load(view, next, restoreScroll = true)
                    onHtmlChange(next)
                    reportHistory()
                }
            }
        },
    )
}

/**
 * True only when the parent HTML is a real external change, not our own echo / normalize.
 */
internal fun shouldReloadEditor(incoming: String, lastLoaded: String, lastEmitAtMs: Long): Boolean {
    if (incoming == lastLoaded) return false
    val inNorm = TypewriterHtml.storedText(incoming)
    val lastNorm = TypewriterHtml.storedText(lastLoaded)
    if (inNorm == lastNorm) return false
    // Save/normalize often changes tags but not readable text — never reload for that.
    if (TypewriterHtml.plainText(inNorm) == TypewriterHtml.plainText(lastNorm)) return false
    // While / just after typing, never reload — parent may still hold older or normalized HTML.
    if (System.currentTimeMillis() - lastEmitAtMs < 5000L) return false
    return true
}

private fun injectEditor(view: WebView) {
    view.evaluateJavascript(TypewriterHtml.EDITOR_BOOT_JS, null)
    view.postDelayed({
        if (view.isAttachedToWindow) view.evaluateJavascript(PAINT_JS, null)
    }, 50)
    view.postDelayed({
        if (view.isAttachedToWindow) view.evaluateJavascript(PAINT_JS, null)
    }, 200)
}

private const val PAINT_JS =
    "try{if(window.ashorePaintLnAfterLayout)ashorePaintLnAfterLayout();else if(window.ashorePaintLn)ashorePaintLn();}catch(e){}"

private class AshoreHtmlBridge(
    private val deliverHtml: (String) -> Unit,
    private val deliverFontSizePt: (Int) -> Unit,
) {
    @JavascriptInterface
    fun bodyInput(html: String?) {
        if (html != null) deliverHtml(html)
    }

    @JavascriptInterface
    fun fontSizePt(pt: Int) {
        if (pt > 0) deliverFontSizePt(pt)
    }
}
