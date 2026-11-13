// صدى — النواة: التطبيق، التخزين، الترجمة، المكتبة، الصور المصغّرة، القوائم، الكلمات المتزامنة
// برمجة: د. محمد توفيق
package app.sada

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ---------- المطوّر ----------
object Dev {
    const val NAME_AR = "د. محمد توفيق"
    const val NAME_EN = "Dr. Mohammed Tawfik"
    const val ROLE_AR = "قسم الأمن السيبراني · كلية تكنولوجيا المعلومات · جامعة عجلون الوطنية"
    const val ROLE_EN = "Department of Cyber Security · Faculty of IT · Ajloun National University"
    const val EMAIL = "kmkhol01@gmail.com"
    const val GITHUB = "https://github.com/kmkholm"
    const val SITE = "https://kmkholm.github.io"
    const val ORCID = "0009-0004-7622-5991"
}

class SadaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Themes.load()
    }
}

// ---------- الترجمة ----------
val LocalArabic = staticCompositionLocalOf { true }

@Composable
fun tr(ar: String, en: String): String = if (LocalArabic.current) ar else en
fun trS(ar: String, en: String): String = if (Store.arabic.value) ar else en

// ---------- التخزين ----------
object Store {
    private lateinit var p: SharedPreferences
    val arabic = MutableStateFlow(true)
    val favorites = MutableStateFlow<Set<Long>>(emptySet())
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val stats = MutableStateFlow<Map<Long, Stat>>(emptyMap())

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("sada", Context.MODE_PRIVATE)
        arabic.value = p.getBoolean("arabic", Locale.getDefault().language == "ar" || !p.contains("arabic"))
        favorites.value = p.getStringSet("fav", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        playlists.value = loadPlaylists()
        stats.value = loadStats()
    }

    fun setArabic(v: Boolean) { arabic.value = v; p.edit().putBoolean("arabic", v).apply() }

    fun toggleFav(id: Long) {
        val s = favorites.value.toMutableSet()
        if (!s.add(id)) s.remove(id)
        favorites.value = s
        p.edit().putStringSet("fav", s.map { it.toString() }.toSet()).apply()
    }

    // إعدادات عامة
    fun getInt(k: String, d: Int) = p.getInt(k, d)
    fun putInt(k: String, v: Int) = p.edit().putInt(k, v).apply()
    fun getFloat(k: String, d: Float) = p.getFloat(k, d)
    fun putFloat(k: String, v: Float) = p.edit().putFloat(k, v).apply()
    fun getBool(k: String, d: Boolean) = p.getBoolean(k, d)
    fun putBool(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    fun getStr(k: String): String? = p.getString(k, null)
    fun putStr(k: String, v: String?) = p.edit().putString(k, v).apply()

    // استئناف الفيديو من آخر موضع
    fun resumePos(key: String): Long = p.getLong("pos_" + key.hashCode(), 0L)
    fun saveResume(key: String, pos: Long) = p.edit().putLong("pos_" + key.hashCode(), pos).apply()

    // القوائم
    private fun loadPlaylists(): List<Playlist> = runCatching {
        val a = JSONArray(p.getString("playlists", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ids = o.getJSONArray("ids")
            Playlist(o.getLong("id"), o.getString("name"), (0 until ids.length()).map { ids.getLong(it) })
        }
    }.getOrDefault(emptyList())

    private fun savePlaylists(l: List<Playlist>) {
        playlists.value = l
        val a = JSONArray()
        l.forEach { pl -> a.put(JSONObject().put("id", pl.id).put("name", pl.name).put("ids", JSONArray(pl.trackIds))) }
        p.edit().putString("playlists", a.toString()).apply()
    }

    fun createPlaylist(name: String, ids: List<Long> = emptyList()): Long {
        val id = System.currentTimeMillis()
        savePlaylists(playlists.value + Playlist(id, name, ids))
        return id
    }
    fun renamePlaylist(id: Long, name: String) = savePlaylists(playlists.value.map { if (it.id == id) it.copy(name = name) else it })
    fun deletePlaylist(id: Long) = savePlaylists(playlists.value.filter { it.id != id })
    fun addToPlaylist(id: Long, tracks: List<Long>) = savePlaylists(playlists.value.map {
        if (it.id == id) it.copy(trackIds = (it.trackIds + tracks).distinct()) else it
    })
    fun removeFromPlaylist(id: Long, track: Long) = savePlaylists(playlists.value.map {
        if (it.id == id) it.copy(trackIds = it.trackIds - track) else it
    })
    fun movePlaylistItem(id: Long, from: Int, to: Int) = savePlaylists(playlists.value.map {
        if (it.id == id && from in it.trackIds.indices && to in it.trackIds.indices) {
            val m = it.trackIds.toMutableList(); val x = m.removeAt(from); m.add(to, x); it.copy(trackIds = m)
        } else it
    })

    // الإحصاءات: عدد مرات التشغيل وآخر تشغيل
    private fun loadStats(): Map<Long, Stat> = runCatching {
        val o = JSONObject(p.getString("stats", "{}")!!)
        o.keys().asSequence().associate { k ->
            val v = o.getJSONArray(k); k.toLong() to Stat(v.getInt(0), v.getLong(1))
        }
    }.getOrDefault(emptyMap())

    fun countPlay(id: Long) {
        if (id <= 0) return
        val m = stats.value.toMutableMap()
        val s = m[id]
        m[id] = Stat((s?.plays ?: 0) + 1, System.currentTimeMillis())
        stats.value = m
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k.toString(), JSONArray().put(v.plays).put(v.last)) }
        p.edit().putString("stats", o.toString()).apply()
    }

