import Foundation

/**
 * Lens data for one decoded image. Corrections come from DNG opcodes and are in
 * sensor coordinates (before orientation); [orientation] maps between the two.
 */
public final class LensInfo: @unchecked Sendable {
    /** DNG WarpRectilinear: per plane kr0..kr3, kt0, kt1; centre relative to the sensor image. */
    public struct Warp: Sendable {
        public let planes: Int
        public let coefficients: [Double]
        public let centerX: Double
        public let centerY: Double
    }

    /** DNG FixVignetteRadial: gain = 1 + k0 r^2 + ... + k4 r^10. */
    public struct RadialVignette: Sendable {
        public let k: [Double]
        public let centerX: Double
        public let centerY: Double
    }

    /** Luminance part of the DNG gain maps (already applied), so it can be undone on the GPU. */
    public struct ShadingMap: Sendable {
        public let pointsV: Int
        public let pointsH: Int
        public let originV: Double
        public let originH: Double
        public let spacingV: Double
        public let spacingH: Double
        public let gains: [Float]
    }

    public let lensName: String
    /** Oriented (display) size of the decoded image. */
    public let width: Int
    public let height: Int
    public let sensorWidth: Int
    public let sensorHeight: Int
    /** TIFF orientation 1..8 that turned the sensor image into the displayed one. */
    public let orientation: Int
    public let warp: Warp?
    public let vignette: RadialVignette?
    public let shading: ShadingMap?
    /** Number of DNG GainMaps applied while decoding (colour + lens shading). */
    public let gainMapsApplied: Int

    public init(lensName: String, width: Int, height: Int, sensorWidth: Int, sensorHeight: Int, orientation: Int,
                warp: Warp?, vignette: RadialVignette?, shading: ShadingMap?, gainMapsApplied: Int) {
        self.lensName = lensName
        self.width = width
        self.height = height
        self.sensorWidth = sensorWidth
        self.sensorHeight = sensorHeight
        self.orientation = orientation
        self.warp = warp
        self.vignette = vignette
        self.shading = shading
        self.gainMapsApplied = gainMapsApplied
    }

    public var hasProfile: Bool { warp != nil || vignette != nil || shading != nil }

    /** One line for the Lens tab, e.g. "vignetting & colour shading, distortion". */
    public var profileSummary: String? {
        var parts: [String] = []
        func add(_ s: String) { if !parts.contains(s) { parts.append(s) } }
        if shading != nil || gainMapsApplied > 0 { add("vignetting & colour shading") }
        if vignette != nil { add("vignetting") }
        if let warp { add(warp.planes == 3 ? "distortion & chromatic aberration" : "distortion") }
        return parts.isEmpty ? nil : parts.joined(separator: ", ")
    }

    /** Unpacks rb_lens_info (layout documented in jni_bridge.cpp). */
    public static func fromNative(name: String, width: Int, height: Int, v: [Double], shadingGains: [Float]) -> LensInfo {
        let warp = v[3] != 0 ? Warp(planes: Int(v[4]), coefficients: Array(v[5..<23]), centerX: v[23], centerY: v[24]) : nil
        let vignette = v[25] != 0 ? RadialVignette(k: Array(v[26..<31]), centerX: v[31], centerY: v[32]) : nil
        let shading = v[33] != 0 && !shadingGains.isEmpty
            ? ShadingMap(pointsV: Int(v[34]), pointsH: Int(v[35]), originV: v[36], originH: v[37], spacingV: v[38],
                         spacingH: v[39], gains: shadingGains)
            : nil
        return LensInfo(
            lensName: name, width: width, height: height, sensorWidth: Int(v[1]), sensorHeight: Int(v[2]),
            orientation: Int(v[0]), warp: warp, vignette: vignette, shading: shading, gainMapsApplied: Int(v[40]))
    }

    /** No image yet: identity geometry. */
    public static func none(width: Int = 1, height: Int = 1) -> LensInfo {
        LensInfo(lensName: "", width: width, height: height, sensorWidth: width, sensorHeight: height, orientation: 1,
                 warp: nil, vignette: nil, shading: nil, gainMapsApplied: 0)
    }
}
