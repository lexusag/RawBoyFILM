import Foundation
import Metal
import MetalKit

/** Frame colours around the photo, cycled by tapping outside it. Levels are display-encoded (sRGB). */
public enum FrameColor: CaseIterable, Sendable {
    /** Perceptual middle grey (~18% reflectance). */
    case grey, white, black

    public var level: Float {
        switch self {
        case .grey: 0.46
        case .white: 1
        case .black: 0
        }
    }

    public func next() -> FrameColor {
        let all = FrameColor.allCases
        return all[(all.firstIndex(of: self)! + 1) % all.count]
    }
}

/**
 * Renders the developed image into an MTKView: either the whole downscaled
 * preview (aspect-fit) or, when zoomed, a 1:1 window into a full-resolution tile.
 * Draws on demand (the view is paused; setNeedsDisplay after each change). Main thread only.
 */
public final class DevelopRenderer: NSObject, MTKViewDelegate {
    private let ctx = MetalContext.shared
    private let textures = DevelopTextures()
    private let solidPipeline: MTLRenderPipelineState
    private var previewTexture: MTLTexture?
    private var uploadedImage: [UInt16]?
    private var uploadedDetail: DetailTile?
    private let denoiser = WaveletDenoise()
    /** Bumped on each preview upload: the denoise pre-pass caches by it. */
    private var previewVersion = 0

    public var image: PreviewImage?
    public var detail: DetailTile?
    public var params: ShaderParams?
    /** Frame around the photo in the fit view: width in pixels and its grey level (display-encoded). */
    public var frameWidth = 0
    public var frameGrey: Float = FrameColor.grey.level
    /** Extra empty space around the frame in the fit view, pixels (room for the mask tool's handles). */
    public var margin = 0
    /** Paints clipped highlights red and crushed shadows blue (Pro's histogram, `clipWarning` in develop.glsl). */
    public var clipWarning = false
    /** Centre of the 100% view in full-resolution pixels, or nil for fit-to-view. */
    public var zoomCenter: (Float, Float)?

    public override init() {
        let d = MTLRenderPipelineDescriptor()
        d.vertexFunction = ctx.library.makeFunction(name: "solidVertex")
        d.fragmentFunction = ctx.library.makeFunction(name: "solidFragment")
        d.colorAttachments[0].pixelFormat = MetalContext.previewPixelFormat
        solidPipeline = try! ctx.device.makeRenderPipelineState(descriptor: d)
        super.init()
    }

    /** Sets up [view] to be drawn by this renderer. */
    public func attach(_ view: MTKView) {
        view.device = ctx.device
        view.colorPixelFormat = MetalContext.previewPixelFormat
        view.framebufferOnly = true
        view.isPaused = true
        view.enableSetNeedsDisplay = true
        view.clearColor = MTLClearColor(red: 0.07, green: 0.07, blue: 0.07, alpha: 1)
        view.delegate = self
    }

    public func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    public func draw(in view: MTKView) {
        if let img = image, uploadedImage.map({ !Self.same($0, img.pixels) }) ?? true {
            previewTexture = img.pixels.withUnsafeBufferPointer {
                DevelopTextures.halfRgbTexture(img.width, img.height, $0.baseAddress!, reuse: previewTexture)
            }
            uploadedImage = img.pixels
            previewVersion += 1
        }
        uploadedDetail = detail

        guard let pass = view.currentRenderPassDescriptor, let drawable = view.currentDrawable,
              let commands = ctx.queue.makeCommandBuffer() else { return }
        // The denoise pre-pass renders into its own targets, so it is encoded before the view's pass.
        var source: MTLTexture?
        var denoised = false
        if let p = params, image != nil, let preview = previewTexture {
            let zoomed = zoomCenter != nil && uploadedDetail != nil
            let texture = zoomed ? uploadedDetail!.texture : preview
            source = texture
            if WaveletDenoise.active(p.denoise, p.colorDenoise) {
                let key: AnyHashable = zoomed ? "tile" : AnyHashable(previewVersion)
                let scale = zoomed ? 1 : Float(preview.width) / Float(image!.fullWidth)
                source = denoiser.encode(commands, src: texture, srcKey: key, pixelScale: scale,
                                         denoise: p.denoise, colorDenoise: p.colorDenoise)
                denoised = true
            }
        }
        guard let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else { return }
        let viewWidth = max(1, Int(view.drawableSize.width))
        let viewHeight = max(1, Int(view.drawableSize.height))
        if let p = params, let img = image, let input = source {
            textures.bind(p)
            let configure = uniforms(denoised: denoised)
            if let center = zoomCenter, let tile = uploadedDetail {
                drawZoomed(encoder, p, img, tile, input, center, viewWidth, viewHeight, configure)
            } else {
                drawFit(encoder, p, img, input, viewWidth, viewHeight, configure)
            }
        }
        encoder.endEncoding()
        commands.present(drawable)
        commands.commit()
    }

