// صدى — أونلاين: بحث يوتيوب وقوائم التشغيل بلا إعلانات (بث مباشر عبر yt-dlp)،
// وتنزيل الفيديو أو الصوت من يوتيوب وX (تويتر) وفيسبوك وإنستغرام وتيك توك وأكثر من ألف موقع
// برمجة: د. محمد توفيق
package app.sada

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

val OnlineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

// ---------- نموذج عنصر أونلاين ----------
data class OnlineItem(val url: String, val title: String, val uploader: String, val duration: Long, val thumb: String?)

/** رابط داخلي يُحَلّ إلى رابط البث الحقيقي لحظة التشغيل (فلا تنتهي صلاحيته في الطابور) */
fun onlineUri(page: String): Uri = Uri.parse("ytdl://play?u=" + Uri.encode(page))

fun OnlineItem.toItem(): androidx.media3.common.MediaItem {
    val u = onlineUri(url)
    return androidx.media3.common.MediaItem.Builder()
        .setMediaId("o:$url").setUri(u)
        .setRequestMetadata(androidx.media3.common.MediaItem.RequestMetadata.Builder().setMediaUri(u).build())
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist(uploader.ifBlank { null })
                .setArtworkUri(thumb?.let { Uri.parse(it) }).setIsPlayable(true).setIsBrowsable(false)
                .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC).build()
        ).build()
}

