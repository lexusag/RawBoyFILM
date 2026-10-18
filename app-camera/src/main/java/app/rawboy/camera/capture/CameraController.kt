package app.rawboy.camera.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import app.rawboy.auto.analysis.MeterArea
import app.rawboy.color.Mat3
import app.rawboy.color.WhiteBalance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** The camera's flash for a shot. */
enum class FlashMode {
    OFF,

    /** Fire the flash with the shot, at once: exposure as metered without it. */
    FLASH,

    /** As Google Camera does it: the light comes on, the camera focuses and meters with it, then the flash fires. */
    FOCUS_FLASH,
}

/** A captured still: the lens's capture result and, for a flash shot, the flash exposure used. */
class Still(
    val result: CaptureResult,
    /** The flash guide the still was exposed with (see [CameraController.flashExposure]); null without flash. */
    val flashGuide: Float?,
    /** The lens reported its focus distance, so [flashGuide] is per metre²; else it is the gain itself. */
    val distanceKnown: Boolean = true,
)

/** A lens's learned flash exposure (see [CameraController.flashExposure]). */
class FlashGuides(
    /** Gain (s x ISO) at 1 m; the gain is this x distance². */
    val byDistance: Float = CameraController.DEFAULT_FLASH_GUIDE,
    /** Gain when the lens doesn't report its focus distance (Pixel 9 Pro UW: always 0). */
    val noDistance: Float = CameraController.DEFAULT_FLASH_GAIN_NO_DISTANCE,
)

/**
 * The back camera through Camera2: a preview stream into the viewfinder's SurfaceTexture with a
 * known tone curve (so it can be linearised), and single RAW_SENSOR captures written as DNG.
 *
 * Camera callbacks run on a private thread; the suspend functions may be called from any thread.
 */
