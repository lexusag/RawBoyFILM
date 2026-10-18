package app.rawboy.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import app.rawboy.camera.ui.CameraScreen

class CameraActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onPermission(granted)
    }

    /** The UI stays portrait; the device's rotation only sets each photo's orientation. */
    private val orientation by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                viewModel.deviceDegrees = ((degrees + 45) / 90 * 90) % 360
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = CameraColors) {
                CameraScreen(viewModel, requestPermission = { permission.launch(Manifest.permission.CAMERA) })
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewModel.onPermission(true)
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    /** Volume up / down take a photo (one per press; holding doesn't repeat), as in Google Camera. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolumeKey(keyCode) && !viewModel.settingsOpen) {
            if (event.repeatCount == 0) viewModel.shutter()
            return true // consumed: no volume panel
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (isVolumeKey(keyCode) && !viewModel.settingsOpen) true else super.onKeyUp(keyCode, event)

    private fun isVolumeKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    override fun onResume() {
        super.onResume()
        orientation.enable()
        viewModel.onResume()
    }

    override fun onPause() {
        orientation.disable()
        viewModel.onPause()
        super.onPause()
    }
}

private val CameraColors = darkColorScheme(
    primary = Color(0xFFFFB74D),
    onPrimary = Color(0xFF2B1700),
    secondary = Color(0xFF80CBC4),
    surface = Color(0xFF1B1B1B),
    background = Color(0xFF000000),
)