/** هل الرابط ملف وسائط مباشر أم صفحة تحتاج استخراجاً؟ */
fun isPageUrl(u: Uri): Boolean {
    val s = u.scheme ?: return false
    if (s != "http" && s != "https") return false
    val path = (u.path ?: "").lowercase()
    val direct = listOf(".mp4", ".mkv", ".webm", ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav", ".m3u8", ".mpd", ".ts", ".mov", ".avi", ".3gp")
    return direct.none { path.endsWith(it) }
}

// ---------- محرك yt-dlp ----------
object Ytdl {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    /** 0 لم يبدأ، 1 قيد التجهيز، 2 جاهز، -1 خطأ */
    val state = MutableStateFlow(0)
    val version = MutableStateFlow("")
    var lastError: String? = null
    private val lock = Any()

    data class Resolved(val urls: List<String>, val headers: Map<String, String>, val hls: Boolean, val at: Long)
    private val cache = ConcurrentHashMap<String, Resolved>()

    fun ensure(ctx: Context): Boolean {
        if (state.value == 2) return true
        synchronized(lock) {
            if (state.value == 2) return true
            state.value = 1
            return try {
                YoutubeDL.getInstance().init(ctx.applicationContext)
                FFmpeg.getInstance().init(ctx.applicationContext)
                // يوتيوب يغيّر واجهاته كثيراً: حدّث المحرك أول مرة ثم مرة يومياً
                val last = Store.getStr("ytdl_upd")?.toLongOrNull() ?: 0L
                if (System.currentTimeMillis() - last > 24 * 3600_000L) {
                    state.value = 3
                    runCatching { YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel._STABLE) }
                        .onSuccess { Store.putStr("ytdl_upd", System.currentTimeMillis().toString()) }
                        .onFailure { android.util.Log.w("Sada", "ytdl update failed", it) }
                }
                version.value = runCatching { YoutubeDL.getInstance().versionName(ctx.applicationContext) ?: "" }.getOrDefault("")
                state.value = 2; true
            } catch (e: Throwable) {
                lastError = e.message; state.value = -1; false
            }
        }
    }

    fun initAsync(ctx: Context) {
        if (state.value == 0 || state.value == -1) Thread { ensure(ctx) }.start()
    }

    fun json(ctx: Context, target: String, vararg opts: String): JSONObject {
        if (!ensure(ctx)) throw IllegalStateException(lastError ?: "engine")
        val req = YoutubeDLRequest(target)
        req.addOption("--dump-single-json")
        req.addOption("--no-warnings")
        var i = 0
        while (i < opts.size) {
            val k = opts[i]
            if (i + 1 < opts.size && !opts[i + 1].startsWith("--") && !(opts[i + 1].startsWith("-") && opts[i + 1].length == 2)) { req.addOption(k, opts[i + 1]); i += 2 }
            else { req.addOption(k); i++ }
        }
        val r = YoutubeDL.getInstance().execute(req)
        if (r.err.isNotBlank()) android.util.Log.d("Sada", "yt-dlp err: " + r.err.take(600))
        return JSONObject(r.out.trim().lines().last { it.startsWith("{") })
    }

    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

    private fun entry(o: JSONObject): OnlineItem? {
        val id = o.str("id")
        val ie = o.str("ie_key").ifBlank { o.str("extractor_key") }
        var url = o.str("webpage_url").ifBlank { o.str("url") }
        if (url.isBlank()) return null
        if (!url.startsWith("http") && ie.contains("Youtube", true)) url = "https://www.youtube.com/watch?v=$url"
        val thumbs = o.optJSONArray("thumbnails")
        var thumb = o.str("thumbnail").ifBlank { null }
        if (thumb == null && thumbs != null && thumbs.length() > 0) thumb = thumbs.getJSONObject(thumbs.length() - 1).str("url").ifBlank { null }
        if (ie.contains("Youtube", true) && id.isNotBlank()) thumb = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
        return OnlineItem(
            url = url,
            title = o.str("title").ifBlank { url },
            uploader = o.str("channel").ifBlank { o.str("uploader") }.ifBlank { o.str("extractor_key") },
            duration = (o.optDouble("duration", 0.0) * 1000).toLong(),
            thumb = thumb,
        )
    }

    /** بحث، أو رابط قائمة/قناة/مقطع: يعيد العنوان والعناصر */
    fun list(ctx: Context, target: String): Pair<String, List<OnlineItem>> {
        val j = json(ctx, target, "--flat-playlist")
        val e = j.optJSONArray("entries")
        android.util.Log.d("Sada", "list $target -> entries=${e?.length()} keys=${j.keys().asSequence().take(12).toList()}")
        if (e == null) return (j.str("title")) to listOfNotNull(entry(j))
        val out = ArrayList<OnlineItem>()
        for (k in 0 until e.length()) {
            val o = e.optJSONObject(k) ?: continue
            // قوائم متداخلة (تبويبات القناة)
            val sub = o.optJSONArray("entries")
            if (sub != null) for (m in 0 until sub.length()) sub.optJSONObject(m)?.let { entry(it) }?.let { out += it }
            else entry(o)?.let { out += it }
        }
        return j.str("title") to out.filter { !it.title.equals("[Private video]", true) && !it.title.equals("[Deleted video]", true) }
    }

    /** يحلّ صفحة المقطع إلى رابط بث مباشر (صوت فقط أو فيديو+صوت) — يُستدعى من خيط التحميل */
    fun resolve(ctx: Context, page: String, audio: Boolean): Resolved {
        val q = Store.getInt("yt_q", 720)
        val key = (if (audio) "a|" else "v$q|") + page
        cache[key]?.let { if (System.currentTimeMillis() - it.at < 2 * 3600_000L) return it }
        val f = if (audio) "bestaudio[ext=m4a]/bestaudio/best"
        else "bv*[height<=$q][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=$q]+ba/b[height<=$q]/b"
        val j = json(ctx, page, "-f", f, "--no-playlist")
        val rf = j.optJSONArray("requested_formats")
        val urls = if (rf != null && rf.length() > 0) (0 until rf.length()).map { rf.getJSONObject(it).getString("url") } else listOf(j.getString("url"))
        val h = (j.optJSONObject("http_headers") ?: rf?.optJSONObject(0)?.optJSONObject("http_headers"))
        val headers = HashMap<String, String>()
        h?.keys()?.forEach { k -> headers[k] = h.getString(k) }
        val proto = j.optString("protocol") + (rf?.optJSONObject(0)?.optString("protocol") ?: "")
        return Resolved(urls, headers, proto.contains("m3u8"), System.currentTimeMillis()).also { cache[key] = it }
    }

    fun invalidate(page: String) { cache.keys.filter { it.endsWith("|$page") }.forEach { cache.remove(it) } }

    fun update(ctx: Context): String {
        if (!ensure(ctx)) return lastError ?: "error"
        val r = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel._STABLE)
        version.value = runCatching { YoutubeDL.getInstance().versionName(ctx.applicationContext) ?: "" }.getOrDefault("")
        return r?.name ?: "?"
    }
}

