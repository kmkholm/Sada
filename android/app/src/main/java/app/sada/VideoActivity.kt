// صدى — مشغّل الفيديو: إيماءات (سطوع، صوت، تقديم، تكبير)، ترجمات داخلية وخارجية، مسارات الصوت،
// صورة داخل صورة، تشغيل في الخلفية كصوت، لقطة شاشة، استئناف، قفل الشاشة، تدوير
// برمجة: د. محمد توفيق
package app.sada

import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Rational
import android.view.LayoutInflater
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

@OptIn(UnstableApi::class)
class VideoActivity : ComponentActivity() {
    lateinit var player: ExoPlayer
    var playerView: PlayerView? = null
    val pip = mutableStateOf(false)
    val resolving = mutableStateOf(false)
    val loadError = mutableStateOf<String?>(null)
    val items = mutableStateListOf<Pair<Uri, String>>()
    val index = mutableIntStateOf(0)
    private val subs = HashMap<Int, MediaItem.SubtitleConfiguration>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        Engine.connect(applicationContext)
        Engine.pause()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive(true)

        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .build()

        loadIntent(intent)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    Store.saveResume(currentKey(), 0)
                    if (player.repeatMode == Player.REPEAT_MODE_OFF && index.intValue < items.size - 1) go(index.intValue + 1)
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (Store.getInt("rotate_mode", 0) == 0 && videoSize.width > 0) {
                    val w = videoSize.width * videoSize.pixelWidthHeightRatio
                    requestedOrientation = if (w > videoSize.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
                updatePipParams()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) saveResume(); updatePipParams() }
        })

        setContent {
            val ar by Store.arabic.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalArabic provides ar, LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                SadaTheme { VideoScreen(this) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveResume()
        loadIntent(intent)
    }

    private fun loadIntent(i: Intent?) {
        items.clear(); subs.clear()
        if (i?.getBooleanExtra("queue", false) == true && VideoQueue.items.isNotEmpty()) {
            items.addAll(VideoQueue.items); index.intValue = VideoQueue.index.coerceIn(0, items.size - 1)
        } else {
            val u = i?.data ?: run { finish(); return }
            items.add(u to (displayName(this, u) ?: u.toString())); index.intValue = 0
        }
        go(index.intValue)
    }

    fun currentKey(): String = items.getOrNull(index.intValue)?.first?.toString() ?: ""

    fun go(i: Int) {
        if (i !in items.indices) return
        if (player.mediaItemCount > 0) saveResume()
        index.intValue = i
        val (u, title) = items[i]
        val meta = androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()
        if (isPageUrl(u)) {          // صفحة يوتيوب/X/فيسبوك…: استخراج رابط البث بلا إعلانات
            player.stop(); resolving.value = true; loadError.value = null
            lifecycleScope.launch {
                try {
                    val r = withContext(Dispatchers.IO) { Ytdl.resolve(this@VideoActivity, u.toString(), false) }
                    val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent(Ytdl.UA).setDefaultRequestProperties(r.headers)
                    val dsf = DefaultDataSource.Factory(this@VideoActivity, http)
                    fun src(x: String) = MediaItem.Builder().setUri(x).setMediaMetadata(meta).build().let {
                        if (r.hls) HlsMediaSource.Factory(dsf).createMediaSource(it) else ProgressiveMediaSource.Factory(dsf).createMediaSource(it)
                    }
                    player.setMediaSource(if (r.urls.size > 1) MergingMediaSource(src(r.urls[0]), src(r.urls[1])) else src(r.urls[0]))
                    player.prepare()
                    val p = if (Store.getBool("resume_video", true)) Store.resumePos(u.toString()) else 0L
                    if (p > 5000) player.seekTo(p)
                    player.play()
                } catch (e: Throwable) {
                    loadError.value = (e.message ?: e.toString()).lines().lastOrNull { it.contains("ERROR") } ?: (e.message ?: "").take(300)
                }
                resolving.value = false
            }
            return
        }
        val b = MediaItem.Builder().setUri(u).setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).build())
        subs[i]?.let { b.setSubtitleConfigurations(listOf(it)) }
        player.setMediaItem(b.build())
        player.prepare()
        if (Store.getBool("resume_video", true)) {
            val p = Store.resumePos(u.toString())
            if (p > 5000) { player.seekTo(p); Toast.makeText(this, trS("استئناف من ", "Resumed at ") + fmtTime(p), Toast.LENGTH_SHORT).show() }
        }
        player.play()
    }

    fun addSubtitle(uri: Uri) {
        val name = (displayName(this, uri) ?: "") + "." + (contentResolver.getType(uri) ?: "")
        val n = (uri.lastPathSegment ?: "").lowercase() + " " + name.lowercase()
        val mime = when {
            ".vtt" in n || "vtt" in n -> MimeTypes.TEXT_VTT
            ".ass" in n || ".ssa" in n -> MimeTypes.TEXT_SSA
            ".ttml" in n || ".xml" in n || ".dfxp" in n -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        subs[index.intValue] = MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setLanguage("und")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).setLabel(displayName(this, uri)).build()
        val pos = player.currentPosition
        val playing = player.isPlaying
        val cur = player.currentMediaItem ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        player.setMediaItem(cur.buildUpon().setSubtitleConfigurations(listOf(subs[index.intValue]!!)).build(), pos)
        player.prepare()
        player.playWhenReady = playing
    }

    fun saveResume() {
        if (!::player.isInitialized) return
        val d = player.duration
        val p = player.currentPosition
        if (d != C.TIME_UNSET && d > 0) Store.saveResume(currentKey(), if (p > d - 8000) 0 else p)
    }

    fun immersive(on: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val vs = player.videoSize
        val r = if (vs.width > 0 && vs.height > 0) Rational((vs.width * vs.pixelWidthHeightRatio).toInt(), vs.height) else Rational(16, 9)
        val clamped = when { r.toFloat() > 2.39f -> Rational(239, 100); r.toFloat() < 0.42f -> Rational(42, 100); else -> r }
        val b = PictureInPictureParams.Builder().setAspectRatio(clamped)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(player.isPlaying && Store.getBool("auto_pip", true)).setSeamlessResizeEnabled(true)
        return b.build()
    }

    fun updatePipParams() { if (Build.VERSION.SDK_INT >= 26) runCatching { pipParams()?.let { setPictureInPictureParams(it) } } }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))
            runCatching { enterPictureInPictureMode(pipParams()!!) }
        else Toast.makeText(this, trS("الجهاز لا يدعم صورة داخل صورة", "PiP not supported"), Toast.LENGTH_SHORT).show()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && player.isPlaying && Store.getBool("auto_pip", true)) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.value = isInPictureInPictureMode
    }

    /** ينقل الفيديو إلى خدمة الخلفية ليكمل كصوت مع إشعار وتحكم من شاشة القفل */
    fun playInBackground() {
        val (u, t) = items.getOrNull(index.intValue) ?: return
        val pos = player.currentPosition
        player.pause()
        saveResume()
        if (isPageUrl(u)) Engine.playOnline(listOf(OnlineItem(u.toString(), t, "", 0, null)))
        else Engine.playUri(u, t, pos)
        if (isPageUrl(u)) Engine.seek(pos)
        Toast.makeText(this, trS("يستمر التشغيل في الخلفية", "Continuing in background"), Toast.LENGTH_SHORT).show()
        finish()
    }

    fun snapshot() {
        val tv = playerView?.videoSurfaceView as? TextureView
        val bmp = tv?.bitmap
        if (bmp == null || Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, trS("تعذّر التقاط الإطار", "Could not capture frame"), Toast.LENGTH_SHORT).show(); return
        }
        val name = "Sada_" + System.currentTimeMillis() + ".png"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sada")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        val ok = uri != null && runCatching { contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrDefault(false)
        Toast.makeText(this, if (ok) trS("حُفظت اللقطة في Pictures/Sada", "Saved to Pictures/Sada") else trS("فشل الحفظ", "Save failed"), Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        super.onStop()
        saveResume()
        if (!isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        saveResume()
        player.release()
        super.onDestroy()
    }
}

