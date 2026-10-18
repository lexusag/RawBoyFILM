package app.rawboy.film

import android.content.Context
import app.rawboy.auto.looks.RetiredLooks
import app.rawboy.lut.LutData
import app.rawboy.lut.LutLoader
import org.json.JSONArray
import org.json.JSONObject

/**
 * A film stock simulated with spektrafilm (https://github.com/andreavolpato/spektrafilm, Andrea Volpato), as baked
 * by tools/spektrafilm_luts into assets/luts/spektrafilm: a film LUT (Apple Log -> normalised film density) and a
 * print LUT (density -> sRGB), plus spektrafilm's grain and halation parameters for shaders/film/film.glsl (RawBoy
 * Film) and RawBoy's own ([formation]) for shaders/film_form.glsl (the standard apps).
 */
class FilmStock(
    val id: String,
    val name: String,
    /** spektrafilm profile names; [print] is null for slides, which are scanned directly. */
    val film: String,
    val print: String?,
    val filmLut: String,
    val printLut: String,
    /** Density wire of the film LUT's output: D = code * (max - min) + min. */
    val densityMin: FloatArray,
    val densityMax: FloatArray,
    val grain: Grain,
    val halation: Halation,
    /** RawBoy's own grain, halation and scatter (shaders/film_form.glsl, the standard apps). */
    val formation: Formation = Formation.DEFAULT,
) {
    class Grain(
        val particleAreaUm2: Float,
        val particleScale: FloatArray,
        val densityMin: FloatArray,
        /** Maximum density of each layer's characteristic curve. */
        val densityMax: FloatArray,
        val uniformity: FloatArray,
        val blurPx: Float,
    )

    class Halation(
        val strength: FloatArray,
        val firstSigmaUm: Float,
        val bounceDecay: Float,
        val scatterTailUm: FloatArray,
        val scatterTailWeight: FloatArray,
    )

    /**
     * Parameters of shaders/film_form.glsl, from formation.json (RawBoy's data, not spektrafilm's): per film layer
     * (C, M, Y) RMS granularity at net density 1 through a 48 um aperture, per channel the fraction of light the base
     * returns and its disc radius, and the in-emulsion scatter.
     */
    class Formation(
        val grainRms: FloatArray,
        val halation: FloatArray,
        val haloRadiusUm: Float,
        val scatter: FloatArray,
        val scatterUm: Float,
    ) {
        companion object {
            val DEFAULT = Formation(FloatArray(3), FloatArray(3), 0f, FloatArray(3), 0f)

            /** The stock [id] in formation.json, its own values over the file's defaults. */
            fun parse(json: JSONObject, id: String): Formation {
                val d = json.getJSONObject("defaults")
                val s = json.getJSONObject("stocks").optJSONObject(id) ?: return DEFAULT
                fun pick(key: String) = if (s.has(key)) s else d
                val rms = s.getDouble("rms").toFloat()
                val layers = d.getJSONArray("layerGrain").floats()
                return Formation(
                    grainRms = FloatArray(3) { rms * layers[it] },
                    halation = pick("halation").getJSONArray("halation").floats(),
                    haloRadiusUm = pick("haloRadiusUm").getDouble("haloRadiusUm").toFloat(),
                    scatter = pick("scatter").getJSONArray("scatter").floats(),
                    scatterUm = pick("scatterUm").getDouble("scatterUm").toFloat(),
                )
            }
        }
    }

    val filmLutId: String get() = "$ASSET_PREFIX$filmLut"
    val printLutId: String get() = "$ASSET_PREFIX$printLut"

    companion object {
        const val ASSET_DIR = "luts/spektrafilm"
        private const val ASSET_PREFIX = "asset:$ASSET_DIR/"

        fun parse(json: JSONObject, formation: JSONObject? = null): FilmStock {
            val g = json.getJSONObject("grain")
            val h = json.getJSONObject("halation")
            return FilmStock(
                id = json.getString("id"),
                name = json.getString("name"),
                film = json.getString("film"),
                print = if (json.isNull("print")) null else json.getString("print"),
                filmLut = json.getString("filmLut"),
                printLut = json.getString("printLut"),
                densityMin = json.getJSONArray("densityMin").floats(),
                densityMax = json.getJSONArray("densityMaxWire").floats(),
                grain = Grain(
                    particleAreaUm2 = g.getDouble("particleAreaUm2").toFloat(),
                    particleScale = g.getJSONArray("particleScale").floats(),
                    densityMin = g.getJSONArray("densityMin").floats(),
                    densityMax = g.getJSONArray("densityMax").floats(),
                    uniformity = g.getJSONArray("uniformity").floats(),
                    blurPx = g.getDouble("blurPx").toFloat(),
                ),
                halation = Halation(
                    strength = h.getJSONArray("strength").floats(),
                    // spektrafilm allows a sigma per channel; its stocks use one for all three.
                    firstSigmaUm = h.getJSONArray("firstSigmaUm").getDouble(0).toFloat(),
                    bounceDecay = h.getDouble("bounceDecay").toFloat(),
                    scatterTailUm = h.getJSONArray("scatterTailUm").floats(),
                    scatterTailWeight = h.getJSONArray("scatterTailWeight").floats(),
                ),
                formation = formation?.let { Formation.parse(it, json.getString("id")) } ?: Formation.DEFAULT,
            )
        }

        private fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }
    }
}

/** A stock with both its LUTs loaded, ready for [app.rawboy.gl.ShaderParams]. */
class FilmData(val stock: FilmStock, val film: LutData, val print: LutData)

/** The stocks in assets/luts/spektrafilm/stocks.json (generated by tools/spektrafilm_luts/generate.py). */
object FilmStocks {
    @Volatile
    private var cached: List<FilmStock>? = null

    fun all(context: Context): List<FilmStock> = cached ?: synchronized(this) {
        cached ?: run {
            fun json(name: String) =
                JSONObject(context.assets.open("${FilmStock.ASSET_DIR}/$name").bufferedReader().use { it.readText() })
            val formation = json("formation.json")
            val stocks = json("stocks.json").getJSONArray("stocks")
            List(stocks.length()) { FilmStock.parse(stocks.getJSONObject(it), formation) }
        }.also { cached = it }
    }

    fun byId(context: Context, id: String): FilmStock? =
        RetiredLooks.stock(id).let { key -> all(context).firstOrNull { it.id == key } }

    /** Loads both LUTs (LutLoader is synchronized and slow; callers cache the result). */
    fun load(context: Context, stock: FilmStock): FilmData =
        FilmData(stock, LutLoader.load(context, stock.filmLutId), LutLoader.load(context, stock.printLutId))
}
