// صدى — شاشة التشغيل: الغلاف، المرئيات الحيّة، الكلمات المتزامنة، الطابور، السرعة والطبقة، مؤقت النوم، تكرار A-B
// برمجة: د. محمد توفيق
package app.sada

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10

@Composable
fun NowPlaying(nav: Nav, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val item by Engine.current.collectAsStateWithLifecycle()
    val playing by Engine.playing.collectAsStateWithLifecycle()
    val pos by Engine.position.collectAsStateWithLifecycle()
    val dur by Engine.duration.collectAsStateWithLifecycle()
    val shuffle by Engine.shuffle.collectAsStateWithLifecycle()
    val repeat by Engine.repeat.collectAsStateWithLifecycle()
    val speed by Engine.speed.collectAsStateWithLifecycle()
    val favs by Store.favorites.collectAsStateWithLifecycle()
    val sleepAt by Hub.sleepAt.collectAsStateWithLifecycle()
    val abA by Engine.abA.collectAsStateWithLifecycle()
    val abB by Engine.abB.collectAsStateWithLifecycle()
    val err by Engine.error.collectAsStateWithLifecycle()
    val t = item?.let { Library.byId(it.trackId()) }

    var panel by remember { mutableIntStateOf(0) }   // 0 غلاف، 1 كلمات، 2 طابور
    var speedDlg by remember { mutableStateOf(false) }
    var sleepDlg by remember { mutableStateOf(false) }
    var visOn by remember { mutableStateOf(Store.getBool("vis", true)) }
    BackHandler(panel != 0) { panel = 0 }

    // لون الخلفية من الغلاف
    val art = item?.mediaMetadata?.artworkUri
    val net = rememberNetThumb(art?.toString()?.takeIf { t == null && it.startsWith("http") }?.replace("mqdefault", "hqdefault"))
    val bmp = rememberThumb(t?.uri, 720, t?.let { Thumbs.albumArtUri(it.albumId) }) ?: net
    val tint = remember(bmp) { bmp?.let { Color(Thumbs.dominant(it)) } ?: K.Signal }

    Column(Modifier.fillMaxSize().background(K.Ink).systemBarsPadding()) {
        // الشريط العلوي
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = K.Paper, modifier = Modifier.size(30.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Tape(tr("يُشغَّل الآن", "Now playing"))
                Text(t?.let { extOf(it.mime, it.path) + " · " + fmtSize(it.size) } ?: "", style = Mono12, modifier = Modifier.padding(top = 2.dp))
            }
            IconButton(onClick = { t?.let { nav.options(listOf(it)) } }) { Icon(Icons.Rounded.MoreVert, null, tint = K.Paper) }
        }

        // المنطقة الوسطى
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (panel) {
                1 -> LyricsPanel(t, pos)
                2 -> QueuePanel()
                else -> Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    var drag by remember { mutableFloatStateOf(0f) }
                    BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                        val side = minOf(maxWidth, maxHeight)
                        Box(Modifier.size(side).pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = { if (drag < -120) Engine.next() else if (drag > 120) Engine.prev(); drag = 0f },
                                onHorizontalDrag = { _, d -> drag += d }
                            )
                        }) {
                            Box(Modifier.fillMaxSize().padding(start = 10.dp, top = 10.dp).clip(RoundedCornerShape(18.dp)).background(tint))
                            ArtBox(bmp, item?.mediaMetadata?.title?.toString() ?: "", side - 10.dp, Modifier.align(Alignment.TopStart), big = true)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (visOn) Visualizer(tint, playing) { visOn = false; Store.putBool("vis", false) }
                }
            }
        }

        // العنوان والفنان
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item?.mediaMetadata?.title?.toString() ?: "", fontSize = 24.sp, fontWeight = FontWeight.Black, color = K.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(item?.mediaMetadata?.artist, item?.mediaMetadata?.albumTitle).joinToString(" · "), color = K.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (t != null) IconButton(onClick = { Store.toggleFav(t.id) }) {
                Icon(if (t.id in favs) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (t.id in favs) K.Signal else K.Paper, modifier = Modifier.size(28.dp))
            }
        }
        err?.let {
            Text(tr("تعذّر التشغيل: ", "Playback error: ") + it, color = K.OnAccent, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(K.Tape).clickable { Engine.error.value = null }.padding(8.dp))
        }

        // شريط التقدّم مع علامات A-B
        var seeking by remember { mutableStateOf<Float?>(null) }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Box {
                Slider(
                    value = seeking ?: (if (dur > 0) pos.toFloat() / dur else 0f),
                    onValueChange = { seeking = it },
                    onValueChangeFinished = { seeking?.let { Engine.seek((it * dur).toLong()) }; seeking = null },
                    colors = SliderDefaults.colors(thumbColor = K.Signal, activeTrackColor = K.Signal, inactiveTrackColor = K.Line)
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Text(fmtTime(seeking?.let { (it * dur).toLong() } ?: pos), style = Mono12, color = K.Paper)
                Spacer(Modifier.weight(1f))
                if (abA >= 0) Text("A " + fmtTime(abA) + (if (abB >= 0) "  B " + fmtTime(abB) else ""), style = Mono12, color = K.Tape)
                Spacer(Modifier.weight(1f))
                Text("-" + fmtTime((dur - pos).coerceAtLeast(0)), style = Mono12)
            }
        }

        // أزرار التحكم الرئيسية
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Engine.setShuffle(!shuffle) }) { Icon(Icons.Rounded.Shuffle, null, tint = if (shuffle) K.Signal else K.Muted) }
            IconButton(onClick = { Engine.seekBy(-10_000) }) { Icon(Icons.Rounded.Replay10, null, tint = K.Paper) }
            IconButton(onClick = { Engine.prev() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, null, tint = K.Paper, modifier = Modifier.size(40.dp)) }
            Box(Modifier.size(78.dp).clip(CircleShape).background(K.Signal).clickable { Engine.toggle() }, contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = K.OnAccent, modifier = Modifier.size(48.dp))
            }
            IconButton(onClick = { Engine.next() }, Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, null, tint = K.Paper, modifier = Modifier.size(40.dp)) }
            IconButton(onClick = { Engine.seekBy(10_000) }) { Icon(Icons.Rounded.Forward10, null, tint = K.Paper) }
            IconButton(onClick = { Engine.cycleRepeat() }) {
                Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null, tint = if (repeat != Player.REPEAT_MODE_OFF) K.Signal else K.Muted)
            }
        }

        // صف الأدوات
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolChip(Icons.Rounded.Lyrics, tr("كلمات", "Lyrics"), panel == 1) { panel = if (panel == 1) 0 else 1 }
            ToolChip(Icons.AutoMirrored.Rounded.QueueMusic, tr("الطابور", "Queue"), panel == 2) { panel = if (panel == 2) 0 else 2 }
            ToolChip(Icons.Rounded.Speed, String.format(Locale.US, "%.2gx", speed), speed != 1f) { speedDlg = true }
            ToolChip(Icons.Rounded.Bedtime, if (sleepAt != 0L) sleepLabel(sleepAt) else tr("مؤقت", "Sleep"), sleepAt != 0L) { sleepDlg = true }
            ToolChip(Icons.Rounded.GraphicEq, tr("المعادل", "Equalizer"), Fx.eqEnabled()) { onClose(); nav.push(Scr.Effects) }
            ToolChip(Icons.Rounded.Repeat, if (abA < 0) "A-B" else if (abB < 0) tr("حدّد B", "Set B") else tr("إلغاء A-B", "Clear A-B"), abA >= 0) { Engine.markAB() }
            ToolChip(Icons.Rounded.BarChart, tr("مرئيات", "Visualizer"), visOn) { visOn = !visOn; Store.putBool("vis", visOn) }
            if (t != null) ToolChip(Icons.AutoMirrored.Rounded.PlaylistAdd, tr("إلى قائمة", "Playlist"), false) { nav.addTo(listOf(t)) }
            if (t != null) ToolChip(Icons.Rounded.Share, tr("مشاركة", "Share"), false) {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(t.mime.ifBlank { "audio/*" }).putExtra(Intent.EXTRA_STREAM, t.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
            }
        }
    }
    if (speedDlg) SpeedDialog { speedDlg = false }
    if (sleepDlg) SleepDialog { sleepDlg = false }
}