// =====================================================================
@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(a: VideoActivity) {
    val p = a.player
    val inPip by a.pip
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(p.isPlaying) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var buffering by remember { mutableStateOf(false) }
    var resize by remember { mutableIntStateOf(Store.getInt("resize", AspectRatioFrameLayout.RESIZE_MODE_FIT)) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var hud by remember { mutableStateOf<String?>(null) }
    var hudLevel by remember { mutableFloatStateOf(-1f) }
    var seekPreview by remember { mutableLongStateOf(-1L) }
    var sheet by remember { mutableIntStateOf(0) }   // 1 مسارات/ترجمة، 2 سرعة، 3 معلومات
    var error by remember { mutableStateOf<String?>(null) }
    var loop by remember { mutableStateOf(false) }
    var interact by remember { mutableIntStateOf(0) }
    val title = a.items.getOrNull(a.index.intValue)?.second ?: ""
    val audio = remember { a.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    DisposableEffect(p) {
        val l = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playing = player.isPlaying
                buffering = player.playbackState == Player.STATE_BUFFERING
                dur = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            }
            override fun onPlayerError(e: PlaybackException) { error = e.errorCodeName + (e.cause?.message?.let { "\n$it" } ?: "") }
        }
        p.addListener(l)
        onDispose { p.removeListener(l) }
    }
    LaunchedEffect(Unit) { while (true) { pos = p.currentPosition; buffered = p.bufferedPosition; delay(250) } }
    LaunchedEffect(controls, interact, playing) { if (controls && playing && sheet == 0) { delay(3500); controls = false } }
    LaunchedEffect(hud, hudLevel) { if (hud != null) { delay(900); hud = null; hudLevel = -1f } }
    BackHandler(sheet != 0) { sheet = 0 }

    val subPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> u?.let { a.addSubtitle(it); sheet = 0 } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).also { v ->
                    v.player = p
                    v.resizeMode = resize
                    v.subtitleView?.setFractionalTextSize(0.0533f * Store.getFloat("sub_scale", 1f))
                    applySubStyle(v, Store.getInt("sub_style", 0))
                    a.playerView = v
                }
            },
            update = { v ->
                v.resizeMode = resize
                v.videoSurfaceView?.scaleX = zoom
                v.videoSurfaceView?.scaleY = zoom
            },
            modifier = Modifier.fillMaxSize()
        )

        // طبقة الإيماءات
        if (!inPip) Box(
            Modifier.fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { controls = !controls; interact++ },
                        onDoubleTap = { off ->
                            if (locked) return@detectTapGestures
                            val third = size.width / 3f
                            val rtlSafeLeft = off.x < third
                            when {
                                rtlSafeLeft -> { p.seekBack(); hud = "« 10s" }
                                off.x > 2 * third -> { p.seekForward(); hud = "10s »" }
                                else -> { if (p.isPlaying) p.pause() else p.play() }
                            }
                        }
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var mode = 0
                        val startPos = p.currentPosition
                        val startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val lp = a.window.attributes
                        val startBright = if (lp.screenBrightness >= 0) lp.screenBrightness else 0.5f
                        var dist0 = 0f; val zoom0 = zoom
                        var target = -1L
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val d = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                                if (mode != 4) { mode = 4; dist0 = d.coerceAtLeast(1f) }
                                zoom = (zoom0 * d / dist0).coerceIn(0.5f, 4f)
                                hud = String.format(Locale.US, "%.0f%%", zoom * 100)
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            if (mode == 4) continue
                            val c = pressed.first()
                            val dx = c.position.x - start.x; val dy = c.position.y - start.y
                            if (mode == 0 && (abs(dx) > 30 || abs(dy) > 30)) {
                                mode = if (abs(dx) > abs(dy)) 1 else if (start.x < size.width / 2f) 2 else 3
                            }
                            when (mode) {
                                1 -> {
                                    val total = if (dur > 0) dur else 1L
                                    target = (startPos + (dx / size.width) * minOf(total, 180_000L)).toLong().coerceIn(0L, total)
                                    seekPreview = target
                                    hud = fmtTime(target) + "  (" + (if (target >= startPos) "+" else "-") + fmtTime(abs(target - startPos)) + ")"
                                }
                                2 -> {
                                    val b = (startBright - dy / (size.height * 0.8f)).coerceIn(0.01f, 1f)
                                    a.window.attributes = a.window.attributes.apply { screenBrightness = b }
                                    hud = trS("السطوع", "Brightness"); hudLevel = b
                                }
                                3 -> {
                                    val v = (startVol - dy / (size.height * 0.8f) * maxVol).toInt().coerceIn(0, maxVol)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                    hud = trS("الصوت", "Volume"); hudLevel = v / maxVol.toFloat()
                                }
                            }
                            if (mode != 0) c.consume()
                        }
                        if (mode == 1 && target >= 0) { p.seekTo(target); seekPreview = -1 }
                    }
                }
        )

        // مؤشر الإيماءة
        hud?.let { h ->
            Column(Modifier.align(Alignment.Center).background(Color(0xCC0D0D0D)).border(2.dp, K.Signal).padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(h, color = K.Paper, fontWeight = FontWeight.Black, fontSize = 22.sp, fontFamily = K.Mono)
                if (hudLevel >= 0) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(160.dp).height(8.dp).background(K.Line)) { Box(Modifier.fillMaxWidth(hudLevel).fillMaxHeight().background(K.Signal)) }
                }
            }
        }
        if (buffering || a.resolving.value) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = K.Signal)
            if (a.resolving.value) Text(tr("جارٍ تجهيز البث…", "Preparing stream…"), color = K.Paper, modifier = Modifier.padding(top = 10.dp))
        }
        a.loadError.value?.let { if (error == null) error = it }

        // عناصر التحكم
        AnimatedVisibility(controls && !inPip && !locked, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                // أعلى
                Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().displayCutoutPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { a.finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = K.Paper) }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = K.Paper, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (a.items.size > 1) Text("${a.index.intValue + 1} / ${a.items.size}", style = Mono12)
                    }
                    IconButton(onClick = { sheet = 1 }) { Icon(Icons.Rounded.Subtitles, null, tint = K.Paper) }
                    IconButton(onClick = { sheet = 3 }) { Icon(Icons.Rounded.Info, null, tint = K.Paper) }
                }
                // الوسط
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    IconButton(onClick = { a.go(a.index.intValue - 1) }, enabled = a.index.intValue > 0) { Icon(Icons.Rounded.SkipPrevious, null, tint = K.Paper, modifier = Modifier.size(36.dp)) }
                    IconButton(onClick = { p.seekBack(); interact++ }) { Icon(Icons.Rounded.Replay10, null, tint = K.Paper, modifier = Modifier.size(34.dp)) }
                    Box(Modifier.size(76.dp).clip(CircleShape).background(K.Signal).clickable { if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }; interact++ }, contentAlignment = Alignment.Center) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = K.OnAccent, modifier = Modifier.size(46.dp))
                    }
                    IconButton(onClick = { p.seekForward(); interact++ }) { Icon(Icons.Rounded.Forward10, null, tint = K.Paper, modifier = Modifier.size(34.dp)) }
                    IconButton(onClick = { a.go(a.index.intValue + 1) }, enabled = a.index.intValue < a.items.size - 1) { Icon(Icons.Rounded.SkipNext, null, tint = K.Paper, modifier = Modifier.size(36.dp)) }
                }
                // أسفل
                Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding().displayCutoutPadding().padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtTime(if (seekPreview >= 0) seekPreview else pos), style = Mono12, color = K.Paper)
                        var drag by remember { mutableStateOf<Float?>(null) }
                        Slider(
                            value = drag ?: (if (dur > 0) pos.toFloat() / dur else 0f),
                            onValueChange = { drag = it; interact++ },
                            onValueChangeFinished = { drag?.let { p.seekTo((it * dur).toLong()) }; drag = null },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(thumbColor = K.Signal, activeTrackColor = K.Signal, inactiveTrackColor = K.Line)
                        )
                        Text(fmtTime(dur), style = Mono12, color = K.Paper)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        VBtn(Icons.Rounded.Lock, tr("قفل", "Lock")) { locked = true; controls = true }
                        VBtn(Icons.Rounded.AspectRatio, resizeName(resize)) {
                            resize = when (resize) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                            zoom = 1f; Store.putInt("resize", resize); hud = resizeName(resize); interact++
                        }
                        VBtn(Icons.Rounded.ScreenRotation, rotateName(Store.getInt("rotate_mode", 0))) {
                            val m = (Store.getInt("rotate_mode", 0) + 1) % 4
                            Store.putInt("rotate_mode", m)
                            a.requestedOrientation = when (m) {
                                1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                3 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                            interact++
                        }
                        VBtn(Icons.Rounded.Speed, String.format(Locale.US, "%.2gx", p.playbackParameters.speed)) { sheet = 2 }
                        VBtn(Icons.Rounded.Headphones, tr("خلفية", "Background")) { a.playInBackground() }
                        VBtn(Icons.Rounded.PictureInPicture, "PiP") { a.enterPip() }
                        VBtn(Icons.Rounded.CameraAlt, tr("لقطة", "Snapshot")) { a.snapshot() }
                        VBtn(if (loop) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, tr("تكرار", "Loop"), on = loop) {
                            loop = !loop; p.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                        }
                    }
                }
            }
        }
        // زر فك القفل
        if (locked && controls && !inPip) {
            Box(Modifier.align(Alignment.CenterStart).padding(24.dp).size(56.dp).clip(CircleShape).background(K.Tape).clickable { locked = false; interact++ }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.LockOpen, null, tint = K.OnAccent)
            }
        }

        error?.let { e ->
            Column(Modifier.align(Alignment.Center).padding(24.dp).background(K.Panel).border(2.dp, K.Signal).padding(18.dp)) {
                Text(tr("تعذّر تشغيل هذا الملف", "Can't play this file"), color = K.Paper, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text(e, style = Mono12)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SolidButton(tr("إعادة المحاولة", "Retry")) { error = null; a.loadError.value = null; a.go(a.index.intValue) }
                    GhostButton(tr("إغلاق", "Close")) { a.finish() }
                }
            }
        }

        when (sheet) {
            1 -> TracksSheet(a, onPickSub = { subPick.launch(arrayOf("*/*")) }) { sheet = 0 }
            2 -> SadaDialog(tr("سرعة التشغيل", "Playback speed"), { sheet = 0 }) {
                var s by remember { mutableFloatStateOf(p.playbackParameters.speed) }
                Text(String.format(Locale.US, "%.2fx", s), fontSize = 40.sp, fontWeight = FontWeight.Black, color = K.Signal, fontFamily = K.Mono)
                Slider(s, { s = Math.round(it * 20) / 20f; p.playbackParameters = PlaybackParameters(s) }, valueRange = 0.25f..4f,
                    colors = SliderDefaults.colors(thumbColor = K.Signal, activeTrackColor = K.Signal))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { v -> Chip("${v}x", s == v) { s = v; p.playbackParameters = PlaybackParameters(v) } }
                }
            }
            3 -> InfoSheet(p, title) { sheet = 0 }
        }
    }
}

