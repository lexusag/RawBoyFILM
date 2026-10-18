package app.rawboy.camera

import android.app.Application
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rawboy.auto.AutoAnalysis
import app.rawboy.auto.analysis.AutoTone
import app.rawboy.auto.analysis.MeterArea
import app.rawboy.auto.analysis.SceneAnalyzer
import app.rawboy.auto.analysis.SceneStats
import app.rawboy.auto.looks.Adjustments
import app.rawboy.auto.looks.LookApplier
import app.rawboy.camera.capture.CameraController
import app.rawboy.camera.capture.FlashMode
import app.rawboy.develop.DevelopParams
import app.rawboy.develop.Histogram
import app.rawboy.export.ExportFormat
import app.rawboy.export.ExportSettings
import app.rawboy.export.ExportGpu
import app.rawboy.export.Exporter
import app.rawboy.export.JpegEncoder
import app.rawboy.export.MediaStoreOutput
import app.rawboy.export.OutputNaming
import app.rawboy.film.FilmData
import app.rawboy.film.FilmStocks
import app.rawboy.gl.PreviewImage
import app.rawboy.gl.ShaderParams
import app.rawboy.gl.ThumbnailRenderer
import app.rawboy.gl.ViewfinderBase
import app.rawboy.gl.ViewfinderRenderer
import app.rawboy.lens.LensInfo
import app.rawboy.lens.LensTransform
import app.rawboy.lut.LutData
import app.rawboy.lut.LutLoader
import app.rawboy.raw.RawImage
import app.rawboy.retouch.SkinMask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class CameraStatus { NEEDS_PERMISSION, OPENING, READY, FAILED }

/** The newest saved photo, for the thumbnail next to the shutter. */
class LastShot(val uri: Uri, val thumbnail: Bitmap)

