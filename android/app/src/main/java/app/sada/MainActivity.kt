// صدى — النشاط الرئيسي: الصلاحيات، التنقل، المكتبة (أغاني، ألبومات، فنانون، فيديو، مجلدات، قوائم)، البحث
// برمجة: د. محمد توفيق
package app.sada

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

sealed class Scr {
    data object Home : Scr()
    data class AlbumS(val id: Long) : Scr()
    data class ArtistS(val name: String) : Scr()
    data class FolderS(val path: String) : Scr()
    data class PlaylistS(val id: Long) : Scr()
    data class Smart(val kind: Int) : Scr()      // 0 مفضلة، 1 الأكثر، 2 الأخيرة، 3 المضافة حديثاً
    data object Search : Scr()
    data object Effects : Scr()
    data object Settings : Scr()
    data object About : Scr()
    data object Downloads : Scr()
    data object FolderList : Scr()
}

/** طابور الفيديو المُمرَّر إلى مشغّل الفيديو */
object VideoQueue {
    var items: List<Pair<Uri, String>> = emptyList()
    var index = 0
}

class MainActivity : ComponentActivity() {
    val openPlayer = mutableStateOf(false)
    val sharedLink = mutableStateOf<String?>(null)
    val sharedDownload = mutableStateOf<String?>(null)
    val goTab = mutableStateOf<Int?>(null)
    val goScr = mutableStateOf<Scr?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0), navigationBarStyle = SystemBarStyle.dark(0))
        super.onCreate(savedInstanceState)
        Engine.connect(applicationContext)
        handle(intent)
        setContent {
            val ar by Store.arabic.collectAsStateWithLifecycle()
            CompositionLocalProvider(
                LocalArabic provides ar,
                LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                val light = Themes.current.value.light
                SideEffect {
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
                    }
                }
                SadaTheme { Root(this) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("open_player", false)) openPlayer.value = true
        if (i.getBooleanExtra("open_downloads", false)) { goTab.value = 2; goScr.value = Scr.Downloads }
        // مشاركة رابط من يوتيوب أو X أو فيسبوك… ← قسم أونلاين
        if (i.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            Regex("https?://\\S+").find(text)?.value?.let { link ->
                openPlayer.value = false; goTab.value = 2; sharedLink.value = link; sharedDownload.value = link
            }
        }
        if (i.action == Intent.ACTION_VIEW && i.data != null) {
            val u = i.data!!
            val mime = i.type ?: contentResolver.getType(u) ?: ""
            if (mime.startsWith("video")) {
                startActivity(Intent(this, VideoActivity::class.java).setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } else {
                Engine.playUri(u, displayName(this, u))
                openPlayer.value = true
            }
        }
    }

    fun rescan() = lifecycleScope.launch { Library.scan(applicationContext) }
}

fun displayName(ctx: Context, u: Uri): String? = runCatching {
    ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
    }
}.getOrNull() ?: u.lastPathSegment

fun neededPerms(): Array<String> = if (Build.VERSION.SDK_INT >= 33)
    arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

fun hasMediaPerm(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= 33)
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
else ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

fun playVideos(ctx: Context, list: List<Video>, index: Int) {
    VideoQueue.items = list.map { it.uri to it.title }
    VideoQueue.index = index
    ctx.startActivity(Intent(ctx, VideoActivity::class.java).putExtra("queue", true))
}

// =====================================================================
@Composable
fun Root(act: MainActivity) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasMediaPerm(ctx)) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasMediaPerm(ctx)
    }
    LaunchedEffect(granted) { if (granted && !Library.loaded.value) act.rescan() else if (!granted) perm.launch(neededPerms()) }

    var welcome by remember { mutableStateOf(!Store.getBool("welcome_done", false)) }
    if (welcome) { Welcome { Store.putBool("welcome_done", true); welcome = false; if (!granted) perm.launch(neededPerms()) }; return }
    if (!granted) { PermissionGate { perm.launch(neededPerms()) }; return }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val stack = remember { mutableStateListOf<Scr>(Scr.Home) }
    var showPlayer by act.openPlayer
    val current by Engine.current.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf<List<Track>?>(null) }
    var addTo by remember { mutableStateOf<List<Track>?>(null) }

    fun push(s: Scr) { stack.add(s) }
    val goTab by act.goTab
    LaunchedEffect(goTab) {
        goTab?.let { tab = it; stack.clear(); stack.add(Scr.Home); act.goScr.value?.let { s -> stack.add(s) }; act.goTab.value = null; act.goScr.value = null }
    }
    BackHandler(enabled = stack.size > 1 || showPlayer) { if (showPlayer) showPlayer = false else stack.removeAt(stack.lastIndex) }

    val nav = Nav(push = ::push, back = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }, options = { options = it }, addTo = { addTo = it })

    Box(Modifier.fillMaxSize().background(K.Ink)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).statusBarsPadding()) {
                when (val s = stack.last()) {
                    Scr.Home -> when (tab) {
                        0 -> MusicTab(nav)
                        1 -> VideosTab(nav)
                        2 -> OnlineTab(nav, act)
                        3 -> PlaylistsTab(nav)
                        else -> MoreTab(nav, act)
                    }
                    is Scr.AlbumS -> AlbumScreen(s.id, nav)
                    is Scr.ArtistS -> ArtistScreen(s.name, nav)
                    is Scr.FolderS -> FolderScreen(s.path, nav)
                    is Scr.PlaylistS -> PlaylistScreen(s.id, nav)
                    is Scr.Smart -> SmartScreen(s.kind, nav)
                    Scr.Search -> SearchScreen(nav)
                    Scr.Effects -> EffectsScreen(nav)
                    Scr.Settings -> SettingsScreen(nav, act)
                    Scr.About -> AboutScreen(nav)
                    Scr.Downloads -> DownloadsScreen(nav, act)
                    Scr.FolderList -> FoldersTab(nav)
                }
            }
            if (current != null) MiniPlayer { showPlayer = true }
            BottomBar(tab) { tab = it; stack.clear(); stack.add(Scr.Home) }
        }
        AnimatedVisibility(showPlayer, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            NowPlaying(nav, onClose = { showPlayer = false })
        }
    }
    options?.let { l -> TrackOptions(l, nav, onDismiss = { options = null }) }
    addTo?.let { l -> AddToPlaylistDialog(l) { addTo = null } }
}