@Composable
fun sleepLabel(at: Long): String {
    if (at == -1L) return tr("نهاية المقطع", "End of track")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    return fmtTime((at - now).coerceAtLeast(0))
}

@Composable
fun ToolChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) K.Paper else K.Panel2).border(1.dp, if (on) K.Paper else K.Line, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (on) K.OnAccent else K.Paper, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (on) K.OnAccent else K.Paper, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

// ---------- المرئيات الحيّة (FFT) ----------
@Composable
fun Visualizer(color: Color, playing: Boolean, onHide: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val session by Hub.sessionFlow.collectAsStateWithLifecycle()
    val bars = remember { mutableStateListOf<Float>().apply { repeat(48) { add(0f) } } }

    if (!granted) {
        Row(Modifier.fillMaxWidth().clip(K.R).border(1.dp, K.Line, K.R).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("المرئيات الحيّة تحتاج إذن تحليل الصوت (لا يُسجَّل شيء).", "Live visualizer needs audio-analysis permission (nothing is recorded)."),
                color = K.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(tr("تفعيل", "Enable"), color = K.OnAccent, fontWeight = FontWeight.Black, modifier = Modifier.clip(K.RS).background(K.Tape).clickable { launcher.launch(Manifest.permission.RECORD_AUDIO) }.padding(8.dp))
            Text("×", color = K.Muted, fontSize = 20.sp, modifier = Modifier.clickable(onClick = onHide).padding(8.dp))
        }
        return
    }

    DisposableEffect(session) {
        var v: Visualizer? = null
        if (session > 0) runCatching {
            v = Visualizer(session).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        fft ?: return
                        val n = fft.size / 2
                        for (i in 0 until bars.size) {
                            // توزيع لوغاريتمي للترددات
                            val lo = (Math.pow(n.toDouble(), i / bars.size.toDouble())).toInt().coerceIn(1, n - 1)
                            val hi = (Math.pow(n.toDouble(), (i + 1) / bars.size.toDouble())).toInt().coerceIn(lo + 1, n)
                            var m = 0.0
                            for (k in lo until hi) { val re = fft[2 * k].toDouble(); val im = fft[2 * k + 1].toDouble(); m = maxOf(m, hypot(re, im)) }
                            val db = if (m > 0) (20 * log10(m) / 42.0).toFloat().coerceIn(0f, 1f) else 0f
                            bars[i] = maxOf(db, bars[i] * 0.82f)
                        }
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)
                enabled = true
            }
        }
        onDispose { runCatching { v?.enabled = false; v?.release() } }
    }
    LaunchedEffect(playing) { if (!playing) { while (bars.any { it > 0.01f }) { for (i in bars.indices) bars[i] *= 0.8f; delay(40) } } }

    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val w = size.width / bars.size
        bars.forEachIndexed { i, b ->
            val h = (b * size.height).coerceAtLeast(2f)
            drawRect(if (i % 8 == 0) K.Tape else color, Offset(i * w + 1, size.height - h), Size(w - 2, h))
        }
    }
}