@Composable
fun VBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.clip(K.RS).background(if (on) K.Signal else Color(0x99000000)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (on) K.OnAccent else K.Paper, modifier = Modifier.size(20.dp))
        Text(label, color = if (on) K.OnAccent else K.Paper, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@OptIn(UnstableApi::class)
fun resizeName(m: Int): String = when (m) {
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> trS("تكبير", "Zoom")
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> trS("ملء", "Stretch")
    AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> trS("عرض كامل", "Fit width")
    else -> trS("ملاءمة", "Fit")
}

fun rotateName(m: Int): String = when (m) {
    1 -> trS("أفقي", "Landscape"); 2 -> trS("عمودي", "Portrait"); 3 -> trS("حرّ", "Free"); else -> trS("تلقائي", "Auto")
}

@OptIn(UnstableApi::class)
fun applySubStyle(v: PlayerView, style: Int) {
    val s = when (style) {
        1 -> CaptionStyleCompat(android.graphics.Color.YELLOW, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null)
        2 -> CaptionStyleCompat(android.graphics.Color.WHITE, 0xCC000000.toInt(), android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.BLACK, null)
        else -> CaptionStyleCompat(android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null)
    }
    v.subtitleView?.setStyle(s)
}

// ---------- مسارات الصوت والترجمة ----------
@OptIn(UnstableApi::class)
@Composable
fun TracksSheet(a: VideoActivity, onPickSub: () -> Unit, onDismiss: () -> Unit) {
    val p = a.player
    var tracks by remember { mutableStateOf(p.currentTracks) }
    var scale by remember { mutableFloatStateOf(Store.getFloat("sub_scale", 1f)) }
    var style by remember { mutableIntStateOf(Store.getInt("sub_style", 0)) }
    DisposableEffect(p) {
        val l = object : Player.Listener { override fun onTracksChanged(t: Tracks) { tracks = t } }
        p.addListener(l); onDispose { p.removeListener(l) }
    }
    val textOff = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    SadaDialog(tr("الصوت والترجمة", "Audio & subtitles"), onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Tape(tr("مسار الصوت", "Audio track"))
            Spacer(Modifier.height(6.dp))
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audio.isEmpty()) Text("—", color = K.Muted)
            audio.forEach { g ->
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    val label = listOfNotNull(f.label, f.language?.let { Locale(it).displayLanguage }, f.sampleMimeType?.substringAfter('/')?.uppercase(),
                        if (f.channelCount > 0) "${f.channelCount}ch" else null).joinToString(" · ")
                    TrackOption(label, g.isTrackSelected(i), g.isTrackSupported(i)) {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Tape(tr("الترجمة", "Subtitles"))
            Spacer(Modifier.height(6.dp))
            TrackOption(tr("بلا ترجمة", "Off"), textOff, true) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
            tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { g ->
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    val label = listOfNotNull(f.label, f.language?.takeIf { it != "und" }?.let { Locale(it).displayLanguage }, f.sampleMimeType?.substringAfter('/'))
                        .joinToString(" · ").ifBlank { tr("ترجمة", "Subtitle") + " ${i + 1}" }
                    TrackOption(label, !textOff && g.isTrackSelected(i), g.isTrackSupported(i)) {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SolidButton(tr("تحميل ملف ترجمة (SRT/VTT/ASS)", "Load subtitle file (SRT/VTT/ASS)"), Icons.Rounded.FileOpen, modifier = Modifier.fillMaxWidth(), onClick = onPickSub)
            Spacer(Modifier.height(12.dp))
            Text(tr("حجم الخط", "Text size") + String.format(Locale.US, "  %.0f%%", scale * 100), color = K.Paper, fontWeight = FontWeight.Bold)
            Slider(scale, { scale = it; Store.putFloat("sub_scale", it); a.playerView?.subtitleView?.setFractionalTextSize(0.0533f * it) }, valueRange = 0.5f..2.5f,
                colors = SliderDefaults.colors(thumbColor = K.Signal, activeTrackColor = K.Signal))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(tr("أبيض", "White"), tr("أصفر", "Yellow"), tr("بخلفية", "Boxed")).forEachIndexed { i, n ->
                    Chip(n, style == i) { style = i; Store.putInt("sub_style", i); a.playerView?.let { applySubStyle(it, i) } }
                }
            }
        }
    }
}

@Composable
fun TrackOption(label: String, selected: Boolean, supported: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = supported, onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).border(2.dp, if (selected) K.Signal else K.Muted).padding(3.dp)) { if (selected) Box(Modifier.fillMaxSize().background(K.Signal)) }
        Spacer(Modifier.width(12.dp))
        Text(label + if (!supported) "  (" + tr("غير مدعوم", "unsupported") + ")" else "", color = if (supported) K.Paper else K.Muted, fontWeight = if (selected) FontWeight.Black else FontWeight.Normal)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun InfoSheet(p: ExoPlayer, title: String, onDismiss: () -> Unit) {
    val v = p.videoFormat; val au = p.audioFormat
    SadaDialog(tr("معلومات الملف", "Media info"), onDismiss) {
        val rows = listOf(
            tr("العنوان", "Title") to title,
            tr("المدة", "Length") to fmtTime(p.duration.takeIf { it != C.TIME_UNSET } ?: 0),
            tr("الحاوية", "Container") to (v?.containerMimeType ?: au?.containerMimeType ?: "—"),
            tr("ترميز الفيديو", "Video codec") to (v?.sampleMimeType ?: "—") + (v?.codecs?.let { " ($it)" } ?: ""),
            tr("الأبعاد", "Resolution") to (v?.let { "${it.width}×${it.height}" } ?: "—"),
            tr("الإطارات/ث", "Frame rate") to (v?.frameRate?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.2f fps", it) } ?: "—"),
            tr("معدّل بت الفيديو", "Video bitrate") to (v?.bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: "—"),
            tr("ترميز الصوت", "Audio codec") to (au?.sampleMimeType ?: "—"),
            tr("القنوات / التردد", "Channels / rate") to (au?.let { "${it.channelCount} ch · ${it.sampleRate} Hz" } ?: "—"),
            tr("نطاق الألوان", "Color") to (v?.colorInfo?.let { if (it.colorTransfer == C.COLOR_TRANSFER_ST2084 || it.colorTransfer == C.COLOR_TRANSFER_HLG) "HDR" else "SDR" } ?: "—"),
        )
        rows.forEach { (k, x) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(k, color = K.Muted, fontSize = 12.sp, modifier = Modifier.width(120.dp))
                Text(x, color = K.Paper, fontFamily = K.Mono, fontSize = 12.sp)
            }
        }
    }
}
