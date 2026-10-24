import Foundation
import Metal

/**
 * Multi-scale denoise pre-pass: renders a denoised copy of the image texture that the develop shader reads in place
 * of the image, with `uDenoisePre` 1 so it only adds its fine chroma stage. Port of gl/WaveletDenoise.kt (method,
 * measurements and constants are explained there); the passes are Helpers.metal's hand ports of
 * <repo>/shaders/denoise_*.frag. Encoded on the caller's command buffer ahead of the develop pass. The camera
 * viewfinder doesn't use it (per frame it would cost too much); its shots do, through OffscreenDeveloper.
 * Not thread-safe: one per renderer.
 */
final class WaveletDenoise {
    /** Luma noise per band relative to band 0, measured on flat raw patches (sqrt domain). */
    private static let lumaProfile: [Float] = [1.0, 0.32, 0.14, 0.065, 0.03, 0.015]
    /** Chroma noise per band, relative to luma band 0. */
    private static let chromaProfile: [Float] = [0.76, 0.48, 0.28, 0.14, 0.06, 0.03]
    /** Threshold per band in noise sigmas (firm, so flat areas lose each band's noise consistently). */
    private static let lumaK: [Float] = [1.3, 1.4, 1.8, 2.2, 2.5, 2.5]
    private static let chromaK: [Float] = [1.5, 2.0, 2.5, 3.0, 3.0, 3.0]
    /** Fraction of each band's original added back: the camera's own noise at a lower level, not plastic. */
    private static let lumaKeep: [Float] = [0.525, 0.525, 0.4375, 0.35, 0.35, 0.35]
    /** Coarse chroma kept: invisible as colour, but grain for a mono LUT under coloured light. */
    private static let chromaKeep: Float = 0.175
    /** Full-res bands below this are the develop shader's fine chroma stage, not shrunk here. */
    private static let fineChromaBands: Float = 1.5
    /** Full-resolution levels: reach 2 * (1 + 2 + 4 + 8 + 16) = 62 px (margin). */
    private static let levelCount = 5
    /** Ratio chroma offset in the sqrt domain. */
    private static let ratioK: Float = 0.02
    /** c of the sqrt(x + c) transform = (offsetK sigma)^2: keeps read noise near black from turning into dark dots. */
    private static let offsetK: Float = 12

    /** Source pixels the pre-pass reads around a pixel (5 levels of a 5-tap kernel, strides 1..16). */
    static let margin = 64

    struct Level: Equatable {
        let stride: Int
        let lumaThreshold: Float
        let chromaThreshold: Float
        let lumaKeep: Float
        let chromaKeep: Float
    }

    /** Matches Helpers.metal's DenoiseSplit. */
    private struct SplitUniforms {
        var k: Float
        var offset: Float
    }

    /** Matches Helpers.metal's DenoiseShrink. */
    private struct ShrinkUniforms {
        var lumaThreshold: Float
        var chromaThreshold: Float
        var k: Float
        var offset: Float
        var keep: Float
        var keepC: Float
        var stride: Int32
        var first: Int32
    }

    private let ctx = MetalContext.shared
    private let split: MTLRenderPipelineState
    private let level: MTLRenderPipelineState
    private let shrink: MTLRenderPipelineState
    private let merge: MTLRenderPipelineState
    private var low: [MTLTexture] = []
    private var acc: [MTLTexture] = []
    private var detail: MTLTexture?
    private var out: MTLTexture?
    /** What [out] holds, so an unchanged image and strength aren't denoised again on every slider move. */
    private var key: [AnyHashable]?
    /** The texture [out] was made from, held so its identity can't be reused while cached. */
    private var source: MTLTexture?

    init() {
        let context = ctx
        func pipeline(_ fragment: String, _ formats: [MTLPixelFormat]) -> MTLRenderPipelineState {
            let d = MTLRenderPipelineDescriptor()
            d.vertexFunction = context.library.makeFunction(name: "passVertex")
            d.fragmentFunction = context.library.makeFunction(name: fragment)
            for (i, f) in formats.enumerated() { d.colorAttachments[i].pixelFormat = f }
            return try! context.device.makeRenderPipelineState(descriptor: d)
        }
        split = pipeline("denoiseSplitFragment", [.rgba32Float])
        level = pipeline("denoiseLevelFragment", [.rgba32Float, .rgba32Float])
        shrink = pipeline("denoiseShrinkFragment", [.rgba32Float])
        merge = pipeline("denoiseMergeFragment", [.rgba16Float])
    }

    /** Whether encode runs for these strengths (0..1 sliders, as ShaderParams.denoise / colorDenoise). */
    static func active(_ denoise: Float, _ colorDenoise: Float) -> Bool { denoise > 0 || colorDenoise > 0 }

    private func ensure(_ w: Int, _ h: Int) {
        if let o = out, o.width == w, o.height == h { return }
        let usage: MTLTextureUsage = [.shaderRead, .renderTarget]
        low = (0..<2).map { _ in ctx.makeTexture(.rgba32Float, w, h, usage: usage)! }
        acc = (0..<2).map { _ in ctx.makeTexture(.rgba32Float, w, h, usage: usage)! }
        detail = ctx.makeTexture(.rgba32Float, w, h, usage: usage)
        out = ctx.makeTexture(.rgba16Float, w, h, usage: usage)
        key = nil
    }

