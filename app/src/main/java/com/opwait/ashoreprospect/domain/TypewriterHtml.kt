package com.opwait.ashoreprospect.domain

/**
 * Mirror of JavaFX `org.ashoreprospect.GUI.TypewriterHtml`.
 * Persist inner HTML (alignment / bold / color). Never persist the HTMLEditor shell,
 * and never round-trip through Compose plain text.
 */
object TypewriterHtml {
    private val UNICODE = Regex("\\\\u([0-9a-fA-F]{4})")
    private val BARE_U00 = Regex("u00([0-9a-fA-F]{2})")
    private val MARKUP = Regex(
        "(?is).*<\\s*(p|div|br|b|i|u|s|strike|del|em|strong|span|font|h[1-6]|ul|ol|li|blockquote)\\b.*",
    )
    private val DROP_TAGS = Regex(
        "(?is)</?(?:script|iframe|object|embed|link|meta|base|form|input|textarea|button|svg|math|video|audio|source|applet|style|template)(?:\\s[^>]*)?>",
    )
    private val ON_ATTR = Regex("(?i)\\s+on[a-z]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)")
    private val JS_URL = Regex("(?i)\\s(?:href|src|xlink:href)\\s*=\\s*(['\"]?)\\s*javascript:[^'\"\\s>]*")

    fun looksLikeEditorDump(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val s = raw
        return s.contains("contenteditable") ||
            s.contains("ashore-ln") ||
            s.contains("ashore-tw-theme") ||
            (UNICODE.containsMatchIn(s) && (s.contains("u003chtml") || s.contains("u003cbody"))) ||
            (BARE_U00.containsMatchIn(s) && (s.contains("u003chtml") || s.contains("u003cbody"))) ||
            s.contains("<html", ignoreCase = true) ||
            s.contains("<body", ignoreCase = true)
    }

    fun looksLikeMarkup(s: String?): Boolean =
        s != null && s.indexOf('<') >= 0 && MARKUP.matches(s)

    fun looksLikeHtml(raw: String): Boolean = looksLikeEditorDump(raw) || looksLikeMarkup(raw)

    /** Persist this: inner writing, never the editor chrome. */
    fun storedText(raw: String?): String {
        val s = unwrapInner(raw)
        if (s.isBlank()) return ""
        return dropDanger(s).replace("\u200b", "").trim()
    }

    fun storedHtmlOrSealed(raw: String): String {
        if (VaultSeal.isSealed(raw)) return raw
        return storedText(raw)
    }

    /** Word / line counts. Do not persist this. */
    fun plainText(raw: String?): String = htmlToPlain(unwrapInner(raw)).trim()

    fun toReadable(raw: String): String = plainText(raw)

    fun htmlForEditor(stored: String?): String {
        val inner = storedText(stored)
        if (inner.isBlank()) return "<p><br></p>"
        if (looksLikeMarkup(inner)) return inner
        val lines = Regex("\\R").split(inner, limit = -1)
        return buildString {
            for (line in lines) {
                if (line.isEmpty()) append("<p><br></p>")
                else append("<p>").append(escape(line)).append("</p>")
            }
        }
    }

    fun unwrapInner(raw: String?): String {
        var s = decodeEscapes(raw)
        if (s.isBlank()) return ""
        s = stripEditorChrome(s)
        for (i in 0 until 8) {
            val inner = bodyInnerOnce(s)
            if (inner == s) break
            s = inner
        }
        return stripEditorChrome(s).trim()
    }

    fun stripEditorChrome(html: String?): String {
        if (html == null) return ""
        return html
            .replace(Regex("(?is)<script[\\s\\S]*?</script>"), "")
            .replace(Regex("(?is)<div[^>]*\\bid\\s*=\\s*(['\"]?)ashore-ln\\1[^>]*>[\\s\\S]*?</div>"), "")
            .replace(Regex("(?is)<style[^>]*\\bid\\s*=\\s*(['\"]?)ashore-tw-theme\\1[^>]*>[\\s\\S]*?</style>"), "")
    }