// ---------- الكلمات ----------
@Composable
fun LyricsPanel(t: Track?, pos: Long) {
    val ctx = LocalContext.current
    var src by remember(t?.id) { mutableStateOf(t?.let { Store.lyricsUri(it.id) }) }
    var lines by remember(t?.id) { mutableStateOf<List<LyricLine>>(emptyList()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null && t != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Store.setLyricsUri(t.id, u.toString()); src = u.toString()
        }
    }
    LaunchedEffect(src) {
        lines = src?.let { s ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val txt = ctx.contentResolver.openInputStream(Uri.parse(s))!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    Lrc.parse(txt).ifEmpty { Lrc.plain(txt) }
                }.getOrDefault(emptyList())
            }
        } ?: emptyList()
    }
    if (t == null) { Empty(tr("الكلمات متاحة لأغاني المكتبة", "Lyrics are available for library songs")); return }
    if (lines.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(tr("لا كلمات بعد", "No lyrics yet"), fontSize = 30.sp, fontWeight = FontWeight.Black, color = K.Paper)
            Spacer(Modifier.height(8.dp))
            Text(tr("اختر ملف كلمات ‎.lrc‎ (متزامن) أو ‎.txt‎ لهذه الأغنية. سيظهر السطر الحالي ملوّناً وتستطيع لمس أي سطر للانتقال إليه.",
                "Pick a synced .lrc (or plain .txt) file for this song. The current line lights up and you can tap any line to jump."), color = K.Muted)
            Spacer(Modifier.height(16.dp))
            SolidButton(tr("اختيار ملف كلمات", "Pick lyrics file"), Icons.Rounded.FileOpen) { pick.launch(arrayOf("*/*")) }
        }
        return
    }
    val synced = lines.first().time >= 0
    val cur = if (synced) Lrc.indexAt(lines, pos + 150) else -1
    val st = rememberLazyListState()
    LaunchedEffect(cur) { if (cur >= 0) st.animateScrollToItem((cur - 3).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp), state = st) {
            itemsIndexed(lines) { i, l ->
                Text(
                    l.text.ifBlank { "♪" }, fontSize = if (i == cur) 24.sp else 19.sp,
                    fontWeight = if (i == cur) FontWeight.Black else FontWeight.Bold,
                    color = when { i == cur -> K.Signal; i < cur -> K.Muted; else -> K.Paper },
                    modifier = Modifier.fillMaxWidth().clickable(enabled = synced) { Engine.seek(l.time) }.padding(vertical = 7.dp)
                )
            }
        }
        Text(tr("تغيير ملف الكلمات", "Change lyrics file"), color = K.Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable { pick.launch(arrayOf("*/*")) }.padding(6.dp))
    }
}

