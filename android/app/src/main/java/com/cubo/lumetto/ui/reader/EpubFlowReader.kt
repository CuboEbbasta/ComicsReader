package com.cubo.lumetto.ui.reader

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cubo.lumetto.archive.EpubParser
import com.cubo.lumetto.archive.ZipArchive
import com.cubo.lumetto.data.AppSettings
import com.cubo.lumetto.data.ComicPrefs
import com.cubo.lumetto.data.HighlightStore
import com.cubo.lumetto.data.LibraryStore
import com.cubo.lumetto.data.ProgressStore
import com.cubo.lumetto.data.mainHandler
import com.cubo.lumetto.model.ComicFile
import com.cubo.lumetto.model.EpubFont
import com.cubo.lumetto.model.EpubHighlight
import com.cubo.lumetto.model.HighlightColor
import com.cubo.lumetto.model.newUuid
import com.cubo.lumetto.model.nowMs
import com.cubo.lumetto.ui.BarButton
import com.cubo.lumetto.ui.FlatArrowButton
import com.cubo.lumetto.ui.LColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.util.Locale

private const val HOST = "lumetto.epub"

/** Script delle evidenziazioni: identico a quello dell'iPad (posizioni in caratteri nel capitolo). */
private val HIGHLIGHT_JS = """
(function(){
if(window.frHL)return;
function pos(c,o){var r=document.createRange();r.setStart(document.body,0);r.setEnd(c,o);return r.toString().length;}
function textNodes(){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null);var a=[],n;while((n=w.nextNode()))a.push(n);return a;}
function post(m){try{LumettoBridge.post(JSON.stringify(m));}catch(e){}}
window.frHL={
 selection:function(){var s=window.getSelection();if(!s||s.rangeCount===0||s.isCollapsed)return '';var r=s.getRangeAt(0);var t=r.toString();if(!t.trim())return '';var st=pos(r.startContainer,r.startOffset);return JSON.stringify({start:st,end:st+t.length,text:t});},
 clear:function(){var s=window.getSelection();if(s)s.removeAllRanges();},
 removeAll:function(){document.querySelectorAll('mark.fr-hl').forEach(function(m){var p=m.parentNode;while(m.firstChild)p.insertBefore(m.firstChild,m);p.removeChild(m);p.normalize();});},
 wrap:function(h){var nodes=textNodes();var p=0;for(var i=0;i<nodes.length;i++){var n=nodes[i];var len=n.data.length;var a=Math.max(h.start,p),b=Math.min(h.end,p+len);if(a<b){var node=n;var s=a-p,e=b-p;if(e<len)node.splitText(e);if(s>0)node=node.splitText(s);var m=document.createElement('mark');m.className='fr-hl';m.setAttribute('data-id',h.id);m.style.background=h.color;node.parentNode.insertBefore(m,node);m.appendChild(node);}p+=len;if(p>=h.end)break;}},
 apply:function(list){frHL.removeAll();list.forEach(function(h){frHL.wrap(h);});},
 scrollTo:function(id){var m=document.querySelector('mark.fr-hl[data-id="'+id+'"]');if(m)m.scrollIntoView({block:'center',behavior:'smooth'});}
};
var last=null;
document.addEventListener('selectionchange',function(){var s=window.getSelection();var has=!!(s&&!s.isCollapsed&&s.toString().trim().length);if(has!==last){last=has;post({type:'sel',has:has});}});
document.addEventListener('click',function(e){var m=e.target&&e.target.closest?e.target.closest('mark.fr-hl'):null;if(m){post({type:'tap',id:m.getAttribute('data-id')});return;}
 if(e.target&&e.target.closest&&e.target.closest('a'))return;var s=window.getSelection();if(s&&!s.isCollapsed)return;post({type:'bodytap'});},true);
var t=null;window.addEventListener('scroll',function(){if(t)return;t=setTimeout(function(){t=null;var h=document.documentElement.scrollHeight-window.innerHeight;post({type:'scroll',value:h>0?window.scrollY/h:0});},400);});
})();
""".trimIndent()

