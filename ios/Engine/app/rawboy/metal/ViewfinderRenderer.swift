import CoreVideo
import Foundation
import Metal
import MetalKit
import os

/**
 * A live camera preview developed by the same shader as a raw: each camera frame is turned back into
 * scene-linear Rec.2020 (pass 1, into a half-float texture), then developed with [params] (pass 2) and drawn
 * aspect-fit. Port of Android's ViewfinderRenderer.
 *
 * AVFoundation delivers the frames upright (and mirrored for the selfie camera) in sRGB, so pass 1 only undoes the
 * sRGB curve and primaries; [Frame.scale] brings the result to raw units (1.0 = sensor clip), learned per lens from
 * shots. The whole-image effects (high-pass contrast, bloom) sample small copies made on the GPU from every frame;
 * every [baseInterval] the 512 px one is also read back (without waiting for the GPU) for analysis, see [onBase].
 */
public final class ViewfinderRenderer: NSObject, MTKViewDelegate, @unchecked Sendable {
    /** How camera frames map to the developed image. */
    public struct Frame: Equatable, Sendable {
        /** Full-resolution (raw) size of the upright image, which sizes grain and denoise like the final. */
        public var fullWidth: Int
        public var fullHeight: Int
        /** Gain from linearised preview values to raw units (1.0 = sensor clip). */
        public var scale: Float
        /** Clockwise quarter turns that make a camera buffer upright. */
        public var rotation: Int
        /** Show a mirror image (selfie camera). */
        public var mirror: Bool
        /**
         * Undo the camera's own rendering of the frames, towards the raw: the iPhone's measured tone curve
         * (Helpers.metal kCameraCurve) and saturation (power of each channel over luminance, 1 = none).
         */
        public var toneCurve: Bool
        public var saturation: Float

        public init(fullWidth: Int, fullHeight: Int, scale: Float, rotation: Int = 0, mirror: Bool = false,
                    toneCurve: Bool = false, saturation: Float = 1) {
            self.fullWidth = fullWidth
            self.fullHeight = fullHeight
            self.scale = scale
            self.rotation = rotation
            self.mirror = mirror
            self.toneCurve = toneCurve
            self.saturation = saturation
        }
    }

    public static let baseMaxDimension = 512
    /** The linear frame's long side: plenty for a phone screen, and a quarter of a 48 MP frame's work. */
    private static let linearMaxDimension = 1920
    private static let log = Logger(subsystem: "app.rawboy", category: "RawBoyViewfinder")

    private let ctx = MetalContext.shared
    private let textures = DevelopTextures()
    private let lock = NSLock()
    private var cache: CVMetalTextureCache?
    private let linearizePipeline: MTLRenderPipelineState
    private let copyPipeline: MTLRenderPipelineState
    private let boostPipeline: MTLRenderPipelineState
    private let boxDownPipeline: MTLRenderPipelineState
    private let mipSampler: MTLSamplerState

    private var pendingBuffer: CVPixelBuffer?
    private var frameValue: Frame?
    private var paramsValue: ShaderParams?
    private var linear: MTLTexture?
    private var small: MTLTexture?
    private var halo: MTLTexture?
    private var tail: MTLTexture?
    private var lastBase = Date.distantPast
    private var readbackInFlight = false
    private var stats = FrameStats()

    public var baseInterval: TimeInterval = 0.3
    /**
     * A ~512 px linear copy of the current frame (RGB half floats, row 0 = top) with the full size it stands for;
     * called on a Metal completion thread. Hand it off quickly.
     */
    public var onBase: ((PreviewImage) -> Void)?

    public var frame: Frame? {
        get { lock.withLock { frameValue } }
        set { lock.withLock { frameValue = newValue } }
    }

    public var params: ShaderParams? {
        get { lock.withLock { paramsValue } }
        set { lock.withLock { paramsValue = newValue } }
    }

    public override init() {
        let context = MetalContext.shared
        func pipeline(_ fragment: String, _ format: MTLPixelFormat) -> MTLRenderPipelineState {
            let d = MTLRenderPipelineDescriptor()
            d.vertexFunction = context.library.makeFunction(name: "passVertex")
            d.fragmentFunction = context.library.makeFunction(name: fragment)
            d.colorAttachments[0].pixelFormat = format
            return try! context.device.makeRenderPipelineState(descriptor: d)
        }
        linearizePipeline = pipeline("linearizeFragment", .rgba16Float)
        copyPipeline = pipeline("copyFragment", .rgba16Float)
        boostPipeline = pipeline("boostFragment", .rgba16Float)
        boxDownPipeline = pipeline("boxDownFragment", .rgba16Float)
        let s = MTLSamplerDescriptor()
        s.minFilter = .linear
        s.magFilter = .linear
        s.mipFilter = .linear
        s.sAddressMode = .clampToEdge
        s.tAddressMode = .clampToEdge
        mipSampler = context.device.makeSamplerState(descriptor: s)!
        super.init()
        CVMetalTextureCacheCreate(nil, nil, ctx.device, nil, &cache)
    }

