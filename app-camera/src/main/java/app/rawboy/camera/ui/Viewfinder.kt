package app.rawboy.camera.ui

import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.rawboy.gl.ShaderParams
import app.rawboy.gl.ViewfinderRenderer

/** The live camera preview, developed with [params] (see [ViewfinderRenderer]). */
@Composable
fun Viewfinder(
    frame: ViewfinderRenderer.Frame?,
    params: ShaderParams?,
    onSurfaceTexture: (SurfaceTexture) -> Unit,
    onBase: (width: Int, height: Int, rgba: FloatArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaceCallback = rememberUpdatedState(onSurfaceTexture)
    val baseCallback = rememberUpdatedState(onBase)
    val view = remember { arrayOfNulls<GLSurfaceView>(1) }
    val renderer = remember {
        ViewfinderRenderer(
            onSurfaceTexture = { surfaceCallback.value(it) },
            requestRender = { view[0]?.requestRender() },
            onBase = { w, h, rgba -> baseCallback.value(w, h, rgba) },
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(3)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                // Frames are drawn as the camera delivers them (SurfaceTexture's frame listener).
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }.also { view[0] = it }
        },
    )

    LaunchedEffect(frame) {
        renderer.frame = frame
        view[0]?.requestRender()
    }
    LaunchedEffect(params) {
        renderer.params = params
        view[0]?.requestRender()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> view[0]?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            renderer.release()
        }
    }
}
