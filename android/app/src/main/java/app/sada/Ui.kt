// صدى — الهوية البصرية والمكوّنات المشتركة (أسود، ورق، برتقالي إشارة، أصفر شريط — زوايا حادة)
// برمجة: د. محمد توفيق
package app.sada

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri

// ---------- لوحات الألوان (قابلة للتبديل من الإعدادات) ----------
data class Palette(
    val ar: String, val en: String, val light: Boolean,
    val ink: Color, val panel: Color, val panel2: Color, val line: Color,
    val paper: Color, val muted: Color, val signal: Color, val tape: Color,
)

object Themes {
    val list = listOf(
        Palette("ليل بنفسجي", "Midnight Violet", false, Color(0xFF0E0F1A), Color(0xFF171A2B), Color(0xFF21253B), Color(0xFF2E3354),
            Color(0xFFF3F1FF), Color(0xFF8E92B4), Color(0xFF8B6CFF), Color(0xFF2EE6C8)),
        Palette("محيط", "Ocean", false, Color(0xFF07131F), Color(0xFF0F2030), Color(0xFF172C40), Color(0xFF233B54),
            Color(0xFFEAF4FF), Color(0xFF7F9AB5), Color(0xFF2F9BFF), Color(0xFFFF7A59)),
        Palette("زمرد", "Emerald", false, Color(0xFF0B1210), Color(0xFF131D1A), Color(0xFF1B2925), Color(0xFF263832),
            Color(0xFFEFF7F3), Color(0xFF86A096), Color(0xFF22D67E), Color(0xFFFFC94A)),
        Palette("وردي", "Rose", false, Color(0xFF150D12), Color(0xFF21141C), Color(0xFF2C1B25), Color(0xFF3B2632),
            Color(0xFFFFF0F5), Color(0xFFB08A9C), Color(0xFFFF4F8B), Color(0xFFFFB35C)),
        Palette("جمر", "Ember", false, Color(0xFF0D0D0D), Color(0xFF171717), Color(0xFF202020), Color(0xFF2C2C2C),
            Color(0xFFEFEBE3), Color(0xFF8E887E), Color(0xFFFF4F1A), Color(0xFFFFD400)),
        Palette("نهاري", "Daylight", true, Color(0xFFF7F6F2), Color(0xFFFFFFFF), Color(0xFFEDEBE6), Color(0xFFDAD7CF),
            Color(0xFF16161D), Color(0xFF6E6C75), Color(0xFF6A4CFF), Color(0xFF00A88F)),
    )
    val current = mutableStateOf(list[0])
    fun load() { current.value = list.getOrElse(Store.getInt("theme", 0)) { list[0] } }
    fun set(i: Int) { Store.putInt("theme", i); current.value = list[i] }
}

object K {
    private val p get() = Themes.current.value
    val Ink get() = p.ink
    val Panel get() = p.panel
    val Panel2 get() = p.panel2
    val Line get() = p.line
    val Paper get() = p.paper
    val Muted get() = p.muted
    val Signal get() = p.signal
    val Tape get() = p.tape
    /** نص فوق الألوان المميّزة */
    val OnAccent get() = if (p.light) Color.White else Color(0xFF0B0B12)
    val Mono = FontFamily.Monospace
    val R = RoundedCornerShape(12.dp)
    val RS = RoundedCornerShape(8.dp)
}

private val Rounded = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(22.dp),
)

@Composable
fun SadaTheme(content: @Composable () -> Unit) {
    val p = Themes.current.value
    val base = if (p.light) lightColorScheme() else darkColorScheme()
    val scheme = base.copy(
        primary = p.signal, onPrimary = K.OnAccent, secondary = p.tape, onSecondary = K.OnAccent, tertiary = p.paper,
        background = p.ink, onBackground = p.paper, surface = p.panel, onSurface = p.paper,
        surfaceVariant = p.panel2, onSurfaceVariant = p.muted, surfaceContainer = p.panel,
        surfaceContainerHigh = p.panel2, surfaceContainerHighest = p.panel2, outline = p.line, outlineVariant = p.line,
    )
    MaterialTheme(colorScheme = scheme, shapes = Rounded, content = content)
}

// ---------- صورة الغلاف أو إطار الفيديو ----------
@Composable
fun rememberThumb(uri: Uri?, px: Int = 256, fallback: Uri? = null): Bitmap? {
    val ctx = LocalContext.current
    var b by remember(uri, px) { mutableStateOf(uri?.let { Thumbs.peek("$it@$px") }) }
    LaunchedEffect(uri, px) {
        if (uri != null && b == null) b = Thumbs.load(ctx, uri, px) ?: fallback?.let { Thumbs.load(ctx, it, px) }
    }
    return b
}