class Nav(val push: (Scr) -> Unit, val back: () -> Unit, val options: (List<Track>) -> Unit, val addTo: (List<Track>) -> Unit)

@Composable
fun PermissionGate(onGrant: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(K.Ink).systemBarsPadding().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text(tr("صدى", "SADA"), fontSize = 84.sp, fontWeight = FontWeight.Black, color = K.Paper, lineHeight = 84.sp)
        Box(Modifier.width(120.dp).height(8.dp).background(K.Signal))
        Spacer(Modifier.height(24.dp))
        Text(tr("نحتاج إذن الوصول إلى ملفات الصوت والفيديو لعرض مكتبتك. لا يغادر أي ملف جهازك.",
            "Sada needs access to your audio and video files to build your library. Nothing leaves your phone."),
            color = K.Paper, fontSize = 17.sp)
        Spacer(Modifier.height(24.dp))
        SolidButton(tr("منح الإذن", "Grant access"), Icons.Rounded.LockOpen, modifier = Modifier.fillMaxWidth(), onClick = onGrant)
        Spacer(Modifier.height(10.dp))
        GhostButton(tr("فتح إعدادات التطبيق", "Open app settings"), Icons.Rounded.Settings, Modifier.fillMaxWidth()) {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }
    }
}

@Composable
fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf(
        Triple(Icons.Rounded.LibraryMusic, tr("موسيقى", "Music"), 0),
        Triple(Icons.Rounded.VideoLibrary, tr("فيديو", "Video"), 1),
        Triple(Icons.Rounded.SmartDisplay, tr("أونلاين", "Online"), 2),
        Triple(Icons.Rounded.QueueMusic, tr("قوائم", "Playlists"), 3),
        Triple(Icons.Rounded.GridView, tr("المزيد", "More"), 4),
    )
    Row(Modifier.fillMaxWidth().background(K.Ink).border(1.dp, K.Line).navigationBarsPadding()) {
        items.forEach { (ic, label, i) ->
            val sel = tab == i
            Column(
                Modifier.weight(1f).clickable { onTab(i) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.width(28.dp).height(3.dp).background(if (sel) K.Signal else Color.Transparent))
                Spacer(Modifier.height(5.dp))
                Icon(ic, null, tint = if (sel) K.Signal else K.Muted)
                Text(label, fontSize = 11.sp, color = if (sel) K.Paper else K.Muted, fontWeight = if (sel) FontWeight.Black else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val item by Engine.current.collectAsStateWithLifecycle()
    val playing by Engine.playing.collectAsStateWithLifecycle()
    val pos by Engine.position.collectAsStateWithLifecycle()
    val dur by Engine.duration.collectAsStateWithLifecycle()
    val t = item?.let { Library.byId(it.trackId()) }
    Column(Modifier.fillMaxWidth().background(K.Panel2).clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(K.Line)) {
            Box(Modifier.fillMaxWidth(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f).fillMaxHeight().background(K.Signal))
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t != null) Art(t, 44.dp, px = 128)
            else ArtBox(rememberNetThumb(item?.mediaMetadata?.artworkUri?.toString()?.takeIf { it.startsWith("http") }), item?.mediaMetadata?.title?.toString() ?: "", 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item?.mediaMetadata?.title?.toString() ?: "", color = K.Paper, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item?.mediaMetadata?.artist?.toString() ?: "", color = K.Muted, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = { Engine.prev() }) { Icon(Icons.Rounded.SkipPrevious, null, tint = K.Paper) }
            Box(Modifier.size(44.dp).clip(CircleShape).background(K.Signal).clickable { Engine.toggle() }, contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = K.OnAccent)
            }
            IconButton(onClick = { Engine.next() }) { Icon(Icons.Rounded.SkipNext, null, tint = K.Paper) }
        }
    }
}

