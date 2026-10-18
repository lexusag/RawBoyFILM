package app.rawboy.session

import android.content.Context
import android.net.Uri
import android.util.Half
import app.rawboy.auto.analysis.AutoTone
import app.rawboy.auto.analysis.NoiseLevel
import app.rawboy.color.WhiteBalance
import app.rawboy.color.WhitePicker
import app.rawboy.develop.CropRect
import app.rawboy.develop.BrushGuide
import app.rawboy.develop.BrushRaster
import app.rawboy.develop.DevelopParams
import app.rawboy.develop.MaskLayer
import app.rawboy.develop.crop
import app.rawboy.export.ExportSettings
import app.rawboy.export.Exporter
import app.rawboy.export.OutputNaming
import app.rawboy.film.FilmData
import app.rawboy.gl.BloomSources
import app.rawboy.gl.DetailTile
import app.rawboy.gl.PreviewImage
import app.rawboy.gl.ShaderParams
import app.rawboy.gl.ZoomMath
import app.rawboy.lens.LensInfo
import app.rawboy.lens.LensTransform
import app.rawboy.lut.LutData
import app.rawboy.lut.LutLoader
import app.rawboy.raw.RawImage
import app.rawboy.retouch.SkinMask
import app.rawboy.retouch.SkinSegmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One open photo and everything the develop pipeline needs for it: the decoded raw,
 * its preview and whole-image textures, skin mask, blemish healing, the 100% view's
 * tiles, LUTs and export. Holds no UI state; apps keep their own and pass the
 * current [DevelopParams] in through [setParams].
 *
 * Runs its work in [scope] (a ViewModel's scope); call from the main thread.
 */
class PhotoSession(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Soft failures to show the user (e.g. skin detection failing). */
    private val onMessage: (String) -> Unit,
    lutCacheSize: Int = 4,
    /** Loads LUTs; apps with their own LUT cache pass it so a LUT is loaded only once. */
    private val lutLoader: (suspend (String) -> LutData)? = null,
    /** Loads film stocks (DevelopParams.film) with both LUTs; null = the session can't show film looks. */
    private val filmLoader: (suspend (String) -> FilmData)? = null,
) : AutoCloseable {
    private val _preview = MutableStateFlow<PreviewImage?>(null)
    val preview = _preview.asStateFlow()

    /** ~512 px copy of the whole image: the wide blur behind high-pass contrast. */
    private val _base = MutableStateFlow<PreviewImage?>(null)
    val base = _base.asStateFlow()

    /** Skin mask for the current photo; computed the first time Skin retouch is used. */
    private val _skin = MutableStateFlow<SkinState>(SkinState.NotComputed)
    val skin = _skin.asStateFlow()
    private var skinJob: Job? = null

    /** Automatic blemish removal for the current photo. */
    private val _blemish = MutableStateFlow(BlemishState())
    val blemish = _blemish.asStateFlow()
    private var blemishJob: Job? = null
    /** (x, y, radius, score) per candidate, most obvious first; detected once per photo. */
    private var blemishSpots: FloatArray? = null

    /** Highlight-boosted small copies that bloom scatters light from. */
    private val _bloomSources = MutableStateFlow<BloomSources?>(null)
    val bloomSources = _bloomSources.asStateFlow()

    /** Centre of the 100% view in full-resolution pixels; null = fit to view. */
    private val _zoomCenter = MutableStateFlow<Pair<Float, Float>?>(null)
    val zoomCenter = _zoomCenter.asStateFlow()

    /** Full-resolution region around the zoom centre, uploaded for the 100% view. */
    private val _detail = MutableStateFlow<DetailTile?>(null)
    val detail = _detail.asStateFlow()

    private val _lensInfo = MutableStateFlow(LensInfo.none())
    val lensInfo = _lensInfo.asStateFlow()

    /** The loaded LUT for the selected id (null while loading or when none is selected). */
    private val _lutData = MutableStateFlow<LutData?>(null)
    val lutData = _lutData.asStateFlow()
    private var selectedLut: String? = null

    /** The loaded film stock for the selected id (null while loading or when none is selected). */
    private val _filmData = MutableStateFlow<FilmData?>(null)
    val filmData = _filmData.asStateFlow()
    private var selectedFilm: String? = null

    /** Recently used LUTs, so switching back and forth is instant. */
    private val lutCache = object : LinkedHashMap<String, LutData>(lutCacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LutData>) = size > lutCacheSize
    }

    private var image: RawImage? = null
    private var params = DevelopParams()
    private var viewWidth = 0
    private var viewHeight = 0
    /** Painted masks by layer id (see [brushRaster]); UI thread only. */
    private val brushes = HashMap<String, BrushRaster>()
    private var guide: BrushGuide? = null
    private var guideSource: PreviewImage? = null
    private var tileJob: Job? = null

    val hasImage get() = image != null

    /** ISO of the open photo, 0 if unknown or nothing is open. */
    val iso: Float get() = image?.iso ?: 0f

    /** The open photo's measured noise (see [RawImage.measureNoise]); blocking, ~30 ms. */
    fun measureNoise(): NoiseLevel = image?.measureNoise() ?: NoiseLevel.NONE

    /** Whether the open photo carries the camera's own exposure (see [AutoTone.cameraMetered]). */
    val cameraMetered: Boolean get() = image?.let { AutoTone.cameraMetered(it.make, it.baselineExposure) } ?: false

    /** The skin mask, computed now if it hasn't been (for face metering); null if there is none or it failed. */
    suspend fun skinMask(): SkinMask? {
        ensureSkinMask(force = true)
        skinJob?.join()
        return (_skin.value as? SkinState.Ready)?.mask
    }

    /**
     * Decodes [uri] and swaps it in. [initialParams] maps the file's as-shot defaults
     * to the params to start with (e.g. carrying edits over from the previous photo).
     * Throws on failure; the previous photo stays open then.
     */
    suspend fun open(uri: Uri, initialParams: (defaults: DevelopParams) -> DevelopParams): Opened {
        val loaded = withContext(Dispatchers.Default) { load(context, uri) }
        exitZoom()
        image?.close()
        image = loaded.image
        _lensInfo.value = loaded.image.lens

        val defaults = asShotDefaults(loaded.image)
        _preview.value = loaded.preview
        _base.value = loaded.base
        _bloomSources.value = loaded.bloom
        skinJob?.cancel()
        _skin.value = SkinState.NotComputed
        blemishJob?.cancel()
        blemishSpots = null
        _blemish.value = BlemishState()
        brushes.clear()
        guide = null
        guideSource = null
        params = initialParams(defaults)
        ensureSkinMask()
        // Camera and exposure (ISO, shutter, aperture, focal length); the decoder's summary if none of it is known.
        return Opened(loaded.image.shotInfo.line().ifEmpty { loaded.image.description }, defaults, params)
    }

    /** Applies new slider values: starts skin detection / blemish healing and keeps the zoom tile valid. */
    fun setParams(newParams: DevelopParams) {
        val before = params
        params = newParams
        if (newParams.skinRetouch > 0f || newParams.blemishRemoval > 0f) ensureSkinMask()
        if (newParams.blemishRemoval != before.blemishRemoval) scheduleBlemishes()
        // A 90 degree turn or a new crop changes the output shape, and a flip moves the content, so the zoom position
        // no longer applies.
        val flipChanged = newParams.flipHorizontal != before.flipHorizontal || newParams.flipVertical != before.flipVertical
        if (newParams.rotation != before.rotation || newParams.crop != before.crop || flipChanged) exitZoom() else recheckZoomTile()
    }

    /** Uniforms for [p] with this photo's current textures; [maskView]: the layer whose mask is shown in red. */
    fun shaderParams(
        p: DevelopParams,
        defaults: DevelopParams,
        lut: LutData? = _lutData.value,
        maskView: String? = null,
        film: FilmData? = _filmData.value,
    ): ShaderParams =
        ShaderParams.from(
            p, defaults, lut, lensFor(p), _base.value, _bloomSources.value, (_skin.value as? SkinState.Ready)?.mask,
            maskView = maskView, brushFor = ::brushRaster, film = film,
        )

    /**
     * The painted mask of [layer] brought up to its strokes (incrementally while a stroke grows), or null without
     * strokes. Rasters are the preview's size and live as long as the photo. Call on the UI thread.
     */
    fun brushRaster(layer: MaskLayer): BrushRaster? {
        val preview = _preview.value
        if (layer.strokes.isEmpty() || preview == null) {
            brushes.remove(layer.id)
            return null
        }
        var r = brushes[layer.id]
        if (r == null || r.width != preview.width || r.height != preview.height) {
            r = BrushRaster(preview.width, preview.height) { brushGuide(preview) }
            brushes[layer.id] = r
        }
        r.sync(layer.strokes)
        return r
    }

    /** Forgets a deleted layer's painted mask. */
    fun dropBrush(layerId: String) {
        brushes.remove(layerId)
    }

    /** Auto mask guide, made from the preview the first time an Auto mask stroke needs it (blemish healing replaces the preview). */
    private fun brushGuide(preview: PreviewImage): BrushGuide {
        val g = guide
        if (g != null && guideSource === preview) return g
        return BrushGuide.of(preview).also {
            guide = it
            guideSource = preview
        }
    }

    fun setViewSize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        _zoomCenter.value?.let { setZoomCenter(it.first, it.second) }
    }

    /** Fit-view position -> full-resolution output pixel, or null outside the photo. */
    fun fitToImage(viewX: Float, viewY: Float, frameWidth: Int): Pair<Float, Float>? {
        if (image == null || viewWidth <= 0 || viewHeight <= 0) return null
        val lens = lensFor(params)
        return ZoomMath.fitToImage(viewX, viewY, viewWidth, viewHeight, lens.outputWidth, lens.outputHeight, frameWidth)
    }

    /**
     * White balance picker at a point of the preview (fit or 100% view; [frameWidth] as for [fitToImage]): the decoded
     * pixels shown there, found through crop, rotation and lens correction, averaged before any develop step.
     * Null when the point is outside the photo or nothing is open.
     */
    suspend fun pickWhite(viewX: Float, viewY: Float, frameWidth: Int, defaults: DevelopParams): WhitePicker.Pick? {
        val img = image ?: return null
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val lens = lensFor(params)
        val w = lens.outputWidth
        val h = lens.outputHeight
        val zoom = _zoomCenter.value
        val r = if (zoom != null) {
            ZoomMath.zoomedRect(zoom.first, zoom.second, viewWidth, viewHeight, w, h)
        } else {
            ZoomMath.fitRect(viewWidth, viewHeight, w, h, frameWidth)
        }
        val outX = (viewX - r[0]) / r[2] * w
        val outY = (viewY - r[1]) / r[3] * h
        if (outX !in 0f..w.toFloat() || outY !in 0f..h.toFloat()) return null
        val src = lens.source(outX.toDouble(), outY.toDouble())
        val box = WhitePicker.box(src[0].toFloat(), src[1].toFloat(), img.width, img.height)
        val bw = box[1] - box[0]
        val bh = box[3] - box[2]
        return withContext(Dispatchers.Default) {
            val buf = ByteBuffer.allocateDirect(bw * bh * RawImage.BYTES_PER_PIXEL).order(ByteOrder.nativeOrder())
            img.copyTile(box[0], box[2], bw, bh, buf)
            val halves = buf.asShortBuffer()
            val rgb = FloatArray(bw * bh * 3) { Half.toFloat(halves.get(it)) }
            WhitePicker.pick(rgb, defaults.temperature, defaults.tint)
        }
    }

    /** Double tap: zoom to 100% at the tapped point of the fit view, or back out. */
    fun toggleZoom(viewX: Float, viewY: Float, frameWidth: Int) {
        if (image == null) return
        if (_zoomCenter.value != null) {
            exitZoom()
            return
        }
        val p = fitToImage(viewX, viewY, frameWidth) ?: return
        setZoomCenter(p.first, p.second)
    }

    /** Drag in the 100% view; [dx], [dy] are screen pixels = full-resolution pixels. */
    fun pan(dx: Float, dy: Float) {
        val c = _zoomCenter.value ?: return
        setZoomCenter(c.first - dx, c.second - dy)
    }

    fun exitZoom() {
        tileJob?.cancel()
        tileJob = null
        _zoomCenter.value = null
        _detail.value = null
    }

    private fun setZoomCenter(cx: Float, cy: Float) {
        val img = image ?: return
        val lens = lensFor(params)
        val c = ZoomMath.clampCenter(cx, cy, viewWidth, viewHeight, lens.outputWidth, lens.outputHeight)
        _zoomCenter.value = c
        ensureDetail(img, c)
    }

    /** Loads a new tile only when the visible region leaves the current one. */
    private fun ensureDetail(img: RawImage, c: Pair<Float, Float>) {
        if (tileJob?.isActive == true) return
        val lens = lensFor(params)
        val view = ZoomMath.viewRect(c.first, c.second, viewWidth, viewHeight, lens.outputWidth, lens.outputHeight)
        // Source pixels the view needs, through rotation and lens geometry, plus the denoise halo.
        val needed = lens.sourceBounds(view, spatialMargin(params))
        val current = _detail.value
        if (current != null && current.rect.contains(needed)) return

        val rect = ZoomMath.tileAround(needed, img.width, img.height)
        tileJob = scope.launch {
            val tile = try {
                withContext(Dispatchers.Default) { img.createTile(rect) }
            } catch (e: Exception) {
                return@launch // image closed or replaced meanwhile
            } catch (e: OutOfMemoryError) {
                return@launch
            }
            tileJob = null
            if (image !== img || _zoomCenter.value == null) return@launch
            _detail.value = tile
            // The user may have panned past this tile while it loaded.
            _zoomCenter.value?.let { ensureDetail(img, it) }
        }
    }

    /** Lens geometry changes can move the view's source outside the loaded tile. */
    private fun recheckZoomTile() {
        val img = image ?: return
        _zoomCenter.value?.let { ensureDetail(img, it) }
    }

    /** Runs skin segmentation once per photo, when Skin retouch is first non-zero (or when [force]d). */
    private fun ensureSkinMask(force: Boolean = false) {
        val p = params
        if ((!force && p.skinRetouch <= 0f && p.blemishRemoval <= 0f) || _skin.value != SkinState.NotComputed) return
        val img = image ?: return
        val base = _base.value ?: return
        _skin.value = SkinState.Computing
        skinJob = scope.launch {
            _skin.value = try {
                val mask = withContext(Dispatchers.Default) {
                    SkinSegmenter.segment(context, base, img.width, img.height)
                }
                if (image === img) SkinState.Ready(mask) else return@launch
            } catch (e: Throwable) {
                // Errors too (e.g. a native library failing to initialise): fail softly, never crash.
                if (e is kotlinx.coroutines.CancellationException) throw e
                onMessage("Skin detection failed: ${e.message ?: e.javaClass.simpleName}")
                SkinState.Failed
            }
            recheckZoomTile() // the skin smoothing radius widens the tiles the view needs
            scheduleBlemishes()
        }
    }

    /**
     * Heals the blemishes the slider asks for, ~300 ms after it settles: spots are
     * detected once per photo, then the most obvious ones (by score) are healed in the
     * full-resolution image and the previews rebuilt from it.
     */
    private fun scheduleBlemishes() {
        blemishJob?.cancel()
        blemishJob = scope.launch {
            delay(300)
            val img = image ?: return@launch
            val level = params.blemishRemoval
            val wanted: FloatArray = if (level <= 0f) {
                FloatArray(0)
            } else {
                val mask = (_skin.value as? SkinState.Ready)?.mask ?: return@launch // runs again when the mask is ready
                if (!mask.hasSkin) return@launch
                val spots = blemishSpots ?: run {
                    _blemish.update { it.copy(working = true) }
                    withContext(Dispatchers.Default) { img.detectBlemishes(mask) }.also { blemishSpots = it }
                }
                blemishesToHeal(spots, level)
            }
            val count = wanted.size / 4
            if (count == _blemish.value.healed) {
                _blemish.update { it.copy(working = false) }
                return@launch
            }
            _blemish.update { it.copy(working = true) }
            // Not cancellable: the full-resolution image and its previews must stay in step.
            val rebuilt = withContext(NonCancellable + Dispatchers.Default) {
                img.healBlemishes(wanted)
                Triple(
                    img.createPreview(PREVIEW_MAX_DIMENSION),
                    img.createPreview(BASE_MAX_DIMENSION),
                    bloomSourcesOf(img),
                )
            }
            if (image !== img) return@launch
            _preview.value = rebuilt.first
            _base.value = rebuilt.second
            _bloomSources.value = rebuilt.third
            _detail.value = null // the 100% tile shows old pixels; reload it
            recheckZoomTile()
            _blemish.value = BlemishState(working = false, healed = count, detected = (blemishSpots?.size ?: 0) / 4)
        }
    }

    /** Source pixels needed around a region for the spatial effects (see ShaderParams.spatialMargin). */
    private fun spatialMargin(params: DevelopParams): Int {
        val mask = (_skin.value as? SkinState.Ready)?.mask
        val retouch = if (params.skinRetouch > 0f && mask?.hasSkin == true) (mask.sigmaPx * 2.4f).toInt() + 4 else 0
        val wide = if (ShaderParams.wideDenoise(maxOf(params.denoise, params.effectiveColorDenoise)) > 0f) ShaderParams.WIDE_DENOISE_MARGIN else 0
        return maxOf(ZoomMath.HALO, retouch, wide)
    }

    private var lensCache: Triple<List<Any>, CropRect, LensTransform>? = null

    /** Lens geometry for [params] on the current image; cached while the lens sliders are unchanged. */
    fun lensFor(params: DevelopParams): LensTransform {
        val info = _lensInfo.value
        val key = listOf(
            info, params.lensProfile, params.lensDistortion, params.vignetteAmount, params.vignetteMidpoint,
            params.rotation, params.flipHorizontal, params.flipVertical, params.straighten,
        )
        val crop = params.crop
        lensCache?.let { (k, c, t) ->
            if (k == key && c == crop) return t
            // Only the crop changed: the auto scale is computed on the full frame, so reuse it.
            if (k == key) return LensTransform(info, params, knownZoom = t.zoom).also { lensCache = Triple(key, crop, it) }
        }
        return LensTransform(info, params).also { lensCache = Triple(key, crop, it) }
    }

    /**
     * Shows LUT [id] (null = none) once it is loaded. On failure [onError] runs, unless
     * another LUT was selected meanwhile.
     */
    fun selectLut(id: String?, onError: (String) -> Unit) {
        if (id == selectedLut && (id == null || _lutData.value?.id == id)) return // already shown
        selectedLut = id
        if (id == null) return
        scope.launch {
            try {
                val data = loadLut(id)
                if (selectedLut == id) _lutData.value = data
            } catch (e: Exception) {
                if (selectedLut == id) onError("Could not load LUT: ${e.message}")
            } catch (e: OutOfMemoryError) {
                if (selectedLut == id) onError("LUT is too large")
            }
        }
    }

    /** Shows film stock [id] (null = none) once it is loaded, as [selectLut]. Needs a filmLoader. */
    fun selectFilm(id: String?, onError: (String) -> Unit) {
        if (id == selectedFilm && (id == null || _filmData.value?.stock?.id == id)) return
        selectedFilm = id
        val loader = filmLoader
        if (id == null || loader == null) return
        scope.launch {
            try {
                val data = loader(id)
                if (selectedFilm == id) _filmData.value = data
            } catch (e: Exception) {
                if (selectedFilm == id) onError("Could not load film: ${e.message}")
            }
        }
    }

    suspend fun loadLut(id: String): LutData {
        lutLoader?.let { return it(id) }
        // One load at a time, so repeated requests for a LUT that is loading wait for it.
        return lutLock.withLock {
            lutCache[id] ?: withContext(Dispatchers.IO) { LutLoader.load(context, id) }.also { lutCache[id] = it }
        }
    }
    private val lutLock = Mutex()

    /** Develops the full-resolution photo with [p] and saves it; returns the saved file's name. */
    suspend fun export(
        p: DevelopParams,
        defaults: DevelopParams,
        sourceUri: Uri?,
        sourceName: String,
        settings: ExportSettings,
        naming: OutputNaming = OutputNaming(),
        onProgress: (Float) -> Unit,
    ): String {
        val img = image ?: throw IllegalStateException("No photo open")
        val lut = p.lut?.takeIf { p.film == null }?.let { loadLut(it) }
        val film = p.film?.let { id -> filmLoader?.invoke(id) }
        return withContext(Dispatchers.Default) {
            Exporter(context).export(
                image = img,
                source = sourceUri,
                sourceName = sourceName,
                params = shaderParams(p, defaults, lut, film = film),
                settings = settings,
                naming = naming,
                onProgress = onProgress,
            )
        }
    }

    override fun close() {
        image?.close()
        image = null
    }

    /** What [open] found in the file. */
    class Opened(val description: String, val defaults: DevelopParams, val params: DevelopParams)

    companion object {
        // GLES 3.0 guarantees 2048px textures; plenty for an on-screen preview.
        const val PREVIEW_MAX_DIMENSION = 2048
        const val BASE_MAX_DIMENSION = 512
        const val BLOOM_TAIL_MAX_DIMENSION = 128

        /** The file's own white balance as slider defaults; everything else untouched. */
        fun asShotDefaults(image: RawImage): DevelopParams {
            val white = image.asShotWhite
            val (kelvin, tint) = WhiteBalance.tempTintFromXY(white[0], white[1]).let { it[0] to it[1] }
            return DevelopParams(temperature = kelvin.toFloat(), tint = tint.toFloat(), displaySource = image.displayReferred)
        }

        /** The highlight-boosted halo (512 px) and tail (128 px) copies bloom scatters light from. */
        fun bloomSourcesOf(image: RawImage) = BloomSources(
            halo = image.createPreview(BASE_MAX_DIMENSION, boostHighlights = true),
            tail = image.createPreview(BLOOM_TAIL_MAX_DIMENSION, boostHighlights = true),
        )

        private fun load(context: Context, uri: Uri): Loaded {
            val img = RawImage.open(context, uri)
            try {
                return Loaded(
                    image = img,
                    preview = img.createPreview(PREVIEW_MAX_DIMENSION),
                    base = img.createPreview(BASE_MAX_DIMENSION),
                    bloom = bloomSourcesOf(img),
                )
            } catch (t: Throwable) {
                img.close()
                throw t
            }
        }
    }
}

/** Everything decoded for one photo, swapped in together. */
private class Loaded(
    val image: RawImage,
    val preview: PreviewImage,
    val base: PreviewImage,
    val bloom: BloomSources,
)

sealed interface SkinState {
    data object NotComputed : SkinState
    data object Computing : SkinState
    data class Ready(val mask: SkinMask) : SkinState
    data object Failed : SkinState
}

/**
 * The detected [spots] (x, y, radius, score; strongest first) that Blemish removal [level] 1..100 heals: those that
 * stand out at least 5 .. 3 standard deviations from the surrounding skin texture (low values: only the most obvious ones).
 */
fun blemishesToHeal(spots: FloatArray, level: Float): FloatArray {
    val minScore = 5f - 2f * level / 100f
    val count = (0 until spots.size / 4).count { spots[it * 4 + 3] >= minScore }
    return spots.copyOf(count * 4)
}

data class BlemishState(
    val working: Boolean = false,
    /** Spots currently healed in the image. */
    val healed: Int = 0,
    /** Candidates found on this photo. */
    val detected: Int = 0,
)
