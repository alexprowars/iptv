package home.iptv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import home.iptv.ui.theme.IptvColors
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text

internal val SidebarOuterPadding = 8.dp

internal enum class MenuIcon { Channels, Favorite, Guide, Search, Sources, Settings, Playlist, Group }

@Composable
internal fun MenuHeading(title: String) {
    Text(title.uppercase(), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 2.dp))
}

@Composable
internal fun MenuEntry(
    title: String,
    icon: MenuIcon,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    TvButton(title, onClick,
        modifier.fillMaxWidth().heightIn(min = 40.dp).semantics { this.selected = selected },
        containerColor = if (selected) IptvColors.Selected else Color.Transparent,
        focusedContainerColor = IptvColors.Focus,
        cornerRadius = 10.dp,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        val tint = LocalContentColor.current
        MenuGlyph(icon, tint)
        Spacer(Modifier.width(10.dp))
        Text(title, modifier = Modifier.weight(1f), fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(count.toString(), fontSize = 11.sp, color = tint.copy(alpha = .72f))
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(5.dp).background(tint, RoundedCornerShape(50)))
        }
    }
}

@Composable
internal fun MenuHeader(groupsOnly: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
        Box(Modifier.size(38.dp).background(IptvColors.Selected, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) { MenuGlyph(MenuIcon.Channels, Accent) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (groupsOnly) "Каналы" else "IPTV", fontSize = 22.sp,
                fontWeight = FontWeight.Bold, color = IptvColors.Text)
            Text(if (groupsOnly) "Выберите, что смотреть" else "Телевидение для вас",
                fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable
internal fun MenuFooter(clock: String, count: Int, hint: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(IptvColors.Outline))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = IptvColors.Text)
            Text("Каналов: $count", fontSize = 11.sp, color = Muted)
        }
        if (hint != null) Text(hint, fontSize = 10.sp, color = Muted)
    }
}

// Small, consistent outline icons without an additional icon dependency.
@Composable
private fun MenuGlyph(icon: MenuIcon, tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(width = 1.7f * unit, cap = StrokeCap.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit),
                strokeWidth = stroke.width, cap = StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float) =
            drawRoundRect(tint, Offset(x * unit, y * unit), Size(w * unit, h * unit),
                CornerRadius(2f * unit), style = stroke)
        when (icon) {
            MenuIcon.Channels -> { rect(3f, 4f, 18f, 13f); line(8f, 21f, 16f, 21f); line(12f, 17f, 12f, 21f) }
            MenuIcon.Favorite -> {
                val path = Path()
                val points = listOf(12f to 2f, 15f to 8.5f, 22f to 9f, 17f to 14f,
                    18.5f to 21f, 12f to 17.5f, 5.5f to 21f, 7f to 14f, 2f to 9f, 9f to 8.5f)
                points.forEachIndexed { index, (x, y) ->
                    if (index == 0) path.moveTo(x * unit, y * unit) else path.lineTo(x * unit, y * unit)
                }
                path.close()
                drawPath(path, tint, style = stroke)
            }
            MenuIcon.Guide -> {
                rect(3f, 5f, 18f, 16f); line(3f, 10f, 21f, 10f)
                line(8f, 3f, 8f, 7f); line(16f, 3f, 16f, 7f)
                line(7f, 14f, 11f, 14f); line(7f, 17f, 16f, 17f)
            }
            MenuIcon.Search -> {
                drawCircle(tint, 7f * unit, Offset(10f * unit, 10f * unit), style = stroke)
                line(15f, 15f, 21f, 21f)
            }
            MenuIcon.Sources -> {
                rect(3f, 3f, 18f, 7f); rect(3f, 14f, 18f, 7f)
                line(7f, 6.5f, 7.5f, 6.5f); line(7f, 17.5f, 7.5f, 17.5f)
            }
            MenuIcon.Settings -> {
                line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f)
                rect(7f, 4f, 3f, 4f); rect(14f, 10f, 3f, 4f); rect(8f, 16f, 3f, 4f)
            }
            MenuIcon.Playlist -> {
                line(4f, 5f, 20f, 5f); line(4f, 11f, 20f, 11f); line(4f, 17f, 11f, 17f)
                val path = Path().apply {
                    moveTo(16f * unit, 15f * unit); lineTo(21f * unit, 18f * unit)
                    lineTo(16f * unit, 21f * unit); close()
                }
                drawPath(path, tint, style = stroke)
            }
            MenuIcon.Group -> {
                rect(3f, 3f, 7f, 7f); rect(14f, 3f, 7f, 7f)
                rect(3f, 14f, 7f, 7f); rect(14f, 14f, 7f, 7f)
            }
        }
    }
}