// ---------- صور مصغّرة من الإنترنت ----------
object NetThumbs {
    private val cache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    fun peek(u: String): Bitmap? = cache.get(u)
    suspend fun load(u: String): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(u)?.let { return@withContext it }
        runCatching {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", Ytdl.UA)
            c.instanceFollowRedirects = true
            val bytes = c.inputStream.use { it.readBytes() }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException("decode ${bytes.size} bytes, code ${c.responseCode}")
        }.onFailure { android.util.Log.w("Sada", "thumb $u: $it") }.getOrNull()?.also { cache.put(u, it) }
    }
}

@Composable
fun rememberNetThumb(u: String?): Bitmap? {
    var b by remember(u) { mutableStateOf(u?.let { NetThumbs.peek(it) }) }
    LaunchedEffect(u) { if (u != null && b == null) b = NetThumbs.load(u) }
    return b
}

// ---------- قوائم يوتيوب المحفوظة ----------
object SavedLists {
    val items = MutableStateFlow<List<Pair<String, String>>>(emptyList())   // (الاسم، الرابط)
    fun load() = runCatching {
        val a = JSONArray(Store.getStr("yt_lists") ?: "[]")
        items.value = (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("n") to o.getString("u") } }
    }
    private fun save(l: List<Pair<String, String>>) {
        items.value = l
        Store.putStr("yt_lists", JSONArray().apply { l.forEach { put(JSONObject().put("n", it.first).put("u", it.second)) } }.toString())
    }
    fun add(name: String, url: String) = save(items.value.filter { it.second != url } + (name to url))
    fun remove(url: String) = save(items.value.filter { it.second != url })
}

// ---------- التنزيلات ----------
class DlTask(val id: String, val url: String, val mode: Int, val quality: Int, val playlist: Boolean) {
    /** 0 فيديو MP4، 1 صوت MP3، 2 الصوت الأصلي (M4A/Opus) */
    val title = MutableStateFlow(url)
    val progress = MutableStateFlow(0f)
    val eta = MutableStateFlow(0L)
    /** 0 بالانتظار، 1 يعمل، 2 اكتمل، 3 فشل، 4 أُلغي */
    val status = MutableStateFlow(0)
    val msg = MutableStateFlow("")
    var files: List<Uri> = emptyList()
}