@Composable
fun Header(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = K.Paper) }
        else Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Black, color = K.Paper, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
fun PlayBar(list: List<Track>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SolidButton(tr("تشغيل", "Play"), Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f)) { Engine.playTracks(list) }
        SolidButton(tr("عشوائي", "Shuffle"), Icons.Rounded.Shuffle, color = K.Tape, modifier = Modifier.weight(1f)) { Engine.playTracks(list, shuffled = true) }
    }
}

// =================== تبويب الموسيقى ===================
@Composable
fun MusicTab(nav: Nav) {
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    val videos by Library.videos.collectAsStateWithLifecycle()
    val loading by Library.loading.collectAsStateWithLifecycle()
    val favs by Store.favorites.collectAsStateWithLifecycle()
    val current by Engine.current.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableIntStateOf(Store.getInt("music_view", 0)) }
    var sort by rememberSaveable { mutableIntStateOf(Store.getInt("sort_songs", 0)) }
    val albums = remember(tracks) { Library.albums(tracks) }
    val artists = remember(tracks) { Library.artists(tracks) }
    val sorted = remember(tracks, sort) {
        when (sort) {
            1 -> tracks.sortedBy { it.artist.lowercase() }
            2 -> tracks.sortedByDescending { it.added }
            3 -> tracks.sortedByDescending { it.duration }
            4 -> tracks.sortedBy { it.album.lowercase() }
            else -> tracks
        }
    }
    val curId = current?.trackId() ?: -1

    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(span = { GridItemSpan(2) }) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("صدى", "SADA"), fontSize = 52.sp, fontWeight = FontWeight.Black, color = K.Paper, lineHeight = 52.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { nav.push(Scr.Search) }) { Icon(Icons.Rounded.Search, null, tint = K.Paper) }
                    LangSwitch()
                }
                Box(Modifier.padding(start = 16.dp).width(90.dp).height(6.dp).background(K.Signal))
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    BigNum(tracks.size, tr("أغنية", "songs"), K.Signal)
                    BigNum(albums.size, tr("ألبوم", "albums"))
                    BigNum(artists.size, tr("فنان", "artists"))
                    BigNum(videos.size, tr("فيديو", "videos"), K.Tape)
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = K.Signal, trackColor = K.Line)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(tr("الأغاني", "Songs"), tr("الألبومات", "Albums"), tr("الفنانون", "Artists"), tr("المفضلة", "Favorites")).forEachIndexed { i, s ->
                        Chip(s, view == i) { view = i; Store.putInt("music_view", i) }
                    }
                    Chip(tr("المجلدات", "Folders"), false) { nav.push(Scr.FolderList) }
                }
            }
        }
        when (view) {
            0, 3 -> {
                val list = if (view == 3) sorted.filter { it.id in favs } else sorted
                item(span = { GridItemSpan(2) }) {
                    Column {
                        PlayBar(list)
                        if (view == 0) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Sort, null, tint = K.Muted)
                            listOf(tr("العنوان", "Title"), tr("الفنان", "Artist"), tr("الأحدث", "Newest"), tr("الأطول", "Longest"), tr("الألبوم", "Album")).forEachIndexed { i, s ->
                                Text(s, color = if (sort == i) K.Tape else K.Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                    modifier = Modifier.clickable { sort = i; Store.putInt("sort_songs", i) }.padding(6.dp))
                            }
                        }
                        if (list.isEmpty() && !loading) Empty(tr("لا توجد أغانٍ هنا", "Nothing here yet"),
                            if (view == 3) tr("اضغط القلب في شاشة التشغيل لإضافة المفضلة", "Tap the heart on the player to add favorites") else "")
                    }
                }
                itemsIndexedSpan(list) { i, t ->
                    TrackRow(t, i + 1, t.id == curId, onClick = { Engine.playTracks(list, i) }, onMore = { nav.options(listOf(t)) })
                }
            }
            1 -> items(albums, key = { it.id }) { a -> AlbumCard(a) { nav.push(Scr.AlbumS(a.id)) } }
            2 -> itemsIndexedSpan(artists) { _, a ->
                Row(Modifier.fillMaxWidth().clickable { nav.push(Scr.ArtistS(a.name)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(a.tracks.first(), 54.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, color = K.Paper, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(tr("${a.albums} ألبوم · ${a.tracks.size} أغنية", "${a.albums} albums · ${a.tracks.size} songs"), color = K.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

fun <T> androidx.compose.foundation.lazy.grid.LazyGridScope.itemsIndexedSpan(list: List<T>, content: @Composable (Int, T) -> Unit) {
    items(list.size, span = { GridItemSpan(maxLineSpan) }) { i -> content(i, list[i]) }
}

@Composable
fun LangSwitch() {
    val ar = LocalArabic.current
    Text(if (ar) "EN" else "ع", color = K.OnAccent, fontWeight = FontWeight.Black,
        modifier = Modifier.padding(end = 8.dp).clip(K.RS).background(K.Tape).clickable { Store.setArabic(!ar) }.padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
fun AlbumCard(a: Album, onClick: () -> Unit) {
    Column(Modifier.padding(8.dp).clickable(onClick = onClick)) {
        BoxWithConstraints { Art(a.tracks.first(), maxWidth, px = 400) }
        Spacer(Modifier.height(6.dp))
        Text(a.name, color = K.Paper, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(a.artist.ifBlank { "—" } + " · " + a.tracks.size, color = K.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun TrackListScreen(title: String, sub: String, list: List<Track>, nav: Nav, numbered: Boolean = true, headArt: Track? = null, extra: @Composable () -> Unit = {}) {
    val current by Engine.current.collectAsStateWithLifecycle()
    val curId = current?.trackId() ?: -1
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header(title, nav.back) { IconButton(onClick = { nav.addTo(list) }) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = K.Paper) } } }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                if (headArt != null) { Art(headArt, 120.dp, px = 400); Spacer(Modifier.width(14.dp)) }
                Column {
                    Text(list.size.toString(), fontSize = 54.sp, fontWeight = FontWeight.Black, color = K.Signal, fontFamily = K.Mono, lineHeight = 54.sp)
                    Text(sub, color = K.Muted, fontSize = 13.sp)
                    Text(fmtTime(list.sumOf { it.duration }), color = K.Paper, fontFamily = K.Mono, fontSize = 13.sp)
                }
            }
        }
        item { extra() }
        item { PlayBar(list) }
        itemsIndexed(list) { i, t ->
            TrackRow(t, if (numbered) i + 1 else null, t.id == curId, onClick = { Engine.playTracks(list, i) }, onMore = { nav.options(listOf(t)) })
        }
        if (list.isEmpty()) item { Empty(tr("القائمة فارغة", "Empty")) }
    }
}

@Composable
fun AlbumScreen(id: Long, nav: Nav) {
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    val a = remember(tracks, id) { Library.albums(tracks.filter { it.albumId == id }).firstOrNull() } ?: return Empty("—")
    TrackListScreen(a.name, a.artist, a.tracks, nav, headArt = a.tracks.first())
}

@Composable
fun ArtistScreen(name: String, nav: Nav) {
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    val a = remember(tracks, name) { Library.artists(tracks).firstOrNull { it.name == name } } ?: return Empty("—")
    TrackListScreen(a.name, tr("${a.albums} ألبوم", "${a.albums} albums"), a.tracks.sortedWith(compareBy({ it.album }, { it.trackNo })), nav, headArt = a.tracks.first())
}

// =================== تبويب الفيديو ===================
@Composable
fun VideosTab(nav: Nav) {
    val videos by Library.videos.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var sort by rememberSaveable { mutableIntStateOf(Store.getInt("sort_vid", 0)) }
    var grid by rememberSaveable { mutableStateOf(Store.getBool("vid_grid", false)) }
    val list = remember(videos, sort) {
        when (sort) { 1 -> videos.sortedBy { it.title.lowercase() }; 2 -> videos.sortedByDescending { it.size }; 3 -> videos.sortedByDescending { it.duration }; else -> videos }
    }
    LazyVerticalGrid(GridCells.Fixed(if (grid) 2 else 1), Modifier.fillMaxSize()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Header(tr("الفيديو", "Video")) {
                    IconButton(onClick = { grid = !grid; Store.putBool("vid_grid", grid) }) { Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, null, tint = K.Paper) }
                    LangSwitch()
                }
                VideoAccessBanner()
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                    Text(videos.size.toString(), fontSize = 54.sp, fontWeight = FontWeight.Black, color = K.Tape, fontFamily = K.Mono, lineHeight = 54.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(fmtSize(videos.sumOf { it.size }) + " · " + fmtTime(videos.sumOf { it.duration }), color = K.Muted, fontFamily = K.Mono, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(tr("الأحدث", "Newest"), tr("الاسم", "Name"), tr("الحجم", "Size"), tr("المدة", "Length")).forEachIndexed { i, s ->
                        Chip(s, sort == i) { sort = i; Store.putInt("sort_vid", i) }
                    }
                }
                if (videos.isEmpty()) Empty(tr("لا توجد مقاطع فيديو", "No videos found"))
            }
        }
        items(list.size) { i -> VideoRow(list[i], grid) { playVideos(ctx, list, i) } }
    }
}

fun resLabel(w: Int, h: Int): String {
    val m = minOf(w, h)
    return when { m >= 2160 -> "4K"; m >= 1440 -> "2K"; m >= 1080 -> "1080p"; m >= 720 -> "720p"; m >= 480 -> "480p"; m > 0 -> "${m}p"; else -> "" }
}

@Composable
fun VideoRow(v: Video, grid: Boolean, onClick: () -> Unit) {
    val bmp = rememberThumb(v.uri, 320)
    val resume = remember(v.uri) { Store.resumePos(v.uri.toString()) }
    val thumb: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(K.Panel2)) {
            ArtBox(bmp, v.title, 1000.dp, Modifier.fillMaxSize())
            val r = resLabel(v.width, v.height)
            if (r.isNotBlank()) Text(r, color = K.OnAccent, fontWeight = FontWeight.Black, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart).background(K.Tape).padding(horizontal = 4.dp, vertical = 1.dp))
            Text(fmtTime(v.duration), color = K.Paper, fontFamily = K.Mono, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomEnd).background(K.Ink).padding(horizontal = 4.dp, vertical = 1.dp))
            if (resume > 0 && v.duration > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth((resume.toFloat() / v.duration).coerceIn(0f, 1f)).height(3.dp).background(K.Signal))
        }
    }
    if (grid) Column(Modifier.padding(6.dp).clickable(onClick = onClick)) {
        thumb(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Text(v.title, color = K.Paper, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    } else Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        thumb(Modifier.width(136.dp).aspectRatio(16f / 9f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, color = K.Paper, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Text(fmtSize(v.size) + " · " + v.mime.substringAfter('/').uppercase() + (if (v.width > 0) " · ${v.width}×${v.height}" else ""), color = K.Muted, fontSize = 11.sp, fontFamily = K.Mono)
        }
    }
}

// =================== تبويب المجلدات ===================
@Composable
fun FoldersTab(nav: Nav) {
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    val videos by Library.videos.collectAsStateWithLifecycle()
    val folders = remember(tracks, videos) { Library.folders(tracks, videos) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header(tr("المجلدات", "Folders"), nav.back) { LangSwitch() } }
        item {
            Text(folders.size.toString(), fontSize = 54.sp, fontWeight = FontWeight.Black, color = K.Paper, fontFamily = K.Mono, modifier = Modifier.padding(horizontal = 16.dp), lineHeight = 54.sp)
        }
        items(folders, key = { it.path }) { f ->
            Row(Modifier.fillMaxWidth().clickable { nav.push(Scr.FolderS(f.path)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(K.RS).background(if (f.videos.isNotEmpty() && f.tracks.isEmpty()) K.Tape else K.Signal), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Folder, null, tint = K.OnAccent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = K.Paper, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(f.path, color = K.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (f.tracks.isNotEmpty()) Text("♪ ${f.tracks.size}", color = K.Signal, fontFamily = K.Mono, fontSize = 12.sp)
                    if (f.videos.isNotEmpty()) Text("▶ ${f.videos.size}", color = K.Tape, fontFamily = K.Mono, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun FolderScreen(path: String, nav: Nav) {
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    val videos by Library.videos.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val t = remember(tracks, path) { tracks.filter { it.folder == path } }
    val v = remember(videos, path) { videos.filter { it.folder == path } }
    if (v.isEmpty()) { TrackListScreen(path.substringAfterLast('/'), path, t, nav); return }
    val current by Engine.current.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header(path.substringAfterLast('/'), nav.back) }
        item { Text(path, color = K.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        item { Tape(tr("فيديو", "Video") + " · ${v.size}", Modifier.padding(16.dp, 12.dp)) }
        items(v.size) { i -> VideoRow(v[i], false) { playVideos(ctx, v, i) } }
        if (t.isNotEmpty()) {
            item { Tape(tr("صوت", "Audio") + " · ${t.size}", Modifier.padding(16.dp, 12.dp)) }
            item { PlayBar(t) }
            itemsIndexed(t) { i, tr0 -> TrackRow(tr0, i + 1, tr0.id == current?.trackId(), { Engine.playTracks(t, i) }, { nav.options(listOf(tr0)) }) }
        }
    }
}

// =================== تبويب القوائم ===================
@Composable
fun PlaylistsTab(nav: Nav) {
    val pls by Store.playlists.collectAsStateWithLifecycle()
    val favs by Store.favorites.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header(tr("القوائم", "Playlists")) { IconButton(onClick = { create = true }) { Icon(Icons.Rounded.Add, null, tint = K.Signal) }; LangSwitch() } }
        item { Tape(tr("قوائم ذكية", "Smart lists"), Modifier.padding(16.dp, 8.dp)) }
        item {
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmartTile(tr("المفضلة", "Favorites"), favs.size.toString(), K.Signal, Icons.Rounded.Favorite, Modifier.weight(1f)) { nav.push(Scr.Smart(0)) }
                    SmartTile(tr("الأكثر تشغيلاً", "Most played"), "50", K.Tape, Icons.Rounded.Whatshot, Modifier.weight(1f)) { nav.push(Scr.Smart(1)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmartTile(tr("شُغّلت مؤخراً", "Recently played"), "50", K.Paper, Icons.Rounded.History, Modifier.weight(1f)) { nav.push(Scr.Smart(2)) }
                    SmartTile(tr("أضيفت حديثاً", "Recently added"), "100", Color(0xFF3D7EFF), Icons.Rounded.NewReleases, Modifier.weight(1f)) { nav.push(Scr.Smart(3)) }
                }
            }
        }
        item { Tape(tr("قوائمي", "My playlists") + " · ${pls.size}", Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)) }
        if (pls.isEmpty()) item {
            Column(Modifier.padding(16.dp)) {
                Text(tr("لا توجد قوائم بعد.", "No playlists yet."), color = K.Muted)
                Spacer(Modifier.height(8.dp))
                SolidButton(tr("قائمة جديدة", "New playlist"), Icons.Rounded.Add) { create = true }
            }
        }
        items(pls, key = { it.id }) { p ->
            val first = p.trackIds.firstNotNullOfOrNull { Library.byId(it) }
            Row(Modifier.fillMaxWidth().clickable { nav.push(Scr.PlaylistS(p.id)) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(first ?: Track(0, Uri.EMPTY, p.name, "", "", 0, 0, 0, "", "", "", 0, 0, 0), 54.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = K.Paper, fontWeight = FontWeight.Bold)
                    Text(tr("${p.trackIds.size} أغنية", "${p.trackIds.size} songs"), color = K.Muted, fontSize = 12.sp)
                }
            }
        }
    }
    if (create) NameDialog(tr("قائمة جديدة", "New playlist"), "", { create = false }) { Store.createPlaylist(it) }
}

@Composable
fun SmartTile(title: String, count: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(K.R).background(color).clickable(onClick = onClick).padding(12.dp)) {
        Icon(icon, null, tint = K.OnAccent)
        Spacer(Modifier.height(18.dp))
        Text(count, color = K.OnAccent, fontWeight = FontWeight.Black, fontSize = 26.sp, fontFamily = K.Mono)
        Text(title, color = K.OnAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun SmartScreen(kind: Int, nav: Nav) {
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    val favs by Store.favorites.collectAsStateWithLifecycle()
    val stats by Store.stats.collectAsStateWithLifecycle()
    val list = remember(tracks, favs, stats, kind) {
        when (kind) {
            0 -> tracks.filter { it.id in favs }
            1 -> tracks.filter { (stats[it.id]?.plays ?: 0) > 0 }.sortedByDescending { stats[it.id]!!.plays }.take(50)
            2 -> tracks.filter { stats[it.id] != null }.sortedByDescending { stats[it.id]!!.last }.take(50)
            else -> tracks.sortedByDescending { it.added }.take(100)
        }
    }
    val title = when (kind) { 0 -> tr("المفضلة", "Favorites"); 1 -> tr("الأكثر تشغيلاً", "Most played"); 2 -> tr("شُغّلت مؤخراً", "Recently played"); else -> tr("أضيفت حديثاً", "Recently added") }
    TrackListScreen(title, tr("قائمة ذكية", "Smart list"), list, nav)
}

@Composable
fun PlaylistScreen(id: Long, nav: Nav) {
    val pls by Store.playlists.collectAsStateWithLifecycle()
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    val p = pls.firstOrNull { it.id == id } ?: return
    val list = remember(p, tracks) { p.trackIds.mapNotNull { Library.byId(it) } }
    var rename by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    val current by Engine.current.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Header(p.name, nav.back) {
                IconButton(onClick = { edit = !edit }) { Icon(Icons.Rounded.Edit, null, tint = if (edit) K.Signal else K.Paper) }
                IconButton(onClick = { rename = true }) { Icon(Icons.Rounded.DriveFileRenameOutline, null, tint = K.Paper) }
                IconButton(onClick = { Store.deletePlaylist(p.id); nav.back() }) { Icon(Icons.Rounded.Delete, null, tint = K.Paper) }
            }
        }
        item {
            Text(list.size.toString(), fontSize = 54.sp, fontWeight = FontWeight.Black, color = K.Signal, fontFamily = K.Mono, modifier = Modifier.padding(horizontal = 16.dp), lineHeight = 54.sp)
        }
        item { PlayBar(list) }
        itemsIndexed(list) { i, t ->
            if (edit) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, color = K.Paper, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { Store.movePlaylistItem(p.id, i, i - 1) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, null, tint = K.Paper) }
                IconButton(onClick = { Store.movePlaylistItem(p.id, i, i + 1) }, enabled = i < list.size - 1) { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = K.Paper) }
                IconButton(onClick = { Store.removeFromPlaylist(p.id, t.id) }) { Icon(Icons.Rounded.Close, null, tint = K.Signal) }
            } else TrackRow(t, i + 1, t.id == current?.trackId(), { Engine.playTracks(list, i) }, { nav.options(listOf(t)) })
        }
        if (list.isEmpty()) item { Empty(tr("القائمة فارغة", "Empty playlist"), tr("أضف أغاني من قائمة خيارات أي أغنية", "Add songs from any song's menu")) }
    }
    if (rename) NameDialog(tr("إعادة تسمية", "Rename"), p.name, { rename = false }) { Store.renamePlaylist(p.id, it) }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    SadaDialog(title, onDismiss) {
        OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
        Spacer(Modifier.height(12.dp))
        SolidButton(tr("حفظ", "Save"), modifier = Modifier.fillMaxWidth()) { if (text.isNotBlank()) { onOk(text.trim()); onDismiss() } }
    }
}

@Composable
fun AddToPlaylistDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    val pls by Store.playlists.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    SadaDialog(tr("إضافة إلى قائمة", "Add to playlist"), onDismiss) {
        Text(tr("${tracks.size} أغنية", "${tracks.size} songs"), color = K.Muted)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 340.dp)) {
            items(pls, key = { it.id }) { p ->
                Text(p.name + "  ·  " + p.trackIds.size, color = K.Paper, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().clickable { Store.addToPlaylist(p.id, tracks.map { it.id }); onDismiss() }.padding(vertical = 12.dp))
            }
        }
        SolidButton(tr("قائمة جديدة", "New playlist"), Icons.Rounded.Add, modifier = Modifier.fillMaxWidth()) { create = true }
    }
    if (create) NameDialog(tr("قائمة جديدة", "New playlist"), "", { create = false }) { Store.createPlaylist(it, tracks.map { t -> t.id }); onDismiss() }
}

