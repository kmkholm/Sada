// صدى — المزيد: المعادل والمؤثرات، فتح ملف أو رابط بث، الإعدادات، عن المطوّر
// برمجة: د. محمد توفيق
package app.sada

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@Composable
fun MoreTab(nav: Nav, act: MainActivity) {
    val ctx = LocalContext.current
    var urlDlg by remember { mutableStateOf(false) }
    var sleepDlg by remember { mutableStateOf(false) }
    val sleepAt by Hub.sleepAt.collectAsStateWithLifecycle()
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val mime = ctx.contentResolver.getType(u) ?: ""
            if (mime.startsWith("video")) ctx.startActivity(Intent(ctx, VideoActivity::class.java).setData(u))
            else { Engine.playUri(u, displayName(ctx, u)); act.openPlayer.value = true }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header(tr("المزيد", "More")) { LangSwitch() }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigTile(tr("المعادل والمؤثرات", "Equalizer & FX"), Icons.Rounded.GraphicEq, K.Signal, Modifier.weight(1f)) { nav.push(Scr.Effects) }
            BigTile(if (sleepAt != 0L) sleepLabel(sleepAt) else tr("مؤقت النوم", "Sleep timer"), Icons.Rounded.Bedtime, K.Tape, Modifier.weight(1f)) { sleepDlg = true }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigTile(tr("فتح ملف", "Open file"), Icons.Rounded.FileOpen, K.Paper, Modifier.weight(1f)) { open.launch(arrayOf("audio/*", "video/*", "application/ogg", "application/x-matroska")) }
            BigTile(tr("بث من رابط", "Network stream"), Icons.Rounded.Language, Color(0xFF3D7EFF), Modifier.weight(1f)) { urlDlg = true }
        }
        Spacer(Modifier.height(16.dp))
        Tape(tr("التطبيق", "App"), Modifier.padding(horizontal = 16.dp))
        MenuRow(Icons.Rounded.Settings, tr("الإعدادات", "Settings"), tr("اللغة، تخطّي الصمت، مدة الحد الأدنى، إعادة الفحص", "Language, skip silence, minimum length, rescan")) { nav.push(Scr.Settings) }
        MenuRow(Icons.Rounded.Refresh, tr("إعادة فحص المكتبة", "Rescan library"), tr("البحث عن ملفات جديدة على الجهاز", "Look for new files on the device")) { act.rescan() }
        MenuRow(Icons.Rounded.Download, tr("التنزيلات", "Downloads"), tr("يوتيوب، X، فيسبوك، إنستغرام، تيك توك…", "YouTube, X, Facebook, Instagram, TikTok…")) { nav.push(Scr.Downloads) }
        MenuRow(Icons.Rounded.Folder, tr("المجلدات", "Folders"), "") { nav.push(Scr.FolderList) }
        MenuRow(Icons.Rounded.Badge, tr("عن المطوّر", "About the developer"), Dev.NAME_AR + " · " + Dev.NAME_EN) { nav.push(Scr.About) }
        Spacer(Modifier.height(16.dp))
        Tape(tr("الصيغ المدعومة", "Supported formats"), Modifier.padding(horizontal = 16.dp))
        Text(FORMATS + "\nONLINE YouTube · X/Twitter · Facebook · Instagram · TikTok · SoundCloud · Vimeo · 1000+ (yt-dlp)", style = Mono12, color = K.Paper, modifier = Modifier.padding(16.dp))
    }
    if (urlDlg) UrlDialog { urlDlg = false }
    if (sleepDlg) SleepDialog { sleepDlg = false }
}

const val FORMATS = "AUDIO  MP3 · AAC · M4A · FLAC · ALAC · WAV · OGG · OPUS · WMA* · AC3 · E-AC3 · DTS* · TrueHD* · AMR · MIDI · APE*\n" +
    "VIDEO  MP4 · MKV · WEBM · AVI · MOV · 3GP · TS · M2TS · FLV · MPEG · WMV*\n" +
    "CODEC  H.264 · H.265/HEVC · VP8 · VP9 · AV1 (hardware) + FFmpeg software audio\n" +
    "STREAM HLS (m3u8) · DASH (mpd) · HTTP progressive · RTSP\n" +
    "SUBS   SRT · VTT · ASS/SSA · TTML · embedded MKV/MP4 tracks\n" +
    "* depends on the device decoder / FFmpeg build"

