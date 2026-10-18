package app.rawboy.view

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.rawboy.gl.DetailTile
import app.rawboy.gl.DevelopRenderer
import app.rawboy.gl.FrameColor
import app.rawboy.gl.PreviewImage
import app.rawboy.gl.ShaderParams

@Composable
fun DevelopPreview(
    image: PreviewImage?,
    params: ShaderParams,
    zoomCenter: Pair<Float, Float>?,
    detail: DetailTile?,
    frameWidth: Int,
    frameColor: FrameColor,
    modifier: Modifier = Modifier,
    /** Empty space around the frame in the fit view, pixels (the mask tool's room for handles). */
    margin: Int = 0,
    /** Marks clipped highlights red and crushed shadows blue (Pro's histogram toggles it). */
    clipWarning: Boolean = false,
) {
    val renderer = remember { DevelopRenderer() }
    var view by remember { mutableStateOf<GLSurfaceView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(3)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }.also { view = it }
        },
    )

    LaunchedEffect(image, view) {
        renderer.setImage(image)
        view?.requestRender()
    }
    LaunchedEffect(params, view) {
        renderer.params = params
        view?.requestRender()
    }
    LaunchedEffect(detail, view) {
        renderer.setDetail(detail)
        view?.requestRender()
    }
    LaunchedEffect(frameWidth, frameColor, margin, view) {
        renderer.frameWidth = frameWidth
        renderer.frameGrey = frameColor.level
        renderer.margin = margin
        view?.requestRender()
    }
    LaunchedEffect(clipWarning, view) {
        renderer.clipWarning = clipWarning
        view?.requestRender()
    }
    LaunchedEffect(zoomCenter, view) {
        renderer.zoomCenter = zoomCenter
        view?.requestRender()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
