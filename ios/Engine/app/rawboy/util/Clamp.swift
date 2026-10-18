import Foundation

// Kotlin's coerceIn, which the ported engine uses everywhere (and the apps).
extension Comparable {
    @inline(__always)
    public func clamped(_ lower: Self, _ upper: Self) -> Self { min(max(self, lower), upper) }
}

// Kotlin's roundToInt / Math.round: ties go up (towards +infinity); Swift's rounded() takes them away from zero.
extension Float {
    @inline(__always)
    func roundToInt() -> Int { Int((self + 0.5).rounded(.down)) }
}

extension Double {
    @inline(__always)
    func roundToInt() -> Int { Int((self + 0.5).rounded(.down)) }
}