    /** Sets up [view] to draw the viewfinder at the display's rate. */
    public func attach(_ view: MTKView) {
        view.device = ctx.device
        view.colorPixelFormat = MetalContext.previewPixelFormat
        view.framebufferOnly = true
        view.preferredFramesPerSecond = 30
        view.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        view.isPaused = false
        view.enableSetNeedsDisplay = false
        view.delegate = self
    }

    /** A new camera frame (any thread): drawn with the next display refresh; older undrawn frames are dropped. */
    public func enqueue(_ buffer: CVPixelBuffer) {
        lock.withLock { pendingBuffer = buffer }
    }

    public func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    public func draw(in view: MTKView) {
        let start = Date()
        let (buffer, f, p): (CVPixelBuffer?, Frame?, ShaderParams?) = lock.withLock {
            let b = pendingBuffer
            pendingBuffer = nil
            return (b, frameValue, paramsValue)
        }
        guard let commands = ctx.queue.makeCommandBuffer() else { return }
        if let buffer, let f, let camera = cameraTexture(buffer) {
            linearizeFrame(commands, camera, f)
        }
        guard let pass = view.currentRenderPassDescriptor, let drawable = view.currentDrawable else {
            commands.commit()
            return
        }
        let encoder = commands.makeRenderCommandEncoder(descriptor: pass)!
        // Pass 2: develop to the screen, aspect-fit. The first params come from the first readback.
        if let p, let f, let linear, let small, let halo, let tail {
            textures.bind(p)
            let viewW = max(1, Int(view.drawableSize.width))
            let viewH = max(1, Int(view.drawableSize.height))
            let outW = p.lens.outputWidth
            let outH = p.lens.outputHeight
            let r = ZoomMath.fitRect(viewW: viewW, viewH: viewH, imgW: outW, imgH: outH, frame: 0)
            let geometry = SampleGeometry(
                textureWidth: linear.width, textureHeight: linear.height,
                input: PixelRect(x: 0, y: 0, width: f.fullWidth, height: f.fullHeight),
                output: PixelRect(x: 0, y: 0, width: outW, height: outH),
                imageWidth: f.fullWidth, imageHeight: f.fullHeight)
            DevelopProgram.draw(
                encoder, pipeline: ctx.previewPipeline, params: p, geometry: geometry, image: linear, inputs: textures,
                scaleX: r[2] / Float(viewW), scaleY: r[3] / Float(viewH), flipY: true,
                overrides: ["uBase": small, "uBloomHalo": halo, "uBloomTail": tail]) { u in
                DevelopProgram.liveCopies(&u, p, baseWidth: small.width, baseHeight: small.height,
                                          tailWidth: tail.width, tailHeight: tail.height)
            }
        }
        encoder.endEncoding()
        commands.present(drawable)
        commands.commit()
        stats.frame(Date().timeIntervalSince(start))
    }

    private func cameraTexture(_ buffer: CVPixelBuffer) -> MTLTexture? {
        guard let cache else { return nil }
        var texture: CVMetalTexture?
        CVMetalTextureCacheCreateTextureFromImage(nil, cache, buffer, nil, .bgra8Unorm,
                                                  CVPixelBufferGetWidth(buffer), CVPixelBufferGetHeight(buffer), 0, &texture)
        return texture.flatMap(CVMetalTextureGetTexture)
    }

