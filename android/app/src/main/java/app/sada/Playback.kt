// صدى — محرّك التشغيل: خدمة الخلفية، الجلسة، المؤثرات الصوتية، مؤقت النوم، وحالة المشغّل للواجهة
// برمجة: د. محمد توفيق
package app.sada

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ---------- بناء عناصر التشغيل ----------
fun Track.toItem(): MediaItem = MediaItem.Builder()
    .setMediaId("t:$id")
    .setUri(uri)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist.ifBlank { null })
            .setAlbumTitle(album.ifBlank { null })
            .setArtworkUri(Thumbs.albumArtUri(albumId))
            .setTrackNumber(trackNo)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    ).build()

fun uriItem(uri: Uri, title: String?): MediaItem = MediaItem.Builder()
    .setMediaId("u:$uri")
    .setUri(uri)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title ?: uri.lastPathSegment ?: "?").build())
    .build()

fun MediaItem.trackId(): Long = mediaId.takeIf { it.startsWith("t:") }?.substring(2)?.toLongOrNull() ?: -1L

// ---------- مركز مشترك بين الخدمة والواجهة (نفس العملية) ----------
object Hub {
    @Volatile var audioSession: Int = C.AUDIO_SESSION_ID_UNSET
    val sessionFlow = MutableStateFlow(C.AUDIO_SESSION_ID_UNSET)
    /** 0 = متوقف، -1 = نهاية المقطع الحالي، وإلا وقت الانتهاء بالمللي ثانية */
    val sleepAt = MutableStateFlow(0L)
    var player: ExoPlayer? = null
}

// ---------- المؤثرات الصوتية ----------
object Fx {
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    var reverb: PresetReverb? = null; private set

    val ready = MutableStateFlow(false)
    val bands = MutableStateFlow<List<Pair<Int, Int>>>(emptyList())   // (التردد Hz، المستوى mB)
    var range: Pair<Int, Int> = -1500 to 1500; private set
    var presets: List<String> = emptyList(); private set

    fun attach(session: Int) {
        release()
        runCatching {
            eq = Equalizer(1000, session).also { e ->
                range = e.bandLevelRange[0].toInt() to e.bandLevelRange[1].toInt()
                presets = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) }
            }
        }
        runCatching { bass = BassBoost(1000, session) }
        runCatching { virt = Virtualizer(1000, session) }
        runCatching { loud = LoudnessEnhancer(session) }
        runCatching { reverb = PresetReverb(1000, 0) }
        applySaved()
        ready.value = eq != null
    }

    private fun applySaved() {
        val e = eq
        if (e != null) runCatching {
            val p = Store.getInt("eq_preset", -1)
            if (p >= 0 && p < e.numberOfPresets) e.usePreset(p.toShort())
            else for (i in 0 until e.numberOfBands) e.setBandLevel(i.toShort(), Store.getInt("eq_b$i", 0).toShort())
            e.enabled = Store.getBool("eq_on", false)
            refreshBands()
        }
        setBass(Store.getInt("bass", 0), save = false)
        setVirt(Store.getInt("virt", 0), save = false)
        setLoud(Store.getInt("loud", 0), save = false)
        setReverb(Store.getInt("reverb", 0), save = false)
    }

    private fun refreshBands() {
        val e = eq ?: return
        bands.value = (0 until e.numberOfBands).map { i ->
            (e.getCenterFreq(i.toShort()) / 1000) to e.getBandLevel(i.toShort()).toInt()
        }
    }

    fun eqEnabled() = Store.getBool("eq_on", false)
    fun setEqEnabled(on: Boolean) { Store.putBool("eq_on", on); runCatching { eq?.enabled = on } }

    fun usePreset(i: Int) {
        val e = eq ?: return
        runCatching { e.usePreset(i.toShort()) }
        Store.putInt("eq_preset", i)
        for (b in 0 until e.numberOfBands) Store.putInt("eq_b$b", e.getBandLevel(b.toShort()).toInt())
        refreshBands()
    }

    fun setBand(i: Int, level: Int) {
        val e = eq ?: return
        runCatching { e.setBandLevel(i.toShort(), level.toShort()) }
        Store.putInt("eq_b$i", level); Store.putInt("eq_preset", -1)
        refreshBands()
    }

    fun resetBands() { val e = eq ?: return; for (i in 0 until e.numberOfBands) setBand(i, 0) }

    fun setBass(v: Int, save: Boolean = true) {
        if (save) Store.putInt("bass", v)
        runCatching { bass?.apply { if (strengthSupported) setStrength(v.toShort()); enabled = v > 0 } }
    }

    fun setVirt(v: Int, save: Boolean = true) {
        if (save) Store.putInt("virt", v)
        runCatching { virt?.apply { if (strengthSupported) setStrength(v.toShort()); enabled = v > 0 } }
    }

    fun setLoud(mB: Int, save: Boolean = true) {
        if (save) Store.putInt("loud", mB)
        runCatching { loud?.apply { setTargetGain(mB); enabled = mB > 0 } }
    }

    /** 0 = بلا صدى، 1..6 = غرفة صغيرة .. قاعة كبيرة .. صفيحة */
    @OptIn(UnstableApi::class)
    fun setReverb(v: Int, save: Boolean = true) {
        if (save) Store.putInt("reverb", v)
        val r = reverb ?: return
        runCatching {
            if (v <= 0) { r.enabled = false; Hub.player?.clearAuxEffectInfo() }
            else {
                r.preset = v.toShort(); r.enabled = true
                Hub.player?.setAuxEffectInfo(AuxEffectInfo(r.id, 1f))
            }
        }
    }

    fun release() {
        listOf(eq, bass, virt, loud, reverb).forEach { runCatching { it?.release() } }
        eq = null; bass = null; virt = null; loud = null; reverb = null
        ready.value = false
    }
}