@Composable
fun BigTile(title: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(118.dp).clip(K.R).background(color).clickable(onClick = onClick).padding(14.dp)) {
        Icon(icon, null, tint = K.OnAccent, modifier = Modifier.size(30.dp))
        Spacer(Modifier.weight(1f))
        Text(title, color = K.OnAccent, fontWeight = FontWeight.Black, fontSize = 17.sp, lineHeight = 19.sp)
    }
}

@Composable
fun MenuRow(icon: ImageVector, title: String, sub: String, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = K.Signal)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = K.Paper, fontWeight = FontWeight.Bold)
            if (sub.isNotBlank()) Text(sub, color = K.Muted, fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
fun UrlDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(Store.getStr("last_url") ?: "https://") }
    SadaDialog(tr("بث من رابط", "Network stream"), onDismiss) {
        Text(tr("رابط مباشر لملف صوت/فيديو، أو بث HLS ‎(.m3u8)‎ أو DASH ‎(.mpd)‎ أو RTSP.", "Direct audio/video link, HLS (.m3u8), DASH (.mpd) or RTSP stream."), color = K.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SolidButton(tr("فيديو", "Video"), Icons.Rounded.Movie, modifier = Modifier.weight(1f)) {
                if (url.length > 8) { Store.putStr("last_url", url); ctx.startActivity(Intent(ctx, VideoActivity::class.java).setData(Uri.parse(url.trim()))); onDismiss() }
            }
            SolidButton(tr("صوت فقط", "Audio only"), Icons.Rounded.Headphones, color = K.Tape, modifier = Modifier.weight(1f)) {
                if (url.length > 8) { Store.putStr("last_url", url); Engine.playUri(Uri.parse(url.trim()), url.substringAfterLast('/')); onDismiss() }
            }
        }
    }
}