    // ملف الكلمات المرتبط بكل أغنية
    fun lyricsUri(id: Long): String? = p.getString("lrc_$id", null)
    fun setLyricsUri(id: Long, uri: String?) = p.edit().putString("lrc_$id", uri).apply()
}

data class Playlist(val id: Long, val name: String, val trackIds: List<Long>)
data class Stat(val plays: Int, val last: Long)

// ---------- نماذج المكتبة ----------
data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val size: Long,
    val folder: String,
    val path: String,
    val mime: String,
    val trackNo: Int,
    val year: Int,
    val added: Long,
)

data class Video(
    val id: Long,
    val uri: Uri,
    val title: String,
    val duration: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val folder: String,
    val mime: String,
    val added: Long,
)

data class Album(val id: Long, val name: String, val artist: String, val tracks: List<Track>)
data class Artist(val name: String, val tracks: List<Track>) { val albums get() = tracks.map { it.albumId }.distinct().size }
data class Folder(val path: String, val name: String, val tracks: List<Track>, val videos: List<Video>)

object Library {
    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val videos = MutableStateFlow<List<Video>>(emptyList())
    val loading = MutableStateFlow(false)
    val loaded = MutableStateFlow(false)

    fun byId(id: Long): Track? = tracks.value.firstOrNull { it.id == id }

    suspend fun scan(ctx: Context) = withContext(Dispatchers.IO) {
        loading.value = true
        runCatching { tracks.value = scanAudio(ctx) }
        runCatching { videos.value = scanVideo(ctx) }
        loading.value = false
        loaded.value = true
    }

    private fun scanAudio(ctx: Context): List<Track> {
        val col = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val minMs = Store.getInt("min_sec", 10) * 1000L
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val out = ArrayList<Track>()
        ctx.contentResolver.query(col, proj, null, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val dur = c.getLong(5)
                if (dur in 1 until minMs) continue
                val path = c.getString(7) ?: ""
                val folder = path.substringBeforeLast('/', "")
                val unknown = "<unknown>"
                out += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(col, id),
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(12) ?: "?",
                    artist = c.getString(2)?.takeIf { it != unknown && it.isNotBlank() } ?: "",
                    album = c.getString(3)?.takeIf { it != unknown } ?: "",
                    albumId = c.getLong(4),
                    duration = dur,
                    size = c.getLong(6),
                    folder = folder,
                    path = path,
                    mime = c.getString(8) ?: "",
                    trackNo = c.getInt(9) % 1000,
                    year = c.getInt(10),
                    added = c.getLong(11),
                )
            }
        }
        return out
    }

    private fun scanVideo(ctx: Context): List<Video> {
        val col = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATA, MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.DATE_ADDED,
        )
        val out = ArrayList<Video>()
        ctx.contentResolver.query(col, proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val path = c.getString(6) ?: ""
                out += Video(
                    id = id,
                    uri = ContentUris.withAppendedId(col, id),
                    title = (c.getString(1) ?: "?").substringBeforeLast('.'),
                    duration = c.getLong(2),
                    size = c.getLong(3),
                    width = c.getInt(4),
                    height = c.getInt(5),
                    folder = path.substringBeforeLast('/', ""),
                    mime = c.getString(7) ?: "",
                    added = c.getLong(8),
                )
            }
        }
        return out
    }

    fun albums(l: List<Track>): List<Album> = l.groupBy { it.albumId }.map { (id, t) ->
        Album(id, t.first().album.ifBlank { trS("ألبوم غير معروف", "Unknown album") },
            t.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key ?: "",
            t.sortedWith(compareBy({ it.trackNo }, { it.title })))
    }.sortedBy { it.name.lowercase() }

    fun artists(l: List<Track>): List<Artist> = l.groupBy { it.artist.ifBlank { trS("فنان غير معروف", "Unknown artist") } }
        .map { (n, t) -> Artist(n, t) }.sortedBy { it.name.lowercase() }

    fun folders(t: List<Track>, v: List<Video>): List<Folder> {
        val keys = (t.map { it.folder } + v.map { it.folder }).distinct()
        val tm = t.groupBy { it.folder }; val vm = v.groupBy { it.folder }
        return keys.map { k -> Folder(k, k.substringAfterLast('/').ifBlank { "/" }, tm[k].orEmpty(), vm[k].orEmpty()) }
            .sortedBy { it.name.lowercase() }
    }
}