// ---------- خدمة التشغيل في الخلفية ----------
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sleepJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(applicationContext)
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)   // FFmpeg للصيغ غير المدعومة
            .setEnableDecoderFallback(true)
        // روابط ytdl:// تُحلّ إلى رابط البث الحقيقي لحظة الفتح (بلا إعلانات، وتبقى صالحة في الطابور)
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent(Ytdl.UA)
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme == "ytdl") {
                val page = spec.uri.getQueryParameter("u") ?: throw java.io.IOException("bad link")
                val r = try { Ytdl.resolve(applicationContext, page, true) } catch (e: Throwable) { throw java.io.IOException(e.message ?: "resolve", e) }
                spec.withUri(Uri.parse(r.urls.first())).withAdditionalHeaders(r.headers)
            } else spec
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val sid = Util.generateAudioSessionIdV21(this)
        player.audioSessionId = sid
        Hub.audioSession = sid
        Hub.sessionFlow.value = sid
        Hub.player = player
        player.skipSilenceEnabled = Store.getBool("skip_silence", false)
        player.playbackParameters = PlaybackParameters(Store.getFloat("speed", 1f), Store.getFloat("pitch", 1f))
        player.shuffleModeEnabled = Store.getBool("shuffle", false)
        player.repeatMode = Store.getInt("repeat", Player.REPEAT_MODE_OFF)
        Fx.attach(sid)

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let { Store.countPlay(it.trackId()) }
                saveQueue(player)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) saveQueue(player) }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = Store.putBool("shuffle", shuffleModeEnabled)
            override fun onRepeatModeChanged(repeatMode: Int) = Store.putInt("repeat", repeatMode)
            override fun onPlaybackParametersChanged(p: PlaybackParameters) { Store.putFloat("speed", p.speed); Store.putFloat("pitch", p.pitch) }
            private var retried = ""
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val cur = player.currentMediaItem ?: return
                if (!cur.mediaId.startsWith("o:")) return
                val page = cur.mediaId.substring(2)
                if (retried != page) {           // ربما انتهت صلاحية الرابط: أعد الحلّ مرة واحدة
                    retried = page; Ytdl.invalidate(page); player.prepare(); player.play()
                } else if (player.hasNextMediaItem()) {   // مقطع محجوب أو محذوف: تخطَّه
                    player.seekToNextMediaItem(); player.prepare(); player.play()
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && Hub.sleepAt.value == -1L) {
                    player.pauseAtEndOfMediaItems = false
                    Hub.sleepAt.value = 0
                }
            }
        })

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra("open_player", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems.map { it.resolved() }.toMutableList())
            })
            .build()

        // مؤقت النوم مع خفوت تدريجي للصوت
        sleepJob = scope.launch {
            Hub.sleepAt.collect { at ->
                player.pauseAtEndOfMediaItems = at == -1L
                if (at > 0) {
                    while (isActive && Hub.sleepAt.value == at) {
                        val left = at - System.currentTimeMillis()
                        if (left <= 0) {
                            player.pause(); player.volume = 1f; Hub.sleepAt.value = 0; break
                        }
                        player.volume = if (left < 15_000) (left / 15_000f).coerceIn(0.05f, 1f) else 1f
                        delay(500)
                    }
                    player.volume = 1f
                }
            }
        }
    }

    private fun MediaItem.resolved(): MediaItem {
        if (localConfiguration != null) return this
        val u = requestMetadata.mediaUri ?: runCatching {
            if (mediaId.startsWith("u:")) Uri.parse(mediaId.substring(2))
            else if (mediaId.startsWith("o:")) onlineUri(mediaId.substring(2))
            else Library.byId(trackId())?.uri
        }.getOrNull()
        return if (u != null) buildUpon().setUri(u).build() else this
    }

    private fun saveQueue(p: Player) {
        val ids = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).trackId() }
        if (ids.isEmpty() || ids.any { it < 0 }) return
        Store.putStr("queue", ids.joinToString(","))
        Store.putInt("queue_idx", p.currentMediaItemIndex)
        Store.putStr("queue_pos", p.currentPosition.toString())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        sleepJob?.cancel()
        Fx.release()
        session?.run { player.release(); release() }
        session = null
        Hub.player = null
        super.onDestroy()
    }
}

