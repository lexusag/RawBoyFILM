package app.rawboy.view

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.rawboy.gl.PreviewImage
import kotlinx.coroutines.delay

/** How long the "double-tap for 100%" hint stays up after a photo opens. */
private const val ZOOM_HINT_MS = 4000L
private const val PREFS = "display"
/** Set once the user has zoomed: the hint has done its job and stops showing. */
private const val KEY_SEEN = "zoomHintSeen"

/** Text of the zoom hint shown over the preview. */
const val ZOOM_HINT_TEXT = "Double-tap for 100% · drag to pan"

/**
 * Whether to show the zoom hint: for a few seconds after each new photo appears, until the user has zoomed once
 * (remembered across launches). [enabled] = false while zoom isn't available (e.g. the crop tool).
 */
@Composable
fun rememberZoomHint(image: PreviewImage?, zoomCenter: Pair<Float, Float>?, enabled: Boolean = true): Boolean {
    val prefs = LocalContext.current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(image, enabled) {
        visible = false
        if (image == null || zoomCenter != null || !enabled || prefs.getBoolean(KEY_SEEN, false)) return@LaunchedEffect
        visible = true
        delay(ZOOM_HINT_MS)
        visible = false
    }
    LaunchedEffect(zoomCenter) {
        if (zoomCenter == null) return@LaunchedEffect
        visible = false
        prefs.edit().putBoolean(KEY_SEEN, true).apply()
    }
    return visible
}
