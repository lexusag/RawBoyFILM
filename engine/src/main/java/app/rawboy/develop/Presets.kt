package app.rawboy.develop

import app.rawboy.auto.looks.RetiredLooks
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Groups of sliders a preset can hold. Geometry (rotation, flips, straighten, crop) belongs to one photo and is
 * never part of a preset. [key] is the name in preset files (shared with RawBoy Web's Presets.ts).
 */
enum class PresetGroup(val key: String, val label: String, val fields: List<String>) {
    // The film fields belong to the tone mapper: Film is its fourth choice (film != null).
    LIGHT(
        "light", "Light",
        listOf(
            "toneMapper", "film", "filmAmount", "filmGrain", "halation",
            "exposure", "contrast", "highlights", "shadows", "whites", "blacks",
        ),
    ),
    WHITE_BALANCE("whiteBalance", "White balance", listOf("temperature", "tint")),
    CURVES("curves", "Curves", listOf("curves")),
    COLOR(
        "color", "Color",
        listOf(
            "saturation", "redHue", "redPurity", "greenHue", "greenPurity", "blueHue", "bluePurity",
            "tintHue", "tintPurity",
        ),
    ),
    DETAIL("detail", "Detail", listOf("denoise", "colorDenoise", "sharpening", "localContrast", "highpassContrast", "grain", "bloom")),
    RETOUCH("retouch", "Retouch", listOf("skinRetouch", "blemishRemoval")),
    LUT("lut", "LUT", listOf("lut", "lutAmount")),
    LENS("lens", "Lens", listOf("lensProfile", "lensDistortion", "vignetteAmount", "vignetteMidpoint")),
    MASKS("masks", "Masks (gradients only)", listOf("masks")),
    ;

    companion object {
        fun ofKey(key: String): PresetGroup? = entries.firstOrNull { it.key == key }

        /** What the save dialog ticks first: white balance is usually specific to one light, masks to one composition. */
        val DEFAULT: Set<PresetGroup> = entries.toSet() - WHITE_BALANCE - MASKS
    }
}

/**
 * A named set of slider values. [values] holds only the fields of [groups] (by DevelopParams property name);
 * applying it overrides those and leaves everything else as it is. [id] is local to one device. [lens] is the lens
 * name of the photo it was saved on ("" = the file names none), null for presets from before it was recorded.
 */
data class Preset(
    val id: String,
    val name: String,
    val groups: Set<PresetGroup>,
    val values: Map<String, Any?>,
    val lens: String? = null,
) {
    /**
     * A lens preset holds the Lens group alone: RawBoy Auto keeps it for photos taken with [lens] and applies it on top
     * of their look (or RawBoy preset), changing only the lens corrections.
     */
    val isLensPreset: Boolean get() = groups == setOf(PresetGroup.LENS)
}

/** Capturing, applying and the JSON file format of [Preset]s (mirrored in web's Presets.ts). */
object Presets {
    const val FORMAT = "rawboy-preset"
    const val VERSION = 1

    fun capture(name: String, params: DevelopParams, groups: Set<PresetGroup>, lens: String? = null): Preset {
        val values = LinkedHashMap<String, Any?>()
        for (g in PresetGroup.entries) if (g in groups) for (f in g.fields) values[f] = get(params, f)
        return Preset(newId(), name.trim(), groups, values, lens?.trim())
    }

    /**
     * A lens preset ([Preset.isLensPreset]) of [params]' lens corrections for the lens named [lens], called after the
     * lens ("Lens preset" when the file names none).
     */
    fun captureLens(params: DevelopParams, lens: String): Preset =
        capture(lens.trim().ifBlank { "Lens preset" }, params, setOf(PresetGroup.LENS), lens)

    /** Whether [preset] was made for the lens named [lens]: same name, ignoring case and outer spaces. */
    fun isFor(preset: Preset, lens: String): Boolean = preset.lens?.trim().equals(lens.trim(), ignoreCase = true)

    /**
     * The lens preset among [presets] for the lens named [lens] (the last one when there are several), else the last
     * one that names no lens (saved before RawBoy recorded it), which stands for any lens; null if none.
     */
    fun lensPresetFor(presets: List<Preset>, lens: String): Preset? =
        presets.lastOrNull { it.isLensPreset && isFor(it, lens) } ?: presets.lastOrNull { it.isLensPreset && it.lens == null }

    /**
     * [presets] with [lensPreset] in place of the lens preset for the same lens (or the same missing lens), keeping its
     * id and position; else added at the end.
     */
    fun putLensPreset(presets: List<Preset>, lensPreset: Preset): List<Preset> {
        val old = presets.lastOrNull { it.isLensPreset && it.lens?.trim().equals(lensPreset.lens?.trim(), ignoreCase = true) }
            ?: return presets + lensPreset
        return presets.map { if (it === old) lensPreset.copy(id = old.id) else it }
    }

    /** [params] with the preset's stored fields; fields it doesn't hold (or doesn't know) stay. */
    fun apply(preset: Preset, params: DevelopParams): DevelopParams =
        preset.values.entries.fold(params) { p, (field, value) -> set(p, field, value) }

    fun newId(): String = UUID.randomUUID().toString()

    fun toJson(p: Preset): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("name", p.name)
        p.lens?.let { put("lens", it) }
        put("groups", JSONArray(PresetGroup.entries.filter { it in p.groups }.map { it.key }))
        put(
            "values",
            JSONObject().apply {
                // Floats via their shortest decimal, so 0.1 is written as 0.1, not 0.10000000149011612.
                for ((k, v) in p.values) {
                    @Suppress("UNCHECKED_CAST")
                    put(
                        k,
                        when {
                            k == "masks" -> Masks.toJson(v as? List<MaskLayer> ?: emptyList())
                            v is ToneCurves -> v.toJson()
                            v is Float -> v.toString().toDouble()
                            else -> v ?: JSONObject.NULL
                        },
                    )
                }
            },
        )
    }

    /** One preset as an object, several as an array (what "Export all" writes). */
    fun encode(presets: List<Preset>): String =
        if (presets.size == 1) toJson(presets[0]).toString(2) else JSONArray(presets.map(::toJson)).toString(2)

    /**
     * Presets in [text] (an object or an array of them), each with a fresh [Preset.id]. Unknown groups and
     * fields are skipped, so files from newer versions still load. Throws on text that isn't a preset file.
     */
    fun decode(text: String): List<Preset> {
        val trimmed = text.trim()
        val objects = if (trimmed.startsWith("[")) {
            val a = JSONArray(trimmed)
            List(a.length()) { a.getJSONObject(it) }
        } else {
            listOf(JSONObject(trimmed))
        }
        return objects.map(::fromJson)
    }

    private fun fromJson(o: JSONObject): Preset {
        require(o.optString("format") == FORMAT) { "Not a RawBoy preset" }
        val groupKeys = o.optJSONArray("groups") ?: JSONArray()
        val groups = List(groupKeys.length()) { PresetGroup.ofKey(groupKeys.optString(it)) }.filterNotNull().toSet()
        val known = groups.flatMap { it.fields }.toSet()
        val v = o.optJSONObject("values") ?: JSONObject()
        val values = LinkedHashMap<String, Any?>()
        for (f in known) {
            if (!v.has(f)) continue
            val raw = v.get(f)
            values[f] = when {
                f == "masks" -> Masks.parseLayers(raw)
                f == "curves" -> ToneCurves.parse(raw) ?: continue
                raw == JSONObject.NULL -> null
                raw is Number -> raw.toFloat()
                else -> raw
            }
        }
        // Presets from before the separate colour denoise had one denoise for both: colour follows it.
        if ("denoise" in values && "colorDenoise" in known && !v.has("colorDenoise")) values["colorDenoise"] = null
        val lens = if (o.has("lens") && !o.isNull("lens")) o.optString("lens") else null
        return Preset(newId(), o.optString("name").ifBlank { "Preset" }, groups, values, lens)
    }

    private fun get(p: DevelopParams, field: String): Any? = when (field) {
        "toneMapper" -> p.toneMapper.key
        "film" -> p.film
        "filmAmount" -> p.filmAmount
        "filmGrain" -> p.filmGrain
        "halation" -> p.halation
        "curves" -> p.curves
        "exposure" -> p.exposure
        "contrast" -> p.contrast
        "highlights" -> p.highlights
        "shadows" -> p.shadows
        "whites" -> p.whites
        "blacks" -> p.blacks
        "temperature" -> p.temperature
        "tint" -> p.tint
        "saturation" -> p.saturation
        "redHue" -> p.redHue
        "redPurity" -> p.redPurity
        "greenHue" -> p.greenHue
        "greenPurity" -> p.greenPurity
        "blueHue" -> p.blueHue
        "bluePurity" -> p.bluePurity
        "tintHue" -> p.tintHue
        "tintPurity" -> p.tintPurity
        "denoise" -> p.denoise
        "colorDenoise" -> p.colorDenoise
        "sharpening" -> p.sharpening
        "localContrast" -> p.localContrast
        "highpassContrast" -> p.highpassContrast
        "grain" -> p.grain
        "bloom" -> p.bloom
        "skinRetouch" -> p.skinRetouch
        "blemishRemoval" -> p.blemishRemoval
        "lut" -> p.lut
        "lutAmount" -> p.lutAmount
        "lensProfile" -> p.lensProfile
        "lensDistortion" -> p.lensDistortion
        "vignetteAmount" -> p.vignetteAmount
        "vignetteMidpoint" -> p.vignetteMidpoint
        "masks" -> Masks.forPreset(p.masks) // strokes stay with the photo
        else -> throw IllegalArgumentException(field)
    }

    /** A value of the wrong type (a hand-edited file) leaves the field unchanged. */
    private fun set(p: DevelopParams, field: String, value: Any?): DevelopParams {
        if (field == "lut") return if (value == null || value is String) p.copy(lut = (value as String?)?.let(RetiredLooks::lut)) else p
        if (field == "film") return if (value == null || value is String) p.copy(film = (value as String?)?.let(RetiredLooks::stock)) else p
        if (field == "colorDenoise" && value == null) return p.copy(colorDenoise = null)
        if (field == "lensProfile") return if (value is Boolean) p.copy(lensProfile = value) else p
        if (field == "toneMapper") return ToneMapper.fromKey(value as? String)?.let { p.copy(toneMapper = it) } ?: p
        if (field == "curves") return if (value is ToneCurves) p.copy(curves = value) else p
        if (field == "masks") {
            // Fresh ids, so two photos never share a layer id.
            val layers = (value as? List<*>)?.filterIsInstance<MaskLayer>() ?: return p
            return p.copy(masks = layers.take(Masks.MAX_LAYERS).map { it.copy(id = Masks.newId()) })
        }
        val f = (value as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: return p
        return when (field) {
            "filmAmount" -> p.copy(filmAmount = f)
            "filmGrain" -> p.copy(filmGrain = f)
            "halation" -> p.copy(halation = f)
            "exposure" -> p.copy(exposure = f)
            "contrast" -> p.copy(contrast = f)
            "highlights" -> p.copy(highlights = f)
            "shadows" -> p.copy(shadows = f)
            "whites" -> p.copy(whites = f)
            "blacks" -> p.copy(blacks = f)
            "temperature" -> p.copy(temperature = f)
            "tint" -> p.copy(tint = f)
            "saturation" -> p.copy(saturation = f)
            "redHue" -> p.copy(redHue = f)
            "redPurity" -> p.copy(redPurity = f)
            "greenHue" -> p.copy(greenHue = f)
            "greenPurity" -> p.copy(greenPurity = f)
            "blueHue" -> p.copy(blueHue = f)
            "bluePurity" -> p.copy(bluePurity = f)
            "tintHue" -> p.copy(tintHue = f)
            "tintPurity" -> p.copy(tintPurity = f)
            "denoise" -> p.copy(denoise = f)
            "colorDenoise" -> p.copy(colorDenoise = f)
            "sharpening" -> p.copy(sharpening = f)
            "localContrast" -> p.copy(localContrast = f)
            "highpassContrast" -> p.copy(highpassContrast = f)
            "grain" -> p.copy(grain = f)
            "bloom" -> p.copy(bloom = f)
            "skinRetouch" -> p.copy(skinRetouch = f)
            "blemishRemoval" -> p.copy(blemishRemoval = f)
            "lutAmount" -> p.copy(lutAmount = f)
            "lensDistortion" -> p.copy(lensDistortion = f)
            "vignetteAmount" -> p.copy(vignetteAmount = f)
            "vignetteMidpoint" -> p.copy(vignetteMidpoint = f)
            else -> p
        }
    }
}