// ---------- الطابور ----------
@Composable
fun QueuePanel() {
    val q by Engine.queue.collectAsStateWithLifecycle()
    val idx by Engine.index.collectAsStateWithLifecycle()
    val st = rememberLazyListState(initialFirstVisibleItemIndex = (idx - 2).coerceAtLeast(0))
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("الطابور", "Queue") + " · ${q.size}", color = K.Paper, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text(tr("مسح", "Clear"), color = K.Signal, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { Engine.clearQueue() }.padding(8.dp))
        }
        LazyColumn(Modifier.weight(1f), state = st) {
            itemsIndexed(q) { i, m ->
                Row(Modifier.fillMaxWidth().background(if (i == idx) K.Panel2 else Color.Transparent).clickable { Engine.jump(i) }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(Locale.US, "%02d", i + 1), fontFamily = K.Mono, color = if (i == idx) K.Signal else K.Muted, modifier = Modifier.width(34.dp), fontWeight = FontWeight.Black)
                    Column(Modifier.weight(1f)) {
                        Text(m.mediaMetadata.title?.toString() ?: "", color = if (i == idx) K.Signal else K.Paper, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        Text(m.mediaMetadata.artist?.toString() ?: "", color = K.Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    IconButton(onClick = { Engine.move(i, i - 1) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, null, tint = K.Muted) }
                    IconButton(onClick = { Engine.move(i, i + 1) }, enabled = i < q.size - 1) { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = K.Muted) }
                    IconButton(onClick = { Engine.remove(i) }) { Icon(Icons.Rounded.Close, null, tint = K.Muted) }
                }
            }
        }
    }
}