// ---------- الصور المصغّرة (غلاف الألبوم وإطار الفيديو) ----------
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()

    fun albumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

    fun peek(key: String): Bitmap? = cache.get(key)

    suspend fun load(ctx: Context, uri: Uri, px: Int = 320): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$uri@$px"
        cache.get(key)?.let { return@withContext it }
        if (key in missing) return@withContext null
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
            else ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                ?: MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, ContentUris.parseId(uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
        }.getOrNull()
        if (bmp != null) cache.put(key, bmp) else synchronized(missing) { missing += key }
        bmp
    }

    // صورة الألبوم: من الأغنية نفسها أولاً ثم من مسار الألبوم
    suspend fun forTrack(ctx: Context, t: Track, px: Int = 320): Bitmap? =
        load(ctx, t.uri, px) ?: load(ctx, albumArtUri(t.albumId), px)

    // لون مهيمن من الغلاف لتلوين شاشة التشغيل
    fun dominant(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 16, 16, true)
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        for (x in 0 until 16) for (y in 0 until 16) {
            val c = s.getPixel(x, y)
            val rr = (c shr 16) and 0xFF; val gg = (c shr 8) and 0xFF; val bb = c and 0xFF
            val mx = maxOf(rr, gg, bb); val mn = minOf(rr, gg, bb)
            val w = 1 + (mx - mn) / 16   // تفضيل الألوان المشبعة
            r += rr * w; g += gg * w; bl += bb * w; n += w
        }
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
    }
}

// ---------- الكلمات المتزامنة (LRC) ----------
data class LyricLine(val time: Long, val text: String)

object Lrc {
    private val tag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<LyricLine> {
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LyricLine>()
        text.lineSequence().forEach { raw ->
            val times = tag.findAll(raw).toList()
            if (times.isEmpty()) return@forEach
            val body = raw.substring(times.last().range.last + 1).trim()
            times.forEach { m ->
                val mm = m.groupValues[1].toLong(); val ss = m.groupValues[2].toLong()
                val fr = m.groupValues[3]
                val ms = when (fr.length) { 0 -> 0L; 1 -> fr.toLong() * 100; 2 -> fr.toLong() * 10; else -> fr.take(3).toLong() }
                out += LyricLine(maxOf(0L, mm * 60000 + ss * 1000 + ms - offset), body)
            }
        }
        return out.sortedBy { it.time }
    }

    // كلمات بلا توقيت: تُعرض كنص عادي
    fun plain(text: String): List<LyricLine> = text.lines().map { LyricLine(-1, it.trim()) }

    fun indexAt(lines: List<LyricLine>, pos: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) { val m = (lo + hi) / 2; if (lines[m].time <= pos) { ans = m; lo = m + 1 } else hi = m - 1 }
        return ans
    }
}

// ---------- أدوات تنسيق ----------
fun fmtTime(ms: Long): String {
    if (ms < 0) return "--:--"
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, ss) else String.format(Locale.US, "%d:%02d", m, ss)
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    else -> String.format(Locale.US, "%d KB", b / 1024)
}

fun extOf(mime: String, path: String): String =
    path.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..5 } ?: mime.substringAfter('/').uppercase()