    /**
     * Encodes the denoise of [src] ([pixelScale] texels per full-resolution pixel) on [commands] and returns the
     * texture that will hold the result. [denoise]/[colorDenoise]: the 0..1 sliders. [srcKey] identifies [src]'s
     * contents: with the same key and strengths the previous result is returned and nothing is encoded.
     */
    func encode(_ commands: MTLCommandBuffer, src: MTLTexture, srcKey: AnyHashable, pixelScale: Float,
                denoise: Float, colorDenoise: Float) -> MTLTexture {
        let w = src.width, h = src.height
        ensure(w, h)
        let newKey: [AnyHashable] = [srcKey, w, h, pixelScale, denoise, colorDenoise]
        if newKey == key, src === source { return out! }
        // The sliders' sigma, as the develop shader's (0.03 at full strength, ~ISO 6400 on a typical sensor).
        let sigmaL = denoise * 0.03
        let sigmaC = colorDenoise * 0.03
        var splitU = SplitUniforms(k: Self.ratioK, offset: Self.offset(sigmaL, sigmaC))

        func pass(_ targets: [MTLTexture], _ body: (MTLRenderCommandEncoder) -> Void) {
            let d = MTLRenderPassDescriptor()
            for (i, t) in targets.enumerated() {
                d.colorAttachments[i].texture = t
                d.colorAttachments[i].loadAction = .dontCare
                d.colorAttachments[i].storeAction = .store
            }
            let e = commands.makeRenderCommandEncoder(descriptor: d)!
            body(e)
            e.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            e.endEncoding()
        }

        pass([low[0]]) { e in
            e.setRenderPipelineState(split)
            e.setFragmentTexture(src, index: 0)
            e.setFragmentBytes(&splitU, length: MemoryLayout<SplitUniforms>.stride, index: 0)
        }
        var cur = 0, a = 0
        for (j, lv) in Self.levels(pixelScale: pixelScale, sigmaL: sigmaL, sigmaC: sigmaC).enumerated() {
            var stride = Int32(lv.stride)
            let from = low[cur]
            pass([low[1 - cur], detail!]) { e in
                e.setRenderPipelineState(level)
                e.setFragmentTexture(from, index: 0)
                e.setFragmentBytes(&stride, length: MemoryLayout<Int32>.stride, index: 0)
            }
            cur = 1 - cur
            // Energy window at about the band's own spacing.
            var u = ShrinkUniforms(lumaThreshold: lv.lumaThreshold, chromaThreshold: lv.chromaThreshold, k: Self.ratioK,
                                   offset: splitU.offset, keep: lv.lumaKeep, keepC: lv.chromaKeep,
                                   stride: Int32(max(1, lv.stride >> 1)), first: j == 0 ? 1 : 0)
            let sum = acc[a]
            pass([acc[1 - a]]) { e in
                e.setRenderPipelineState(shrink)
                e.setFragmentTexture(detail, index: 0)
                e.setFragmentTexture(sum, index: 1)
                e.setFragmentBytes(&u, length: MemoryLayout<ShrinkUniforms>.stride, index: 0)
            }
            a = 1 - a
        }
        let lowFinal = low[cur], sumFinal = acc[a]
        pass([out!]) { e in
            e.setRenderPipelineState(merge)
            e.setFragmentTexture(lowFinal, index: 0)
            e.setFragmentTexture(sumFinal, index: 1)
            e.setFragmentBytes(&splitU, length: MemoryLayout<SplitUniforms>.stride, index: 0)
        }
        key = newKey
        source = src
        return out!
    }

    /** c of the sqrt(x + c) transform for the sliders' sigmas. */
    static func offset(_ sigmaL: Float, _ sigmaC: Float) -> Float {
        let s = offsetK * max(sigmaL, sigmaC * 0.5)
        return s * s
    }

    /** Value of a per-band table at fractional band b (log-linear between entries, halving past the end). */
    private static func profileAt(_ table: [Float], _ b: Float) -> Float {
        let last = table.count - 1
        if b >= Float(last) { return table[last] * pow(0.5, b - Float(last)) }
        let i = Int(b.rounded(.down))
        let f = b - Float(i)
        return exp(log(table[i]) * (1 - f) + log(table[i + 1]) * f)
    }

    private static func linearAt(_ table: [Float], _ b: Float) -> Float {
        let i = min(Int(b.rounded(.down)), table.count - 1)
        let j = min(i + 1, table.count - 1)
        let f = min(1, b - Float(i))
        return table[i] * (1 - f) + table[j] * f
    }

    /**
     * The levels for a texture of [pixelScale] texels per full-res pixel; [sigmaL]/[sigmaC]: the sliders' noise std
     * in the sqrt domain (full resolution).
     */
    static func levels(pixelScale: Float, sigmaL: Float, sigmaC: Float) -> [Level] {
        let shift = max(0, log2(1 / pixelScale))
        let count = min(levelCount, max(1, Int((Float(levelCount) - shift).rounded())))
        return (0..<count).map { j in
            let band = Float(j) + shift  // the full-resolution band this texture band stands for
            let fineChroma = band < fineChromaBands
            return Level(
                stride: 1 << j,
                lumaThreshold: sigmaL * profileAt(lumaProfile, band) * linearAt(lumaK, band),
                chromaThreshold: fineChroma ? 0 : sigmaC * profileAt(chromaProfile, band) * linearAt(chromaK, band),
                lumaKeep: linearAt(lumaKeep, band),
                chromaKeep: fineChroma ? 1 : chromaKeep)
        }
    }
}