@Composable
fun Art(t: Track?, size: Dp, modifier: Modifier = Modifier, px: Int = 200, big: Boolean = false) {
    val bmp = rememberThumb(t?.uri, px, t?.let { Thumbs.albumArtUri(it.albumId) })
    ArtBox(bmp, t?.title ?: "", size, modifier, big)
}

@Composable
fun ArtBox(bmp: Bitmap?, label: String, size: Dp, modifier: Modifier = Modifier, big: Boolean = false) {
    Box(modifier.size(size).clip(K.RS).background(K.Panel2), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            // بديل الغلاف: حرف كبير على كتلة لونية ثابتة مشتقة من الاسم
            val palette = listOf(K.Signal, K.Tape, Color(0xFF3D7EFF), Color(0xFF19C37D), Color(0xFFE5484D), K.Paper)
            val col = palette[(label.hashCode() and 0x7fffffff) % palette.size]
            Box(Modifier.fillMaxSize().background(col))
            Text(
                label.trim().take(1).uppercase().ifBlank { "#" },
                color = K.OnAccent, fontWeight = FontWeight.Black,
                fontSize = if (big) 140.sp else (size.value * 0.5f).sp, fontFamily = K.Mono
            )
        }
    }
}

// ---------- عناوين الأقسام بشريط أصفر ----------
@Composable
fun Tape(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = K.OnAccent, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.5.sp,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(K.Tape).padding(horizontal = 8.dp, vertical = 3.dp), fontFamily = K.Mono
    )
}

@Composable
fun BigNum(n: Int, label: String, color: Color = K.Paper) {
    Column {
        Text(n.toString(), fontSize = 40.sp, fontWeight = FontWeight.Black, color = color, fontFamily = K.Mono, lineHeight = 40.sp)
        Text(label, fontSize = 11.sp, color = K.Muted, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        color = if (selected) K.OnAccent else K.Paper,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) K.Signal else K.Panel2)
            .border(1.dp, if (selected) K.Signal else K.Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
fun SolidButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, color: Color = K.Signal, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(K.R).background(color).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) { Icon(icon, null, tint = K.OnAccent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = K.OnAccent, fontWeight = FontWeight.Black, fontSize = 14.sp)
    }
}

@Composable
fun GhostButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(K.R).border(1.5.dp, K.Paper, K.R).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) { Icon(icon, null, tint = K.Paper, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = K.Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

// ---------- صف أغنية ----------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(t: Track, number: Int?, playingNow: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .clip(K.R).background(if (playingNow) K.Panel2 else Color.Transparent)
            .padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (number != null) {
            Text(
                String.format(java.util.Locale.US, "%02d", number), fontFamily = K.Mono, fontWeight = FontWeight.Black,
                color = if (playingNow) K.Signal else K.Muted, fontSize = 15.sp, modifier = Modifier.width(34.dp)
            )
        }
        Box {
            Art(t, 48.dp)
            if (playingNow) Box(Modifier.align(Alignment.BottomStart).size(48.dp, 4.dp).background(K.Signal))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = if (playingNow) K.Signal else K.Paper, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            Text(
                listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { extOf(t.mime, t.path) },
                color = K.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp
            )
        }
        Text(fmtTime(t.duration), color = K.Muted, fontFamily = K.Mono, fontSize = 12.sp)
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, null, tint = K.Muted) }
    }
}

@Composable
fun Empty(title: String, sub: String = "") {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).border(2.dp, K.Line, K.R))
        Spacer(Modifier.height(16.dp))
        Text(title, color = K.Paper, fontWeight = FontWeight.Bold)
        if (sub.isNotBlank()) Text(sub, color = K.Muted, fontSize = 13.sp)
    }
}

@Composable
fun PanelBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(K.R).background(K.Panel).border(1.dp, K.Line, K.R).padding(16.dp), content = content)
}

@Composable
fun SadaDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Panel).border(1.dp, K.Line, RoundedCornerShape(18.dp))
                .heightIn(max = 620.dp)
        ) {
            Row(Modifier.fillMaxWidth().background(K.Signal).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(title, color = K.OnAccent, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

val Mono12 = TextStyle(fontFamily = K.Mono, fontSize = 12.sp, color = K.Muted)