// =================== المعادل والمؤثرات ===================
@Composable
fun EffectsScreen(nav: Nav) {
    val ready by Fx.ready.collectAsStateWithLifecycle()
    val bands by Fx.bands.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(Fx.eqEnabled()) }
    var preset by remember { mutableIntStateOf(Store.getInt("eq_preset", -1)) }
    var bass by remember { mutableFloatStateOf(Store.getInt("bass", 0).toFloat()) }
    var virt by remember { mutableFloatStateOf(Store.getInt("virt", 0).toFloat()) }
    var loud by remember { mutableFloatStateOf(Store.getInt("loud", 0).toFloat()) }
    var reverb by remember { mutableIntStateOf(Store.getInt("reverb", 0)) }
    var skip by remember { mutableStateOf(Store.getBool("skip_silence", false)) }
    val (lo, hi) = Fx.range

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header(tr("المعادل والمؤثرات", "Equalizer & effects"), nav.back) {
            Switch(on, { on = it; Fx.setEqEnabled(it) }, colors = SwitchDefaults.colors(checkedTrackColor = K.Signal, checkedThumbColor = K.OnAccent))
            Spacer(Modifier.width(12.dp))
        }
        if (!ready) {
            PanelBox(Modifier.padding(16.dp)) {
                Text(tr("شغّل أي أغنية أولاً ليتصل المعادل بمحرّك الصوت.", "Play any song first so the equalizer can attach to the audio engine."), color = K.Paper)
            }
        }
        // منحنى المعادل: أعمدة رأسية كبيرة
        if (bands.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(250.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                bands.forEachIndexed { i, (hz, lvl) ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(String.format(Locale.US, "%+.0f", lvl / 100f), style = Mono12, color = if (lvl != 0) K.Signal else K.Muted)
                        VBar(lvl, lo, hi, on, Modifier.weight(1f)) { Fx.setBand(i, it); preset = -1 }
                        Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", style = Mono12, color = K.Paper)
                    }
                }
            }
            Text(tr("اسحب الأعمدة لأعلى وأسفل (ديسيبل)", "Drag the bars up and down (dB)"), color = K.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (Fx.presets.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(tr("مخصّص", "Custom"), preset < 0) { preset = -1 }
                Fx.presets.forEachIndexed { i, n -> Chip(n, preset == i) { preset = i; Fx.usePreset(i); if (!on) { on = true; Fx.setEqEnabled(true) } } }
                Chip(tr("تصفير", "Flat"), false) { Fx.resetBands(); preset = -1 }
            }
        }
        Spacer(Modifier.height(8.dp))
        FxSlider(tr("تعزيز الجهير", "Bass boost"), Icons.Rounded.Speaker, bass, 0f..1000f, "${(bass / 10).toInt()}%") { bass = it; Fx.setBass(it.toInt()) }
        FxSlider(tr("صوت محيطي ثلاثي الأبعاد", "3D surround"), Icons.Rounded.SurroundSound, virt, 0f..1000f, "${(virt / 10).toInt()}%") { virt = it; Fx.setVirt(it.toInt()) }
        FxSlider(tr("مضخّم الصوت", "Volume booster"), Icons.Rounded.VolumeUp, loud, 0f..2000f, String.format(Locale.US, "+%.1f dB", loud / 100f)) { loud = it; Fx.setLoud(it.toInt()) }
        if (loud > 1000) Text(tr("تنبيه: التضخيم العالي قد يسبب تشوّهاً ويضرّ السمع.", "Warning: high boost may distort and harm hearing."), color = K.Tape, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))

        Spacer(Modifier.height(12.dp))
        Tape(tr("صدى القاعة", "Reverb"), Modifier.padding(horizontal = 16.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(tr("بلا", "Off"), tr("غرفة صغيرة", "Small room"), tr("غرفة متوسطة", "Medium room"), tr("غرفة كبيرة", "Large room"),
                tr("قاعة متوسطة", "Medium hall"), tr("قاعة كبيرة", "Large hall"), tr("صفيحة", "Plate")).forEachIndexed { i, n ->
                Chip(n, reverb == i) { reverb = i; Fx.setReverb(i) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("تخطّي الصمت", "Skip silence"), color = K.Paper, fontWeight = FontWeight.Bold)
                Text(tr("يقفز فوق المقاطع الصامتة في الكتب الصوتية والبودكاست", "Jumps over silent gaps in audiobooks and podcasts"), color = K.Muted, fontSize = 12.sp)
            }
            Switch(skip, { skip = it; Engine.setSkipSilence(it) }, colors = SwitchDefaults.colors(checkedTrackColor = K.Signal, checkedThumbColor = K.OnAccent))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun VBar(level: Int, lo: Int, hi: Int, enabled: Boolean, modifier: Modifier, onChange: (Int) -> Unit) {
    val cb by rememberUpdatedState(onChange)
    androidx.compose.foundation.Canvas(
        modifier.width(34.dp).padding(vertical = 6.dp).background(K.Panel).border(1.dp, K.Line)
            .pointerInput(lo, hi) {
                fun emit(y: Float) {
                    val frac = 1f - (y / size.height).coerceIn(0f, 1f)
                    cb((lo + frac * (hi - lo)).toInt().let { (it / 50) * 50 })
                }
                awaitEachGesture {
                    val d = awaitFirstDown(); emit(d.position.y); d.consume()
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        emit(c.position.y); c.consume()
                    }
                }
            }
    ) {
        val frac = ((level - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
        val mid = (0f - lo) / (hi - lo)
        val top = size.height * (1f - frac)
        drawRect(if (enabled) K.Signal else K.Muted, androidx.compose.ui.geometry.Offset(4f, top), androidx.compose.ui.geometry.Size(size.width - 8f, size.height - top))
        drawRect(K.Tape, androidx.compose.ui.geometry.Offset(0f, size.height * (1f - mid) - 2f), androidx.compose.ui.geometry.Size(size.width, 4f))
    }
}

@Composable
fun FxSlider(title: String, icon: ImageVector, value: Float, range: ClosedFloatingPointRange<Float>, label: String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = K.Signal); Spacer(Modifier.width(10.dp))
            Text(title, color = K.Paper, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(label, style = Mono12, color = K.Tape)
        }
        Slider(value, onChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = K.Signal, activeTrackColor = K.Signal, inactiveTrackColor = K.Line))
    }
}

