import Foundation
import Metal

/**
 * Runs the develop shader on full-resolution tiles off screen. Not thread-safe: use one per thread or queue.
 *
 * Output is read back as RGBA 16-bit values (0..65535) from an RGBA16Uint target, row 0 = top. Android reads a GL
 * framebuffer (row 0 = bottom) with flipY off; Metal's row 0 is the top, so the quad is drawn with flipY on.
 */
final class OffscreenDeveloper {
    /** Source image size (display orientation); setImageSize changes it when the developer is reused. */
    private var imageWidth: Int
    private var imageHeight: Int
    private let ctx = MetalContext.shared
    private let textures = DevelopTextures()
    private var inputTexture: MTLTexture?
    private var outputTexture: MTLTexture?

    /** Bytes per sample of render/renderPreview output: always 2 on Metal (Android may need 4). */
    let bytesPerSample = 2

    /** Smallest grain cell for render (see SampleGeometry.minGrainPx). */
    var minGrainPx: Float = 1

    init(imageWidth: Int, imageHeight: Int) {
        self.imageWidth = imageWidth
        self.imageHeight = imageHeight
    }

    func setImageSize(_ width: Int, _ height: Int) {
        imageWidth = width
        imageHeight = height
    }

    /**
     * Develops the full-resolution region [out] and reads it back.
     *
     * [input] holds RGB half floats for [inRect], which must contain every source
     * pixel [out] needs (lens geometry + denoise neighbourhood), so tiles match
     * seamlessly. [output] receives out.width*out.height*4 values in 0..65535.
     */
    func render(_ params: ShaderParams, input: UnsafePointer<UInt16>, inRect: PixelRect, out: PixelRect,
                output: UnsafeMutablePointer<UInt16>) throws {
        textures.bind(params)
        let image = DevelopTextures.halfRgbTexture(inRect.width, inRect.height, input, reuse: inputTexture)
        inputTexture = image
        var geometry = SampleGeometry(textureWidth: inRect.width, textureHeight: inRect.height, input: inRect,
                                      output: out, imageWidth: imageWidth, imageHeight: imageHeight)
        geometry.minGrainPx = minGrainPx
        try draw(params, geometry, image, out.width, out.height, output)
    }

    /**
     * Develops the whole photo from its downscaled [preview] into a [width] x [height]
     * image (thumbnails), the way the on-screen fit view does: spatial effects are
     * scaled by the preview's footprint. [output] receives width*height*4 values in 0..65535.
     */
    func renderPreview(_ params: ShaderParams, preview: PreviewImage, width: Int, height: Int,
                       output: UnsafeMutablePointer<UInt16>) throws {
        textures.bind(params)
        let image = preview.pixels.withUnsafeBufferPointer {
            DevelopTextures.halfRgbTexture(preview.width, preview.height, $0.baseAddress!, reuse: inputTexture)
        }
        inputTexture = image
        let input = PixelRect(x: 0, y: 0, width: preview.fullWidth, height: preview.fullHeight)
        let out = PixelRect(x: 0, y: 0, width: params.lens.outputWidth, height: params.lens.outputHeight)
        let geometry = SampleGeometry(textureWidth: preview.width, textureHeight: preview.height, input: input,
                                      output: out, imageWidth: preview.fullWidth, imageHeight: preview.fullHeight)
        try draw(params, geometry, image, width, height, output)
    }

    /** Draws into a [width] x [height] target and reads it back. */
    private func draw(_ params: ShaderParams, _ geometry: SampleGeometry, _ image: MTLTexture, _ width: Int,
                      _ height: Int, _ output: UnsafeMutablePointer<UInt16>) throws {
        let target: MTLTexture
        if let t = outputTexture, t.width == width, t.height == height {
            target = t
        } else {
            guard let t = ctx.makeTexture(.rgba16Uint, width, height, usage: .renderTarget) else {
                throw MetalError(message: "cannot allocate a \(width)x\(height) render target")
            }
            target = t
            outputTexture = t
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = target
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        guard let commands = ctx.queue.makeCommandBuffer(),
              let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else {
            throw MetalError(message: "cannot encode the develop pass")
        }
        DevelopProgram.draw(encoder, pipeline: ctx.exportPipeline, params: params, geometry: geometry, image: image,
                            inputs: textures, flipY: true)
        encoder.endEncoding()
        commands.commit()
        commands.waitUntilCompleted()
        if let error = commands.error { throw MetalError(message: "develop failed: \(error.localizedDescription)") }
        target.getBytes(output, bytesPerRow: width * 8, from: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0)
    }
}