private fun highlightsJson(list: List<EpubHighlight>): String {
    val a = JSONArray()
    list.forEach { a.put(JSONObject().put("id", it.id).put("start", it.start).put("end", it.end).put("color", it.color.css)) }
    return a.toString()
}

private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "xhtml", "xht", "xml" -> "application/xhtml+xml"
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "js" -> "application/javascript"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}

/** Stato del lettore EPUB a testo scorrevole. */
private class EpubFlowState(val comic: ComicFile) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val title = LibraryStore.title(comic)
    val path = comic.relativePath
    var archive: ZipArchive? = null
    var chapters by mutableStateOf<List<String>>(emptyList())
    var titles by mutableStateOf<Map<String, String>>(emptyMap())
    var chapter by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var showControls by mutableStateOf(true)
    var showChapters by mutableStateOf(false)
    var showFonts by mutableStateOf(false)
    var showHighlights by mutableStateOf(false)
    var hasSelection by mutableStateOf(false)
    var tapped by mutableStateOf<EpubHighlight?>(null)
    var pendingScrollId: String? = null
    var webView: WebView? = null
    private var sessionStart: Double? = null
    private val seen = HashSet<Int>()

    fun load() {
        scope.launch {
            try {
                val (a, paths, t) = withContext(Dispatchers.IO) {
                    val zip = ZipArchive.open(comic.uri)
                    val pkg = EpubParser.packageOf(zip)
                    Triple(zip, pkg.spinePaths, EpubParser.chapterTitles(pkg, zip))
                }
                if (paths.isEmpty()) { error = "Questo EPUB non contiene capitoli leggibili."; a.close(); return@launch }
                archive = a
                titles = t
                chapter = (ProgressStore.progress(path)?.currentPage ?: 0).coerceIn(0, paths.size - 1)
                chapters = paths
                startSession()
            } catch (e: Throwable) {
                error = "Impossibile aprire l'EPUB: ${e.message ?: "errore sconosciuto"}"
            }
        }
    }

    fun titleOf(index: Int): String = chapters.getOrNull(index)?.let { titles[it] } ?: "Capitolo ${index + 1}"

    fun chapterUrl(index: Int): String {
        val p = chapters.getOrNull(index) ?: return "about:blank"
        return "https://$HOST/" + p.split('/').joinToString("/") { Uri.encode(it) }
    }

    /** Posizione dentro il capitolo (0…1), nel formato dell'iPad "capitolo:frazione". */
    val savedFraction: Double
        get() {
            val raw = ComicPrefs.value("epubPosition", path) ?: return 0.0
            val parts = raw.split(':')
            if (parts.size != 2 || parts[0].toIntOrNull() != chapter) return 0.0
            return parts[1].toDoubleOrNull() ?: 0.0
        }

    fun saveFraction(f: Double) {
        ComicPrefs.set("$chapter:${String.format(Locale.ROOT, "%.4f", f.coerceIn(0.0, 1.0))}", "epubPosition", path)
    }

    fun go(index: Int) {
        if (index !in chapters.indices) return
        chapter = index
        hasSelection = false
        saveFraction(0.0)
        seen.add(index)
        ProgressStore.updatePage(path, index, chapters.size)
    }

    // --- evidenziazioni ---

    val chapterHighlights get() = HighlightStore.highlights(path, chapter)
    val allHighlights get() = HighlightStore.highlights(path)

    fun applyHighlights() {
        webView?.evaluateJavascript("window.frHL && frHL.apply(${highlightsJson(chapterHighlights)})", null)
    }

    fun highlightSelection(color: HighlightColor) {
        val wv = webView ?: return
        wv.evaluateJavascript("window.frHL ? frHL.selection() : ''") { result ->
            val raw = runCatching { JSONTokener(result ?: "").nextValue() as? String }.getOrNull() ?: return@evaluateJavascript
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@evaluateJavascript
            val start = o.optInt("start", -1)
            val end = o.optInt("end", -1)
            val text = o.optString("text").trim()
            if (start < 0 || end <= start || text.isEmpty()) return@evaluateJavascript
            HighlightStore.add(EpubHighlight(newUuid(), path, chapter, start, end, text, color, nowMs()))
            wv.evaluateJavascript("window.frHL && frHL.clear()", null)
            hasSelection = false
            applyHighlights()
        }
    }

    fun remove(h: EpubHighlight) {
        HighlightStore.remove(h.id)
        if (h.chapter == chapter) applyHighlights()
    }

    fun recolor(h: EpubHighlight, color: HighlightColor) {
        HighlightStore.recolor(h.id, color)
        if (h.chapter == chapter) applyHighlights()
    }

    fun open(h: EpubHighlight) {
        if (h.chapter == chapter) {
            val safe = h.id.filter { it.isLetterOrDigit() || it == '-' }
            webView?.evaluateJavascript("window.frHL && frHL.scrollTo('$safe')", null)
        } else {
            pendingScrollId = h.id
            go(h.chapter)
        }
    }

    val isCompleted get() = ProgressStore.progress[path]?.isCompleted == true
    fun toggleCompleted() = ProgressStore.setCompleted(!isCompleted, path, chapters.size)

    fun startSession() {
        if (sessionStart != null || chapters.isEmpty()) return
        sessionStart = nowMs()
        seen.clear(); seen.add(chapter)
        ProgressStore.beginSession(path, chapters.size)
    }

    fun endSession() {
        val start = sessionStart ?: return
        sessionStart = null
        ProgressStore.endSession(path, title, start, (nowMs() - start) / 1000.0, seen.size)
        ProgressStore.flush()
    }

    fun dispose() {
        endSession()
        scope.cancel()
        webView?.destroy()
        webView = null
        runCatching { archive?.close() }
    }
}