// =================== الإعدادات ===================
@Composable
fun SettingsScreen(nav: Nav, act: MainActivity) {
    val ar = LocalArabic.current
    var minSec by remember { mutableIntStateOf(Store.getInt("min_sec", 10)) }
    var pip by remember { mutableStateOf(Store.getBool("auto_pip", true)) }
    var resume by remember { mutableStateOf(Store.getBool("resume_video", true)) }
    var skip by remember { mutableStateOf(Store.getBool("skip_silence", false)) }
    var ytq by remember { mutableIntStateOf(Store.getInt("yt_q", 720)) }
    val sw = SwitchDefaults.colors(checkedTrackColor = K.Signal, checkedThumbColor = K.OnAccent)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header(tr("الإعدادات", "Settings"), nav.back)
        Tape(tr("اللغة", "Language"), Modifier.padding(16.dp, 8.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("العربية", ar) { Store.setArabic(true) }
            Chip("English", !ar) { Store.setArabic(false) }
        }
        Tape(tr("الألوان", "Colours"), Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
        Box(Modifier.padding(horizontal = 16.dp)) { ThemeGrid() }
        Tape(tr("يوتيوب والبث", "YouTube & streaming"), Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
        Text(tr("جودة الفيديو عند التشغيل أونلاين:", "Online video playback quality:"), color = K.Paper, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(16.dp, 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(360, 480, 720, 1080).forEach { v -> Chip("${v}p", ytq == v) { ytq = v; Store.putInt("yt_q", v) } }
        }
        Tape(tr("الموسيقى", "Music"), Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
        MenuRow(Icons.Rounded.FastForward, tr("تخطّي الصمت", "Skip silence"), "", trailing = { Switch(skip, { skip = it; Engine.setSkipSilence(it) }, colors = sw) }) { skip = !skip; Engine.setSkipSilence(skip) }
        Text(tr("تجاهل المقاطع الأقصر من:", "Ignore audio shorter than:"), color = K.Paper, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 10, 30, 60).forEach { s -> Chip(if (s == 0) tr("لا شيء", "None") else "${s}s", minSec == s) { minSec = s; Store.putInt("min_sec", s); act.rescan() } }
        }
        Tape(tr("الفيديو", "Video"), Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
        MenuRow(Icons.Rounded.PictureInPicture, tr("صورة داخل صورة تلقائياً", "Auto picture-in-picture"), tr("عند الخروج من التطبيق أثناء التشغيل", "When leaving the app while playing"),
            trailing = { Switch(pip, { pip = it; Store.putBool("auto_pip", it) }, colors = sw) }) { pip = !pip; Store.putBool("auto_pip", pip) }
        MenuRow(Icons.Rounded.History, tr("الاستئناف من آخر موضع", "Resume where you left off"), "",
            trailing = { Switch(resume, { resume = it; Store.putBool("resume_video", it) }, colors = sw) }) { resume = !resume; Store.putBool("resume_video", resume) }
        Spacer(Modifier.height(16.dp))
        MenuRow(Icons.Rounded.Refresh, tr("إعادة فحص المكتبة الآن", "Rescan library now"), "") { act.rescan() }
    }
}

// =================== عن المطوّر ===================
@Composable
fun AboutScreen(nav: Nav) {
    val ctx = LocalContext.current
    fun open(u: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header(tr("عن المطوّر", "About"), nav.back)
        Column(Modifier.fillMaxWidth().padding(16.dp).clip(K.R).background(K.Paper).padding(20.dp)) {
            Tape(tr("ملف المطوّر", "Developer file"))
            Spacer(Modifier.height(14.dp))
            Text(Dev.NAME_AR, color = K.OnAccent, fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp)
            Text(Dev.NAME_EN, color = K.OnAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.padding(vertical = 12.dp).width(80.dp).height(6.dp).background(K.Signal))
            Text(tr(Dev.ROLE_AR, Dev.ROLE_EN), color = K.OnAccent, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(tr("باحث في الذكاء الاصطناعي والأمن السيبراني", "AI & cybersecurity researcher"), color = K.OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        ContactRow(Icons.Rounded.Email, Dev.EMAIL) { open("mailto:" + Dev.EMAIL + "?subject=" + Uri.encode("Sada Player")) }
        ContactRow(Icons.Rounded.Code, "github.com/kmkholm") { open(Dev.GITHUB) }
        ContactRow(Icons.Rounded.Public, "kmkholm.github.io") { open(Dev.SITE) }
        ContactRow(Icons.Rounded.Badge, "ORCID " + Dev.ORCID) { open("https://orcid.org/" + Dev.ORCID) }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).border(1.dp, K.Line).padding(16.dp)) {
            Text(tr("صدى", "SADA") + "  v" + runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull(), color = K.Paper, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(tr("مشغّل موسيقى وفيديو أصلي، بلا إعلانات وبلا تتبّع. كل ملفاتك تبقى على جهازك.",
                "Native music & video player. No ads, no tracking. Your files never leave your phone."), color = K.Muted)
            Spacer(Modifier.height(8.dp))
            Text("Kotlin · Jetpack Compose · AndroidX Media3 (ExoPlayer) · FFmpeg", style = Mono12)
            Spacer(Modifier.height(8.dp))
            Text("© 2026 " + Dev.NAME_EN, style = Mono12, color = K.Paper)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ContactRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(K.RS).background(K.Signal), contentAlignment = Alignment.Center) { Icon(icon, null, tint = K.OnAccent) }
        Spacer(Modifier.width(14.dp))
        Text(text, color = K.Paper, fontWeight = FontWeight.Bold, fontFamily = K.Mono, fontSize = 14.sp)
    }
}
