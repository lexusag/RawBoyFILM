import Foundation

/**
 * Where SceneAnalyzer meters exposure, in fractions of the upright image (x right, y down). Camera meters
 * centre(0.5) and, while focus is locked by a tap, a spot there. Port of MeterArea.kt.
 */
public enum MeterArea: Equatable, Sendable {
    /** The centred box this fraction of the width and height (0.5 = middle half each way). */
    case centre(Float)
    /**
     * A square `size` of the short side around (x, y), moved inside the frame near an edge (so a tap in a corner still
     * meters a box of the same size).
     */
    case spot(x: Float, y: Float, size: Float = MeterArea.spotSize)

    /** Tap metering: 16% of the short side (about the size of the focus ring). */
    public static let spotSize: Float = 0.16

    /** Pixel box [x0, x1) × [y0, y1) in a width × height image: never empty, always inside it. */
    public func box(_ width: Int, _ height: Int) -> (x0: Int, x1: Int, y0: Int, y1: Int) {
        switch self {
        case let .centre(fraction):
            let x0 = Int((1 - fraction) / 2 * Float(width))
            let y0 = Int((1 - fraction) / 2 * Float(height))
            return (x0, max(x0 + 1, width - x0), y0, max(y0 + 1, height - y0))
        case let .spot(x, y, size):
            let short = min(width, height)
            let side = min(max(Int((size * Float(short)).rounded()), 1), short)
            let x0 = min(max(Int((x * Float(width) - Float(side) / 2).rounded()), 0), width - side)
            let y0 = min(max(Int((y * Float(height) - Float(side) / 2).rounded()), 0), height - side)
            return (x0, x0 + side, y0, y0 + side)
        }
    }

    /**
     * The same area in the photo taken with the device turned `deviceDegrees` clockwise from portrait: the photo is
     * level with the horizon, so the viewfinder's (portrait) point turns the other way. The centre box stays.
     */
    public func turned(_ deviceDegrees: Int) -> MeterArea {
        guard case let .spot(x, y, size) = self else { return self }
        switch (deviceDegrees % 360 + 360) % 360 {
        case 90: return .spot(x: 1 - y, y: x, size: size)
        case 180: return .spot(x: 1 - x, y: 1 - y, size: size)
        case 270: return .spot(x: y, y: 1 - x, size: size)
        default: return self
        }
    }
}
