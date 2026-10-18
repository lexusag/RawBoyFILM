import SwiftUI

/**
 * The developed photo's red, green and blue levels (Pro above the slider tabs, Camera in a corner of the viewfinder),
 * with the share of crushed shadows (left corner) and clipped highlights (right corner). [active]: the clipping
 * warning is on, marked by the border and indicators. Tapping calls [onTap] when given. Port of web's HistogramView.
 */
public struct HistogramView: View {
    /** Shown as clipped from this share of the photo (0.1%): a few stray pixels aren't worth a warning. */
    public static let clipShown: Float = 0.001

    let histogram: Histogram?
    let active: Bool
    let onTap: (() -> Void)?

    public init(histogram: Histogram?, active: Bool = false, onTap: (() -> Void)? = nil) {
        self.histogram = histogram
        self.active = active
        self.onTap = onTap
    }

    public var body: some View {
        let shape = RoundedRectangle(cornerRadius: 6)
        ZStack {
            Canvas { ctx, size in
                guard let h = histogram else { return }
                ctx.clip(to: Path(CGRect(origin: .zero, size: size)))
                // Overlaps add up: red + green reads yellow, all three white.
                ctx.blendMode = .plusLighter
                let channels: [([Int], Color)] = [
                    (h.red, Color(red: 0.90, green: 0.27, blue: 0.24)),
                    (h.green, Color(red: 0.27, green: 0.78, blue: 0.35)),
                    (h.blue, Color(red: 0.27, green: 0.47, blue: 0.94)),
                ]
                for (bins, color) in channels {
                    ctx.fill(Self.area(bins, peak: h.peak, size: size), with: .color(color.opacity(0.75)))
                }
            }
            .padding(.horizontal, 2)
            .padding(.vertical, 3)
            HStack(alignment: .bottom) {
                indicator(histogram?.shadows ?? 0, Color(red: 0.35, green: 0.55, blue: 1), leading: true)
                Spacer(minLength: 0)
                indicator(histogram?.highlights ?? 0, Color(red: 1, green: 0.3, blue: 0.25), leading: false)
            }
            .frame(maxHeight: .infinity, alignment: .top)
            .padding(4)
        }
        .background(Color.black.opacity(0.45), in: shape)
        .overlay(shape.stroke(active ? Color.accentColor : Color.white.opacity(0.15), lineWidth: active ? 1.5 : 1))
        .clipShape(shape)
        .contentShape(shape)
        .onTapGesture { onTap?() }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Histogram")
        .accessibilityValue(accessibilityValue)
        .accessibilityAddTraits(onTap != nil ? [.isButton] : [])
        .accessibilityAddTraits(active ? [.isSelected] : [])
    }

    /** A dot (grey, or [color] once clipped) with the clipped share next to it. */
    private func indicator(_ fraction: Float, _ color: Color, leading: Bool) -> some View {
        let on = fraction > Self.clipShown
        let dot = Circle().fill(on ? color : Color.gray.opacity(0.6)).frame(width: 6, height: 6)
            .overlay(Circle().stroke(Color.white.opacity(active && on ? 0.9 : 0), lineWidth: 1))
        let label = fraction > Self.clipShown ? Text(Self.percent(fraction)) : nil
        return HStack(spacing: 3) {
            if leading { dot }
            if let label {
                label.font(.system(size: 9, weight: .medium).monospacedDigit())
                    .foregroundStyle(on ? Color.white : Color.white.opacity(0.55))
            }
            if !leading { dot }
        }
    }

    private var accessibilityValue: String {
        guard let h = histogram else { return "" }
        return "Shadows clipped \(Self.percent(h.shadows)), highlights clipped \(Self.percent(h.highlights))"
            + (active ? ", warning shown on the photo" : "")
    }

    static func percent(_ f: Float) -> String {
        if f >= 0.1 { return "\(Int((f * 100).rounded()))%" }
        if f > 0 && f < 0.001 { return "<0.1%" }
        return String(format: "%.1f%%", f * 100)
    }

    /** One channel as a filled area; bins above [peak] (the clipped end bins) run off the top. */
    static func area(_ bins: [Int], peak: Int, size: CGSize) -> Path {
        var path = Path()
        guard bins.count > 1 else { return path }
        let step = size.width / CGFloat(bins.count - 1)
        let p = CGFloat(max(1, peak))
        path.move(to: CGPoint(x: 0, y: size.height))
        for (i, n) in bins.enumerated() {
            path.addLine(to: CGPoint(x: CGFloat(i) * step, y: size.height - min(1, CGFloat(n) / p) * size.height))
        }
        path.addLine(to: CGPoint(x: size.width, y: size.height))
        path.closeSubpath()
        return path
    }
}
