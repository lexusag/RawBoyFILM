package app.rawboy.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One-row slider (label | slider | value) with a thin thumb and track, so a stack of them leaves the
 * photo most of the screen. Double-tap the slider or tap the value to reset to [default].
 * [trackBrush] paints the whole track instead of the active/inactive colours (e.g. a colour gradient).
 * [stacked] puts the label and value above the slider instead (label and value widths unused), so two can share a row
 * and still have a usable track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 44.dp,
    labelWidth: Dp = 96.dp,
    valueWidth: Dp = 72.dp,
    format: (Float) -> String = { formatSliderValue(it, range) },
    trackBrush: Brush? = null,
    stacked: Boolean = false,
    onChange: (Float) -> Unit,
) {
    val labelText = @Composable { m: Modifier ->
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m)
    }
    val valueText = @Composable { m: Modifier ->
        Text(
            format(value),
            style = MaterialTheme.typography.bodyMedium,
            color = if (value == default) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.clickable { onChange(default) }.then(m),
        )
    }
    val slider = @Composable { m: Modifier, height: Dp ->
        val taps = remember { SliderTapTracker() }
        val interaction = remember { MutableInteractionSource() }
        val colors = SliderDefaults.colors()
        // The row is the touch target; Material's 48dp minimum would double its height.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Slider(
                value = value.coerceIn(range),
                onValueChange = {
                    taps.onValueChange()
                    onChange(it)
                },
                onValueChangeFinished = { if (taps.onGestureFinished()) onChange(default) },
                valueRange = range,
                interactionSource = interaction,
                colors = colors,
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = interaction,
                        colors = colors,
                        thumbSize = DpSize(4.dp, (height - 16.dp).coerceAtLeast(16.dp)),
                    )
                },
                track = { state ->
                    if (trackBrush != null) {
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(trackBrush))
                    } else if (range.start < 0f && range.endInclusive > 0f) {
                        SignedTrack(value.coerceIn(range), range, colors.activeTrackColor, colors.inactiveTrackColor)
                    } else SliderDefaults.Track(
                        sliderState = state,
                        colors = colors,
                        modifier = Modifier.height(8.dp),
                        drawStopIndicator = null,
                        thumbTrackGapSize = 3.dp,
                    )
                },
                modifier = m.height(height),
            )
        }
    }
    if (stacked) {
        Column(modifier.fillMaxWidth().height(rowHeight)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(STACKED_LABEL_ROW)) {
                labelText(Modifier.weight(1f))
                valueText(Modifier.padding(start = 8.dp))
            }
            slider(Modifier.fillMaxWidth(), (rowHeight - STACKED_LABEL_ROW).coerceAtLeast(24.dp))
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().height(rowHeight)) {
            labelText(Modifier.width(labelWidth))
            slider(Modifier.weight(1f).padding(horizontal = 8.dp), rowHeight)
            valueText(Modifier.width(valueWidth).padding(vertical = 6.dp))
        }
    }
}

/**
 * Track of a signed range (−100..100 and the like), filled from 0 to the value with a mark at 0. Material's own
 * track always fills from the left end, so every slider at 0 looked half applied.
 */
@Composable
private fun SignedTrack(value: Float, range: ClosedFloatingPointRange<Float>, active: Color, inactive: Color) {
    val zeroMark = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val span = range.endInclusive - range.start
        val zero = size.width * (-range.start / span)
        val at = size.width * ((value - range.start) / span)
        drawRoundRect(inactive, cornerRadius = CornerRadius(size.height / 2))
        drawRect(active, topLeft = Offset(min(zero, at), 0f), size = Size(abs(at - zero), size.height))
        val mark = 2.dp.toPx()
        drawRect(zeroMark, topLeft = Offset(zero - mark / 2, 0f), size = Size(mark, size.height))
    }
}

/** Height of a [CompactSlider]'s label line when stacked. */
private val STACKED_LABEL_ROW = 20.dp

/** Whole numbers, with a + on positive values of a signed range. */
fun formatSliderValue(v: Float, range: ClosedFloatingPointRange<Float>): String {
    val shown = v.roundToInt()
    return if (range.start < 0f && shown > 0) "+$shown" else "$shown"
}