object Downloads {
    val tasks = MutableStateFlow<List<DlTask>>(emptyList())
    fun add(ctx: Context, url: String, mode: Int, quality: Int, playlist: Boolean) {
        val t = DlTask("dl" + System.nanoTime(), url.trim(), mode, quality, playlist)
        tasks.value = tasks.value + t
        val i = Intent(ctx, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }
    fun cancel(t: DlTask) {
        if (t.status.value == 1) runCatching { YoutubeDL.getInstance().destroyProcessById(t.id) }
        t.status.value = 4
    }
    fun retry(ctx: Context, t: DlTask) { tasks.value = tasks.value - t; add(ctx, t.url, t.mode, t.quality, t.playlist) }
    fun clearFinished() { tasks.value = tasks.value.filter { it.status.value <= 1 } }
    fun active() = tasks.value.count { it.status.value <= 1 }
}

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("dl", trS("التنزيلات", "Downloads"), NotificationManager.IMPORTANCE_LOW))
        val n = note(trS("تجهيز التنزيل…", "Preparing download…"), -1f)
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(7, n)
        if (job?.isActive != true) job = scope.launch {
            while (true) {
                val t = Downloads.tasks.value.firstOrNull { it.status.value == 0 } ?: break
                run(t)
            }
            Library.scan(applicationContext)
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
            val done = Downloads.tasks.value.count { it.status.value == 2 }
            nm.notify(8, NotificationCompat.Builder(this@DownloadService, "dl").setSmallIcon(R.drawable.ic_note)
                .setContentTitle(trS("اكتملت التنزيلات", "Downloads finished")).setContentText(trS("$done ملف جاهز في المكتبة", "$done file(s) ready in your library"))
                .setContentIntent(openApp()).setAutoCancel(true).build())
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun openApp() = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("open_downloads", true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun note(text: String, p: Float): Notification = NotificationCompat.Builder(this, "dl")
        .setSmallIcon(R.drawable.ic_note).setContentTitle(trS("صدى — تنزيل", "Sada — downloading")).setContentText(text)
        .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(openApp())
        .setProgress(100, (p * 100).toInt().coerceAtLeast(0), p < 0).build()

    private fun run(t: DlTask) {
        t.status.value = 1
        if (!Ytdl.ensure(this)) { t.status.value = 3; t.msg.value = Ytdl.lastError ?: "engine"; return }
        val dir = File(getExternalFilesDir(null) ?: filesDir, "dl/${t.id}").apply { mkdirs() }
        val req = YoutubeDLRequest(t.url)
        req.addOption("-o", dir.absolutePath + "/%(title).90B [%(id)s].%(ext)s")
        req.addOption(if (t.playlist) "--yes-playlist" else "--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--embed-metadata")
        when (t.mode) {
            0 -> {
                val q = t.quality
                req.addOption("-f", if (q >= 4320) "bv*+ba/b" else "bv*[height<=$q]+ba/b[height<=$q]/b")
                req.addOption("--merge-output-format", "mp4")
            }
            1 -> { req.addOption("-x"); req.addOption("--audio-format", "mp3"); req.addOption("--audio-quality", "0"); req.addOption("--embed-thumbnail") }
            else -> req.addOption("-f", "bestaudio[ext=m4a]/bestaudio")
        }
        var last = 0L
        try {
            YoutubeDL.getInstance().execute(req, t.id) { p, eta, line ->
                if (p >= 0) t.progress.value = p / 100f
                t.eta.value = eta
                if ("Destination:" in line) t.title.value = line.substringAfter("Destination:").trim().substringAfterLast('/').substringBeforeLast(" [")
                if ("[Merger]" in line || "[ExtractAudio]" in line) t.msg.value = trS("دمج وتحويل…", "Merging / converting…")
                val now = System.currentTimeMillis()
                if (now - last > 800) { last = now; nm.notify(7, note(t.title.value + "  " + (p.toInt()) + "%", p / 100f)) }
            }
            if (t.status.value == 4) return
            val skip = setOf("part", "ytdl", "webp", "jpg", "png", "json", "temp")
            val outs = dir.listFiles()?.filter { it.isFile && it.extension.lowercase() !in skip }.orEmpty()
            t.files = outs.mapNotNull { export(it, t.mode == 0) }
            outs.firstOrNull()?.let { t.title.value = it.nameWithoutExtension.substringBeforeLast(" [") }
            t.progress.value = 1f
            t.status.value = if (t.files.isNotEmpty()) 2 else 3
            if (t.files.isEmpty()) t.msg.value = trS("لم يُنتَج أي ملف", "No file was produced")
        } catch (e: YoutubeDL.CanceledException) {
            t.status.value = 4
        } catch (e: Throwable) {
            if (t.status.value != 4) {
                t.status.value = 3
                t.msg.value = (e.message ?: e.toString()).lines().lastOrNull { it.contains("ERROR") } ?: (e.message ?: "").take(300)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** ينسخ الملف إلى مجلد عام (Movies/Sada أو Music/Sada) ليظهر في المكتبة وفي معرض الهاتف */
    private fun export(f: File, videoMode: Boolean): Uri? = runCatching {
        val ext = f.extension.lowercase()
        val isVideo = videoMode && ext in setOf("mp4", "mkv", "webm", "mov", "3gp", "flv")
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: if (isVideo) "video/mp4" else "audio/mpeg"
        if (Build.VERSION.SDK_INT >= 29) {
            val col = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) + "/Sada")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val u = contentResolver.insert(col, cv)!!
            contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            u
        } else {
            @Suppress("DEPRECATION")
            val d = File(Environment.getExternalStoragePublicDirectory(if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC), "Sada").apply { mkdirs() }
            val out = File(d, f.name); f.copyTo(out, overwrite = true)
            MediaScannerConnection.scanFile(this, arrayOf(out.absolutePath), arrayOf(mime), null)
            Uri.fromFile(out)
        }
    }.getOrNull()

    override fun onDestroy() { job?.cancel(); super.onDestroy() }
}

// =================== واجهة أونلاين ===================
/** تبقى نتائج البحث عند التنقل بين التبويبات */
object OnlineState {
    val q = mutableStateOf("")
    val loading = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val title = mutableStateOf("")
    val isList = mutableStateOf(false)
    val source = mutableStateOf("")
    val results = mutableStateListOf<OnlineItem>()
}

@Composable
fun OnlineTab(nav: Nav, act: MainActivity) {
    val ctx = LocalContext.current
    val engine by Ytdl.state.collectAsStateWithLifecycle()
    val saved by SavedLists.items.collectAsStateWithLifecycle()
    val tasks by Downloads.tasks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var q by OnlineState.q
    var loading by OnlineState.loading
    var error by OnlineState.error
    var title by OnlineState.title
    var isList by OnlineState.isList
    var source by OnlineState.source
    val results = OnlineState.results
    var pick by remember { mutableStateOf<OnlineItem?>(null) }
    var saveDlg by remember { mutableStateOf(false) }
    val shared by act.sharedLink
    val focus = androidx.compose.ui.platform.LocalFocusManager.current

    LaunchedEffect(Unit) { Ytdl.initAsync(ctx); SavedLists.load() }

    fun go(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        focus.clearFocus()
        loading = true; error = null; results.clear()
        OnlineScope.launch {
            try {
                val target = if (t.startsWith("http")) t else "ytsearch30:$t"
                val (name, items) = withContext(Dispatchers.IO) { Ytdl.list(ctx, target) }
                results.addAll(items); source = target
                isList = t.startsWith("http") && items.size > 1
                title = if (t.startsWith("http")) name.ifBlank { t } else trS("نتائج: ", "Results: ") + t
                if (t.startsWith("http") && items.size == 1) pick = items.first()
                if (items.isEmpty()) error = trS("لا نتائج", "No results")
            } catch (e: Throwable) {
                error = (e.message ?: e.toString()).lines().lastOrNull { it.contains("ERROR") } ?: (e.message ?: "").take(240)
            }
            loading = false
        }
    }
    LaunchedEffect(shared) { shared?.let { q = it; go(it); act.sharedLink.value = null } }

    LazyColumn(Modifier.fillMaxSize().imePadding()) {
        item {
            Header(tr("أونلاين", "Online")) {
                Box {
                    IconButton(onClick = { nav.push(Scr.Downloads) }) { Icon(Icons.Rounded.Download, null, tint = K.Paper) }
                    val n = tasks.count { it.status.value <= 1 }
                    if (n > 0) Text("$n", color = K.OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(K.Signal).padding(horizontal = 5.dp))
                }
                LangSwitch()
            }
        }
        item {
            Text(tr("يوتيوب بلا إعلانات · تنزيل من أي رابط", "Ad-free YouTube · download from any link"),
                color = K.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, shape = K.R,
                    placeholder = { Text(tr("ابحث أو الصق رابط قائمة/مقطع", "Search or paste a playlist/video link")) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = {
                            val cb = ctx.getSystemService(ClipboardManager::class.java)
                            cb.primaryClip?.getItemAt(0)?.text?.toString()?.let { q = it; go(it) }
                        }) { Icon(Icons.Rounded.ContentPaste, null) }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go(q) }))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(54.dp).clip(K.R).background(K.Signal).clickable { go(q) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowForward, null, tint = K.OnAccent)
                }
            }
        }
        if (engine == 1 || engine == 3) item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = K.Tape, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (engine == 3) tr("تحديث محرك yt-dlp…", "Updating the yt-dlp engine…") else tr("تجهيز محرك yt-dlp (أول مرة فقط)…", "Preparing the yt-dlp engine (first time only)…"), color = K.Muted, fontSize = 12.sp)
            }
        }
        if (engine == -1) item { Text(tr("تعذّر تجهيز المحرك: ", "Engine failed: ") + (Ytdl.lastError ?: ""), color = K.Tape, fontSize = 12.sp, modifier = Modifier.padding(16.dp)) }
        if (loading) item {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = K.Signal)
                Spacer(Modifier.height(8.dp))
                Text(tr("جارٍ الجلب…", "Fetching…"), color = K.Muted)
            }
        }
        error?.let { e -> item { PanelBox(Modifier.padding(12.dp)) { Text(e, color = K.Paper, fontSize = 13.sp) } } }

        if (results.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(title, color = K.Paper, fontWeight = FontWeight.Black, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(tr("${results.size} مقطع", "${results.size} items") + " · " + fmtTime(results.sumOf { it.duration }), style = Mono12)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SolidButton(tr("تشغيل الكل", "Play all"), Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f)) { Engine.playOnline(results.toList()); act.openPlayer.value = true }
                    SolidButton(tr("عشوائي", "Shuffle"), Icons.Rounded.Shuffle, color = K.Tape, modifier = Modifier.weight(1f)) { Engine.playOnline(results.toList(), shuffled = true); act.openPlayer.value = true }
                }
                if (isList) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(tr("تنزيل الكل MP3", "Download all MP3"), Icons.Rounded.Download, Modifier.weight(1f)) {
                        Downloads.add(ctx, source, 1, 720, true); nav.push(Scr.Downloads)
                    }
                    GhostButton(tr("حفظ القائمة", "Save list"), Icons.Rounded.BookmarkAdd, Modifier.weight(1f)) { saveDlg = true }
                }
            }
            itemsIndexed(results) { i, it -> OnlineRow(it) { pick = it } }
        } else if (!loading) {
            if (saved.isNotEmpty()) {
                item { Tape(tr("قوائمي المحفوظة", "Saved lists"), Modifier.padding(16.dp, 12.dp)) }
                items(saved, key = { it.second }) { (n, u) ->
                    Row(Modifier.fillMaxWidth().clickable { q = u; go(u) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(K.RS).background(K.Signal), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, tint = K.OnAccent) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n, color = K.Paper, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(u, color = K.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { SavedLists.remove(u) }) { Icon(Icons.Rounded.Close, null, tint = K.Muted) }
                    }
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Tape(tr("كيف أستخدمه؟", "How it works"))
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        tr("ابحث في يوتيوب مباشرة، أو الصق رابط قائمة تشغيل أو قناة أو مقطع.", "Search YouTube directly, or paste a playlist, channel or video link."),
                        tr("التشغيل يتم ببث مباشر للملف: بلا إعلانات، ويستمر مع إطفاء الشاشة.", "Playback streams the file directly: no ads, and it keeps going with the screen off."),
                        tr("من تطبيق يوتيوب أو X أو فيسبوك: مشاركة ← صدى، لتشغيل الرابط أو تنزيله.", "From YouTube, X or Facebook: Share → Sada to play or download the link."),
                        tr("التنزيل يدعم يوتيوب، X/تويتر، فيسبوك، إنستغرام، تيك توك، ساوند كلاود، فيميو وأكثر من 1000 موقع.", "Downloads work with YouTube, X/Twitter, Facebook, Instagram, TikTok, SoundCloud, Vimeo and 1000+ sites."),
                    ).forEachIndexed { i, s ->
                        Row(Modifier.padding(vertical = 5.dp)) {
                            Text("${i + 1}", color = K.Signal, fontWeight = FontWeight.Black, fontFamily = K.Mono, modifier = Modifier.width(24.dp))
                            Text(s, color = K.Paper, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SolidButton(tr("تنزيل من رابط", "Download from a link"), Icons.Rounded.Download, modifier = Modifier.fillMaxWidth()) { nav.push(Scr.Downloads) }
                    Spacer(Modifier.height(10.dp))
                    Text(tr("للاستخدام الشخصي: احترم حقوق أصحاب المحتوى وشروط المواقع.", "For personal use: respect creators' rights and each site's terms."), color = K.Muted, fontSize = 11.sp)
                }
            }
        }
    }
    pick?.let { OnlineOptions(it, act, nav) { pick = null } }
    if (saveDlg) NameDialog(tr("حفظ القائمة", "Save list"), title, { saveDlg = false }) { SavedLists.add(it, source) }
}