    fun htmlToPlain(html: String?): String {
        if (html.isNullOrBlank()) return ""
        var s = stripEditorChrome(html)
            .replace(Regex("(?i)<script[\\s\\S]*?</script>"), "")
            .replace(Regex("(?i)<style[\\s\\S]*?</style>"), "")
            .replace(Regex("(?i)<head[\\s\\S]*?</head>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("(?i)</div>"), "\n")
            .replace(Regex("(?i)</li>"), "\n")
            .replace(Regex("(?i)</h[1-6]>"), "\n")
            .replace(Regex("(?i)</tr>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("\u200b", "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("&[a-zA-Z]+;"), " ")
        return s.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").replace(Regex("\\n+$"), "")
    }

    fun editorDocument(
        stored: String?,
        editable: Boolean,
        paper: String = "#1C2430",
        ink: String = "#F3EEE4",
        muted: String = "#8B93A1",
    ): String {
        val inner = htmlForEditor(stored)
        val edit = if (editable) "true" else "false"
        val bg = paper.ifBlank { "#1C2430" }
        val fg = ink.ifBlank { "#F3EEE4" }
        val gutter = muted.ifBlank { "#8B93A1" }
        return buildString {
            append("<!DOCTYPE html><html><head>")
            append("<meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<style>")
            append("html,body{background:")
            append(bg)
            append(" !important;color:")
            append(fg)
            append(" !important;}")
            append("html{overflow-y:auto;}")
            append("body{font-size:15px;line-height:1.45;margin:0;padding:8px 12px 24px 44px;")
            append("position:relative;font-family:sans-serif;min-height:100%;}")
            append("p,div,h1,h2,h3,h4,h5,h6,li,blockquote,pre,font,span{margin:0 !important;padding:0 !important;")
            append("line-height:normal !important;overflow:visible !important;}")
            append("p,div,h1,h2,h3,h4,h5,h6,li,blockquote,pre{min-height:1.45em;}")
            append("body{-webkit-text-decoration-skip:none;text-decoration-skip-ink:none;}")
            append("u,ins{text-decoration-skip-ink:none;text-underline-position:under;")
            append("text-underline-offset:0.18em;text-decoration-thickness:0.08em;padding-bottom:0.22em !important;}")
            append("s,strike,del{text-decoration-skip-ink:none;text-decoration-thickness:0.09em;}")
            append("u s,u strike,u del,s u,strike u,del u,ins s,s ins,")
            append("span[style*=\"underline\"][style*=\"line-through\"],")
            append("font[style*=\"underline\"][style*=\"line-through\"]{")
            append("text-decoration-line:underline line-through !important;")
            append("text-underline-position:under;text-underline-offset:0.18em;")
            append("text-decoration-skip-ink:none;text-decoration-thickness:0.08em;}")
            append("#ashore-ln{position:absolute;left:0;top:0;width:36px;min-height:100%;")
            append("pointer-events:none;user-select:none;font:13px Consolas,Courier New,monospace;")
            append("color:")
            append(gutter)
            append(";text-align:right;overflow:visible;}")
            append("#ashore-ln span{position:absolute;right:8px;left:0;display:flex;")
            append("align-items:flex-start;justify-content:flex-end;padding-top:2px;")
            append("box-sizing:border-box;overflow:hidden;}")
            append("</style><script>")
            append(EDITOR_BOOT_JS)
            append("</script></head><body contenteditable=\"")
            append(edit)
            append("\">")
            append(inner)
            append("</body></html>")
        }
    }

    fun normalizeBundle(bundle: TypewriterBundle) {
        for (e in bundle.diary) e.body = storedHtmlOrSealed(e.body)
        for (p in bundle.pages) p.body = storedHtmlOrSealed(p.body)
        for (list in bundle.lists) {
            for (item in list.items) item.text = storedHtmlOrSealed(item.text)
        }
    }

    fun sameStoredContent(a: TypewriterBundle, b: TypewriterBundle): Boolean {
        if (a.diary.size != b.diary.size || a.lists.size != b.lists.size || a.pages.size != b.pages.size) {
            return false
        }
        a.diary.zip(b.diary).forEach { (x, y) ->
            if (x.date != y.date || x.id != y.id || x.title != y.title) return false
            if (x.images != y.images) return false
            if (storedHtmlOrSealed(x.body) != storedHtmlOrSealed(y.body)) return false
        }
        a.lists.zip(b.lists).forEach { (x, y) ->
            if (x.id != y.id || x.title != y.title || x.items.size != y.items.size) return false
            x.items.zip(y.items).forEach { (ix, iy) ->
                if (ix.done != iy.done) return false
                if (storedHtmlOrSealed(ix.text) != storedHtmlOrSealed(iy.text)) return false
            }
        }
        a.pages.zip(b.pages).forEach { (x, y) ->
            if (x.id != y.id || x.parentId != y.parentId || x.kind != y.kind || x.title != y.title || x.pos != y.pos) {
                return false
            }
            if (x.images != y.images) return false
            if (storedHtmlOrSealed(x.body) != storedHtmlOrSealed(y.body)) return false
        }
        return true
    }

    private fun dropDanger(html: String): String {
        var s = DROP_TAGS.replace(html, "")
        s = ON_ATTR.replace(s, "")
        s = JS_URL.replace(s, "")
        return s
    }

    private fun decodeEscapes(raw: String?): String {
        if (raw == null) return ""
        var s = UNICODE.replace(raw) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        if (s.contains("u003chtml") || s.contains("u003cbody") || s.contains("u003cp")) {
            s = BARE_U00.replace(s) { m ->
                m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
            }
        }
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    }

    private fun bodyInnerOnce(html: String?): String {
        val s = html?.trim().orEmpty()
        if (s.isBlank()) return ""
        val lower = s.lowercase()
        val bodyAt = lower.indexOf("<body")
        if (bodyAt >= 0) {
            val gt = s.indexOf('>', bodyAt)
            val end = lower.lastIndexOf("</body>")
            if (gt >= 0 && end > gt) return s.substring(gt + 1, end).trim()
        }
        val htmlAt = lower.indexOf("<html")
        if (htmlAt >= 0) {
            val gt = s.indexOf('>', htmlAt)
            val end = lower.lastIndexOf("</html>")
            if (gt >= 0 && end > gt) return s.substring(gt + 1, end).trim()
        }
        return s
    }

    private fun escape(s: String?): String {
        if (s == null) return ""
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    /**
     * JavaFX TypewriterPane injectPageTheme: gutter spans follow each block's
     * box, so a 56pt line 3 cannot cover numbers 4 and 5.
     * Injected from Kotlin too — Android WebView often skips inline script
     * and paints before layout (all heights 0).
     */
    internal const val EDITOR_BOOT_JS = """
(function(){
  function saveSel(){
    try{
      var sel=window.getSelection();
      if(!sel||!sel.rangeCount)return;
      var n=sel.anchorNode;
      if(!n||!document.body||!document.body.contains(n))return;
      if(document.activeElement!==document.body)return;
      window.__ashoreRange=sel.getRangeAt(0).cloneRange();
    }catch(e){}
  }
  window.ashoreSaveSel=saveSel;
  window.ashoreRestoreSel=function(){
    try{
      if(!window.__ashoreRange||!document.body)return;
      var sel=window.getSelection();
      sel.removeAllRanges();
      sel.addRange(window.__ashoreRange);
    }catch(e){}
  };
  window.ashorePaintLn=function(){
    if(!document.body)return 0;
    var g=document.getElementById('ashore-ln');
    if(!g){
      g=document.createElement('div');
      g.id='ashore-ln';
      g.setAttribute('contenteditable','false');
      g.setAttribute('unselectable','on');
      document.body.appendChild(g);
    }
    var html='';
    var n=0;
    try{
      var bodyRect=document.body.getBoundingClientRect();
      var kids=document.body.children;
      for(var i=0;i<kids.length;i++){
        var el=kids[i];
        if(!el||el.id==='ashore-ln'||el.id==='ashore-tw-theme')continue;
        var tag=el.tagName;
        if(tag==='STYLE'||tag==='SCRIPT')continue;
        var r=el.getBoundingClientRect();
        var h=Math.max(el.offsetHeight||0, r.height||0);
        if(h<2)continue;
        n++;
        var top=el.offsetTop||0;
        if(bodyRect && r.height>=1){
          top=Math.max(0, Math.round(r.top-bodyRect.top));
        }
        html+='<span style="top:'+top+'px;height:'+Math.round(h)+'px;">'+n+'</span>';
      }
    }catch(err){}
    if(n<1){
      n=1;
      html='<span style="top:8px;height:22px;">1</span>';
      window.__ashoreLnRetry=(window.__ashoreLnRetry||0)+1;
      if(window.__ashoreLnRetry<10){
        setTimeout(window.ashorePaintLn,40);
      }
    }else{
      window.__ashoreLnRetry=0;
    }
    if(g.innerHTML!==html)g.innerHTML=html;
    g.style.height=Math.max(document.body.scrollHeight,document.body.clientHeight,1)+'px';
    window.__ashoreLineCount=n;
    return n;
  };
  window.ashorePaintLnSoon=function(){
    if(window.__ashoreLnT)clearTimeout(window.__ashoreLnT);
    window.__ashoreLnT=setTimeout(window.ashorePaintLn,50);
  };
  window.ashorePaintLnAfterLayout=function(){
    window.ashorePaintLn();
    if(window.requestAnimationFrame){
      requestAnimationFrame(function(){
        window.ashorePaintLn();
        requestAnimationFrame(window.ashorePaintLn);
      });
    }
    setTimeout(window.ashorePaintLn,80);
    setTimeout(window.ashorePaintLn,220);
  };
  /* Preferred size for new typing. null = body default (15px). Prevents Enter
     from inheriting the previous line's oversized span forever on Android WebView. */
  if(typeof window.__ashoreTypingSizePt==='undefined')window.__ashoreTypingSizePt=null;
  function typingSizeCss(){
    return window.__ashoreTypingSizePt!=null?(window.__ashoreTypingSizePt+'pt'):'15px';
  }
  function sizeToPx(css){
    if(!css)return 0;
    var n=parseFloat(css);
    if(!isFinite(n))return 0;
    if(String(css).indexOf('pt')>=0)return n*(96/72);
    return n;
  }
  function sizesClose(a,b){
    return Math.abs(sizeToPx(a)-sizeToPx(b))<0.85;
  }
  function findBlock(node){
    var el=node&&node.nodeType===3?node.parentElement:node;
    while(el&&el!==document.body){
      var t=el.tagName;
      if(t==='P'||t==='DIV'||t==='H1'||t==='H2'||t==='H3'||t==='H4'||t==='H5'||t==='H6'||t==='LI'||t==='BLOCKQUOTE')return el;
      el=el.parentElement;
    }
    return null;
  }
  function rewriteSize7(pt){
    var css=(typeof pt==='number'?pt+'pt':String(pt||'15px'));
    var fonts=document.querySelectorAll('font[size="7"]');
    for(var i=0;i<fonts.length;i++){
      fonts[i].removeAttribute('size');
      fonts[i].style.fontSize=css;
      fonts[i].style.lineHeight='normal';
    }
    var spans=document.querySelectorAll('span');
    for(var i=0;i<spans.length;i++){
      var fs=spans[i].style.fontSize;
      if(fs==='xxx-large'||fs==='xx-large'||fs==='-webkit-xxx-large'){
        spans[i].style.fontSize=css;
        spans[i].style.lineHeight='normal';
      }
    }
  }
  function ensureCaretTypingSize(){
    var want=typingSizeCss();
    var sel=window.getSelection();
    if(!sel||!sel.rangeCount||!document.body)return;
    var range=sel.getRangeAt(0);
    if(!range.collapsed){
      try{document.execCommand('styleWithCSS',false,false);}catch(e){}
      try{document.execCommand('fontSize',false,'7');}catch(e){}
      rewriteSize7(window.__ashoreTypingSizePt!=null?window.__ashoreTypingSizePt:11);
      return;
    }
    var node=range.startContainer;
    var el=node.nodeType===3?node.parentElement:node;
    if(!el)return;
    var cur='';
    try{cur=window.getComputedStyle(el).fontSize;}catch(e){}
    if(sizesClose(cur,want))return;
    try{
      var span=document.createElement('span');
      span.style.fontSize=want;
      span.style.lineHeight='normal';
      var zw=document.createTextNode('\u200b');
      span.appendChild(zw);
      range.insertNode(span);
      var nr=document.createRange();
      nr.setStart(zw,1);
      nr.collapse(true);
      sel.removeAllRanges();
      sel.addRange(nr);
      window.__ashoreRange=nr.cloneRange();
    }catch(e){}
  }
  function afterNewLineForceSize(){
    var sel=window.getSelection();
    if(!sel||!sel.rangeCount||!document.body)return;
    var block=findBlock(sel.getRangeAt(0).startContainer);
    if(!block||block.id==='ashore-ln')return;
    var want=typingSizeCss();
    try{
      block.style.fontSize=want;
      block.style.lineHeight='normal';
      var plain=(block.textContent||'').replace(/\u200b/g,'').replace(/\s/g,'');
      if(!plain){
        block.innerHTML='<br>';
        var nr=document.createRange();
        nr.setStart(block,0);
        nr.collapse(true);
        sel.removeAllRanges();
        sel.addRange(nr);
        window.__ashoreRange=nr.cloneRange();
      }else{
        ensureCaretTypingSize();
      }
    }catch(e){}
  }
  var ASHORE_SIZE_PTS=[8,10,12,14,18,24,36,48,56];
  function nearestSizePt(px){
    var pt=px*(72/96);
    var best=ASHORE_SIZE_PTS[0],bd=Math.abs(best-pt);
    for(var i=1;i<ASHORE_SIZE_PTS.length;i++){
      var d=Math.abs(ASHORE_SIZE_PTS[i]-pt);
      if(d<bd){bd=d;best=ASHORE_SIZE_PTS[i];}
    }
    return best;
  }
  window.ashoreReportFontSize=function(){
    try{
      if(!window.Ashore||!window.Ashore.fontSizePt)return;
      var sel=window.getSelection();
      if(!sel||!sel.rangeCount)return;
      var node=sel.getRangeAt(0).startContainer;
      var el=node&&node.nodeType===3?node.parentElement:node;
      if(!el||typeof el.getBoundingClientRect!=='function')el=document.body;
      var px=parseFloat(window.getComputedStyle(el).fontSize)||15;
      var pt=nearestSizePt(px);
      if(window.__ashoreLastReportedPt===pt)return;
      window.__ashoreLastReportedPt=pt;
      Ashore.fontSizePt(pt);
    }catch(e){}
  };
  window.ashoreScrollCaretIntoView=function(){
    try{
      var sel=window.getSelection();
      if(!sel||!sel.rangeCount)return;
      var range=sel.getRangeAt(0);
      var r=range.getBoundingClientRect();
      if((!r||(r.width===0&&r.height===0))&&range.startContainer){
        var n=range.startContainer;
        if(n.nodeType===3&&n.parentElement)r=n.parentElement.getBoundingClientRect();
        else if(n.getBoundingClientRect)r=n.getBoundingClientRect();
      }
      if(!r)return;
      var vh=window.innerHeight||document.documentElement.clientHeight||0;
      if(vh<1)return;
      var margin=56;
      if(r.bottom>vh-margin){
        window.scrollBy(0,Math.ceil(r.bottom-(vh-margin)));
      }else if(r.top<12){
        window.scrollBy(0,Math.floor(r.top-12));
      }
    }catch(e){}
  };
  window.ashoreFontSize=function(pt){
    window.__ashoreTypingSizePt=+pt;
    window.__ashoreLastReportedPt=+pt;
    try{if(window.Ashore&&window.Ashore.fontSizePt)Ashore.fontSizePt(+pt);}catch(e){}
    window.ashoreRestoreSel();
    try{document.execCommand('styleWithCSS',false,false);}catch(e){}
    try{document.execCommand('fontSize',false,'7');}catch(e){}
    rewriteSize7(pt);
    ensureCaretTypingSize();
    window.ashorePaintLnAfterLayout();
  };
  window.ashoreForeColor=function(hex){
    window.ashoreRestoreSel();
    try{document.execCommand('styleWithCSS',false,false);}catch(e){}
    try{document.execCommand('foreColor',false,hex);}catch(e){}
    window.ashorePaintLnAfterLayout();
  };
  function ashoreHasDecor(node,kind){
    var el=node&&node.nodeType===3?node.parentElement:node;
    while(el&&el!==document.body){
      var t=el.tagName;
      if(kind==='underline'&&(t==='U'||t==='INS'))return true;
      if(kind==='line-through'&&(t==='S'||t==='STRIKE'||t==='DEL'))return true;
      var st=((el.getAttribute('style')||'')+' '+(el.style&&el.style.textDecorationLine||'')).toLowerCase();
      if(st.indexOf(kind)>=0)return true;
      el=el.parentElement;
    }
    return false;
  }
  function ashoreForceBoth(){
    var sel=window.getSelection();
    if(!sel||!sel.rangeCount)return;
    var el=sel.getRangeAt(0).commonAncestorContainer;
    if(el.nodeType===3)el=el.parentElement;
    while(el&&el!==document.body){
      var t=el.tagName;
      if(t==='SPAN'||t==='FONT'||t==='U'||t==='S'||t==='STRIKE'||t==='INS'||t==='DEL'){
        el.style.setProperty('text-decoration-line','underline line-through','important');
        el.style.setProperty('text-underline-position','under');
        el.style.setProperty('text-underline-offset','0.18em');
        el.style.setProperty('text-decoration-skip-ink','none');
        return;
      }
      el=el.parentElement;
    }
  }
  window.ashoreMergeDecor=function(){
    try{
      var all=document.body.querySelectorAll('u,ins,s,strike,del,span,font');
      for(var i=0;i<all.length;i++){
        var el=all[i];
        if(ashoreHasDecor(el,'underline')&&ashoreHasDecor(el,'line-through')){
          el.style.setProperty('text-decoration-line','underline line-through','important');
          el.style.setProperty('text-underline-position','under');
          el.style.setProperty('text-underline-offset','0.18em');
          el.style.setProperty('text-decoration-skip-ink','none');
        }
      }
    }catch(e){}
  };
  window.ashoreUnderline=function(){
    window.ashoreRestoreSel();
    var sel=window.getSelection();
    var hadS=sel&&sel.anchorNode?ashoreHasDecor(sel.anchorNode,'line-through'):false;
    try{document.execCommand('styleWithCSS',false,true);}catch(e){}
    try{document.execCommand('underline');}catch(e){}
    if(hadS)ashoreForceBoth();
    window.ashoreMergeDecor();
    window.ashorePaintLnAfterLayout();
  };
  window.ashoreStrikeThrough=function(){
    window.ashoreRestoreSel();
    var sel=window.getSelection();
    var hadU=sel&&sel.anchorNode?ashoreHasDecor(sel.anchorNode,'underline'):false;
    try{document.execCommand('styleWithCSS',false,true);}catch(e){}
    try{document.execCommand('strikeThrough');}catch(e){}
    if(hadU)ashoreForceBoth();
    window.ashoreMergeDecor();
    window.ashorePaintLnAfterLayout();
  };
  function watch(){
    if(!document.body)return;
    if(window.__ashoreLnWatch){
      window.ashorePaintLnAfterLayout();
      return;
    }
    window.__ashoreLnWatch=true;
    document.addEventListener('keydown',function(e){
      if(e&&(e.key==='Enter'||e.keyCode===13))window.__ashoreForceSizeNext=true;
    },true);
    document.addEventListener('beforeinput',function(e){
      if(!e)return;
      if(e.inputType==='insertParagraph'||e.inputType==='insertLineBreak'){
        window.__ashoreForceSizeNext=true;
      }
    },true);
    document.addEventListener('keyup',function(e){
      saveSel();
      if(window.__ashoreForceSizeNext||(e&&(e.key==='Enter'||e.keyCode===13))){
        afterNewLineForceSize();
        window.__ashoreForceSizeNext=false;
      }
      window.ashoreReportFontSize();
      window.ashoreScrollCaretIntoView();
      window.ashorePaintLnSoon();
    });
    document.addEventListener('mouseup',function(){
      saveSel();
      window.ashoreReportFontSize();
    },true);
    document.addEventListener('selectionchange',function(){
      if(window.__ashoreSelT)clearTimeout(window.__ashoreSelT);
      window.__ashoreSelT=setTimeout(function(){
        saveSel();
        window.ashoreReportFontSize();
      },80);
    });
    window.addEventListener('resize',function(){
      window.ashorePaintLnSoon();
      window.ashoreScrollCaretIntoView();
    });
    if(window.visualViewport){
      window.visualViewport.addEventListener('resize',function(){
        window.ashoreScrollCaretIntoView();
      });
    }
    if(window.Ashore && document.body.dataset.ashoreBound!=='1'){
      document.body.dataset.ashoreBound='1';
      document.body.addEventListener('input', function(){
        if(window.__ashoreForceSizeNext){
          afterNewLineForceSize();
          window.__ashoreForceSizeNext=false;
        }
        window.ashoreReportFontSize();
        window.ashoreScrollCaretIntoView();
        try{Ashore.bodyInput(document.body.innerHTML);}catch(e2){}
      });
    }
    window.ashorePaintLnAfterLayout();
    setTimeout(window.ashoreReportFontSize,0);
  }
  if(document.body)watch();
  document.addEventListener('DOMContentLoaded',watch);
  document.addEventListener('readystatechange',function(){if(document.body)watch();});
  setTimeout(watch,0);
})();
"""
}