    private func drawFit(_ encoder: MTLRenderCommandEncoder, _ p: ShaderParams, _ img: PreviewImage,
                         _ preview: MTLTexture, _ viewWidth: Int, _ viewHeight: Int,
                         _ configure: @escaping (inout UniformBlock) -> Void) {
        let outW = p.lens.outputWidth
        let outH = p.lens.outputHeight
        let frame = frameWidth
        let r = ZoomMath.fitRect(viewW: viewWidth, viewH: viewHeight, imgW: outW, imgH: outH, frame: frame + margin)

        // Frame: a band of neutral grey / white / black around the photo, for judging exposure.
        if frame > 0 {
            let x0 = max(0, Int(r[0]) - frame)
            let y0 = max(0, Int(r[1]) - frame)
            let x1 = min(viewWidth, Int(r[0] + r[2]) + frame)
            let y1 = min(viewHeight, Int(r[1] + r[3]) + frame)
            if x1 > x0 && y1 > y0 {
                var color = SIMD4<Float>(frameGrey, frameGrey, frameGrey, 1)
                encoder.setScissorRect(MTLScissorRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0))
                encoder.setRenderPipelineState(solidPipeline)
                encoder.setFragmentBytes(&color, length: MemoryLayout<SIMD4<Float>>.stride, index: 0)
                encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
                encoder.setScissorRect(MTLScissorRect(x: 0, y: 0, width: viewWidth, height: viewHeight))
            }
        }

        let sx = r[2] / Float(viewWidth)
        let sy = r[3] / Float(viewHeight)
        let input = PixelRect(x: 0, y: 0, width: img.fullWidth, height: img.fullHeight)
        let output = PixelRect(x: 0, y: 0, width: outW, height: outH)
        let geometry = SampleGeometry(textureWidth: img.width, textureHeight: img.height, input: input, output: output,
                                      imageWidth: img.fullWidth, imageHeight: img.fullHeight)
        // flipY as on Android: in both GL and Metal, clip-space +y is the top of the screen.
        DevelopProgram.draw(encoder, pipeline: ctx.previewPipeline, params: p, geometry: geometry, image: preview,
                            inputs: textures, scaleX: sx, scaleY: sy, flipY: true, configure: configure)
    }

    private func drawZoomed(_ encoder: MTLRenderCommandEncoder, _ p: ShaderParams, _ img: PreviewImage,
                            _ tile: DetailTile, _ texture: MTLTexture, _ center: (Float, Float), _ viewWidth: Int,
                            _ viewHeight: Int, _ configure: @escaping (inout UniformBlock) -> Void) {
        let t = tile.rect
        // Output = what's on screen; input = the loaded full-resolution tile. If the
        // lens moves pixels outside the tile while a new one loads, edges smear briefly.
        let view = ZoomMath.viewRect(cx: center.0, cy: center.1, viewW: viewWidth, viewH: viewHeight,
                                     imgW: p.lens.outputWidth, imgH: p.lens.outputHeight)
        let geometry = SampleGeometry(textureWidth: t.width, textureHeight: t.height, input: t, output: view,
                                      imageWidth: img.fullWidth, imageHeight: img.fullHeight)
        DevelopProgram.draw(encoder, pipeline: ctx.previewPipeline, params: p, geometry: geometry, image: texture,
                            inputs: textures, scaleX: Float(view.width) / Float(viewWidth),
                            scaleY: Float(view.height) / Float(viewHeight), flipY: true, configure: configure)
    }

    /** Preview-only uniforms: the clip warning, and whether the image went through the denoise pre-pass. */
    private func uniforms(denoised: Bool) -> (inout UniformBlock) -> Void {
        let on: Float = clipWarning ? 1 : 0
        let pre: Float = denoised ? 1 : 0
        return {
            $0.float("uClipWarn", on)
            $0.float("uDenoisePre", pre)
        }
    }

    private static func same(_ a: [UInt16], _ b: [UInt16]) -> Bool {
        a.withUnsafeBufferPointer { $0.baseAddress } == b.withUnsafeBufferPointer { $0.baseAddress }
    }
}