class CameraController(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("RawBoy-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    /** One lens and what it offers. */
    class Info(
        /** Camera to open: the logical back camera, or the lens itself. */
        val openId: String,
        /** Set when this lens is streamed as a physical camera of [openId] (a logical multi-camera). */
        val physicalId: String?,
        /** UW, W or T (as Google Camera labels them); set by [lenses]. */
        val label: String,
        /** CameraMetadata.LENS_FACING_BACK or LENS_FACING_FRONT. */
        val facing: Int,
        /** 35 mm-equivalent focal length, for ordering and labelling. */
        val equivalentFocal: Float,
        /** This lens's own characteristics (DNG colour matrices, sizes). */
        val characteristics: CameraCharacteristics,
        /** Active array of [openId], the coordinate space for focus/metering regions. */
        val regionArray: Rect,
        val rawSize: Size,
        val previewSize: Size,
        /** Clockwise rotation that makes sensor images upright on a portrait screen. */
        val sensorOrientation: Int,
        /** The preview's tone curve: 0 = sRGB (preset curve), > 0 = power, see ViewfinderRenderer.Frame.gamma. */
        val previewGamma: Float,
        private val toneMapModes: IntArray,
        private val shadingMapModes: IntArray,
        private val faceModes: IntArray,
        val activeArray: Rect,
        /** XYZ -> camera at D65 (DNG ColorMatrix), row-major; null if the camera doesn't say. */
        val xyzToCamera: DoubleArray?,
        /** White-balanced camera RGB -> linear sRGB as the raw decoder (LibRaw rgb_cam) has it, row-major; or null. */
        val cameraToSrgb: DoubleArray?,
        /** The camera (the logical one when a lens is streamed from it) has a flash unit. */
        val hasFlash: Boolean,
    ) {
        /** The lens can focus (a fixed-focus lens reports a minimum focus distance of 0). */
        val canFocus get() = (characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f

        val hasLensShadingMap get() = CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON in shadingMapModes
        val hasFaceDetection get() = CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE in faceModes
        val hasPresetCurve get() = CameraMetadata.TONEMAP_MODE_PRESET_CURVE in toneMapModes
        val hasGammaCurve get() = CameraMetadata.TONEMAP_MODE_GAMMA_VALUE in toneMapModes
        val hasContrastCurve get() = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in toneMapModes

        /**
         * Fixed preview frame rate for smooth low light: [30, 30], else [24, 30]; null to leave AE's own choice.
         * AE then raises ISO rather than lengthening the preview's exposure past 1/30 s (or 1/24 s).
         */
        val previewFpsRange: Range<Int>? get() {
            val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            return ranges.firstOrNull { it.lower == 30 && it.upper == 30 } ?: ranges.firstOrNull { it.lower == 24 && it.upper == 30 }
        }

        /** AE range for stills: the lowest minimum with a 30 fps maximum ([15, 30]), so AE may expose up to 1/15 s. */
        val stillFpsRange: Range<Int>? get() =
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                .filter { it.upper == 30 }.minByOrNull { it.lower }


        val isFront get() = facing == CameraMetadata.LENS_FACING_FRONT

        fun labelled(label: String) = Info(
            openId, physicalId, label, facing, equivalentFocal, characteristics, regionArray, rawSize, previewSize,
            sensorOrientation, previewGamma, toneMapModes, shadingMapModes, faceModes, activeArray, xyzToCamera,
            cameraToSrgb, hasFlash,
        )

        /**
         * Share of the sensor's width / height (sensor orientation) the preview shows. 1 when the preview has the
         * raw's aspect; less when none does (Pixel 9 Pro selfie raw is 1.40:1, its previews 4:3), and the ISP
         * shows the centre crop: then the viewfinder is a little tighter than the saved photo.
         */
        val previewCropX: Float get() {
            val r = previewSize.width.toFloat() / previewSize.height / (rawSize.width.toFloat() / rawSize.height)
            return if (r < 1f) r else 1f
        }
        val previewCropY: Float get() {
            val r = previewSize.width.toFloat() / previewSize.height / (rawSize.width.toFloat() / rawSize.height)
            return if (r > 1f) 1f / r else 1f
        }

        /** Upright size of what the viewfinder shows, in raw pixels (the raw, cropped to the preview's aspect). */
        val viewfinderWidth: Int get() =
            ((if (sensorOrientation % 180 == 0) rawSize.width * previewCropX else rawSize.height * previewCropY)).toInt()
        val viewfinderHeight: Int get() =
            ((if (sensorOrientation % 180 == 0) rawSize.height * previewCropY else rawSize.width * previewCropX)).toInt()
    }

    /** Per-frame metadata of the preview. */
    class FrameState(
        val iso: Float,
        /** Digital gain the ISP adds after the raw (1 = none); the raw doesn't have it. */
        val postRawBoost: Float,
        /** As-shot white (CIE xy) the camera's AWB settled on, or null. */
        val whiteXY: DoubleArray?,
        /** Faces in upright normalised coordinates (0..1, y down, as the viewfinder shows them). */
        val faces: List<RectF>,
        /** CONTROL_AF_STATE of this frame, or null if unknown. */
        val afState: Int?,
        /** Preview exposure time in ns (0 if unknown). */
        val exposureNs: Long,
        /** TONEMAP_MODE the camera actually applied to the preview (null if not reported). */
        val tonemapMode: Int?,
        /** Linear preview RGB -> Rec.2020 with the raw's colours (see [previewToRec2020]), GL column-major; or null. */
        val previewColor: FloatArray?,
        /** LENS_FOCUS_DISTANCE in diopters (0 = infinity), or null if not reported. */
        val focusDiopters: Float? = null,
    )

    private val _frames = MutableStateFlow<FrameState?>(null)
    val frames = _frames.asStateFlow()

    private var info: Info? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawReader: ImageReader? = null
    private var pendingImage: CompletableDeferred<Image>? = null
    /** Locked focus (and metering) point, upright normalised as shown; null = continuous autofocus. */
    @Volatile
    private var focusPoint: Pair<Float, Float>? = null

    /** The physical lens the session streams from the logical camera, or null. */
    @Volatile
    private var streamingPhysical: String? = null
    private val captureLock = Mutex()

    /** AE and flash modes for every request; changed only while a flash shot runs (see [torchFocus]). */
    @Volatile
    private var aeMode = CameraMetadata.CONTROL_AE_MODE_ON
    @Volatile
    private var flashMode = CameraMetadata.FLASH_MODE_OFF

    /** Gets each preview result while a flash shot waits for focus or metering ([triggerAndWait]); camera thread only. */
    private var precaptureWatch: ((TotalCaptureResult) -> Unit)? = null

    /**
     * The back lenses that can deliver RAW_SENSOR, widest first, labelled UW / W / T. A logical
     * multi-camera (Pixel: one "back camera" over the wide, ultrawide and tele) contributes its physical
     * lenses; the logical camera itself stands for the lens it starts on (W), since that path has the
     * most complete 3A. Lenses are logged under RawBoyCamera for diagnosing other phones.
     * [facing]: CameraMetadata.LENS_FACING_BACK or LENS_FACING_FRONT; empty when that side has no RAW.
     */
    fun lenses(facing: Int = CameraMetadata.LENS_FACING_BACK): List<Info> {
        val found = ArrayList<Info>()
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != facing) continue
            val region = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect()
            infoFor(id, null, c, region)?.let(found::add)
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) {
                for (physical in c.physicalCameraIds) {
                    val pc = runCatching { manager.getCameraCharacteristics(physical) }.getOrNull() ?: continue
                    val lens = infoFor(id, physical, pc, region)
                    lens?.let { Log.d(TAG, "camera $id/$physical: RAW ${it.rawSize}, preview ${it.previewSize}, ${it.equivalentFocal.toInt()} mm") }
                    lens?.let(found::add)
                }
            }
        }
        // One entry per lens: physical lenses with the focal length of a camera already listed are the same lens.
        val lenses = ArrayList<Info>()
        for (lens in found.sortedBy { if (it.physicalId == null) 0 else 1 }) {
            if (lenses.none { abs(it.equivalentFocal - lens.equivalentFocal) < 0.12f * lens.equivalentFocal }) lenses += lens
        }
        if (lenses.isEmpty()) {
            Log.d(TAG, "lenses (facing $facing): none with RAW")
            return emptyList()
        }
        lenses.sortBy { it.equivalentFocal }
        // The main lens is the one the logical camera opens on, else the one nearest 26 mm.
        val main = lenses.firstOrNull { it.physicalId == null } ?: lenses.minBy { abs(it.equivalentFocal - 26f) }
        val mainIndex = lenses.indexOf(main)
        return lenses.mapIndexed { i, lens ->
            val label = when {
                i == mainIndex -> "W"
                i < mainIndex -> if (mainIndex - i == 1) "UW" else "UW${mainIndex - i}"
                else -> if (i - mainIndex == 1) "T" else "T${i - mainIndex}"
            }
            lens.labelled(label)
        }.also { list ->
            Log.d(TAG, "lenses (facing $facing): " + list.joinToString { "${it.label}=${it.openId}/${it.physicalId ?: "-"} ${it.equivalentFocal.toInt()}mm ${it.rawSize}" })
        }
    }

    private fun infoFor(openId: String, physicalId: String?, c: CameraCharacteristics, regionArray: Rect): Info? {
        val name = physicalId?.let { "$openId/$it" } ?: openId
        fun reject(why: String): Info? {
            Log.d(TAG, "camera $name: $why")
            return null
        }
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return reject("no capabilities")
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps) return reject("no RAW")
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return reject("no stream map")
        val raw = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
            ?: return reject("no RAW_SENSOR size")
        val aspect = raw.width.toFloat() / raw.height
        // The raw's aspect if a preview has it (the viewfinder then shows the whole frame), else the closest
        // (Pixel selfie: raw 1.40:1, previews 4:3); ~1440 px is plenty.
        val candidates = map.getOutputSizes(SurfaceTexture::class.java)?.filter { it.width <= MAX_PREVIEW_WIDTH }.orEmpty()
        val bestAspect = candidates.minOfOrNull { abs(it.width.toFloat() / it.height - aspect) }
            ?: return reject("no preview sizes")
        val preview = candidates
            .filter { abs(it.width.toFloat() / it.height - aspect) <= bestAspect + 0.01f }
            .maxBy { it.width }
        val toneModes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
        val gamma = when {
            CameraMetadata.TONEMAP_MODE_PRESET_CURVE in toneModes -> 0f
            CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in toneModes -> 0f // our own sRGB curve, see SRGB_CURVE
            CameraMetadata.TONEMAP_MODE_GAMMA_VALUE in toneModes -> PREVIEW_GAMMA
            else -> 0f // the default curve is close to sRGB; the raw calibration absorbs the rest
        }
        // The flash belongs to the camera the requests go to: the logical one for a physical lens.
        val opened = if (physicalId != null) runCatching { manager.getCameraCharacteristics(openId) }.getOrNull() ?: c else c
        return Info(
            openId = openId,
            physicalId = physicalId,
            label = "",
            facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_BACK,
            equivalentFocal = equivalentFocal(c),
            characteristics = c,
            regionArray = regionArray,
            rawSize = raw,
            previewSize = preview,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            previewGamma = gamma,
            toneMapModes = toneModes,
            shadingMapModes = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES) ?: IntArray(0),
            faceModes = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: IntArray(0),
            activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, raw.width, raw.height),
            xyzToCamera = d65ColorMatrix(c),
            cameraToSrgb = rawCameraToSrgb(c),
            hasFlash = opened.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
        )
    }

    /** Opens the camera and starts the preview into [surfaceTexture]. Requires the CAMERA permission. */
    suspend fun start(info: Info, surfaceTexture: SurfaceTexture) {
        stop()
        this.info = info
        surfaceTexture.setDefaultBufferSize(info.previewSize.width, info.previewSize.height)
        val preview = Surface(surfaceTexture).also { previewSurface = it }
        val reader = ImageReader.newInstance(info.rawSize.width, info.rawSize.height, ImageFormat.RAW_SENSOR, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
            val waiting = pendingImage
            pendingImage = null
            if (waiting == null || !waiting.complete(image)) image.close()
        }, handler)
        rawReader = reader

        // A physical lens of a logical camera: stream it from the logical camera if the phone allows that
        // combination; otherwise try opening the lens on its own.
        val physical = info.physicalId
        if (physical != null) {
            val camera = open(info.openId)
            val outputs = outputs(preview, reader, physical)
            val supported = runCatching {
                camera.isSessionConfigurationSupported(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, NO_SESSION))
            }.getOrDefault(true) // not answered: just try
            Log.d(TAG, "${info.label}: physical $physical streams from ${info.openId}: supported=$supported")
            if (supported) {
                device = camera
                try {
                    session = createSession(camera, outputs)
                    streamingPhysical = physical
                    repeatPreview()
                    return
                } catch (e: CancellationException) {
                    camera.close()
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "${info.label}: physical streams failed, opening $physical directly", e)
                    runCatching { session?.close() }
                    session = null
                }
            }
            camera.close()
            device = null
        }
        val camera = open(physical ?: info.openId)
        device = camera
        session = createSession(camera, outputs(preview, reader, null))
        streamingPhysical = null
        repeatPreview()
    }

    @SuppressLint("MissingPermission") // the activity holds the CAMERA permission before starting
    private suspend fun open(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        try {
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IOException("Camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IOException("Camera $id error $error"))
                }
            })
        } catch (e: Exception) {
            // e.g. a hidden physical camera id that can't be opened on its own
            if (cont.isActive) cont.resumeWithException(IOException("Cannot open camera $id: ${e.message}", e))
        }
    }

    private fun outputs(preview: Surface, reader: ImageReader, physicalId: String?) =
        listOf(OutputConfiguration(preview), OutputConfiguration(reader.surface)).onEach { o ->
            physicalId?.let { o.setPhysicalCameraId(it) }
        }

    private suspend fun createSession(camera: CameraDevice, outputs: List<OutputConfiguration>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            camera.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(IOException("Camera session failed"))
                    }
                }),
            )
        }

    /** A request that may carry per-physical-lens settings when a physical lens is streamed. */
    private fun request(s: CameraCaptureSession, template: Int): CaptureRequest.Builder =
        streamingPhysical?.let { s.device.createCaptureRequest(template, setOf(it)) } ?: s.device.createCaptureRequest(template)

    /** The result of the lens actually streaming (its own when a physical lens is streamed). */
    private fun lensResult(total: TotalCaptureResult): CaptureResult =
        streamingPhysical?.let { total.physicalCameraResults[it] } ?: total

    /** (Re)starts the repeating preview, with the focus lock if there is one. */
    private fun repeatPreview() {
        val s = session ?: return
        val inf = info ?: return
        val request = request(s, CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface ?: return)
            setCommon(inf)
            if (inf.hasFaceDetection) set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)
        }.build()
        s.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                _frames.value = frameState(inf, lensResult(result))
                precaptureWatch?.invoke(result)
            }
        }, handler)
    }

    /**
     * Focus lock: focuses and meters at an upright normalised point ([x], [y] as the viewfinder shows it)
     * and keeps focus there, for the preview and for captures, until [releaseFocus] (or a lens switch).
     */
    fun focusAt(x: Float, y: Float) {
        handler.post {
            val s = session ?: return@post
            focusPoint = x to y
            val inf = info ?: return@post
            runCatching {
                // Cancel any scan in progress, then scan once at the new point; AF_MODE_AUTO then holds it.
                val cancel = request(s, CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface ?: return@post)
                    setCommon(inf)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                }.build()
                val trigger = request(s, CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface ?: return@post)
                    setCommon(inf)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }.build()
                repeatPreview()
                s.capture(cancel, null, handler)
                s.capture(trigger, null, handler)
            }
        }
    }

    /** Ends the focus lock: back to continuous autofocus and whole-frame metering. */
    fun releaseFocus() {
        handler.post {
            if (focusPoint == null) return@post
            focusPoint = null
            val s = session ?: return@post
            val inf = info ?: return@post
            runCatching {
                val cancel = request(s, CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface ?: return@post)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                }.build()
                s.capture(cancel, null, handler)
                repeatPreview()
            }
        }
    }

    /**
     * Captures one raw frame and writes it as DNG to [out], tagged with [exifOrientation].
     * One capture at a time. [flash] is ignored without a flash unit; [guides]: see [flashExposure], learned per lens
     * by the caller.
     */
    suspend fun captureDng(
        exifOrientation: Int,
        out: OutputStream,
        flash: FlashMode = FlashMode.OFF,
        guides: FlashGuides = FlashGuides(),
        onSubmitted: () -> Unit = {},
    ): Still = captureLock.withLock {
        val s = session ?: throw IOException("Camera not ready")
        val inf = info ?: throw IOException("Camera not ready")
        val reader = rawReader ?: throw IOException("Camera not ready")
        val mode = if (inf.hasFlash) flash else FlashMode.OFF
        // Ambient exposure before any light comes on: the flash still never needs more.
        val ambient = _frames.value
        var focused = false
        try {
            if (mode == FlashMode.FOCUS_FLASH) focused = torchFocus(s, inf)
            val diopters = _frames.value?.focusDiopters
            val exposure = if (mode == FlashMode.OFF) null else flashExposure(inf, ambient, diopters, guides)
            val result = stillDng(s, inf, reader, mode, exposure?.first, exifOrientation, out, onSubmitted)
            Still(result, exposure?.second, distanceKnown = diopters != null && diopters > 0f)
        } finally {
            if (mode != FlashMode.OFF) endFlashSequence(inf, focused)
        }
    }

    private suspend fun stillDng(
        s: CameraCaptureSession,
        inf: Info,
        reader: ImageReader,
        flash: FlashMode,
        /** Manual (exposure ns, ISO) for a flash still, or null for AE's. */
        manual: Pair<Long, Int>?,
        exifOrientation: Int,
        out: OutputStream,
        onSubmitted: () -> Unit,
    ): CaptureResult {
        val image = CompletableDeferred<Image>()
        val result = CompletableDeferred<TotalCaptureResult>()
        handler.post { pendingImage = image }
        val request = request(s, CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            addTarget(previewSurface ?: throw IOException("Camera not ready"))
            setCommon(inf)
            if (flash == FlashMode.OFF) {
                // Low light: the preview is held at 30 fps for a smooth viewfinder; let AE give the still the longer
                // exposure it used to choose (its full range down to 15 fps).
                inf.stillFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            } else {
                // The flash freezes the subject; a longer ambient exposure would only add blur around it. And never a
                // zero-shutter-lag frame from before the flash.
                set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
                // The camera's AE keeps the ambient exposure for a flash still (Pixel 9 Pro), so the raw clipped:
                // expose it ourselves (see flashExposure) and fire the flash.
                if (manual != null) {
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    setForLens(CaptureRequest.SENSOR_EXPOSURE_TIME, manual.first)
                    setForLens(CaptureRequest.SENSOR_SENSITIVITY, manual.second)
                    setForLens(CaptureRequest.SENSOR_FRAME_DURATION, max(manual.first, FLASH_FRAME_NS))
                    setForLens(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)
                }
                set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
            }
            // DngCreator writes the lens shading map as GainMap opcodes, which the decoder applies.
            if (inf.hasLensShadingMap) {
                setForLens(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            }
        }.build()
        s.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
                result.complete(r)
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                result.completeExceptionally(IOException("Capture failed (${failure.reason})"))
                image.completeExceptionally(IOException("Capture failed"))
            }
        }, handler)
        onSubmitted()
        val captureResult = lensResult(result.await())
        Log.d(TAG, "still (flash $flash${manual?.let { ", manual ${it.first / 1000} µs ISO ${it.second}" } ?: ""}, state ${captureResult.get(CaptureResult.FLASH_STATE)}, AE ${captureResult.get(CaptureResult.CONTROL_AE_STATE)}): ${(captureResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) / 1000} µs, ISO " +
            "${captureResult.get(CaptureResult.SENSOR_SENSITIVITY)}, boost ${captureResult.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)}, " +
            "black ${captureResult.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.joinToString()} " +
            "(static ${inf.characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)}), " +
            "white ${captureResult.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)}")
        image.await().use { img ->
            // The lens's own characteristics and result: colour matrices, shading map and sizes are per lens.
            DngCreator(inf.characteristics, captureResult).use { dng ->
                dng.setOrientation(exifOrientation)
                dng.writeImage(out, img)
            }
        }
        return captureResult
    }

    /**
     * [FlashMode.FOCUS_FLASH], as Google Camera does it: the torch lights the scene, then the camera focuses with that
     * light (an AF trigger, which holds the focus until [endFlashSequence]). Skipped when focus is locked by a tap or the
     * lens can't focus. Returns whether it triggered AF.
     */
    private suspend fun torchFocus(s: CameraCaptureSession, inf: Info): Boolean {
        if (focusPoint != null || !inf.canFocus) return false
        aeMode = CameraMetadata.CONTROL_AE_MODE_ON // the torch needs AE_MODE_ON
        flashMode = CameraMetadata.FLASH_MODE_TORCH
        handler.post { runCatching { repeatPreview() } }
        delay(TORCH_WARMUP_MS) // the light comes up and AE follows, so AF sees the lit scene
        triggerAndWait(s, inf, "torch focus", FOCUS_TIMEOUT_MS, {
            set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        }) { r ->
            val af = r.get(CaptureResult.CONTROL_AF_STATE)
            af == null || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
        }
        return true
    }

    /**
     * Manual (exposure ns, ISO) for a flash still, and the guide it amounts to. The camera's own flash metering is no
     * use for the raw (Pixel 9 Pro: the AE precapture fires no pre-flash for other apps, and the still keeps the
     * ambient exposure, so the raw clipped), so this works like a flash guide number: the LED's light falls off with
     * the square of the distance, so the gain (s x ISO) is [guide] x distance² (m), with the distance the lens is
     * focused at; never more than the ambient exposure. A distance of exactly 0 is taken as unknown, not infinity: the
     * Pixel 9 Pro's UW reports 0 on every frame, even with AF locked close up (as 5 m it got the ambient exposure plus
     * the flash: clipped); then the gain is [FlashGuides.noDistance], learned on its own. [FLASH_EXPOSURE_NS], shorter
     * when the ISO is at its minimum.
     */
    private fun flashExposure(inf: Info, ambient: FrameState?, diopters: Float?, guides: FlashGuides): Pair<Pair<Long, Int>, Float?> {
        val isoRange = inf.characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(50, 3200)
        val timeRange = inf.characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(10_000L, 100_000_000L)
        val ambientGain = ambient?.let { it.exposureNs / 1e9f * it.iso }?.takeIf { it > 0f } ?: Float.MAX_VALUE
        val known = diopters != null && diopters > 0f
        val metres = if (known) (1f / diopters!!).coerceIn(MIN_FLASH_DISTANCE_M, MAX_FLASH_DISTANCE_M) else 1f
        val guide = if (known) guides.byDistance else guides.noDistance
        val byFlash = guide * metres * metres
        val gain = min(byFlash, ambientGain).coerceAtMost(FLASH_EXPOSURE_NS / 1e9f * isoRange.upper)
        var t = FLASH_EXPOSURE_NS.coerceIn(timeRange.lower, timeRange.upper)
        var iso = gain / (t / 1e9f)
        if (iso < isoRange.lower) {
            t = (gain / isoRange.lower * 1e9f).toLong().coerceIn(timeRange.lower, timeRange.upper)
            iso = isoRange.lower.toFloat()
        }
        val exposure = t to iso.toInt().coerceIn(isoRange.lower, isoRange.upper)
        val used = exposure.first / 1e9f * exposure.second
        Log.d(TAG, "flash exposure: focus ${diopters ?: "?"} dpt -> ${if (known) "%.2f m".format(metres) else "unknown"}, guide $guide -> gain $byFlash, " +
            "ambient $ambientGain -> ${exposure.first / 1000} µs ISO ${exposure.second}")
        // Calibrate only from shots the flash exposed (not those capped by ambient light or the sensor's range).
        val guideUsed = if (byFlash <= ambientGain && abs(used / byFlash - 1f) < 0.1f) used / (metres * metres) else null
        return exposure to guideUsed
    }

    /**
     * Restarts the repeating preview with the current [aeMode]/[flashMode], sends one preview request with [trigger],
     * then passes each later preview result to [done] until it returns true, at most [timeoutMs]. Only frames after the
     * trigger's own count: earlier ones still report the states from before it. Logs each AE/AF/flash state change.
     */
    private suspend fun triggerAndWait(
        s: CameraCaptureSession,
        inf: Info,
        step: String,
        timeoutMs: Long,
        trigger: CaptureRequest.Builder.() -> Unit,
        done: (CaptureResult) -> Boolean,
    ): Boolean {
        val settled = CompletableDeferred<Unit>()
        val started = System.nanoTime()
        val states = StringBuilder()
        var last = ""
        handler.post {
            runCatching {
                repeatPreview()
                val request = request(s, CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface ?: return@post)
                    setCommon(inf)
                    trigger()
                }.build()
                var after = Long.MAX_VALUE
                precaptureWatch = { r ->
                    if (r.frameNumber > after && !settled.isCompleted) {
                        val now = "AE ${r.get(CaptureResult.CONTROL_AE_STATE)} AF ${r.get(CaptureResult.CONTROL_AF_STATE)} " +
                            "flash ${r.get(CaptureResult.FLASH_STATE)} " +
                            "${(r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) / 1000} µs ISO ${r.get(CaptureResult.SENSOR_SENSITIVITY)} " +
                            "boost ${r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)}"
                        if (now != last) states.append(" | ${(System.nanoTime() - started) / 1_000_000} ms: $now")
                        last = now
                        if (done(r)) settled.complete(Unit)
                    }
                }
                s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
                        after = r.frameNumber
                    }

                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        settled.complete(Unit) // shoot anyway
                    }
                }, handler)
            }.onFailure { settled.complete(Unit) }
        }
        val inTime = withTimeoutOrNull(timeoutMs) { settled.await() } != null
        handler.post { precaptureWatch = null }
        Log.d(TAG, "$step: ${(System.nanoTime() - started) / 1_000_000} ms${if (inTime) "" else ", timed out"}$states")
        return inTime
    }

    /** After a flash shot: light off, AE back to normal and (if [focused] triggered it) AF released. */
    private fun endFlashSequence(inf: Info, focused: Boolean) {
        aeMode = CameraMetadata.CONTROL_AE_MODE_ON
        flashMode = CameraMetadata.FLASH_MODE_OFF
        handler.post {
            val s = session ?: return@post
            runCatching {
                val cancel = request(s, CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface ?: return@post)
                    setCommon(inf)
                    if (focused && focusPoint == null) set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                }.build()
                s.capture(cancel, null, handler)
                repeatPreview()
            }
        }
    }

    /**
     * How much brighter the still's raw will be than the preview frame [frame] (in raw terms: exposure time x ISO).
     * In low light the preview is held at 1/30 s, and for the still AE moves exposure from its post-raw digital
     * boost back into time, up to 1/15 s, at the same ISO (measured on the Pixel 9 Pro: preview 1/30 s, ISO 1410,
     * boost 2.39 -> still 1/15 s, ISO 1410, boost 1.2). Otherwise the still matches the preview.
     */
    fun stillGain(frame: FrameState): Float {
        val range = info?.previewFpsRange ?: return 1f
        if (frame.exposureNs <= 0L) return 1f
        val capNs = 1_000_000_000L / range.upper
        if (frame.exposureNs < capNs * 9 / 10) return 1f
        return minOf(MAX_STILL_EXPOSURE_NS.toFloat() / frame.exposureNs, frame.postRawBoost).coerceAtLeast(1f)
    }

    /**
     * EXIF orientation of a capture taken with the device turned [deviceDegrees] clockwise from portrait.
     * Front camera: Camera2's front rotation, mirrored, so the photo looks as the (mirror-like) viewfinder did.
     */
    fun exifOrientation(deviceDegrees: Int): Int {
        val inf = info
        val sensor = inf?.sensorOrientation ?: 90
        return if (inf?.isFront == true) {
            when ((sensor - deviceDegrees + 360) % 360) {
                // flip-horizontal after rotating clockwise by 0 / 90 / 180 / 270 degrees
                90 -> ExifInterface.ORIENTATION_TRANSPOSE
                180 -> ExifInterface.ORIENTATION_FLIP_VERTICAL
                270 -> ExifInterface.ORIENTATION_TRANSVERSE
                else -> ExifInterface.ORIENTATION_FLIP_HORIZONTAL
            }
        } else {
            when ((sensor + deviceDegrees + 360) % 360) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
        }
    }

    /** Closes the camera (on pause); [start] opens it again. */
    fun stop() {
        aeMode = CameraMetadata.CONTROL_AE_MODE_ON
        flashMode = CameraMetadata.FLASH_MODE_OFF
        runCatching { session?.close() }
        session = null
        streamingPhysical = null
        focusPoint = null
        device?.close()
        device = null
        rawReader?.close()
        rawReader = null
        previewSurface?.release()
        previewSurface = null
        pendingImage?.cancel()
        pendingImage = null
    }

    override fun close() {
        stop()
        thread.quitSafely()
    }

    private fun CaptureRequest.Builder.setCommon(inf: Info) {
        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        inf.previewFpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        val lock = focusPoint
        if (lock != null) {
            // Focus lock: AUTO holds the focus the trigger found, also for captures; meter there too.
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            setRegions(inf, lock)
        } else {
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            // Meter the middle half of the frame (width and height), as the auto develop does.
            if ((characteristicsOf(inf).get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(centreRegion(inf)))
            }
        }
        set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        set(CaptureRequest.FLASH_MODE, flashMode)
        set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        // The Pixel's templates turn video stabilization on: it crops the preview ~20% (the raw isn't), so the
        // viewfinder showed a tighter frame than the photo. OFF also disables preview stabilization.
        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        // A known curve on the preview, so the viewfinder can undo it (ViewfinderRenderer).
        when {
            inf.hasPresetCurve -> {
                setForLens(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_PRESET_CURVE)
                setForLens(CaptureRequest.TONEMAP_PRESET_CURVE, CameraMetadata.TONEMAP_PRESET_CURVE_SRGB)
            }
            // Pixel phones offer neither preset nor gamma, only an explicit curve (and ignore anything else:
            // left alone the preview gets their own scene-dependent tone mapping, which can't be undone).
            inf.hasContrastCurve -> {
                setForLens(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                setForLens(CaptureRequest.TONEMAP_CURVE, SRGB_CURVE)
            }
            inf.hasGammaCurve -> {
                setForLens(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
                setForLens(CaptureRequest.TONEMAP_GAMMA, PREVIEW_GAMMA) // out = in^(1/gamma)
            }
        }
    }

    /** Sets [key] on the request and, when a physical lens is streamed, on that lens too (where it's allowed). */
    private fun <T> CaptureRequest.Builder.setForLens(key: CaptureRequest.Key<T>, value: T) {
        set(key, value)
        streamingPhysical?.let { id -> runCatching { setPhysicalCameraKey(key, value, id) } }
    }

    /** The centre box (25-75% both ways) of the request's coordinate space, at full weight. */
    private fun centreRegion(inf: Info): MeteringRectangle {
        val a = if (streamingPhysical != null) inf.regionArray else inf.activeArray
        return MeteringRectangle(
            Rect(a.left + a.width() / 4, a.top + a.height() / 4, a.right - a.width() / 4, a.bottom - a.height() / 4),
            MeteringRectangle.METERING_WEIGHT_MAX - 1,
        )
    }

    /** The opened camera's characteristics (the logical camera when a physical lens is streamed). */
    private fun characteristicsOf(inf: Info): CameraCharacteristics =
        if (streamingPhysical != null) manager.getCameraCharacteristics(inf.openId) else inf.characteristics

    private fun CaptureRequest.Builder.setRegions(inf: Info, point: Pair<Float, Float>) {
        val (px, py) = uprightToBuffer(inf.sensorOrientation, inf.isFront, point.first, point.second)
        // Preview -> whole sensor, when the preview shows a centre crop.
        val bx = 0.5f + (px - 0.5f) * inf.previewCropX
        val by = 0.5f + (py - 0.5f) * inf.previewCropY
        val a = if (streamingPhysical != null) inf.regionArray else inf.activeArray
        // The auto develop's tap spot (MeterArea.Spot), moved inside the sensor near an edge rather than cut off,
        // so AE always meters a reasonable box.
        val (x0, x1, y0, y1) = MeterArea.Spot(bx, by).box(a.width(), a.height())
        val region = MeteringRectangle(
            Rect(a.left + x0, a.top + y0, a.left + x1, a.top + y1),
            MeteringRectangle.METERING_WEIGHT_MAX - 1,
        )
        set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
    }

    private fun frameState(inf: Info, r: CaptureResult): FrameState {
        val neutral = r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        val white = if (neutral != null && inf.xyzToCamera != null) {
            whiteFromNeutral(inf.xyzToCamera, DoubleArray(3) { neutral[it].toDouble() })
        } else {
            null
        }
        val a = inf.activeArray
        val faces = r.get(CaptureResult.STATISTICS_FACES).orEmpty().map { face ->
            val b = face.bounds
            // Whole sensor -> preview (centre crop), then upright as shown.
            fun px(x: Int) = 0.5f + ((x - a.left).toFloat() / a.width() - 0.5f) / inf.previewCropX
            fun py(y: Int) = 0.5f + ((y - a.top).toFloat() / a.height() - 0.5f) / inf.previewCropY
            val (u0, v0) = bufferToUpright(inf.sensorOrientation, inf.isFront, px(b.left), py(b.top))
            val (u1, v1) = bufferToUpright(inf.sensorOrientation, inf.isFront, px(b.right), py(b.bottom))
            RectF(min(u0, u1), min(v0, v1), max(u0, u1), max(v0, v1))
        }
        return FrameState(
            iso = (r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0).toFloat(),
            postRawBoost = (r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100) / 100f,
            whiteXY = white,
            faces = faces,
            afState = r.get(CaptureResult.CONTROL_AF_STATE),
            exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            tonemapMode = r.get(CaptureResult.TONEMAP_MODE),
            previewColor = previewToRec2020(inf, r, neutral)?.let(Mat3::toGlColumnMajor),
            focusDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE),
        )
    }

    companion object {
        private const val TAG = "RawBoyCamera"

        /** Longest still exposure AE uses in low light: 1/15 s (its 15-30 fps range). */
        private const val MAX_STILL_EXPOSURE_NS = 66_666_667L

        /** Focus-flash: how long the torch is on before the AF trigger, and how long AF may take with it. */
        private const val TORCH_WARMUP_MS = 300L
        private const val FOCUS_TIMEOUT_MS = 1500L


        /** Flash still: exposure time and the frame duration it needs at least. */
        private const val FLASH_EXPOSURE_NS = 33_333_333L
        private const val FLASH_FRAME_NS = 33_333_333L

        /**
         * Flash still gain (s x ISO) at 1 m before a lens has learned its own: Pixel 9 Pro W exposed a desk at ~0.4 m
         * well at about 2.5 (first tries).
         */
        const val DEFAULT_FLASH_GUIDE = 16f

        /** Flash still gain without a focus distance, before a lens has learned its own: as for 0.5 m at the guide above. */
        const val DEFAULT_FLASH_GAIN_NO_DISTANCE = 4f

        /** Focus distances the flash exposure trusts (nearer: macro; farther: the flash hardly reaches). */
        private const val MIN_FLASH_DISTANCE_M = 0.1f
        private const val MAX_FLASH_DISTANCE_M = 5f

        private const val MAX_PREVIEW_WIDTH = 1920

        /** Only for asking whether a session configuration is supported. */
        private val NO_SESSION = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = Unit
            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
        }

        /** 35 mm-equivalent focal length from the focal length and the sensor's physical size. */
        private fun equivalentFocal(c: CameraCharacteristics): Float {
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 26f
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 26f
            val diagonal = sqrt(size.width * size.width + size.height * size.height)
            return if (diagonal > 0f) focal * 43.27f / diagonal else 26f
        }

        /** Gamma for cameras without the sRGB preset curve. */
        private const val PREVIEW_GAMMA = 2.2f

        /**
         * The sRGB transfer curve as a TONEMAP_CURVE (64 segments, the Pixel's maximum is 65 points), with points
         * spaced more densely near black, where the curve bends most. The viewfinder's inverse sRGB undoes it.
         */
        private val SRGB_CURVE: TonemapCurve = run {
            val n = 65
            val points = FloatArray(n * 2)
            for (i in 0 until n) {
                val x = (i.toFloat() / (n - 1)).let { it * it } // denser near 0
                val y = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.toDouble().pow(1 / 2.4).toFloat() - 0.055f
                points[i * 2] = x
                points[i * 2 + 1] = y.coerceIn(0f, 1f)
            }
            TonemapCurve(points, points, points)
        }

        private const val ILLUMINANT_D65 = CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65

        /**
         * Upright (portrait) normalised point, as the viewfinder shows it -> sensor buffer normalised point.
         * [mirrored]: the viewfinder is a mirror image (front camera), so its x runs the other way.
         */
        fun uprightToBuffer(rotation: Int, mirrored: Boolean, shownU: Float, v: Float): Pair<Float, Float> {
            val u = if (mirrored) 1f - shownU else shownU
            return when (((rotation / 90) % 4 + 4) % 4) {
                1 -> v to 1f - u
                2 -> 1f - u to 1f - v
                3 -> 1f - v to u
                else -> u to v
            }
        }

        fun bufferToUpright(rotation: Int, mirrored: Boolean, bx: Float, by: Float): Pair<Float, Float> {
            val (u, v) = when (((rotation / 90) % 4 + 4) % 4) {
                1 -> 1f - by to bx
                2 -> 1f - bx to 1f - by
                3 -> by to 1f - bx
                else -> bx to by
            }
            return (if (mirrored) 1f - u else u) to v
        }

        /**
         * The ISP renders the preview with its own white balance gains and colour matrix (the Pixel's are more
         * saturated than the DNG's, ~20-40% in reds), so a plain sRGB -> Rec.2020 viewfinder didn't match the photo.
         * This undoes both (result COLOR_CORRECTION_GAINS / TRANSFORM) back to camera RGB and applies what the raw
         * decoder does: 1 / AsShotNeutral, then rgb_cam. Scaled so the ISP's white keeps its luminance, which keeps
         * the exposure calibration (previewScale) valid. Row-major; null when the camera doesn't report it all.
         */
        private fun previewToRec2020(inf: Info, r: CaptureResult, neutral: Array<android.util.Rational>?): DoubleArray? {
            val rgbCam = inf.cameraToSrgb ?: return null
            val ccm = r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return null
            val g = r.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return null
            if (neutral == null || neutral.size < 3) return null
            val n = DoubleArray(3) { neutral[it].toDouble() }
            val gains = doubleArrayOf(g.red.toDouble(), (g.greenEven + g.greenOdd) / 2.0, g.blue.toDouble())
            if (n.any { it <= 0.0 } || gains.any { it <= 0.0 }) return null
            val ispInverse = runCatching { Mat3.inverse(toDoubles(ccm)) }.getOrNull() ?: return null
            val toCamera = Mat3.multiply(Mat3.diagonal(1 / gains[0], 1 / gains[1], 1 / gains[2]), ispInverse)
            val balance = Mat3.diagonal(1 / n[0], 1 / n[1], 1 / n[2])
            val m = Mat3.multiply(SRGB_TO_REC2020, Mat3.multiply(rgbCam, Mat3.multiply(balance, toCamera)))
            val white = Mat3.apply(m, doubleArrayOf(1.0, 1.0, 1.0))
            val y = 0.2627 * white[0] + 0.6780 * white[1] + 0.0593 * white[2]
            if (!(y > 0)) return null
            return DoubleArray(9) { m[it] / y }
        }

        /**
         * LibRaw's rgb_cam for a DNG from this camera: XYZ -> camera = CameraCalibration x ColorMatrix (the D65 one),
         * times XYZ <- sRGB, rows normalised so camera white maps to sRGB white, inverted.
         */
        private fun rawCameraToSrgb(c: CameraCharacteristics): DoubleArray? {
            val m1 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
            val m2 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
            val i2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
            val useSecond = m2 != null && i2 == ILLUMINANT_D65 || m1 == null
            val cm = (if (useSecond) m2 else m1)?.let(::toDoubles) ?: return null
            val cal = (if (useSecond) c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2) else c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))
                ?.let(::toDoubles)
            val camXyz = if (cal != null) Mat3.multiply(cal, cm) else cm
            val camRgb = Mat3.multiply(camXyz, XYZ_FROM_SRGB)
            for (row in 0 until 3) {
                val sum = camRgb[row * 3] + camRgb[row * 3 + 1] + camRgb[row * 3 + 2]
                if (!(sum > 1e-5)) return null
                for (col in 0 until 3) camRgb[row * 3 + col] /= sum
            }
            return runCatching { Mat3.inverse(camRgb) }.getOrNull()
        }

        /** LibRaw's xyz_rgb (sRGB D65 -> XYZ). */
        private val XYZ_FROM_SRGB = doubleArrayOf(
            0.412453, 0.357580, 0.180423,
            0.212671, 0.715160, 0.072169,
            0.019334, 0.119193, 0.950227,
        )

        /** As the decoder's kSrgbToRec2020. */
        private val SRGB_TO_REC2020 = doubleArrayOf(
            0.6274040, 0.3292820, 0.0433136,
            0.0690970, 0.9195400, 0.0113612,
            0.0163916, 0.0880132, 0.8955950,
        )

        /** The DNG ColorMatrix for D65 (or the only one given), as the raw decoder sees it. */
        private fun d65ColorMatrix(c: CameraCharacteristics): DoubleArray? {
            val m1 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
            val m2 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
            val i2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
            val m = if (m2 != null && i2 == ILLUMINANT_D65) m2 else m1 ?: m2
            return m?.let(::toDoubles)
        }

        private fun toDoubles(t: ColorSpaceTransform) = DoubleArray(9) { i -> t.getElement(i % 3, i / 3).toDouble() }

        /** Camera neutral -> white xy, as the decoder estimates the as-shot white. */
        private fun whiteFromNeutral(xyzToCamera: DoubleArray, neutral: DoubleArray): DoubleArray? {
            val xyz = runCatching { Mat3.apply(Mat3.inverse(xyzToCamera), neutral) }.getOrNull() ?: return null
            val sum = xyz[0] + xyz[1] + xyz[2]
            if (!(sum > 0)) return null
            val x = xyz[0] / sum
            val y = xyz[1] / sum
            return if (x > 0.2 && x < 0.6 && y > 0.2 && y < 0.5) doubleArrayOf(x, y) else null
        }

        /** Kelvin and tint for the camera's current white, as the raw's as-shot defaults will be. */
        fun tempTint(whiteXY: DoubleArray): Pair<Float, Float> =
            WhiteBalance.tempTintFromXY(whiteXY[0], whiteXY[1]).let { it[0].toFloat() to it[1].toFloat() }
    }
}