// ---------- السرعة والطبقة ----------
@Composable
fun SpeedDialog(onDismiss: () -> Unit) {
    val sp0 by Engine.speed.collectAsStateWithLifecycle()
    val pi0 by Engine.pitch.collectAsStateWithLifecycle()
    var s by remember { mutableFloatStateOf(sp0) }
    var p by remember { mutableFloatStateOf(pi0) }
    var locked by remember { mutableStateOf(abs(sp0 - pi0) < 0.001f && sp0 != 1f) }
    SadaDialog(tr("السرعة والطبقة", "Speed & pitch"), onDismiss) {
        Text(String.format(Locale.US, "%.2fx", s), fontSize = 44.sp, fontWeight = FontWeight.Black, color = K.Signal, fontFamily = K.Mono)
        Slider(s, { s = (Math.round(it * 20) / 20f); if (locked) p = s; Engine.setSpeedPitch(s, p) }, valueRange = 0.25f..3f,
            colors = SliderDefaults.colors(thumbColor = K.Signal, activeTrackColor = K.Signal))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v -> Chip("${v}x", s == v) { s = v; if (locked) p = v; Engine.setSpeedPitch(s, p) } }
        }
        Spacer(Modifier.height(14.dp))
        Text(tr("الطبقة", "Pitch") + String.format(Locale.US, "  %.2f", p), color = K.Paper, fontWeight = FontWeight.Bold)
        Slider(p, { p = (Math.round(it * 20) / 20f); Engine.setSpeedPitch(s, p) }, valueRange = 0.5f..2f, enabled = !locked,
            colors = SliderDefaults.colors(thumbColor = K.Tape, activeTrackColor = K.Tape))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(locked, { locked = it; if (it) { p = s; Engine.setSpeedPitch(s, p) } })
            Text(tr("ربط الطبقة بالسرعة (أسلوب الأسطوانة)", "Tie pitch to speed (vinyl style)"), color = K.Paper, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        GhostButton(tr("إعادة الضبط", "Reset"), Icons.Rounded.RestartAlt, Modifier.fillMaxWidth()) { s = 1f; p = 1f; locked = false; Engine.setSpeedPitch(1f, 1f) }
    }
}

// ---------- مؤقت النوم ----------
@Composable
fun SleepDialog(onDismiss: () -> Unit) {
    val at by Hub.sleepAt.collectAsStateWithLifecycle()
    var custom by remember { mutableFloatStateOf(Store.getInt("sleep_custom", 25).toFloat()) }
    SadaDialog(tr("مؤقت النوم", "Sleep timer"), onDismiss) {
        if (at != 0L) {
            Text(sleepLabel(at), fontSize = 40.sp, fontWeight = FontWeight.Black, color = K.Signal, fontFamily = K.Mono)
            Text(tr("سيخفت الصوت تدريجياً قبل الإيقاف.", "Volume fades out before stopping."), color = K.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        @Composable fun opt(min: Int) {
            Text(tr("$min دقيقة", "$min minutes"), color = K.Paper, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().clickable { Hub.sleepAt.value = System.currentTimeMillis() + min * 60_000L; onDismiss() }.padding(vertical = 10.dp))
        }
        listOf(5, 15, 30, 45, 60, 90).forEach { opt(it) }
        Text(tr("نهاية المقطع الحالي", "End of current track"), color = K.Tape, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().clickable { Hub.sleepAt.value = -1L; onDismiss() }.padding(vertical = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(custom, { custom = it.toInt().toFloat() }, valueRange = 1f..240f, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = K.Signal, activeTrackColor = K.Signal))
            Text(tr("${custom.toInt()} د", "${custom.toInt()} m"), color = K.Paper, fontWeight = FontWeight.Black, fontFamily = K.Mono,
                modifier = Modifier.background(K.Signal).clickable {
                    Store.putInt("sleep_custom", custom.toInt()); Hub.sleepAt.value = System.currentTimeMillis() + custom.toInt() * 60_000L; onDismiss()
                }.padding(8.dp))
        }
        if (at != 0L) {
            Spacer(Modifier.height(8.dp))
            GhostButton(tr("إلغاء المؤقت", "Cancel timer"), Icons.Rounded.Close, Modifier.fillMaxWidth()) { Hub.sleepAt.value = 0; onDismiss() }
        }
    }
}
