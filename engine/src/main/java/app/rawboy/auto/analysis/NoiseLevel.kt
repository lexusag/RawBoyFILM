package app.rawboy.auto.analysis

/**
 * A photo's measured noise ([app.rawboy.raw.RawImage.measureNoise], native `estimateNoise`): the std of luma and of
 * colour noise in the develop shader's denoise domain (sqrt of the decoded linear values), 0 = not measured.
 */
data class NoiseLevel(val luma: Float, val chroma: Float) {
    val measured: Boolean get() = luma > 0f

    companion object {
        val NONE = NoiseLevel(0f, 0f)
    }
}
