package app.rawboy.camera.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import app.rawboy.camera.capture.FlashMode
import app.rawboy.view.HistogramView
import android.content.Intent
import android.hardware.camera2.CameraMetadata
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rawboy.auto.looks.LookApplier
import app.rawboy.camera.CameraSettings
import app.rawboy.camera.CameraStatus
import app.rawboy.camera.CameraViewModel
import app.rawboy.camera.Edition
import app.rawboy.view.LicensesDialog
import app.rawboy.view.CompactSlider
import app.rawboy.view.HelpButton
import app.rawboy.view.LookDropdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Viewfinder with the look controls, shutter and the last photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(vm: CameraViewModel, requestPermission: () -> Unit) {
    val status by vm.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val params by vm.shaderParams.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val lastShot by vm.lastShot.collectAsStateWithLifecycle()
    val compare by vm.compare.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val lenses by vm.lenses.collectAsStateWithLifecycle()
    val frontAvailable by vm.frontAvailable.collectAsStateWithLifecycle()
    val focusPoint by vm.focusPoint.collectAsStateWithLifecycle()
    val flashAvailable by vm.flashAvailable.collectAsStateWithLifecycle()
    val histogram by vm.histogram.collectAsStateWithLifecycle()
    // Only the AF state of the camera's per-frame metadata, so the screen doesn't recompose 30 times a second.
    val afState by remember(vm) { vm.controller.frames.map { it?.afState }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(null)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    val appName = remember { context.applicationInfo.loadLabel(context.packageManager).toString() }
    var showHelp by remember { mutableStateOf(false) }
    // The volume keys don't shoot while a sheet is open.
    LaunchedEffect(showSettings, showHelp) { vm.settingsOpen = showSettings || showHelp }

    // Shutter feedback: the viewfinder blinks dark, the shutter button dips, and a haptic tick.
    val blink = remember { Animatable(0f) }
    val shutterScale = remember { Animatable(1f) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(vm) {
        vm.shutterEvents.collect {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            launch {
                shutterScale.snapTo(0.85f)
                shutterScale.animateTo(1f, tween(220))
            }
            blink.snapTo(0f)
            blink.animateTo(0.85f, tween(60))
            blink.animateTo(0f, tween(160))
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // A Surface, so text and icons get the theme's light content colour on black.
    Surface(color = Color.Black, contentColor = Color.White) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (pending > 0) "Saving $pending…" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                RawButton(settings.keepDng) { vm.setKeepDng(!settings.keepDng) }
                if (flashAvailable) FlashButton(settings.flash, vm::cycleFlash)
                HelpButton(appName, CAMERA_HELP, onOpenChange = { showHelp = it })
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }

            // Viewfinder, at the raw's aspect ratio, in whatever height the controls leave.
            val f = frame
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
            Box(
                Modifier
                    .aspectRatio(f?.let { it.fullWidth.toFloat() / it.fullHeight } ?: (3f / 4f))
                    .background(Color(0xFF0A0A0A)),
                contentAlignment = Alignment.Center,
            ) {
                Viewfinder(f, params, vm::onSurfaceTexture, vm::onViewfinderBase, Modifier.fillMaxSize())
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                // Tap: focus lock at that point (a new tap moves it; the AF LOCK badge releases it).
                                onTap = { vm.focusAt(it.x / size.width, it.y / size.height) },
                                onLongPress = {}, // a hold compares (onPress); it mustn't also focus on release
                                onPress = {
                                    // Press and hold: the auto develop without the look.
                                    val hold = scope.launch {
                                        delay(COMPARE_DELAY_MS)
                                        vm.setCompare(true)
                                    }
                                    tryAwaitRelease()
                                    hold.cancel()
                                    if (vm.compare.value) vm.setCompare(false)
                                },
                            )
                        },
                )
                when (status) {
                    CameraStatus.NEEDS_PERMISSION -> Button(onClick = requestPermission) { Text("Allow camera") }
                    CameraStatus.OPENING -> CircularProgressIndicator()
                    CameraStatus.FAILED -> Text("Camera unavailable", color = MaterialTheme.colorScheme.error)
                    CameraStatus.READY -> if (params == null) CircularProgressIndicator()
                }
                if (lenses.size > 1) {
                    LensSelector(
                        labels = lenses.map { it.label },
                        selected = settings.lens,
                        onSelect = vm::selectLens,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                    )
                }
                focusPoint?.let { (x, y) -> FocusRing(x, y, afState) }
                if (focusPoint != null) {
                    Text(
                        "AF LOCK  ✕",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Black,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFFFD54F))
                            .clickable { vm.releaseFocus() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                // Shutter blink over the viewfinder.
                if (blink.value > 0f) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = blink.value)),
                    )
                }
                // Top right: "Without look" takes the left corner and AF LOCK the middle.
                if (settings.showHistogram && params != null) {
                    HistogramView(
                        histogram,
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(width = 120.dp, height = 48.dp),
                        background = Color(0x99000000),
                    )
                }
                if (compare) {
                    Text(
                        "Without look",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .background(Color(0x99000000))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            }

            // A menu instead of a strip of chips, and one thin row per slider: the viewfinder keeps the screen.
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)) {
                LookDropdown(
                    label = "Look",
                    looks = Edition.looks,
                    selected = Edition.looks.firstOrNull { it.id == settings.lookId },
                    onSelect = { vm.setLook(it.id) },
                    labelWidth = 88.dp,
                )
                LookSlider("Strength", settings.strength, 0f..100f, LookApplier.DEFAULT_STRENGTH, vm::setStrength)
                LookSlider("Brightness", settings.brightness, -100f..100f, 0f, vm::setBrightness)
                LookSlider("Warmth", settings.warmth, -100f..100f, 0f, vm::setWarmth)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp)) {
                    lastShot?.let { shot ->
                        Image(
                            shot.thumbnail.asImageBitmap(),
                            contentDescription = "Last photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val intent = Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(shot.uri, "image/jpeg")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    runCatching { context.startActivity(intent) }
                                },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Shutter(
                    enabled = status == CameraStatus.READY,
                    onClick = vm::shutter,
                    modifier = Modifier.graphicsLayer {
                        scaleX = shutterScale.value
                        scaleY = shutterScale.value
                    },
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    if (frontAvailable) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF2A2A2A))
                                .clickable(enabled = status != CameraStatus.OPENING) { vm.switchFacing() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                CameraSwitchIcon,
                                contentDescription = if (settings.front) "Back camera" else "Selfie camera",
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            SettingsContent(settings, vm)
        }
    }
}