@Composable
fun TrackOptions(tracks: List<Track>, nav: Nav, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val t = tracks.first()
    val favs by Store.favorites.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf(false) }
    SadaDialog(t.title, onDismiss) {
        @Composable fun opt(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, act: () -> Unit) {
            Row(Modifier.fillMaxWidth().clickable { act() }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = K.Signal); Spacer(Modifier.width(14.dp)); Text(label, color = K.Paper, fontWeight = FontWeight.Bold)
            }
        }
        opt(Icons.Rounded.PlayArrow, tr("تشغيل الآن", "Play now")) { Engine.playTracks(tracks); onDismiss() }
        opt(Icons.Rounded.SkipNext, tr("تشغيل التالي", "Play next")) { Engine.playNext(tracks); onDismiss() }
        opt(Icons.AutoMirrored.Rounded.QueueMusic, tr("إضافة إلى الطابور", "Add to queue")) { Engine.enqueue(tracks); onDismiss() }
        opt(Icons.AutoMirrored.Rounded.PlaylistAdd, tr("إضافة إلى قائمة", "Add to playlist")) { onDismiss(); nav.addTo(tracks) }
        opt(if (t.id in favs) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (t.id in favs) tr("إزالة من المفضلة", "Remove favorite") else tr("إلى المفضلة", "Add to favorites")) { Store.toggleFav(t.id) }
        if (t.album.isNotBlank()) opt(Icons.Rounded.Album, tr("الذهاب إلى الألبوم", "Go to album")) { onDismiss(); nav.push(Scr.AlbumS(t.albumId)) }
        if (t.artist.isNotBlank()) opt(Icons.Rounded.Person, tr("الذهاب إلى الفنان", "Go to artist")) { onDismiss(); nav.push(Scr.ArtistS(t.artist)) }
        opt(Icons.Rounded.Share, tr("مشاركة الملف", "Share file")) {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(t.mime.ifBlank { "audio/*" }).putExtra(Intent.EXTRA_STREAM, t.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
            onDismiss()
        }
        opt(Icons.Rounded.Info, tr("التفاصيل", "Details")) { details = !details }
        if (details) Column(Modifier.background(K.Ink).padding(10.dp)) {
            listOf(
                tr("الصيغة", "Format") to extOf(t.mime, t.path) + " (" + t.mime + ")",
                tr("المدة", "Length") to fmtTime(t.duration),
                tr("الحجم", "Size") to fmtSize(t.size),
                tr("معدّل البت التقريبي", "Approx. bitrate") to (if (t.duration > 0) "${t.size * 8 / t.duration} kbps" else "—"),
                tr("السنة", "Year") to (if (t.year > 0) t.year.toString() else "—"),
                tr("المسار", "Path") to t.path,
            ).forEach { (k, v) -> Text("$k: $v", style = Mono12, color = K.Paper) }
        }
    }
}