@Composable
fun OnlineRow(it: OnlineItem, onClick: () -> Unit) {
    val bmp = rememberNetThumb(it.thumb)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(K.RS).background(K.Panel2)) {
            ArtBox(bmp, it.title, 1000.dp, Modifier.fillMaxSize())
            if (it.duration > 0) Text(fmtTime(it.duration), color = Color.White, fontFamily = K.Mono, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000)).padding(horizontal = 4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(it.title, color = K.Paper, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Text(it.uploader, color = K.Muted, fontSize = 12.sp, maxLines = 1)
        }
        Icon(Icons.Rounded.MoreVert, null, tint = K.Muted)
    }
}

@Composable
fun OnlineOptions(it: OnlineItem, act: MainActivity, nav: Nav, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableIntStateOf(Store.getInt("dl_q", 720)) }
    SadaDialog(it.title, onDismiss) {
        @Composable fun opt(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, act0: () -> Unit) {
            Row(Modifier.fillMaxWidth().clip(K.RS).clickable { act0() }.padding(vertical = 11.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = K.Signal); Spacer(Modifier.width(14.dp)); Text(label, color = K.Paper, fontWeight = FontWeight.Bold)
            }
        }
        opt(Icons.Rounded.Headphones, tr("تشغيل صوت (في الخلفية)", "Play audio (background)")) { Engine.playOnline(listOf(it)); act.openPlayer.value = true; onDismiss() }
        opt(Icons.Rounded.SmartDisplay, tr("تشغيل فيديو", "Play video")) {
            VideoQueue.items = listOf(Uri.parse(it.url) to it.title); VideoQueue.index = 0
            ctx.startActivity(Intent(ctx, VideoActivity::class.java).putExtra("queue", true)); onDismiss()
        }
        opt(Icons.AutoMirrored.Rounded.QueueMusic, tr("إضافة إلى الطابور", "Add to queue")) { Engine.enqueueOnline(listOf(it)); onDismiss() }
        HorizontalDivider(color = K.Line, modifier = Modifier.padding(vertical = 6.dp))
        Text(tr("جودة الفيديو", "Video quality"), color = K.Muted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(360, 480, 720, 1080, 4320).forEach { v -> Chip(if (v == 4320) tr("الأعلى", "Best") else "${v}p", q == v) { q = v; Store.putInt("dl_q", v) } }
        }
        opt(Icons.Rounded.VideoFile, tr("تنزيل فيديو MP4", "Download video MP4")) { Downloads.add(ctx, it.url, 0, q, false); onDismiss(); nav.push(Scr.Downloads) }
        opt(Icons.Rounded.AudioFile, tr("تنزيل صوت MP3", "Download audio MP3")) { Downloads.add(ctx, it.url, 1, q, false); onDismiss(); nav.push(Scr.Downloads) }
        opt(Icons.Rounded.MusicNote, tr("تنزيل الصوت الأصلي (أسرع)", "Download original audio (faster)")) { Downloads.add(ctx, it.url, 2, q, false); onDismiss(); nav.push(Scr.Downloads) }
        opt(Icons.Rounded.Share, tr("مشاركة الرابط", "Share link")) {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it.url), null)); onDismiss()
        }
    }
}