/** UW · W · T, as Google Camera shows them: the selected lens in a filled circle. */
@Composable
private fun LensSelector(labels: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0x99000000))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (label in labels) {
            val isSelected = label == selected
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFFD0D0D0) else Color.Transparent)
                    .clickable { onSelect(label) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected) Color.Black else Color.White,
                )
            }
        }
    }
}

/**
 * Focus lock ring at ([x], [y]) of the viewfinder (normalised): pulsing while focusing, white once
 * focus is locked, red when the camera couldn't find focus there.
 */
@Composable
private fun BoxScope.FocusRing(x: Float, y: Float, afState: Int?) {
    val scanning = afState == CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN || afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN
    val pulse by rememberInfiniteTransition(label = "focus").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(350), RepeatMode.Reverse),
        label = "focus pulse",
    )
    val color = when (afState) {
        CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> Color(0xFFFF5252)
        else -> Color.White
    }
    val ringSize = 72.dp
    Canvas(
        Modifier
            .matchParentSize()
            .graphicsLayer { alpha = if (scanning) pulse else 1f },
    ) {
        val radius = ringSize.toPx() / 2
        drawCircle(color, radius, Offset(x * size.width, y * size.height), style = Stroke(width = 2.dp.toPx()))
        drawCircle(color, 3.dp.toPx(), Offset(x * size.width, y * size.height))
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ring = if (enabled) Color.White else Color.Gray
    Box(
        modifier
            .size(76.dp)
            .border(BorderStroke(4.dp, ring), CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(ring)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/** Slider with its value; double-tap the slider or tap the value to reset. */
@Composable
private fun LookSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    onChange: (Float) -> Unit,
) = CompactSlider(label, value, range, default, rowHeight = 36.dp, labelWidth = 88.dp, valueWidth = 44.dp, onChange = onChange)

@Composable
private fun SettingsContent(settings: CameraSettings, vm: CameraViewModel) {
    Column(
        Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("JPEG quality", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${settings.jpegQuality}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = settings.jpegQuality.toFloat(),
            onValueChange = { vm.setJpegQuality(it.roundToInt()) },
            valueRange = CameraSettings.MIN_QUALITY.toFloat()..100f,
            steps = 100 - CameraSettings.MIN_QUALITY - 1,
        )
        Text(
            "Encoded with jpegli, full-resolution colour (4:4:4). Default 95.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep raw (DNG)", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Saved to Pictures/${Edition.FOLDER}/RAW",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.keepDng, onCheckedChange = vm::setKeepDng)
        }
        Spacer(Modifier.height(16.dp))
        SettingSwitch(
            title = "Smaller photos",
            subtitle = "${CameraSettings.SMALL_LONG_SIDE} px on the long side; off saves full resolution",
            checked = settings.smallExport,
            onChange = vm::setSmallExport,
        )
        Spacer(Modifier.height(16.dp))
        SettingSwitch(
            title = "Histogram",
            subtitle = "A live RGB histogram in a corner of the viewfinder",
            checked = settings.showHistogram,
            onChange = vm::setShowHistogram,
        )
        Spacer(Modifier.height(16.dp))
        SettingSwitch(
            title = "Remember camera & settings",
            subtitle = "Otherwise each start uses W, Portra 400 and default sliders",
            checked = settings.rememberLast,
            onChange = vm::setRememberLast,
        )
        for (line in Edition.credits) {
            Spacer(Modifier.height(8.dp))
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        var showLicences by remember { mutableStateOf(false) }
        TextButton(onClick = { showLicences = true }) { Text("Open-source licences") }
        if (showLicences) LicensesDialog(onDismiss = { showLicences = false })
    }
}

/** Keep the DNG next to the JPEG (the same setting as in Settings): "RAW" lit when on, dimmed and struck through when off. */
@Composable
private fun RawButton(keep: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { stateDescription = if (keep) "On" else "Off" }) {
        Text(
            "RAW",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                textDecoration = if (keep) null else TextDecoration.LineThrough,
            ),
            color = if (keep) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = 0.6f),
            modifier = Modifier.semantics { contentDescription = "Keep RAW (DNG)" },
        )
    }
}

