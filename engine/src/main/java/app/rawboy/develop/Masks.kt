package app.rawboy.develop

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Mask layers (Capture One style; mirrored in web's Masks.ts): each layer is a mask with its own adjustments,
 * blended over the global develop. Positions are fractions of the decoded image (display orientation, before crop
 * and lens), so a mask stays on the same scene content when the photo is cropped, straightened, rotated or
 * lens-corrected.
 *
 * - [MaskShape.Linear]: full effect on the (x0, y0) side, fading to none at (x1, y1). curve -100..100 bends the
 *   lines into arcs: + pulls their ends towards the full-effect side (a dome over a horizon), 0 = straight.
 * - [MaskShape.Radial]: centre (cx, cy), radii rx/ry as fractions of the image's long side (circles stay round),
 *   angle in degrees (clockwise), feather 0..100 = the outer part of the radius over which the effect fades.
 * - [MaskShape.Brush]: no gradient; the mask is only what is painted.
 *
 * Any layer can also be painted on: strokes add to its mask (brush) or take away from it (erase, also from the
 * gradient), in order. Strokes belong to one photo's content, so presets never keep them (see [forPreset]).
 *
 * Adjustments use the global sliders' units (exposure EV -5..5, others -100..100). Temperature and tint are offsets
 * from the global white balance (+ = warmer / more magenta, as the global sliders). All 0 = no change.
 *
 * Everything is immutable with [List]s (never arrays): the editor compares params by value to skip re-renders.
 */
sealed interface MaskShape {
    data class Linear(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val curve: Float = 0f) : MaskShape
    data class Radial(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angle: Float, val feather: Float) : MaskShape
    data object Brush : MaskShape
}

/** One brush or erase stroke. Positions are image fractions, like gradient positions. */
data class BrushStroke(
    val id: String,
    val erase: Boolean,
    /** Auto mask: paint only on colours like the one under the brush centre. */
    val auto: Boolean,
    /** Fraction of the image's long side. */
    val radius: Float,
    /** 0..100 */
    val hardness: Float,
    /** 1..100: how much one pass adds. */
    val flow: Float,
    /** Flat x, y pairs. */
    val points: List<Float>,
)

data class LayerAdjust(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val clarity: Float = 0f,
) {
    val isZero: Boolean get() = this == LayerAdjust()
}

data class MaskLayer(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    /** 0..100 */
    val opacity: Float = 100f,
    val invert: Boolean = false,
    val shape: MaskShape,
    val adjust: LayerAdjust = LayerAdjust(),
    val strokes: List<BrushStroke> = emptyList(),
) {
    /** True when the layer can't change the image (off, transparent or no adjustments). */
    val isIdentity: Boolean get() = !enabled || opacity <= 0f || adjust.isZero

    /** A gradient, or at least one stroke (an empty brush layer has nothing to mask with). */
    val hasMask: Boolean get() = shape !is MaskShape.Brush || strokes.isNotEmpty()
}

object Masks {
    /** Uniform arrays in the shader are this long. */
    const val MAX_LAYERS = 4

    /**
     * Linear curvature: at curve 100 the lines' ends, half the image's long side from the middle, are pulled this
     * many long sides along the gradient. Shared by the shader packing and the overlay so both bend alike.
     */
    const val CURVE_SCALE = 0.5f

    /** Brush radius limits, fractions of the long side (the upper one bounds the cost of one dab). */
    const val MIN_BRUSH_RADIUS = 0.002f
    const val MAX_BRUSH_RADIUS = 0.15f

    /** How far (px) a linear mask's lines are pulled along the gradient at perpendicular distance [s] (px). */
    fun curveOffset(curve: Float, s: Float, long: Float): Float = curve / 100f * CURVE_SCALE * 4f * s * s / long

    fun newId(): String = UUID.randomUUID().toString()

    fun name(shape: MaskShape): String = when (shape) {
        is MaskShape.Linear -> "Linear"
        is MaskShape.Radial -> "Radial"
        MaskShape.Brush -> "Brush"
    }

    /** A graduated filter for the sky: full at the top, gone by the middle. */
    fun newLinear(name: String = "Linear") = MaskLayer(newId(), name, shape = MaskShape.Linear(0.5f, 0.15f, 0.5f, 0.5f))

    /** A centred ellipse with a soft edge. */
    fun newRadial(name: String = "Radial") =
        MaskLayer(newId(), name, shape = MaskShape.Radial(0.5f, 0.5f, 0.25f, 0.18f, 0f, 50f))

    /** Nothing until painted. */
    fun newBrush(name: String = "Brush") = MaskLayer(newId(), name, shape = MaskShape.Brush)

    /** The layers as a preset keeps them: gradients without strokes; brush-only layers are dropped. */
    fun forPreset(layers: List<MaskLayer>): List<MaskLayer> =
        layers.filter { it.shape !is MaskShape.Brush }.map { if (it.strokes.isEmpty()) it else it.copy(strokes = emptyList()) }

    // Preset files (the same JSON as web's Masks.ts writes). Strokes are never written or read.

    fun toJson(layers: List<MaskLayer>): JSONArray = JSONArray(forPreset(layers).map(::layerJson))

    private fun layerJson(l: MaskLayer) = JSONObject().apply {
        put("id", l.id)
        put("name", l.name)
        put("enabled", l.enabled)
        put("opacity", num(l.opacity))
        put("invert", l.invert)
        put(
            "shape",
            when (val s = l.shape) {
                is MaskShape.Linear -> JSONObject().apply {
                    put("kind", "linear"); put("x0", num(s.x0)); put("y0", num(s.y0)); put("x1", num(s.x1)); put("y1", num(s.y1))
                    put("curve", num(s.curve))
                }
                is MaskShape.Radial -> JSONObject().apply {
                    put("kind", "radial"); put("cx", num(s.cx)); put("cy", num(s.cy)); put("rx", num(s.rx)); put("ry", num(s.ry))
                    put("angle", num(s.angle)); put("feather", num(s.feather))
                }
                MaskShape.Brush -> JSONObject().put("kind", "brush")
            },
        )
        val a = l.adjust
        put(
            "adjust",
            JSONObject().apply {
                put("exposure", num(a.exposure)); put("contrast", num(a.contrast)); put("highlights", num(a.highlights))
                put("shadows", num(a.shadows)); put("temperature", num(a.temperature)); put("tint", num(a.tint))
                put("saturation", num(a.saturation)); put("clarity", num(a.clarity))
            },
        )
        put("strokes", JSONArray())
    }

    /** Floats via their shortest decimal, so 0.1 is written as 0.1. */
    private fun num(f: Float): Double = f.toString().toDouble()

    /** Layers from untrusted JSON (a preset file), each with a fresh id: invalid ones are dropped, ranges clamped. */
    fun parseLayers(v: Any?): List<MaskLayer> {
        val a = v as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { parseLayer(a.opt(it)) }.take(MAX_LAYERS)
    }

    private fun parseLayer(v: Any?): MaskLayer? {
        val o = v as? JSONObject ?: return null
        val shape = parseShape(o.optJSONObject("shape")) ?: return null
        val a = o.optJSONObject("adjust") ?: JSONObject()
        fun adj(k: String, range: Float) = num(a, k, -range, range) ?: 0f
        return MaskLayer(
            id = newId(),
            name = o.optString("name").trim().ifEmpty { name(shape) },
            enabled = o.opt("enabled") as? Boolean ?: true,
            opacity = num(o, "opacity", 0f, 100f) ?: 100f,
            invert = o.opt("invert") == true,
            shape = shape,
            adjust = LayerAdjust(
                adj("exposure", 5f), adj("contrast", 100f), adj("highlights", 100f), adj("shadows", 100f),
                adj("temperature", 100f), adj("tint", 100f), adj("saturation", 100f), adj("clarity", 100f),
            ),
            strokes = emptyList(), // strokes never come from presets
        )
    }

    private fun parseShape(s: JSONObject?): MaskShape? {
        if (s == null) return null
        fun f(k: String, min: Float = -1f, max: Float = 2f) = num(s, k, min, max)
        return when (s.optString("kind")) {
            "linear" -> MaskShape.Linear(
                f("x0") ?: return null, f("y0") ?: return null, f("x1") ?: return null, f("y1") ?: return null,
                f("curve", -100f, 100f) ?: 0f, // older files: straight
            )
            "radial" -> MaskShape.Radial(
                f("cx") ?: return null, f("cy") ?: return null, f("rx", 0.005f, 3f) ?: return null, f("ry", 0.005f, 3f) ?: return null,
                f("angle", -360f, 360f) ?: return null, f("feather", 0f, 100f) ?: return null,
            )
            else -> null
        }
    }

    private fun num(o: JSONObject, k: String, min: Float, max: Float): Float? {
        val v = o.opt(k) as? Number ?: return null
        val f = v.toFloat()
        return if (f.isFinite()) f.coerceIn(min, max) else null
    }
}