/**
 * RawBoy Camera: a viewfinder developed live with the auto develop and the chosen look, and a
 * queue that develops each raw shot into a JPEG with the same engine as RawBoy Auto.
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    val controller = CameraController(application)

    private val _settings = MutableStateFlow(CameraSettingsStore.load(application))
    val settings = _settings.asStateFlow()

    private val _status = MutableStateFlow(CameraStatus.NEEDS_PERMISSION)
    val status = _status.asStateFlow()

    /** Shots captured but not yet saved as JPEG. */
    private val _pending = MutableStateFlow(0)
    val pending = _pending.asStateFlow()

    private val _lastShot = MutableStateFlow<LastShot?>(null)
    val lastShot = _lastShot.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /** How camera frames map to the developed image; null until the camera runs. */
    private val _frame = MutableStateFlow<ViewfinderRenderer.Frame?>(null)
    val frame = _frame.asStateFlow()

    /** The viewfinder's develop: auto develop of the live frame plus the look. */
    private val _shaderParams = MutableStateFlow<ShaderParams?>(null)
    val shaderParams = _shaderParams.asStateFlow()

    /** The live develop's RGB histogram ([CameraSettings.showHistogram]); null while it is off. */
    private val _histogram = MutableStateFlow<Histogram?>(null)
    val histogram = _histogram.asStateFlow()

    /** Renders the histogram's small copy of the live develop; its own GL context and thread. */
    private val histogramRenderer = ThumbnailRenderer()
    private var histogramJob: Job? = null

    /** Press and hold: show the auto develop without the look. */
    private val _compare = MutableStateFlow(false)
    val compare = _compare.asStateFlow()

    /** The current side's lenses with RAW (UW / W / T…), found when the camera first starts. */
    private val _lenses = MutableStateFlow<List<CameraController.Info>>(emptyList())
    val lenses = _lenses.asStateFlow()

    /** Lenses per side (true = front), discovered once. */
    private var lensesBySide: Map<Boolean, List<CameraController.Info>>? = null

    /** Whether the front camera can deliver RAW (the selfie flip button shows only then). */
    private val _frontAvailable = MutableStateFlow(false)
    val frontAvailable = _frontAvailable.asStateFlow()

    /** Whether the current lens has a flash (the flash button shows only then). */
    private val _flashAvailable = MutableStateFlow(false)
    val flashAvailable = _flashAvailable.asStateFlow()

    /** Focus lock point (upright normalised, as shown), or null for continuous autofocus. */
    private val _focusPoint = MutableStateFlow<Pair<Float, Float>?>(null)
    val focusPoint = _focusPoint.asStateFlow()

    /** One event per capture, as the request goes to the camera: the UI's shutter feedback. */
    private val _shutterEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val shutterEvents = _shutterEvents.asSharedFlow()

    /** The settings or help sheet is open: the volume keys shouldn't take photos then. */
    @Volatile
    var settingsOpen = false
    private var info: CameraController.Info? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var hasPermission = false
    private var resumed = false
    private var startJob: Job? = null

    /** Device rotation (degrees clockwise from portrait), for the photo's orientation. */
    @Volatile
    var deviceDegrees = 0

    /** The latest analysed viewfinder frame. */
    private class Live(val base: ViewfinderBase, val stats: SceneStats, val defaults: DevelopParams, val auto: DevelopParams)

    @Volatile
    private var live: Live? = null
    private var liveJob: Job? = null

    private val luts = HashMap<String, LutData>()
    private val films = HashMap<String, FilmData>()
    private val lutLock = Mutex()

    /** A captured raw waiting to be developed, with the controls as they were at the shutter. */
    private data class Shot(
        val dng: Uri,
        val name: String,
        val temporary: File?,
        val settings: CameraSettings,
        /** The viewfinder's scene key at the shutter, for calibrating [CameraSettings.previewScale]. */
        val viewfinderKey: Float?,
        /** Sensor ISO from the capture, in case the DNG lacks it. */
        val iso: Float,
        /** The camera's face detection saw a face at the shutter (or can't tell): run skin detection. */
        val mayHaveFace: Boolean = true,
        /** Flash shot: the guide it was exposed with, for calibrating [CameraSettings.flashGuides]. */
        val flashGuide: Float? = null,
        /** The flash shot's lens reported a focus distance (which of the lens's guides [flashGuide] is). */
        val flashDistanceKnown: Boolean = true,
        /** Where the photo is metered, in the photo's own (level) orientation: see [meterArea]. */
        val meter: MeterArea = METER_CENTRE,
    )

    private val shots = Channel<Shot>(Channel.UNLIMITED)

    /**
     * Shots develop on one thread of their own: it keeps the export GL context between shots, and runs a
     * little below normal priority so the viewfinder stays smooth while a photo develops. (Not
     * THREAD_PRIORITY_BACKGROUND: that moves a thread to the little cores.) Threads it starts for
     * decoding (LibRaw's OpenMP, parallel rows) inherit the priority.
     */
    private val developExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(DEVELOP_THREAD_PRIORITY)
            r.run()
        }, "RawBoy-develop")
    }
    private val developDispatcher = developExecutor.asCoroutineDispatcher()

    /** Used only on the develop thread; low GPU priority so the viewfinder's frames come first. */
    private val exportGpu = ExportGpu(lowPriority = true)

    init {
        // One shot at a time: each needs a full-resolution decode.
        viewModelScope.launch {
            for (shot in shots) {
                try {
                    develop(shot)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "develop ${shot.name}", e)
                    _message.value = "Could not save ${shot.name}: " +
                        if (e is OutOfMemoryError) "not enough memory" else e.message ?: e.javaClass.simpleName
                } finally {
                    shot.temporary?.delete()
                    _pending.update { it - 1 }
                }
            }
        }
    }

    // ---- Camera lifecycle ----

    fun onPermission(granted: Boolean) {
        hasPermission = granted
        if (!granted) _status.value = CameraStatus.NEEDS_PERMISSION
        startIfReady()
    }

    fun onResume() {
        resumed = true
        startIfReady()
    }

    fun onPause() {
        resumed = false
        // The process may be killed in the background: write settings still waiting for a slider to rest.
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            CameraSettingsStore.save(app, _settings.value)
        }
        startJob?.cancel()
        startJob = null
        controller.stop() // also ends a focus lock
        _focusPoint.value = null
        if (hasPermission) _status.value = CameraStatus.OPENING
    }

    /** The viewfinder's (new) camera texture; called whenever its GL context is created. */
    fun onSurfaceTexture(st: SurfaceTexture) {
        viewModelScope.launch {
            surfaceTexture = st
            startJob?.cancel()
            startJob = null
            startIfReady()
        }
    }

    private fun startIfReady() {
        val st = surfaceTexture ?: return
        if (!hasPermission || !resumed || startJob != null) return
        _status.value = CameraStatus.OPENING
        startJob = viewModelScope.launch {
            try {
                val inf = info ?: run {
                    val sides = lensesBySide ?: withContext(Dispatchers.Default) {
                        mapOf(
                            false to controller.lenses(CameraMetadata.LENS_FACING_BACK),
                            true to controller.lenses(CameraMetadata.LENS_FACING_FRONT),
                        )
                    }.also { lensesBySide = it }
                    _frontAvailable.value = sides[true].orEmpty().isNotEmpty()
                    if (sides[false].orEmpty().isEmpty()) throw java.io.IOException("No back camera with RAW support")
                    // The saved side may have no RAW (or another phone): fall back to the back cameras.
                    val front = _settings.value.front && _frontAvailable.value
                    val all = sides.getValue(front)
                    _lenses.value = all
                    if (front != _settings.value.front) change { it.copy(front = front) }
                    (all.firstOrNull { it.label == _settings.value.lens } ?: all.first { it.label == "W" }).also { info = it }
                }
                _flashAvailable.value = inf.hasFlash
                controller.start(inf, st)
                _frame.value = ViewfinderRenderer.Frame(
                    bufferWidth = inf.previewSize.width,
                    bufferHeight = inf.previewSize.height,
                    rotation = inf.sensorOrientation,
                    fullWidth = inf.viewfinderWidth,
                    fullHeight = inf.viewfinderHeight,
                    gamma = inf.previewGamma,
                    scale = _settings.value.previewScale,
                    mirror = inf.isFront,
                )
                _status.value = CameraStatus.READY
                if (inf.label != _settings.value.lens) change { it.copy(lens = inf.label) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "camera start", e)
                _message.value = "Camera: ${e.message ?: e.javaClass.simpleName}"
                _status.value = CameraStatus.FAILED
                startJob = null
            }
        }
    }

    /** Focus lock at a point of the viewfinder (upright normalised, as shown). */
    fun focusAt(x: Float, y: Float) {
        if (_status.value != CameraStatus.READY) return
        _focusPoint.value = x to y
        controller.focusAt(x, y)
    }

    fun releaseFocus() {
        _focusPoint.value = null
        controller.releaseFocus()
    }

    /**
     * Where the auto develop meters exposure: around the tapped focus point while focus is locked (as Camera2's AE
     * does), else the centre box. Upright as the viewfinder shows it, like its 512 px copy.
     */
    private fun meterArea(): MeterArea = _focusPoint.value?.let { (x, y) -> MeterArea.Spot(x, y) } ?: METER_CENTRE

    /** Switches to the lens labelled [label] (UW, W, T) on the current side. */
    fun selectLens(label: String) {
        val lens = _lenses.value.firstOrNull { it.label == label } ?: return
        if (lens === info) return
        switchTo(lens)
        change { it.copy(lens = label) }
    }

    /** Back cameras <-> selfie camera (its main lens). */
    fun switchFacing() {
        val front = !_settings.value.front
        val all = lensesBySide?.get(front).orEmpty()
        val lens = all.firstOrNull { it.label == "W" } ?: all.firstOrNull() ?: return
        _lenses.value = all
        switchTo(lens)
        change { it.copy(front = front, lens = lens.label) }
    }

    private fun switchTo(lens: CameraController.Info) {
        info = lens
        _focusPoint.value = null // the restart ends any focus lock
        // The live develop belongs to the old lens: start the new one fresh.
        live = null
        _shaderParams.value = null
        startJob?.cancel()
        startJob = null
        startIfReady()
    }

    // ---- Live develop ----

    /** A small copy of the viewfinder frame (GL thread): analyse it unless the last one is still running. */
    fun onViewfinderBase(width: Int, height: Int, rgba: FloatArray) {
        if (liveJob?.isActive == true) return
        val f = _frame.value ?: return
        // The ISP's post-raw digital gain isn't in the raw: take it back out, and add what the still will gain
        // over the preview in low light (longer exposure), so the viewfinder meters like the shot's raw.
        val state = controller.frames.value
        val boost = state?.postRawBoost ?: 1f
        val stillGain = state?.let { controller.stillGain(it) } ?: 1f
        val scale = _settings.value.previewScale / boost * stillGain
        // The raw's colours for the ISP's current white balance and colour matrix (they follow AWB).
        val color = state?.previewColor
        if (scale != f.scale || !color.contentEquals(f.color)) _frame.value = f.copy(scale = scale, color = color)
        liveJob = viewModelScope.launch(Dispatchers.Default) {
            val base = ViewfinderBase.from(width, height, rgba, f.fullWidth, f.fullHeight)
            val state = controller.frames.value
            val defaults = state?.whiteXY?.let { xy ->
                val (kelvin, tint) = CameraController.tempTint(xy)
                DevelopParams(temperature = kelvin, tint = tint)
            } ?: DevelopParams()
            val skin = state?.faces?.takeIf { it.isNotEmpty() }?.let { faceMask(it, base) }
            val stats = SceneAnalyzer.analyze(base.base, skin, state?.iso ?: 0f, meterArea())
            live = Live(base, stats, defaults, AutoTone.develop(stats, defaults))
            publish()
        }
    }

    /** Viewfinder uniforms for the latest frame and the current controls. */
    private suspend fun publish() {
        val l = live ?: return
        val f = _frame.value ?: return
        val s = _settings.value
        val look = Edition.look(s.lookId)
        val developed = if (_compare.value) l.auto else LookApplier.apply(l.auto, look, s.strength, Adjustments(brightness = s.brightness, warmth = s.warmth), l.stats.hasFace)
        // The ISP has already denoised and sharpened the preview stream.
        val p = developed.copy(denoise = 0f, sharpening = 0f, skinRetouch = 0f)
        val lut = p.lut?.let { id -> runCatching { loadLut(id) }.getOrNull() }
        val film = p.film?.let { id -> runCatching { loadFilm(id) }.getOrNull() }
        val lens = LensTransform(LensInfo.none(f.fullWidth, f.fullHeight), p)
        val params = ShaderParams.from(
            p, l.defaults, lut, lens, l.base.base, null, null, GRAIN_SIZE_SCALE, grainStrength(s), film = film,
        )
        _shaderParams.value = params
        updateHistogram(params, l.base.base, s.showHistogram)
    }

    /**
     * Histogram of the live develop at [HISTOGRAM_SIZE] px, from the same base copy and params as the viewfinder.
     * Skipped while the previous one is still rendering: the next live update brings a newer one anyway.
     */
    private fun updateHistogram(params: ShaderParams, base: PreviewImage, on: Boolean) {
        if (!on) {
            _histogram.value = null
            return
        }
        if (histogramJob?.isActive == true) return
        histogramJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val bitmap = histogramRenderer.render(params, base, HISTOGRAM_SIZE)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
                // Switched off while rendering: don't bring it back.
                if (_settings.value.showHistogram) _histogram.value = Histogram.fromArgb(pixels)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Histogram failed", e)
            }
        }
    }

    /** Only the newest control values matter: a newer publish replaces one still running. */
    private fun republish() {
        publishJob?.cancel()
        publishJob = viewModelScope.launch(Dispatchers.Default) { publish() }
    }
    private var publishJob: Job? = null

    // ---- Controls ----

    fun setLook(id: String) = change { it.copy(lookId = id) }
    fun setStrength(v: Float) = change { it.copy(strength = v) }
    fun setBrightness(v: Float) = change { it.copy(brightness = v) }
    fun setWarmth(v: Float) = change { it.copy(warmth = v) }
    fun setJpegQuality(q: Int) = change { it.copy(jpegQuality = q.coerceIn(CameraSettings.MIN_QUALITY, 100)) }
    fun setKeepDng(keep: Boolean) = change { it.copy(keepDng = keep) }
    fun setSmallExport(on: Boolean) = change { it.copy(smallExport = on) }
    fun setRememberLast(on: Boolean) = change { it.copy(rememberLast = on) }
    fun setShowHistogram(on: Boolean) = change { it.copy(showHistogram = on) }

    /** Off -> Flash -> Focus-flash -> Off. */
    fun cycleFlash() = change { it.copy(flash = FlashMode.entries[(it.flash.ordinal + 1) % FlashMode.entries.size]) }

    fun setCompare(on: Boolean) {
        _compare.value = on
        republish()
    }

    private fun change(edit: (CameraSettings) -> CameraSettings) {
        val s = edit(_settings.value)
        _settings.value = s
        // A slider drag changes settings every frame: save once it rests.
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            CameraSettingsStore.save(app, _settings.value)
        }
        republish()
    }
    private var saveJob: Job? = null

    fun consumeMessage() {
        _message.value = null
    }

    // ---- Capture ----

    /** Takes a raw now; it is developed into a JPEG in the background. */
    fun shutter() {
        if (_status.value != CameraStatus.READY) return
        val settings = _settings.value
        val flash = settings.flash.takeIf { info?.hasFlash == true } ?: FlashMode.OFF
        // A flash shot's raw is lit by the flash, the viewfinder wasn't: no calibration from it.
        val viewfinderKey = if (flash == FlashMode.OFF) live?.stats?.key else null
        val mayHaveFace = info?.hasFaceDetection != true || controller.frames.value?.faces?.isNotEmpty() != false
        controller.frames.value?.let { f ->
            Log.d(TAG, "shutter ${settings.lensKey}: preview ISO ${f.iso.toInt()}, ${f.exposureNs / 1000} µs, boost ${f.postRawBoost}, " +
                "tonemap ${f.tonemapMode}, viewfinder key $viewfinderKey, preview scale ${settings.previewScale}, " +
                "colour ${f.previewColor?.joinToString(" ") { "%.3f".format(it) } ?: "sRGB"}, " +
                "wb ${live?.defaults?.let { "${it.temperature.toInt()}K/${it.tint.toInt()}" }} -> " +
                "${live?.auto?.let { "${it.temperature.toInt()}K/${it.tint.toInt()}" }}")
        }
        _pending.update { it + 1 }
        viewModelScope.launch {
            val shot = try {
                capture(settings, viewfinderKey, flash).copy(mayHaveFace = mayHaveFace)
            } catch (e: CancellationException) {
                _pending.update { it - 1 }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "capture", e)
                _message.value = "Capture failed: ${e.message ?: e.javaClass.simpleName}"
                _pending.update { it - 1 }
                return@launch
            }
            shots.send(shot)
        }
    }

    /** Captures a raw and saves it as DNG (in Pictures/<Edition.FOLDER>/RAW, or a temporary file). */
    private suspend fun capture(settings: CameraSettings, viewfinderKey: Float?, flash: FlashMode): Shot {
        val resolver = app.contentResolver
        val baseName = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val degrees = deviceDegrees
        val orientation = controller.exifOrientation(degrees)
        // The photo is level with the horizon; the tap was on the (portrait) viewfinder.
        val meter = meterArea().let { if (it is MeterArea.Spot) it.turned(degrees) else it }
        return if (settings.keepDng) {
            val (uri, name) = withContext(Dispatchers.IO) {
                MediaStoreOutput.create(resolver, baseName, "dng", DNG_MIME, RAW_NAMING)
            }
            val dngStart = System.nanoTime()
            val result = try {
                withContext(Dispatchers.IO) {
                    val out = resolver.openOutputStream(uri, "w") ?: throw java.io.IOException("Cannot write $name")
                    val result = out.buffered(1 shl 20).use { controller.captureDng(orientation, it, flash, settings.flashGuide) { _shutterEvents.tryEmit(Unit) } }
                    MediaStoreOutput.publish(resolver, uri)
                    result
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { resolver.delete(uri, null, null) }
                throw t
            }
            Log.d(TAG, "$name: capture + DNG write ${(System.nanoTime() - dngStart) / 1_000_000} ms")
            Shot(uri, name, null, settings, viewfinderKey, isoOf(result.result), flashGuide = result.flashGuide, flashDistanceKnown = result.distanceKnown, meter = meter)
        } else {
            val file = File(app.cacheDir, "$baseName.dng")
            val result = try {
                withContext(Dispatchers.IO) { file.outputStream().buffered(1 shl 20).use { controller.captureDng(orientation, it, flash, settings.flashGuide) { _shutterEvents.tryEmit(Unit) } } }
            } catch (t: Throwable) {
                file.delete()
                throw t
            }
            Shot(Uri.fromFile(file), file.name, file, settings, viewfinderKey, isoOf(result.result), flashGuide = result.flashGuide, flashDistanceKnown = result.distanceKnown, meter = meter)
        }
    }

    /** Raw -> auto develop + look -> JPEG (jpegli), as RawBoy Auto develops a photo. */
    private suspend fun develop(shot: Shot) {
        val s = shot.settings
        val look = Edition.look(s.lookId)
        val started = System.nanoTime()
        fun ms(from: Long) = (System.nanoTime() - from) / 1_000_000
        val (name, rawKey, autoEv) = withContext(developDispatcher) {
            val decodeStart = System.nanoTime()
            RawImage.open(app, shot.dng, EXTRA_BLACK).use { img ->
                val decodeMs = ms(decodeStart)
                val analysisStart = System.nanoTime()
                val noiseScale = if (s.front) FRONT_NOISE_ISO_SCALE else PHONE_NOISE_ISO_SCALE
                val a = AutoAnalysis.analyze(
                    app, img, shot.name, noiseScale, shot.iso, detectSkin = shot.mayHaveFace, meter = shot.meter,
                )
                val analysisMs = ms(analysisStart)
                val looked = LookApplier.apply(a.autoBase, look, s.strength, Adjustments(brightness = s.brightness, warmth = s.warmth), a.stats.hasFace)
                // High ISO: more denoise than the auto develop's table, up to the coarse stage (denoise > 65).
                val params = looked.copy(
                    denoise = maxOf(looked.denoise, highIsoDenoise(a.stats.iso * noiseScale)),
                    shadows = maxOf(looked.shadows, backlightShadows(a.stats)),
                )
                val lut = params.lut?.let { loadLut(it) }
                val film = params.film?.let { loadFilm(it) }
                val shader = ShaderParams.from(
                    params, a.defaults, lut, LensTransform(img.lens, params), a.base, a.bloom, a.skin,
                    GRAIN_SIZE_SCALE, grainStrength(s), film = film,
                )
                val export = ExportSettings(
                    format = ExportFormat.JPEG,
                    jpegQuality = s.jpegQuality,
                    jpegEncoder = JpegEncoder.JPEGLI,
                    maxLongSide = s.maxLongSide,
                )
                val exportStart = System.nanoTime()
                val saved = Exporter(app).export(img, shot.dng, shot.name, shader, export, JPEG_NAMING, exportGpu) {}
                Log.d(TAG, "${shot.name}: decode $decodeMs ms, analysis $analysisMs ms, develop + JPEG ${ms(exportStart)} ms")
                Triple(saved, a.stats.key, a.autoBase.exposure)
            }
        }
        Log.d(TAG, "saved $name in ${ms(started)} ms")
        shot.viewfinderKey?.let { calibrate(rawKey, it, s.lensKey, s.previewScale) }
        shot.flashGuide?.let { calibrateFlash(autoEv, it, CameraSettings.flashGuideKey(s.lensKey, shot.flashDistanceKnown)) }
        withContext(Dispatchers.IO) { findJpeg(name) }?.let { uri ->
            withContext(Dispatchers.IO) { thumbnail(uri) }?.let { _lastShot.value = LastShot(uri, it) }
        }
    }

    /**
     * Learns the viewfinder's preview gain for a lens from a shot: the raw's scene key over the viewfinder's.
     * Each lens's preview pipeline has its own gain (on the Pixel 9 Pro 0.5 for the selfie camera up to ~9
     * for T3), so a lens's first shot takes the full correction; later shots move halfway (in stops), which
     * smooths out scene-to-scene noise (about ±0.3 stop between shots of the same view).
     */
    private fun calibrate(rawKey: Float, viewfinderKey: Float, lens: String, scaleAtShutter: Float) {
        if (!(rawKey > 0f) || !(viewfinderKey > 0f)) return
        // The gain that would have matched this shot. Judged against the gain in use at its shutter: shots taken
        // back to back are calibrated after both were captured.
        val estimate = (scaleAtShutter * (rawKey / viewfinderKey).coerceIn(1f / 16, 16f))
        val known = _settings.value.previewScales[lens]
        val scale = if (known == null) estimate else known * sqrt(estimate / known) // halfway in stops
        val clamped = scale.coerceIn(CameraSettings.MIN_SCALE, CameraSettings.MAX_SCALE)
        Log.d(TAG, "calibrate $lens: raw key $rawKey, viewfinder key $viewfinderKey (raw/viewfinder ${rawKey / viewfinderKey}) -> preview scale $clamped")
        change { it.copy(previewScales = it.previewScales + (lens to clamped)) }
    }

    /**
     * Learns a lens's flash exposure from a flash shot: the auto develop's exposure says how far the raw was from
     * where it should be; aiming [FLASH_LIFT_EV] under it keeps highlights in the raw (dark raws are recoverable,
     * clipped ones aren't). First flash shot on a lens takes the full correction, later ones move halfway.
     */
    private fun calibrateFlash(autoEv: Float, guideUsed: Float, lens: String) {
        if (!(guideUsed > 0f)) return
        val estimate = guideUsed * 2f.pow(autoEv - FLASH_LIFT_EV)
        val known = _settings.value.flashGuides[lens]
        val guide = (if (known == null) estimate else known * sqrt(estimate / known)).coerceIn(MIN_FLASH_GUIDE, MAX_FLASH_GUIDE)
        Log.d(TAG, "calibrate flash $lens: auto exposure $autoEv EV at guide $guideUsed -> flash guide $guide")
        change { it.copy(flashGuides = it.flashGuides + (lens to guide)) }
    }

    private suspend fun loadLut(id: String): LutData = lutLock.withLock {
        luts[id] ?: withContext(Dispatchers.IO) { LutLoader.load(app, id) }.also { luts[id] = it }
    }

    /** A spektrafilm stock's two LUTs, loaded once (shares the LUT lock: loads are large and synchronized anyway). */
    private suspend fun loadFilm(id: String): FilmData = lutLock.withLock {
        films[id] ?: withContext(Dispatchers.IO) {
            val stock = FilmStocks.byId(app, id) ?: throw IllegalArgumentException("Unknown film stock $id")
            FilmStocks.load(app, stock)
        }.also { films[id] = it }
    }

    private fun findJpeg(name: String): Uri? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return app.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, JPEG_NAMING.relativePath),
            null,
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
    }

    private fun thumbnail(uri: Uri): Bitmap? = runCatching {
        app.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 16 })
        }
    }.getOrNull()

    override fun onCleared() {
        if (saveJob?.isActive == true) {
            saveJob?.cancel()
            CameraSettingsStore.save(app, _settings.value) // still pending: save now
        }
        shots.close()
        controller.close()
        histogramRenderer.close()
        // The export GL context belongs to the develop thread: release it there, after any queued work.
        developExecutor.execute { exportGpu.close() }
        developExecutor.shutdown()
    }

    companion object {
        private const val TAG = "RawBoyCamera"

        /** Flash raws are aimed this far under the auto develop's target (it lifts them back; see [calibrateFlash]). */
        private const val FLASH_LIFT_EV = 0.5f
        private const val MIN_FLASH_GUIDE = 1e-2f
        private const val MAX_FLASH_GUIDE = 1e4f
        private const val DNG_MIME = "image/x-adobe-dng"

        /**
         * A phone sensor is roughly as noisy as the camera raws the auto denoise was tuned on at
         * ~4x (2 stops more) its ISO, so the develop picks denoise/sharpening for that ISO.
         */
        private const val PHONE_NOISE_ISO_SCALE = 4f

        /**
         * Black beyond the DNG's (a fraction of the white range, ~1.4 DN of 959 on the Pixel 9 Pro): the phone's ISP
         * renders black that much deeper than the DNG's BlackLevel (which is the frame's dynamic black), measured
         * against the viewfinder at ISO 170-250. Without it the photo's deepest shadows were lifted, and bluish
         * after the white balance's blue gain, compared with the viewfinder.
         */
        private const val EXTRA_BLACK = 1.46e-3f

        /** Selfie sensors are smaller still: a first guess, to tune on photos. */
        private const val FRONT_NOISE_ISO_SCALE = 8f

        /** Nice value of the develop thread: below the UI and GL threads (0), above the background group (10). */
        private const val DEVELOP_THREAD_PRIORITY = 0

        private const val SAVE_DELAY_MS = 500L

        /**
         * Backlit scenes (a window, a lamp in frame): the phone's own preview lifts the dark parts locally, and the plain
         * raw develop left them near black (owner preferred the preview). Shadows rise with the scene's range, p98 - p5:
         * ordinary scenes span 3-5 stops (no change), a room against a window 7 (75, which matched the iPhone's
         * viewfinder on that shot). Fades out from ISO 800 to 3200, where lifting would mostly lift noise. Shots only:
         * the viewfinder's frames come already lifted.
         */
        private fun backlightShadows(s: SceneStats): Float {
            val lift = ((s.p98 - s.p5 - 5f) * 37.5f).coerceIn(0f, 80f)
            val t = ((s.iso - 800f) / 2400f).coerceIn(0f, 1f)
            return (lift * (1f - t * t * (3f - 2f * t))).roundToInt().toFloat()
        }

        /**
         * Denoise for a phone shot at high ISO, by "equivalent" ISO (shot ISO x noise scale): unchanged up to 3200
         * (back ISO 800), then 6400 -> 60, 12800 -> 80, 25600 and up -> 100 (back ISO 1600 / 3200 / 6400; selfie
         * 800 / 1600 / 3200), interpolated in stops. Above 65 the shader adds its coarse stage.
         */
        private fun highIsoDenoise(equivalentIso: Float): Float {
            if (equivalentIso <= 3200f) return 0f
            val stops = log2(equivalentIso / 3200f) // 1 at 6400, 2 at 12800, 3 at 25600
            return when {
                stops <= 1f -> 60f * stops // the auto table already gives ~45 at 6400; max() keeps the higher
                stops <= 2f -> 60f + 20f * (stops - 1f)
                stops <= 3f -> 80f + 20f * (stops - 2f)
                else -> 100f
            }.roundToInt().toFloat()
        }

        /** Exposure is metered on the middle half of the frame's width and height (owner's choice), unless tapped. */
        private val METER_CENTRE = MeterArea.Centre(0.5f)

        /** Camera grain is twice as fine as the film-scaled default (owner's preference). */
        private const val GRAIN_SIZE_SCALE = 0.5f

        /** Long side of the live histogram's copy: enough pixels for 64 bins, cheap every update. */
        private const val HISTOGRAM_SIZE = 128

        /**
         * Half-size grain partly averages out when the photo is saved at 3000 px (area downscale ×1.36): measured
         * std 0.83 -> 0.62 (simulation of the shader's grain), so it's raised to look as strong as before.
         * At full size each pixel is a point sample: no loss.
         */
        private fun grainStrength(s: CameraSettings) = if (s.smallExport) 1.34f else 1f

        private fun isoOf(r: android.hardware.camera2.CaptureResult): Float =
            (r.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 0).toFloat()
        val JPEG_NAMING = OutputNaming(folder = Edition.FOLDER, suffix = Edition.JPEG_SUFFIX)
        val RAW_NAMING = OutputNaming(folder = Edition.FOLDER + "/RAW", suffix = "")

        /**
         * Camera face rectangles as a coarse skin mask for metering (SceneAnalyzer meters skin and
         * sizes faces from it): an ellipse in the middle of each face box.
         */
        private fun faceMask(faces: List<android.graphics.RectF>, base: ViewfinderBase): SkinMask {
            val w = 64
            val h = max(1, (64f * base.base.height / base.base.width).toInt())
            val data = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            var diameter = 0f
            for (face in faces) {
                diameter = max(diameter, face.width() * base.base.fullWidth)
                val cx = face.centerX() * w
                val cy = face.centerY() * h
                val rx = face.width() * w * 0.35f
                val ry = face.height() * h * 0.35f
                if (rx <= 0f || ry <= 0f) continue
                for (y in 0 until h) for (x in 0 until w) {
                    val dx = (x + 0.5f - cx) / rx
                    val dy = (y + 0.5f - cy) / ry
                    if (dx.pow(2) + dy.pow(2) <= 1f) data.put(y * w + x, 1f)
                }
            }
            return SkinMask(w, h, data, sigmaPx = 3f, hasSkin = diameter > 0f, faceDiameterPx = diameter)
        }
    }
}