// ---------- واجهة التحكم للتطبيق (MediaController) ----------
object Engine {
    var controller: MediaController? = null; private set
    val connected = MutableStateFlow(false)
    val current = MutableStateFlow<MediaItem?>(null)
    val playing = MutableStateFlow(false)
    val position = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val buffering = MutableStateFlow(false)
    val shuffle = MutableStateFlow(false)
    val repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val speed = MutableStateFlow(1f)
    val pitch = MutableStateFlow(1f)
    val queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val index = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
    // تكرار مقطع A-B
    val abA = MutableStateFlow(-1L)
    val abB = MutableStateFlow(-1L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    fun connect(ctx: Context) {
        if (controller != null) return
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            sync(c)
            connected.value = true
            restoreQueue(c)
            pending.forEach { it(c) }; pending.clear()
            startTicker()
        }, MoreExecutors.directExecutor())
    }

    private fun run(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending += block
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
        override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
            error.value = e.errorCodeName + (e.cause?.message?.let { ": $it" } ?: "")
        }
    }

    private fun sync(p: Player) {
        current.value = p.currentMediaItem
        playing.value = p.isPlaying
        buffering.value = p.playbackState == Player.STATE_BUFFERING
        duration.value = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        position.value = p.currentPosition
        shuffle.value = p.shuffleModeEnabled
        repeat.value = p.repeatMode
        speed.value = p.playbackParameters.speed
        pitch.value = p.playbackParameters.pitch
        index.value = p.currentMediaItemIndex
        val n = p.mediaItemCount
        if (n != queue.value.size || (n > 0 && queue.value.getOrNull(index.value)?.mediaId != p.currentMediaItem?.mediaId))
            queue.value = (0 until n).map { p.getMediaItemAt(it) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                controller?.let { c ->
                    val pos = c.currentPosition
                    position.value = pos
                    val a = abA.value; val b = abB.value
                    if (a >= 0 && b > a && pos >= b) c.seekTo(a)
                }
                delay(200)
            }
        }
    }

    private fun restoreQueue(c: MediaController) {
        if (c.mediaItemCount > 0) return
        val ids = Store.getStr("queue")?.split(",")?.mapNotNull { it.toLongOrNull() } ?: return
        scope.launch {
            // انتظار المكتبة
            var tries = 0
            while (!Library.loaded.value && tries++ < 50) delay(100)
            val items = ids.mapNotNull { Library.byId(it)?.toItem() }
            if (items.isEmpty() || c.mediaItemCount > 0) return@launch
            val idx = Store.getInt("queue_idx", 0).coerceIn(0, items.size - 1)
            c.setMediaItems(items, idx, Store.getStr("queue_pos")?.toLongOrNull() ?: 0L)
            c.prepare()
        }
    }

    // ---- أوامر ----
    fun playTracks(list: List<Track>, start: Int = 0, shuffled: Boolean = false) = run { c ->
        if (list.isEmpty()) return@run
        clearAB()
        c.shuffleModeEnabled = shuffled
        val s = if (shuffled) list.indices.random() else start.coerceIn(0, list.size - 1)
        c.setMediaItems(list.map { it.toItem() }, s, 0L)
        c.prepare(); c.play()
    }

    fun playUri(uri: Uri, title: String?, pos: Long = 0L) = run { c ->
        clearAB()
        c.setMediaItem(uriItem(uri, title), pos)
        c.prepare(); c.play()
    }

    fun playOnline(list: List<OnlineItem>, start: Int = 0, shuffled: Boolean = false) = run { c ->
        if (list.isEmpty()) return@run
        clearAB()
        c.shuffleModeEnabled = shuffled
        val s = if (shuffled) list.indices.random() else start.coerceIn(0, list.size - 1)
        c.setMediaItems(list.map { it.toItem() }, s, 0L)
        c.prepare(); c.play()
    }

    fun enqueueOnline(list: List<OnlineItem>) = run { c ->
        if (c.mediaItemCount == 0) playOnline(list) else c.addMediaItems(list.map { it.toItem() })
    }

    fun playNext(t: List<Track>) = run { c ->
        if (c.mediaItemCount == 0) playTracks(t) else c.addMediaItems(c.currentMediaItemIndex + 1, t.map { it.toItem() })
    }

    fun enqueue(t: List<Track>) = run { c ->
        if (c.mediaItemCount == 0) playTracks(t) else c.addMediaItems(t.map { it.toItem() })
    }

    fun toggle() = run { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
        }
    }
    fun pause() = run { it.pause() }
    fun next() = run { it.seekToNext() }
    fun prev() = run { c -> if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPrevious() }
    fun seek(ms: Long) = run { it.seekTo(ms); position.value = ms }
    fun seekBy(ms: Long) = run { c -> c.seekTo((c.currentPosition + ms).coerceAtLeast(0)) }
    fun jump(i: Int) = run { c -> c.seekToDefaultPosition(i); c.play() }
    fun remove(i: Int) = run { it.removeMediaItem(i) }
    fun move(from: Int, to: Int) = run { it.moveMediaItem(from, to) }
    fun clearQueue() = run { it.clearMediaItems(); Store.putStr("queue", null) }
    fun setShuffle(v: Boolean) = run { it.shuffleModeEnabled = v }
    fun cycleRepeat() = run { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun setSpeedPitch(s: Float, p: Float) = run { it.playbackParameters = PlaybackParameters(s, p) }

    fun markAB() {
        val pos = position.value
        when {
            abA.value < 0 -> abA.value = pos
            abB.value < 0 && pos > abA.value + 500 -> abB.value = pos
            else -> clearAB()
        }
    }
    fun clearAB() { abA.value = -1; abB.value = -1 }

    @OptIn(UnstableApi::class)
    fun setSkipSilence(v: Boolean) { Store.putBool("skip_silence", v); Hub.player?.skipSilenceEnabled = v }

}