@Composable
fun DownloadsScreen(nav: Nav, act: MainActivity) {
    val ctx = LocalContext.current
    val tasks by Downloads.tasks.collectAsStateWithLifecycle()
    val ver by Ytdl.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(Store.getInt("dl_mode", 0)) }
    var q by remember { mutableIntStateOf(Store.getInt("dl_q", 720)) }
    var playlist by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf<String?>(null) }
    val shared by act.sharedDownload
    LaunchedEffect(shared) { shared?.let { url = it; act.sharedDownload.value = null } }
    LaunchedEffect(Unit) { Ytdl.initAsync(ctx) }

    LazyColumn(Modifier.fillMaxSize().imePadding()) {
        item { Header(tr("التنزيلات", "Downloads"), nav.back) { IconButton(onClick = { Downloads.clearFinished() }) { Icon(Icons.Rounded.ClearAll, null, tint = K.Paper) } } }
        item {
            PanelBox(Modifier.padding(12.dp)) {
                Text(tr("رابط من يوتيوب، X، فيسبوك، إنستغرام، تيك توك…", "Link from YouTube, X, Facebook, Instagram, TikTok…"), color = K.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, shape = K.R,
                    placeholder = { Text("https://") },
                    trailingIcon = {
                        IconButton(onClick = { ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()?.let { url = it.trim() } }) {
                            Icon(Icons.Rounded.ContentPaste, null)
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(tr("فيديو MP4", "Video MP4"), tr("صوت MP3", "Audio MP3"), tr("صوت أصلي", "Original audio")).forEachIndexed { i, s ->
                        Chip(s, mode == i) { mode = i; Store.putInt("dl_mode", i) }
                    }
                }
                if (mode == 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    listOf(360, 480, 720, 1080, 4320).forEach { v -> Chip(if (v == 4320) tr("الأعلى", "Best") else "${v}p", q == v) { q = v; Store.putInt("dl_q", v) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Checkbox(playlist, { playlist = it })
                    Text(tr("تنزيل القائمة كاملة إن كان الرابط قائمة", "Download the whole playlist if the link is a list"), color = K.Paper, fontSize = 13.sp)
                }
                SolidButton(tr("تنزيل", "Download"), Icons.Rounded.Download, modifier = Modifier.fillMaxWidth()) {
                    if (url.startsWith("http")) { Downloads.add(ctx, url, mode, q, playlist); url = "" }
                }
            }
        }
        if (tasks.isEmpty()) item { Empty(tr("لا تنزيلات بعد", "No downloads yet"), tr("تُحفظ الملفات في Music/Sada و Movies/Sada", "Files are saved to Music/Sada and Movies/Sada")) }
        items(tasks.reversed(), key = { it.id }) { t -> TaskRow(t) }
        item {
            Column(Modifier.padding(16.dp)) {
                Tape(tr("المحرك", "Engine"))
                Spacer(Modifier.height(6.dp))
                Text(ver.ifBlank { "yt-dlp —" }, style = Mono12, color = K.Paper)
                Text(tr("إن توقف موقع عن العمل فحدّث المحرك.", "If a site stops working, update the engine."), color = K.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                GhostButton(updating ?: tr("تحديث المحرك", "Update engine"), Icons.Rounded.SystemUpdateAlt, Modifier.fillMaxWidth()) {
                    if (updating == null) {
                        updating = trS("جارٍ التحديث…", "Updating…")
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { Ytdl.update(ctx) }.getOrElse { it.message ?: "error" } }
                            updating = when (r) { "DONE" -> trS("تم التحديث ✓", "Updated ✓"); "ALREADY_UP_TO_DATE" -> trS("المحرك محدّث", "Already up to date"); else -> r }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TaskRow(t: DlTask) {
    val ctx = LocalContext.current
    val title by t.title.collectAsStateWithLifecycle()
    val p by t.progress.collectAsStateWithLifecycle()
    val st by t.status.collectAsStateWithLifecycle()
    val eta by t.eta.collectAsStateWithLifecycle()
    val msg by t.msg.collectAsStateWithLifecycle()
    val col = when (st) { 2 -> K.Tape; 3 -> Color(0xFFFF5A5A); 4 -> K.Muted; else -> K.Signal }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clip(K.R).background(K.Panel).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(when (t.mode) { 0 -> Icons.Rounded.VideoFile; else -> Icons.Rounded.AudioFile }, null, tint = col)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = K.Paper, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text(when (st) {
                    0 -> trS("بالانتظار", "Queued"); 1 -> String.format(Locale.US, "%.0f%%", p * 100) + (if (eta > 0) " · " + fmtTime(eta * 1000) else "") + (if (msg.isNotBlank()) " · $msg" else "")
                    2 -> trS("اكتمل · في المكتبة", "Done · in your library"); 3 -> trS("فشل: ", "Failed: ") + msg; else -> trS("أُلغي", "Canceled")
                }, color = if (st == 3) Color(0xFFFF8A8A) else K.Muted, fontSize = 12.sp, maxLines = 3)
            }
            when (st) {
                0, 1 -> IconButton(onClick = { Downloads.cancel(t) }) { Icon(Icons.Rounded.Close, null, tint = K.Muted) }
                2 -> IconButton(onClick = {
                    t.files.firstOrNull()?.let { u ->
                        if (t.mode == 0) { VideoQueue.items = listOf(u to title); VideoQueue.index = 0; ctx.startActivity(Intent(ctx, VideoActivity::class.java).putExtra("queue", true)) }
                        else Engine.playUri(u, title)
                    }
                }) { Icon(Icons.Rounded.PlayArrow, null, tint = K.Tape) }
                else -> IconButton(onClick = { Downloads.retry(ctx, t) }) { Icon(Icons.Rounded.Refresh, null, tint = K.Paper) }
            }
        }
        if (st <= 1) {
            Spacer(Modifier.height(8.dp))
            if (st == 1 && p > 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)), color = K.Signal, trackColor = K.Line)
            else LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)), color = K.Signal, trackColor = K.Line)
        }
    }
}