private fun handleBridgeMessage(state: EpubFlowState, message: String) {
    val o = runCatching { JSONObject(message) }.getOrNull() ?: return
    when (o.optString("type")) {
        "sel" -> state.hasSelection = o.optBoolean("has")
        "tap" -> state.tapped = state.allHighlights.firstOrNull { it.id == o.optString("id") }
        "bodytap" -> state.showControls = !state.showControls
        "scroll" -> state.saveFraction(o.optDouble("value", 0.0))
        else -> {}
    }
}

private fun cssFor(font: EpubFont, scale: Double): String {
    var rules = """
        html, body { background: #0B0907 !important; color: #F1E8D3 !important; }
        body { padding: 24px 7% 120px !important; line-height: 1.65 !important; font-size: ${(scale * 100).toInt()}% !important; -webkit-text-size-adjust: none; }
        a { color: #E39B2D !important; }
        mark.fr-hl { color: inherit !important; border-radius: 3px; padding: 0 1px; }
        img, svg, video { max-width: 100% !important; height: auto !important; }
    """.trimIndent()
    font.cssFamily?.let { rules += "\nbody, p, div, span, li { font-family: $it !important; }" }
    return rules
}

private fun cssScript(css: String): String {
    val escaped = css.replace("\\", "\\\\").replace("`", "\\`")
    return "(function(){var s=document.getElementById('fr-style');if(!s){s=document.createElement('style');s.id='fr-style';(document.head||document.documentElement).appendChild(s);}s.textContent=`$escaped`;})();"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubFlowReader(comic: ComicFile, onClose: () -> Unit) {
    val state = remember(comic.id) { EpubFlowState(comic) }
    LaunchedEffect(state) { state.load() }
    DisposableEffect(state) { onDispose { state.dispose() } }
    SessionLifecycle(onStart = { state.startSession() }, onStop = { state.endSession() })
    ReaderWindowEffects(state.showControls)
    BackHandler(onBack = onClose)

    val css = cssFor(AppSettings.epubFont, AppSettings.epubFontScale)
    // Il CSS cambia senza ricaricare il capitolo.
    LaunchedEffect(css) { state.webView?.evaluateJavascript(cssScript(css), null) }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0907))) {
        val error = state.error
        when {
            state.chapters.isNotEmpty() -> {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(AColor.rgb(11, 9, 7))
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.domStorageEnabled = false
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun post(message: String) {
                                    mainHandler.post { handleBridgeMessage(state, message) }
                                }
                            }, "LumettoBridge")
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val url = request.url
                                    if (url.host != HOST) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                                    val p = (url.path ?: "").removePrefix("/")
                                    val data = runCatching { state.archive?.data(p) }.getOrNull()
                                        ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                                    val mime = mimeFor(p)
                                    val encoding = if (mime.startsWith("text/") || mime.contains("xml") || mime.contains("javascript")) "utf-8" else null
                                    return WebResourceResponse(mime, encoding, ByteArrayInputStream(data))
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url
                                    if (url.host == HOST) {
                                        // Collegamento interno: se porta a un altro capitolo, lo apre.
                                        val p = (url.path ?: "").removePrefix("/")
                                        val i = state.chapters.indexOf(p)
                                        if (i >= 0 && i != state.chapter) { state.go(i); return true }
                                        return false
                                    }
                                    return true
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    view.evaluateJavascript(HIGHLIGHT_JS, null)
                                    view.evaluateJavascript(cssScript(cssFor(AppSettings.epubFont, AppSettings.epubFontScale)), null)
                                    val f = state.savedFraction
                                    view.evaluateJavascript("(function(){var h=document.documentElement.scrollHeight-window.innerHeight;window.scrollTo(0,$f*Math.max(h,0));})();", null)
                                    var extra = "window.frHL && frHL.apply(${highlightsJson(state.chapterHighlights)});"
                                    state.pendingScrollId?.let { id ->
                                        val safe = id.filter { it.isLetterOrDigit() || it == '-' }
                                        extra += "setTimeout(function(){window.frHL && frHL.scrollTo('$safe');},150);"
                                        state.pendingScrollId = null
                                    }
                                    view.evaluateJavascript(extra, null)
                                }
                            }
                            state.webView = this
                        }
                    },
                    update = { wv ->
                        val url = state.chapterUrl(state.chapter)
                        if (wv.tag != url) {
                            wv.tag = url
                            wv.loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize().then(if (state.showControls) Modifier.statusBarsPadding().padding(top = 56.dp) else Modifier),
                )
            }
            error != null -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(error, color = LColors.textSecondary, textAlign = TextAlign.Center)
                BarButton(onClick = onClose, prominent = true) { Text("Torna alla libreria") }
            }
            else -> CircularProgressIndicator(color = LColors.accent, modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(state.showControls && state.chapters.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxSize()) {
                EpubTopBar(state, onClose)
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    FlatArrowButton(onClick = { state.go(state.chapter - 1) }, enabled = state.chapter > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null); Text("Precedente")
                    }
                    Spacer(Modifier.weight(1f))
                    FlatArrowButton(onClick = { state.go(state.chapter + 1) }, enabled = state.chapter < state.chapters.size - 1) {
                        Text("Successivo"); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
            }
        }

        AnimatedVisibility(
            state.hasSelection,
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (state.showControls) 70.dp else 24.dp),
        ) {
            Row(
                Modifier.clip(CircleShape).background(LColors.panel.copy(alpha = 0.96f)).border(1.dp, LColors.stroke, CircleShape).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.BorderColor, null, tint = LColors.textSecondary, modifier = Modifier.size(18.dp))
                Text("Evidenzia", color = LColors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                HighlightColor.entries.forEach { c ->
                    Box(Modifier.size(44.dp).clickable { state.highlightSelection(c) }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c.argb)).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape))
                    }
                }
            }
        }
    }

    if (state.showChapters) ChapterSheet(state)
    if (state.showHighlights) HighlightsSheet(state)
    if (state.showFonts) FontSheet(state)
    state.tapped?.let { h ->
        AlertDialog(
            onDismissRequest = { state.tapped = null },
            title = { Text("Evidenziazione") },
            text = {
                Column {
                    HighlightColor.entries.filter { it != h.color }.forEach { c ->
                        TextButton(onClick = { state.recolor(h, c); state.tapped = null }) { Text(c.displayName, color = Color(c.argb)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { state.remove(h); state.tapped = null }) { Text("Rimuovi evidenziazione", color = LColors.error) } },
            dismissButton = { TextButton(onClick = { state.tapped = null }) { Text("Annulla", color = LColors.textSecondary) } },
            containerColor = LColors.surface,
        )
    }
}

@Composable
private fun EpubTopBar(state: EpubFlowState, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(LColors.panel.copy(alpha = 0.95f)).statusBarsPadding().padding(horizontal = 6.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Torna alla libreria", tint = LColors.textPrimary) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.title, color = LColors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${state.titleOf(state.chapter)} · ${state.chapter + 1} di ${state.chapters.size}", color = LColors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { state.showChapters = true }) { Icon(Icons.AutoMirrored.Filled.List, "Capitoli", tint = LColors.textPrimary) }
        IconButton(onClick = { state.showHighlights = true }) { Icon(Icons.Filled.BorderColor, "Evidenziazioni", tint = LColors.textPrimary) }
        val done = state.isCompleted
        IconButton(onClick = { state.toggleCompleted() }) {
            Icon(if (done) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle, if (done) "Segna come non letto" else "Segna come letto", tint = if (done) LColors.accent else LColors.textPrimary)
        }
        IconButton(onClick = { state.showFonts = true }) { Icon(Icons.Filled.TextFields, "Carattere", tint = LColors.textPrimary) }
    }
}

@Composable
private fun ChapterSheet(state: EpubFlowState) {
    ModalBottomSheet(onDismissRequest = { state.showChapters = false }, containerColor = LColors.surface) {
        val list = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, state.chapter - 3))
        Text("Capitoli", color = LColors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.chapters.size, key = { it }) { i ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(LColors.card)
                        .clickable { state.go(i); state.showChapters = false }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(state.titleOf(i), color = if (i == state.chapter) LColors.accent else LColors.textPrimary, maxLines = 2, modifier = Modifier.weight(1f))
                    Text("${i + 1}", color = LColors.textSecondary, fontSize = 12.sp)
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun HighlightsSheet(state: EpubFlowState) {
    ModalBottomSheet(onDismissRequest = { state.showHighlights = false }, containerColor = LColors.surface) {
        val all = state.allHighlights
        Text("Evidenziazioni", color = LColors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
        if (all.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.BorderColor, null, tint = LColors.accent, modifier = Modifier.size(34.dp))
                Text("Nessuna evidenziazione", color = LColors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("Seleziona del testo e scegli un colore.", color = LColors.textSecondary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(all.size, key = { all[it].id }) { i ->
                    val h = all[i]
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(LColors.card)
                            .clickable { state.open(h); state.showHighlights = false }.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(Color(h.color.argb), RoundedCornerShape(2.dp)))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(h.text, color = LColors.textPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Text(state.titleOf(h.chapter), color = LColors.textSecondary, fontSize = 12.sp, maxLines = 1)
                        }
                        IconButton(onClick = { state.remove(h) }) { Icon(Icons.Filled.Delete, "Rimuovi", tint = LColors.textSecondary) }
                    }
                }
                item { Spacer(Modifier.size(24.dp)) }
            }
        }
    }
}

@Composable
private fun FontSheet(state: EpubFlowState) {
    ModalBottomSheet(onDismissRequest = { state.showFonts = false }, containerColor = LColors.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Carattere", color = LColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Segmented(EpubFont.entries, AppSettings.epubFont, { it.displayName }) { AppSettings.epubFont = it }
            Text("Dimensione", color = LColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlatArrowButton(onClick = { AppSettings.epubFontScale = maxOf(0.7, AppSettings.epubFontScale - 0.1) }) { Text("A−") }
                Text("${Math.round(AppSettings.epubFontScale * 100)}%", color = LColors.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                FlatArrowButton(onClick = { AppSettings.epubFontScale = minOf(2.2, AppSettings.epubFontScale + 0.1) }) { Text("A+") }
            }
        }
    }
}

@Suppress("unused")
private fun Modifier.limitWidth() = this.widthIn(max = 600.dp)
