package app.rawboy.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rawboy.develop.Histogram
import java.util.Locale

/**
 * A compact RGB [histogram] (Pro above the tabs, Camera over the viewfinder): the three channels as filled areas that
 * add up to grey where they overlap, scaled by [Histogram.peak] (taller end bins are cut off at the top), with clip
 * indicators in the top corners, blue for crushed shadows on the left and red for clipped highlights on the right
 * once more than [CLIP_VISIBLE] of the pixels are there, and their share as text. [active]: the photo's clipping
 * warning is on (a light border). Null draws an empty box (before the first render).
 */
@Composable
fun HistogramView(
    histogram: Histogram?,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
    background: Color = Color(0x66000000),
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .clip(shape)
            .background(background)
            .then(if (active) Modifier.border(1.dp, ACTIVE_BORDER, shape) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = "Toggle clipping warning", role = Role.Switch, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = "Histogram"
                if (onClick != null) stateDescription = if (active) "Clipping warning on" else "Clipping warning off"
            },
    ) {
        // Offscreen, so the channels' Plus blending adds them to each other only, not to the background.
        Canvas(
            Modifier
                .matchParentSize()
                .padding(horizontal = 4.dp, vertical = 3.dp)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            val h = histogram ?: return@Canvas
            clipRect {
                channel(h.red, h.peak, CHANNEL_RED)
                channel(h.green, h.peak, CHANNEL_GREEN)
                channel(h.blue, h.peak, CHANNEL_BLUE)
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val shadows = (histogram?.shadows ?: 0f) > CLIP_VISIBLE
            val highlights = (histogram?.highlights ?: 0f) > CLIP_VISIBLE
            corner(left = true, if (shadows) SHADOW_COLOR else IDLE_COLOR)
            corner(left = false, if (highlights) HIGHLIGHT_COLOR else IDLE_COLOR)
        }
        histogram?.shadows?.takeIf { it > CLIP_VISIBLE }?.let {
            ClipLabel(it, Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 1.dp))
        }
        histogram?.highlights?.takeIf { it > CLIP_VISIBLE }?.let {
            ClipLabel(it, Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 1.dp))
        }
    }
}

/** A clipped share as a short percentage ("<0.1%", "2.4%", "18%"). */
@Composable
private fun ClipLabel(fraction: Float, modifier: Modifier) {
    val pct = fraction * 100f
    val text = when {
        pct < 0.1f -> "<0.1%"
        pct < 10f -> String.format(Locale.US, "%.1f%%", pct)
        else -> String.format(Locale.US, "%.0f%%", pct)
    }
    Text(text, color = LABEL_COLOR, fontSize = 9.sp, lineHeight = 10.sp, modifier = modifier)
}

/** One channel as a filled area through the bin centres, down to the baseline at both ends. */
private fun DrawScope.channel(bins: IntArray, peak: Int, color: Color) {
    val w = size.width
    val h = size.height
    val n = bins.size
    val top = h * 0.05f // headroom, so the peak doesn't touch the edge
    val path = Path().apply {
        moveTo(0f, h)
        for (i in 0 until n) {
            val x = (i + 0.5f) / n * w
            val y = h - bins[i].toFloat() / peak * (h - top)
            if (i == 0) lineTo(0f, y)
            lineTo(x, y)
            if (i == n - 1) lineTo(w, y)
        }
        lineTo(w, h)
        close()
    }
    drawPath(path, color, blendMode = BlendMode.Plus)
}

/** A small triangle in a top corner: the clip indicator. */
private fun DrawScope.corner(left: Boolean, color: Color) {
    val s = 7.dp.toPx()
    val inset = 3.dp.toPx()
    val x0 = if (left) inset else size.width - inset
    val dx = if (left) s else -s
    val path = Path().apply {
        moveTo(x0, inset)
        lineTo(x0 + dx, inset)
        lineTo(x0, inset + s)
        close()
    }
    drawPath(path, color)
}

/** Share of pixels above which a clip indicator lights up (0.1%). */
private const val CLIP_VISIBLE = 0.001f

private val CHANNEL_RED = Color(0xFFFF3030).copy(alpha = 0.6f)
private val CHANNEL_GREEN = Color(0xFF30FF30).copy(alpha = 0.6f)
private val CHANNEL_BLUE = Color(0xFF3060FF).copy(alpha = 0.6f)
private val IDLE_COLOR = Color(0xFF808080)
private val SHADOW_COLOR = Color(0xFF4A8CFF)
private val HIGHLIGHT_COLOR = Color(0xFFFF4040)
private val LABEL_COLOR = Color(0xDDFFFFFF)
private val ACTIVE_BORDER = Color(0xCCFFFFFF)