    /** Pass 1 and the small copies, from [camera]; starts a base readback when one is due. */
    private func linearizeFrame(_ commands: MTLCommandBuffer, _ camera: MTLTexture, _ f: Frame) {
        let scale = min(1, Double(Self.linearMaxDimension) / Double(max(camera.width, camera.height)))
        // Turn only landscape buffers: the back lenses' frames come in the sensor's landscape orientation, the selfie
        // camera's already upright (measured on the iPhone 17 Pro Max against the saved photos).
        let rotation = camera.width > camera.height ? f.rotation : 0
        let turned = rotation % 2 == 1
        let w = max(1, Int((Double(turned ? camera.height : camera.width) * scale).rounded()))
        let h = max(1, Int((Double(turned ? camera.width : camera.height) * scale).rounded()))
        let linear = target(&self.linear, w, h, mipmapped: true)
        var params = LinearizeParams(scale: f.scale, rotation: Int32(rotation), mirror: f.mirror ? 1 : 0,
                                     toneCurve: f.toneCurve ? 1 : 0, saturation: f.saturation)
        draw(commands, linearizePipeline, into: linear, from: camera) { e in
            e.setFragmentBytes(&params, length: MemoryLayout<LinearizeParams>.stride, index: 0)
        }

        // The whole-image copies, from this frame: built from a readback instead, they lagged the image by
        // a few hundred ms and bright lights left a trailing glow when the camera moved.
        let baseScale = Float(Self.baseMaxDimension) / Float(max(w, h))
        let sw = max(1, Int((Float(w) * baseScale).rounded()))
        let sh = max(1, Int((Float(h) * baseScale).rounded()))
        let tailScale = Float(PhotoSession.bloomTailMaxDimension) / Float(max(sw, sh))
        let tw = max(1, Int(Float(sw) * tailScale))
        let th = max(1, Int(Float(sh) * tailScale))
        if let blit = commands.makeBlitCommandEncoder() {
            blit.generateMipmaps(for: linear)
            blit.endEncoding()
        }
        let small = target(&self.small, sw, sh, mipmapped: false)
        draw(commands, copyPipeline, into: small, from: linear)
        let halo = target(&self.halo, sw, sh, mipmapped: false)
        draw(commands, boostPipeline, into: halo, from: small)
        let tail = target(&self.tail, tw, th, mipmapped: false)
        var texel = SIMD2<Float>(1 / Float(sw), 1 / Float(sh))
        draw(commands, boxDownPipeline, into: tail, from: halo) { e in
            e.setFragmentBytes(&texel, length: MemoryLayout<SIMD2<Float>>.stride, index: 0)
        }

        // Every so often the 512 px copy is read back for the auto develop, once the GPU has finished it: the
        // render thread never waits (a blocking read here dropped frames on Android).
        let now = Date()
        if !readbackInFlight && now.timeIntervalSince(lastBase) >= baseInterval, let onBase {
            lastBase = now
            readbackInFlight = true
            let full = (f.fullWidth, f.fullHeight)
            commands.addCompletedHandler { [weak self] _ in
                var rgba = [UInt16](repeating: 0, count: sw * sh * 4)
                small.getBytes(&rgba, bytesPerRow: sw * 8, from: MTLRegionMake2D(0, 0, sw, sh), mipmapLevel: 0)
                var rgb = [UInt16](repeating: 0, count: sw * sh * 3)
                for i in 0..<(sw * sh) {
                    // Negative values (from the sRGB -> Rec.2020 matrix) are clamped, as Android's ViewfinderBase does.
                    for c in 0..<3 { rgb[i * 3 + c] = rgba[i * 4 + c] & 0x8000 != 0 ? 0 : rgba[i * 4 + c] }
                }
                onBase(PreviewImage(width: sw, height: sh, pixels: rgb, fullWidth: full.0, fullHeight: full.1))
                self?.lock.withLock { self?.readbackInFlight = false }
            }
        }
    }

    private struct LinearizeParams {
        /** Plain sRGB -> Rec.2020 primaries (columns, each padded to 16 bytes as in MSL's float3x3). */
        var color = (SIMD4<Float>(0.6274040, 0.0690970, 0.0163916, 0),
                     SIMD4<Float>(0.3292820, 0.9195400, 0.0880132, 0),
                     SIMD4<Float>(0.0433136, 0.0113612, 0.8955950, 0))
        var scale: Float
        var rotation: Int32
        var mirror: Float
        var toneCurve: Float
        var saturation: Float

        init(scale: Float, rotation: Int32, mirror: Float, toneCurve: Float, saturation: Float) {
            self.scale = scale
            self.rotation = rotation
            self.mirror = mirror
            self.toneCurve = toneCurve
            self.saturation = saturation
        }
    }

    /** A half-float target, reallocated when its size changes; shared storage so the base can be read back. */
    private func target(_ slot: inout MTLTexture?, _ w: Int, _ h: Int, mipmapped: Bool) -> MTLTexture {
        if let t = slot, t.width == w, t.height == h { return t }
        let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba16Float, width: w, height: h, mipmapped: mipmapped)
        d.usage = [.renderTarget, .shaderRead]
        d.storageMode = .shared
        let t = ctx.device.makeTexture(descriptor: d)!
        slot = t
        return t
    }

    private func draw(_ commands: MTLCommandBuffer, _ pipeline: MTLRenderPipelineState, into target: MTLTexture,
                      from source: MTLTexture, configure: ((MTLRenderCommandEncoder) -> Void)? = nil) {
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = target
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        guard let e = commands.makeRenderCommandEncoder(descriptor: pass) else { return }
        e.setRenderPipelineState(pipeline)
        e.setFragmentTexture(source, index: 0)
        e.setFragmentSamplerState(mipSampler, index: 0)
        configure?(e)
        e.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        e.endEncoding()
    }

    /** Logs frame rate and render-thread time every few seconds (category RawBoyViewfinder), for tuning on the phone. */
    private struct FrameStats {
        var frames = 0
        var cpu = 0.0
        var worst = 0.0
        var since = Date()

        mutating func frame(_ seconds: TimeInterval) {
            frames += 1
            cpu += seconds
            worst = max(worst, seconds)
            let elapsed = Date().timeIntervalSince(since)
            if elapsed >= 5 {
                let line = String(format: "%.1f fps, render thread %.1f ms/frame (worst %.1f)", Double(frames) / elapsed,
                                  cpu / Double(frames) * 1000, worst * 1000)
                ViewfinderRenderer.log.notice("\(line)")
                frames = 0
                cpu = 0
                worst = 0
                since = Date()
            }
        }
    }
}