// =================== البحث ===================
@Composable
fun SearchScreen(nav: Nav) {
    val tracks by Library.tracks.collectAsStateWithLifecycle()
    val videos by Library.videos.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    val ql = q.trim().lowercase()
    val ts = remember(ql, tracks) { if (ql.isEmpty()) emptyList() else tracks.filter { ql in it.title.lowercase() || ql in it.artist.lowercase() || ql in it.album.lowercase() } }
    val vs = remember(ql, videos) { if (ql.isEmpty()) emptyList() else videos.filter { ql in it.title.lowercase() } }
    val al = remember(ql, tracks) { if (ql.isEmpty()) emptyList() else Library.albums(tracks).filter { ql in it.name.lowercase() } }
    LazyColumn(Modifier.fillMaxSize().imePadding()) {
        item {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = K.Paper) }
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text(tr("ابحث عن أغنية، فنان، ألبوم، فيديو", "Search songs, artists, albums, videos")) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) })
            }
        }
        if (al.isNotEmpty()) {
            item { Tape(tr("ألبومات", "Albums"), Modifier.padding(16.dp, 8.dp)) }
            items(al.take(10)) { a ->
                Row(Modifier.fillMaxWidth().clickable { nav.push(Scr.AlbumS(a.id)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(a.tracks.first(), 44.dp); Spacer(Modifier.width(12.dp)); Text(a.name, color = K.Paper, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (ts.isNotEmpty()) {
            item { Tape(tr("أغانٍ", "Songs") + " · ${ts.size}", Modifier.padding(16.dp, 8.dp)) }
            itemsIndexed(ts.take(200)) { i, t -> TrackRow(t, null, false, { Engine.playTracks(ts, i) }, { nav.options(listOf(t)) }) }
        }
        if (vs.isNotEmpty()) {
            item { Tape(tr("فيديو", "Videos") + " · ${vs.size}", Modifier.padding(16.dp, 8.dp)) }
            items(vs.size) { i -> VideoRow(vs[i], false) { playVideos(ctx, vs, i) } }
        }
        if (ql.isNotEmpty() && ts.isEmpty() && vs.isEmpty() && al.isEmpty()) item { Empty(tr("لا نتائج", "No results")) }
    }
}


/** في أندرويد 14 قد يمنح المستخدم وصولاً «لبعض الفيديوهات فقط» — نعرض تنبيهاً لمنح الوصول الكامل */
@Composable
fun VideoAccessBanner() {
    if (Build.VERSION.SDK_INT < 33) return
    val ctx = LocalContext.current
    var full by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        full = ok
        if (ok) (ctx as? MainActivity)?.rescan()
        else ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
    }
    if (full) return
    Row(Modifier.fillMaxWidth().padding(12.dp).clip(K.R).background(K.Panel2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Info, null, tint = K.Tape)
        Spacer(Modifier.width(10.dp))
        Text(tr("الوصول إلى الفيديو محدود ببعض الملفات.", "Video access is limited to some files."), color = K.Paper, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(tr("السماح للكل", "Allow all"), color = K.OnAccent, fontWeight = FontWeight.Black,
            modifier = Modifier.clip(K.RS).background(K.Tape).clickable { launcher.launch(Manifest.permission.READ_MEDIA_VIDEO) }.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

// ---------- الترحيب: اختيار اللغة والألوان عند أول تشغيل ----------
@Composable
fun Welcome(onDone: () -> Unit) {
    val ar = LocalArabic.current
    Column(Modifier.fillMaxSize().background(K.Ink).systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(if (ar) "صدى" else "Sada", fontSize = 72.sp, fontWeight = FontWeight.Black, color = K.Paper, lineHeight = 76.sp)
        Box(Modifier.width(110.dp).height(8.dp).clip(RoundedCornerShape(50)).background(K.Signal))
        Spacer(Modifier.height(10.dp))
        Text(tr("موسيقى · فيديو · يوتيوب بلا إعلانات · تنزيل", "Music · Video · Ad-free YouTube · Downloads"), color = K.Muted)
        Spacer(Modifier.height(28.dp))
        Text("اللغة · Language", color = K.Paper, fontWeight = FontWeight.Black, fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LangTile("العربية", "عربي", ar, Modifier.weight(1f)) { Store.setArabic(true) }
            LangTile("English", "EN", !ar, Modifier.weight(1f)) { Store.setArabic(false) }
        }
        Spacer(Modifier.height(28.dp))
        Text(tr("الألوان", "Colours"), color = K.Paper, fontWeight = FontWeight.Black, fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        ThemeGrid()
        Spacer(Modifier.height(32.dp))
        SolidButton(tr("ابدأ", "Start"), Icons.AutoMirrored.Rounded.ArrowForward, modifier = Modifier.fillMaxWidth(), onClick = onDone)
        Spacer(Modifier.height(12.dp))
        Text(tr("برمجة ", "Built by ") + tr(Dev.NAME_AR, Dev.NAME_EN), color = K.Muted, fontSize = 12.sp)
    }
}

@Composable
fun LangTile(big: String, small: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(96.dp).clip(K.R).background(if (on) K.Signal else K.Panel).border(1.dp, if (on) K.Signal else K.Line, K.R)
        .clickable(onClick = onClick).padding(14.dp)) {
        Text(small, color = if (on) K.OnAccent else K.Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(big, color = if (on) K.OnAccent else K.Paper, fontWeight = FontWeight.Black, fontSize = 22.sp)
    }
}

@Composable
fun ThemeGrid() {
    val cur = Themes.current.value
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Themes.list.chunked(3).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { c, p ->
                    val i = r * 3 + c
                    Column(Modifier.weight(1f).clip(K.R).background(p.ink).border(if (p == cur) 3.dp else 1.dp, if (p == cur) p.signal else p.line, K.R)
                        .clickable { Themes.set(i) }.padding(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(p.signal))
                            Box(Modifier.size(22.dp).clip(CircleShape).background(p.tape))
                            Box(Modifier.size(22.dp).clip(CircleShape).background(p.panel2))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(if (LocalArabic.current) p.ar else p.en, color = p.paper, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