/** Off -> Flash -> Focus-flash (the bolt with "AF": the light comes on to focus and meter first). */
@Composable
private fun FlashButton(mode: FlashMode, onClick: () -> Unit) {
    val description = when (mode) {
        FlashMode.OFF -> "Flash off"
        FlashMode.FLASH -> "Flash"
        FlashMode.FOCUS_FLASH -> "Flash with focus light"
    }
    IconButton(onClick = onClick) {
        Box {
            Icon(
                if (mode == FlashMode.OFF) FlashOffIcon else FlashOnIcon,
                contentDescription = description,
                tint = if (mode == FlashMode.OFF) LocalContentColor.current else MaterialTheme.colorScheme.primary,
            )
            if (mode == FlashMode.FOCUS_FLASH) {
                Text(
                    "AF",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 8.sp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 8.dp),
                )
            }
        }
    }
}

private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
    .build()

/** Material "flash_on" icon (Apache 2.0), built here because it isn't in material-icons-core. */
private val FlashOnIcon: ImageVector by lazy { icon("FlashOn", "M7,2v11h3v9l7,-12h-4l4,-8z") }

/**
 * Material "cameraswitch" icon (Apache 2.0): a camera between two turning arrows, as Google Camera's switch.
 * Refresh (the circular arrows alone) read as reset.
 */
private val CameraSwitchIcon: ImageVector by lazy {
    icon(
        "CameraSwitch",
        "M16,7h-1l-1,-1h-4L9,7H8C6.9,7 6,7.9 6,9v6c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V9C18,7.9 17.1,7 16,7z" +
            "M12,14c-1.1,0 -2,-0.9 -2,-2c0,-1.1 0.9,-2 2,-2s2,0.9 2,2C14,13.1 13.1,14 12,14z" +
            "M8.57,0.51l4.48,4.48V2.04c4.72,0.47 8.48,4.23 8.95,8.95c0,0 2,0 2,0C23.34,3.02 15.49,-1.59 8.57,0.51z" +
            "M10.95,21.96C6.23,21.49 2.47,17.73 2,13.01c0,0 -2,0 -2,0c0.66,7.97 8.51,12.58 15.43,10.48l-4.48,-4.48V21.96z",
    )
}

/** Material "flash_off" icon (Apache 2.0). */
private val FlashOffIcon: ImageVector by lazy {
    icon("FlashOff", "M3.27,3L2,4.27l5,5V13h3v9l3.58,-6.14L17.73,20 19,18.73 3.27,3zM17,10h-4l4,-8H7v2.18l8.46,8.46L17,10z")
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private const val COMPARE_DELAY_MS = 250L
